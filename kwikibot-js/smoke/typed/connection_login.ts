import assert from "node:assert/strict";
import { existsSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { inspect } from "node:util";
import {
  BotPassword,
  connect,
  connectFamily,
  connectWith,
  describeError,
  detect,
  type JsonValue,
  OAuth2,
  type Wiki,
} from "kwikibot";
import {
  ApiWarning,
  type ConnectOptions,
  CONTENT_MODEL_CSS,
  CONTENT_MODEL_JAVASCRIPT,
  CONTENT_MODEL_JSON,
  CONTENT_MODEL_SANITIZED_CSS,
  CONTENT_MODEL_SCRIBUNTO,
  CONTENT_MODEL_WIKITEXT,
  createClient,
  credentialsFromEnvironment,
  type DecodedPage,
  DEFAULT_CONCURRENT_REQUESTS,
  DEFAULT_CONNECTIONS,
  DEFAULT_READ_DELAY,
  DEFAULT_RELOGINS,
  DEFAULT_TIMEOUT,
  DEFAULT_WRITE_DELAY,
  DEPRECATION_HELP,
  DISK_CACHE_DEFAULT_TTL,
  DiskCache,
  failOnDeprecation,
  type HttpOptions,
  LIBRARY_NAME,
  LIBRARY_URL,
  libraryPlatform,
  libraryRuntime,
  libraryVersion,
  type ListenerOptions,
  type LogLevel,
  logLevel,
  MAX_GET_LENGTH,
  PROTECTION_AUTOCONFIRMED,
  PROTECTION_SYSOP,
  type ResponseCache,
  ResponseDecoder,
  responseError,
  type RetryOptions,
  setLogLevel,
  type ThrottleOptions,
  throwOnResponseError,
  UserAgent,
  versionReport,
  WikiClient,
} from "kwikibot/client";
import { dumpPagesOf } from "kwikibot/dumps";
import { type ErrorInfo } from "kwikibot/errors";
import { sparqlAuthWcqs } from "kwikibot/sparql";
import { customFamily, type Endpoint } from "kwikibot/titles";
import { type Answer, FakeSite, QUICK, assertArray, assertDate, assertNone, assertRecord, collect, json, withWiki } from "./support.js";

const CONTACT = "https://example.org/TestBot";
const LOGIN = new BotPassword("TestBot", "task", "secret");

const before: LogLevel = logLevel();
setLogLevel("off");

function theConstants(): void {
  assert.equal(DEFAULT_CONCURRENT_REQUESTS, 3);
  assert.equal(DEFAULT_CONNECTIONS, 32);
  assert.equal(DEFAULT_RELOGINS, 3);
  assert.equal(MAX_GET_LENGTH, 2000);
  assert.equal(DEFAULT_TIMEOUT, 60_000);
  assert.equal(DEFAULT_READ_DELAY, 100);
  assert.equal(DEFAULT_WRITE_DELAY, 10_000);
  assert.equal(DISK_CACHE_DEFAULT_TTL, 43_200_000);
  assert.equal(DEPRECATION_HELP, "deprecation-help");
  assert.equal(PROTECTION_SYSOP, "sysop");
  assert.equal(PROTECTION_AUTOCONFIRMED, "autoconfirmed");
  assert.equal(CONTENT_MODEL_WIKITEXT, "wikitext");
  assert.equal(CONTENT_MODEL_JSON, "json");
  assert.equal(CONTENT_MODEL_CSS, "css");
  assert.equal(CONTENT_MODEL_JAVASCRIPT, "javascript");
  assert.equal(CONTENT_MODEL_SCRIBUNTO, "Scribunto");
  assert.equal(CONTENT_MODEL_SANITIZED_CSS, "sanitized-css");
}

function whoThisLibraryIs(): void {
  assert.equal(LIBRARY_NAME, "kwikibot");
  assert.ok(LIBRARY_URL.includes("github.com"));
  const version: string = libraryVersion();
  assert.ok(version.length > 0);
  assert.equal(versionReport().split("\n")[0], `kwikibot ${version}`);
  assert.ok(libraryRuntime().length > 0);
  assert.ok(libraryPlatform().length > 0);

  setLogLevel("warn");
  assert.equal(logLevel(), "warn");
  setLogLevel("off");
  assert.equal(logLevel(), "off");
  assert.ok(typeof before === "string");
  assert.throws(() => {
    // @ts-expect-error a level nobody has heard of fails at runtime too
    setLogLevel("loud");
  }, /'trace'/);
}

function credentials(): void {
  const token = credentialsFromEnvironment("X", (name) => (name === "X_OAUTH_TOKEN" ? "t" : null));
  assert.ok(token instanceof OAuth2);
  assert.ok(token instanceof BotPassword);

  const environment: Record<string, string> = { Y_ACCOUNT: "Bot", Y_BOT_NAME: "task", Y_PASSWORD: "p" };
  const password = credentialsFromEnvironment("Y", (name) => environment[name]);
  assert.ok(password instanceof BotPassword);
  assert.equal(password.account, "Bot");
  assert.equal(password.botName, "task");

  assertNone(credentialsFromEnvironment("Z", () => null));
  assertNone(credentialsFromEnvironment("Z", () => undefined));
  assert.throws(() => credentialsFromEnvironment("Y", (name) => (name === "Y_ACCOUNT" ? "Bot" : null)));
  assertNone(credentialsFromEnvironment("KWIKIBOT_NO_SUCH_PREFIX"));
  assertNone(credentialsFromEnvironment("KWIKIBOT_NO_SUCH_PREFIX", null));

  const oauth = new OAuth2("token-1", "Bot");
  assert.equal(oauth.username, "Bot");
  assert.equal(oauth.account, "Bot");
  assert.equal(oauth.botName, "");
  assertNone(new OAuth2("t").username);
  assert.equal(new OAuth2("t", null).account, "");
  assert.throws(() => new OAuth2(" "));
}

function userAgents(): void {
  const agent = new UserAgent("Mine", "2.0", CONTACT);
  assert.equal(agent.tool, "Mine");
  assert.equal(agent.version, "2.0");
  assert.equal(agent.contact, CONTACT);
  assert.ok(agent.headerValue.startsWith(`Mine/2.0 (${CONTACT})`));
  assert.equal(agent.toString(), agent.headerValue);
  assert.throws(() => new UserAgent("Mine", "1", " "));
}

function watchingHeaders(): { seen: Array<{ url: string; headers: Headers }>; stop: () => void } {
  const inner = globalThis.fetch;
  const seen: Array<{ url: string; headers: Headers }> = [];
  globalThis.fetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    seen.push({ url: input instanceof Request ? input.url : input.toString(), headers: new Headers(init?.headers) });
    return inner(input, init);
  };
  return {
    seen,
    stop: () => {
      globalThis.fetch = inner;
    },
  };
}

async function opening(): Promise<void> {
  const site = new FakeSite();
  const restore = site.install();
  const headers = watchingHeaders();
  try {
    const anonymous: Wiki = await connect("test.example.org", CONTACT);
    assert.equal(anonymous.user.isAnonymous, true);
    assert.ok(headers.seen[0]?.url.startsWith("https://test.example.org/w/api.php"));
    anonymous.close();

    const named = await connect("test.example.org", CONTACT, LOGIN, "/mw");
    assert.equal(named.user.name, "TestBot");
    const login = site.requests.find((request) => request.action === "login");
    assert.equal(login?.lgname, "TestBot@task");
    assert.ok(headers.seen.some((request) => request.url.startsWith("https://test.example.org/mw/api.php")));
    named.close();

    const withNull = await connect("test.example.org", CONTACT, null);
    withNull.close();

    const agent = new UserAgent("Mine", "2.0", CONTACT);
    const throttle: ThrottleOptions = { read: 0, write: 0 };
    const retry: RetryOptions = { maxRetries: 0, initialDelay: 1, maxDelay: 1 };
    const http: HttpOptions = { maxConcurrentRequests: 2, maxIdleConnections: 4, timeout: 5_000 };
    const options: ConnectOptions = {
      login: null,
      scriptPath: "/wiki",
      secure: false,
      userAgent: agent,
      throttle,
      retry,
      maxlag: 2,
      relogins: 1,
      http,
    };
    const count = headers.seen.length;
    const everything = await connectWith("test.example.org", CONTACT, options);
    const opened = headers.seen[count];
    assert.ok(opened?.url.startsWith("http://test.example.org/wiki/api.php"), opened?.url);
    assert.equal(opened?.headers.get("User-Agent"), agent.headerValue);
    assert.ok(site.requests[site.requests.length - 1]?.maxlag === "2");
    everything.close();

    const noLag = await connectWith("test.example.org", CONTACT, { ...QUICK, maxlag: null });
    assert.equal(site.requests[site.requests.length - 1]?.maxlag, undefined);
    noLag.close();

    const family = customFamily("t", "test.example.org");
    const wikiOfFamily = await connectFamily("en", family, CONTACT, QUICK);
    assert.equal(wikiOfFamily.endpoint.server, "test.example.org");
    wikiOfFamily.close();
    const familyPlain = await connectFamily("en", family, CONTACT);
    familyPlain.close();

    const client: WikiClient = createClient(CONTACT, QUICK);
    assert.ok(client instanceof WikiClient);
    const first = await client.wiki("test.example.org");
    const second = await client.wikiOf("en", family);
    first.close();
    assert.equal(second.siteName, "Test Wiki");
    second.close();
    client.close();
    createClient(CONTACT).close();

    const before = headers.seen.length;
    const bearer = await connectWith("test.example.org", CONTACT, { ...QUICK, login: new OAuth2("token-1", "Bot") });
    assert.equal(headers.seen[before]?.headers.get("Authorization"), "Bearer token-1");
    bearer.close();
  } finally {
    headers.stop();
    restore();
  }
}

async function detecting(): Promise<void> {
  const site = new FakeSite();
  site.raw = (url) => {
    if (url.includes("has-api")) {
      return new Response(`<html><head><link rel="EditURI" type="application/rsd+xml" href="//has-api.example.org/w/api.php?action=rsd"></head></html>`, {
        status: 200,
        headers: { "Content-Type": "text/html" },
      });
    }
    return new Response("<html></html>", { status: 200, headers: { "Content-Type": "text/html" } });
  };
  const restore = site.install();
  try {
    const endpoint: Endpoint | null | undefined = await detect("https://has-api.example.org/wiki/Volcano", CONTACT);
    assert.ok(endpoint != null);
    assert.equal(endpoint.apiUrl, "https://has-api.example.org/w/api.php");
    assert.equal(endpoint.server, "has-api.example.org");
    assert.equal(endpoint.scriptPath, "/w");
    assertNone(await detect("https://none.example.org/", CONTACT));
  } finally {
    restore();
  }
}

async function listenersAndWarnings(): Promise<void> {
  const warned: ApiWarning[] = [];
  const responses: Array<string | undefined> = [];
  const site = new FakeSite({
    categorymembers: {
      warnings: [
        { module: "query", code: "deprecation", text: "old" },
        { module: "main", text: "plain" },
      ],
      query: { categorymembers: [{ ns: 0, title: "Etna" }] },
    },
  });
  const restore = site.install();
  try {
    const listener: ListenerOptions = {
      onResponse: (params, response) => {
        assertRecord(params, "string");
        assertRecord(response);
        responses.push(params.list);
      },
    };
    const wiki = await connectWith("test.example.org", CONTACT, {
      ...QUICK,
      onWarning: (warning) => {
        warned.push(warning);
      },
      listener,
    });
    assert.deepEqual(await collect(wiki.categoryMembers("Volcanoes")), ["Etna"]);
    assert.ok(responses.includes("categorymembers"));

    const deprecation = warned.find((warning) => warning.isDeprecation);
    const other = warned.find((warning) => !warning.isDeprecation);
    assert.ok(deprecation instanceof ApiWarning && other instanceof ApiWarning);
    assert.equal(deprecation.module, "query");
    assert.equal(deprecation.text, "old");
    assert.equal(deprecation.code, "deprecation");
    assert.ok(deprecation.toString().includes("old"));
    assert.equal(other.module, "main");
    assert.equal(other.text, "plain");
    assertNone(other.code);
    wiki.close();

    const heard: string[] = [];
    assert.throws(() => failOnDeprecation()(deprecation), /old/);
    failOnDeprecation((warning) => {
      heard.push(warning.text);
    })(other);
    failOnDeprecation()(other);
    failOnDeprecation(null)(other);
    assert.deepEqual(heard, ["plain"]);
  } finally {
    restore();
  }
}

async function retriesPausesAndLogins(): Promise<void> {
  let busy = 1;
  let lost = 0;
  const site = new FakeSite({
    search: (): JsonValue => {
      if (lost > 0) {
        lost -= 1;
        return { error: { code: "assertuserfailed", info: "gone" } };
      }
      return { query: { search: [{ ns: 0, title: "Volcano" }] } };
    },
  });
  site.raw = (_url, params) => {
    if (params.list === "search" && busy > 0) {
      busy -= 1;
      return new Response("", { status: 429, headers: { "Retry-After": "1" } });
    }
    return undefined;
  };
  const restore = site.install();
  const attempts: number[] = [];
  const reasons: string[] = [];
  const pauses: number[] = [];
  const restored: string[] = [];
  try {
    const wiki = await connectWith("test.example.org", CONTACT, {
      throttle: { read: 0, write: 0 },
      retry: { maxRetries: 1, initialDelay: 1, maxDelay: 1 },
      login: LOGIN,
      listener: {
        onRetry: (params, attempt, wait, reason) => {
          assert.equal(params.list, "search");
          assert.equal(typeof wait, "number");
          attempts.push(attempt);
          reasons.push(reason);
        },
        onPenalty: (wait) => {
          pauses.push(wait);
        },
        onRelogin: (identity) => {
          restored.push(identity.name);
        },
      },
    });
    assert.deepEqual(await collect(wiki.search("lava")), ["Volcano"]);
    assert.deepEqual(attempts, [1]);
    assert.equal(typeof reasons[0], "string");
    assert.deepEqual(pauses, [1000]);
    lost = 1;
    assert.deepEqual(await collect(wiki.search("lava")), ["Volcano"]);
    assert.deepEqual(restored, ["TestBot"]);
    wiki.close();
  } finally {
    restore();
  }
}

async function caches(): Promise<void> {
  const directory = mkdtempSync(join(tmpdir(), "kwikibot-typed-cache-"));
  const site = new FakeSite({ categorymembers: { query: { categorymembers: [{ ns: 0, title: "Lava" }] } } });
  const restore = site.install();
  const asked = (): number => site.requests.filter((request) => request.list === "categorymembers").length;
  try {
    const stored = new Map<string, Record<string, JsonValue>>();
    const mine: ResponseCache = {
      get: (params) => stored.get(params.list ?? ""),
      put: async (params, response) => {
        assertRecord(params, "string");
        stored.set(params.list ?? "", response);
      },
    };
    const cached = await connectWith("test.example.org", CONTACT, { ...QUICK, cache: mine });
    assert.deepEqual(await collect(cached.categoryMembers("Lava")), ["Lava"]);
    const once = asked();
    assert.deepEqual(await collect(cached.categoryMembers("Lava")), ["Lava"]);
    assert.equal(asked(), once);
    assert.ok(stored.has("categorymembers"));
    cached.close();

    const root = join(directory, "cache");
    const disk = new DiskCache(root, 60_000);
    assert.equal(disk.root, root);
    const onDisk = await connectWith("test.example.org", CONTACT, { ...QUICK, cache: disk });
    const already = asked();
    assert.deepEqual(await collect(onDisk.categoryMembers("Lava")), ["Lava"]);
    assert.equal(asked(), already + 1);
    assert.deepEqual(await collect(onDisk.categoryMembers("Lava")), ["Lava"]);
    assert.equal(asked(), already + 1);
    assert.ok(existsSync(root));
    disk.clear();
    assert.deepEqual(await collect(onDisk.categoryMembers("Lava")), ["Lava"]);
    assert.equal(asked(), already + 2);
    onDisk.close();

    const params = { action: "query", list: "x" };
    await disk.put(params, { a: 1 });
    const back = await disk.get(params);
    assert.ok(back != null);
    assert.deepEqual(assertRecord(back), { a: 1 });
    disk.clear();
    assertNone(await disk.get(params));
    assert.equal(new DiskCache(join(directory, "plain")).root, join(directory, "plain"));
    assert.equal(new DiskCache(join(directory, "plain"), null).root, join(directory, "plain"));
  } finally {
    restore();
    rmSync(directory, { recursive: true, force: true });
  }
}

async function decoding(): Promise<void> {
  await withWiki(new FakeSite(), async (wiki) => {
    const decoder: ResponseDecoder = wiki.decoder;
    assert.ok(decoder instanceof ResponseDecoder);
    const page: Record<string, JsonValue> = {
      pageid: 1,
      ns: 0,
      title: "Volcano",
      contentmodel: "wikitext",
      revisions: [{ revid: 7, timestamp: "2026-08-31T21:43:26Z", slots: { main: { content: "ash" } } }],
    };

    const existing: DecodedPage = decoder.decodePage(page);
    assert.equal(existing.kind, "existing");
    assert.equal(existing.title, "Volcano");
    assert.equal(existing.contentModel, "wikitext");
    assert.equal(existing.isRedirect, false);
    assert.equal(existing.latestRevision?.id, 7);
    assert.equal(existing.content?.text, "ash");
    assertNone(existing.reason);

    const missing = decoder.decodePage({ ns: 0, title: "Gone", missing: true });
    assert.equal(missing.kind, "missing");
    assertNone(missing.contentModel);
    assertNone(missing.isRedirect);
    assertNone(missing.latestRevision);
    assertNone(missing.content);
    const invalid = decoder.decodePage({ title: "[", invalid: true, invalidreason: "bad" });
    assert.equal(invalid.kind, "invalid");
    assert.equal(invalid.title, "[");
    assert.equal(invalid.reason, "bad");

    assert.deepEqual(assertArray(decoder.decodeContents(page)).map((content) => content.text), ["ash"]);
    const redirect: Record<string, JsonValue> = {
      pageid: 2,
      ns: 0,
      title: "Fire",
      revisions: [{ revid: 8, timestamp: "2026-08-31T21:43:26Z", slots: { main: { content: "#REDIRECT [[Volcano]]" } } }],
    };
    assert.equal(decoder.decodeContents(redirect, () => true)[0]?.redirectTarget, "Volcano");
    assert.equal(decoder.titleOf(page), "Volcano");
    assertNone(decoder.titleOf({ ns: 0 }));
    assert.equal(decoder.decodeRevision({ revid: 9, timestamp: "2026-08-31T21:43:26Z", size: 3 }).id, 9);

    assert.equal(
      decoder.decodeLogEvent({ logid: 1, type: "delete", action: "delete", ns: 0, title: "Volcano", timestamp: "2026-08-31T21:43:26Z", params: {} }).page,
      "Volcano",
    );
    const change = decoder.decodeRecentChange({ type: "edit", ns: 0, title: "Volcano", rcid: 1, timestamp: "2026-08-31T21:43:26Z" });
    assert.equal(change.page, "Volcano");
    assertDate(change.timestamp);
    const contribution = decoder.decodeContribution({
      userid: 7,
      user: "Alice",
      pageid: 4,
      revid: 9,
      parentid: 8,
      ns: 0,
      title: "Volcano",
      timestamp: "2026-08-31T21:43:26Z",
      size: 30,
      sizediff: 30,
      comment: "c",
      tags: [],
    });
    assert.equal(contribution?.page, "Volcano");
    assert.equal(decoder.decodeBlockRecord({ id: 5, user: "Bob", by: "Al", timestamp: "2026-08-01T00:00:00Z", expiry: "infinity" }).target, "Bob");
    assert.equal(decoder.decodeUser({ userid: 7, name: "Alice", groups: [] }).name, "Alice");
    assert.equal(decoder.decodeCurrentUser({ id: 7, name: "Alice", groups: [] }).name, "Alice");
  });
}

async function rejection(work: () => Promise<unknown>): Promise<ErrorInfo> {
  try {
    await work();
  } catch (error) {
    const info = describeError(error);
    assert.ok(info != null, `not described: ${String(error)}`);
    assert.equal(typeof info.message, "string");
    assert.equal(typeof info.isTransient, "boolean");
    return info;
  }
  return assert.fail("it did not reject");
}

function thrown(work: () => unknown): ErrorInfo {
  try {
    work();
  } catch (error) {
    const info = describeError(error);
    assert.ok(info != null, `not described: ${String(error)}`);
    return info;
  }
  return assert.fail("it did not throw");
}

async function withFetch<T>(fetcher: typeof globalThis.fetch, work: () => Promise<T>): Promise<T> {
  const original = globalThis.fetch;
  globalThis.fetch = fetcher;
  try {
    return await work();
  } finally {
    globalThis.fetch = original;
  }
}

const NO_RETRY: ConnectOptions = { throttle: { read: 0, write: 0 }, retry: { maxRetries: 0 } };

async function whatGoesWrongOnTheWay(): Promise<void> {
  const unreachable = await withFetch(
    async () => {
      throw new TypeError("fetch failed");
    },
    () => rejection(() => connectWith("test.example.org", CONTACT, NO_RETRY)),
  );
  assert.equal(unreachable.kind, "unreachable");
  assert.ok(unreachable.url?.includes("test.example.org"));
  assert.equal(unreachable.isTransient, true);
  assertNone(unreachable.status);

  const timeout = await withFetch(
    () => new Promise<Response>(() => undefined),
    () => rejection(() => connectWith("test.example.org", CONTACT, { ...NO_RETRY, http: { timeout: 20 } })),
  );
  assert.equal(timeout.kind, "timeout");
  assert.ok(timeout.url?.includes("test.example.org"));

  const server = await withFetch(
    async () => new Response("", { status: 500 }),
    () => rejection(() => connectWith("test.example.org", CONTACT, NO_RETRY)),
  );
  assert.equal(server.kind, "server-error");
  assert.equal(server.status, 500);
  assert.ok(server.url?.includes("test.example.org"));

  const refused = await withFetch(
    async () => new Response("no", { status: 403 }),
    () => rejection(() => connectWith("test.example.org", CONTACT, NO_RETRY)),
  );
  assert.equal(refused.kind, "refused");
  assert.equal(refused.status, 403);
  assert.equal(refused.detail, "no");
  assert.equal(refused.isTransient, false);

  const limited = await withFetch(
    async () => new Response("", { status: 429, headers: { "Retry-After": "2" } }),
    () => rejection(() => connectWith("test.example.org", CONTACT, NO_RETRY)),
  );
  assert.equal(limited.kind, "rate-limited");
  assert.equal(limited.retryAfter, 2000);

  const lagged = await withFetch(
    async () => json({ error: { code: "maxlag", info: "Waiting for db1: 3 seconds lagged", host: "db1", lag: 3 } }),
    () => rejection(() => connectWith("test.example.org", CONTACT, NO_RETRY)),
  );
  assert.equal(lagged.kind, "maxlag");
  assert.equal(lagged.lag, 3000);
  assert.equal(lagged.host, "db1");

  const wrongPassword = await withFetch(globalThis.fetch, async () => {
    const restore = new FakeSite().install();
    try {
      return await rejection(() => connectWith("test.example.org", CONTACT, { ...NO_RETRY, login: new BotPassword("Bot", "task", "wrong") }));
    } finally {
      restore();
    }
  });
  assert.equal(wrongPassword.kind, "login-failed");
  assert.ok(wrongPassword.detail != null && wrongPassword.detail.length > 0);
}

function whatTheWikiSays(): void {
  const answer = (code: string, info = "not this", module?: string): Record<string, JsonValue> => ({
    error: module === undefined ? { code, info } : { code, info, module },
  });
  const fine: Record<string, JsonValue> = { query: {} };
  assertNone(responseError(fine));
  assert.deepEqual(throwOnResponseError(fine), fine);

  const api = responseError(answer("nope", "not this", "query"));
  assert.ok(api != null);
  assert.equal(api.kind, "api");
  assert.equal(api.code, "nope");
  assert.equal(api.info, "not this");
  assert.equal(api.module, "query");
  assert.ok(api.message.includes("not this"));
  assertNone(api.url);
  assertNone(api.status);
  assertNone(api.detail);
  assertNone(api.title);
  assertNone(api.retryAfter);
  assertNone(api.lag);
  assertNone(api.host);
  assertNone(api.action);
  assertNone(api.right);
  assertNone(api.tokenType);
  assertNone(api.titleProblem);
  assertNone(api.extension);
  assertNone(api.requiredVersion);
  assertNone(api.actualVersion);
  assertNone(api.parameter);
  assertNone(api.alternatives);
  assertNone(api.dumpProblem);
  assertNone(api.offset);
  assertNone(api.wiki);
  assertNone(api.language);
  assertNone(api.family);
  assertNone(api.contentModel);
  assert.equal(thrown(() => throwOnResponseError(answer("nope"))).kind, "api");

  const notLoggedIn = responseError(answer("assertuserfailed", "gone", "edit"));
  assert.equal(notLoggedIn?.kind, "not-logged-in");
  assert.equal(notLoggedIn?.action, "edit");
  const blocked = responseError(answer("blocked", "you are blocked"));
  assert.equal(blocked?.kind, "account-blocked");
  assert.equal(blocked?.detail, "you are blocked");
  const denied = responseError(answer("permissiondenied", "no"));
  assert.equal(denied?.kind, "permission-denied");
  assert.equal(denied?.detail, "no");
  const badToken = responseError(answer("badtoken"));
  assert.equal(badToken?.kind, "bad-token");
  assert.equal(badToken?.tokenType, "csrf");
  const readOnly = responseError(answer("readonly", "down"));
  assert.equal(readOnly?.kind, "read-only");
  assert.equal(readOnly?.detail, "down");
}

async function whatTheWikiWillNotDo(): Promise<void> {
  const answers: Record<string, Answer> = {
    paraminfo: {
      paraminfo: {
        modules: [{ name: "parse", path: "parse", prefix: "", parameters: [{ name: "prop", type: ["text"] }] }],
      },
    },
  };
  await withWiki(
    new FakeSite(answers),
    async (wiki) => {
      const right = await rejection(() => wiki.requireRight("delete"));
      assert.equal(right.kind, "missing-right");
      assert.equal(right.right, "delete");
      const group = thrown(() => wiki.requireGroup("sysop"));
      assert.equal(group.kind, "permission-denied");
      const extension = thrown(() => wiki.requireExtension("Nope"));
      assert.equal(extension.kind, "missing-extension");
      assert.equal(extension.extension, "Nope");
      const version = thrown(() => wiki.requireVersion("1.48"));
      assert.equal(version.kind, "version-too-old");
      assert.equal(version.requiredVersion, "1.48");
      assert.equal(version.actualVersion, "1.47.0");

      const badTitle = thrown(() => wiki.ref("[x]"));
      assert.equal(badTitle.kind, "bad-title");
      assert.equal(badTitle.title, "[x]");
      assert.equal(badTitle.titleProblem, "illegal-character");
      const offWiki = thrown(() => wiki.ref("w:Lava"));
      assert.equal(offWiki.kind, "off-wiki");
      assert.equal(offWiki.title, "w:Lava");

      const unsupported = await rejection(() => wiki.render.resolve("Volcano", ["sections"]));
      assert.equal(unsupported.kind, "unsupported");
      assert.equal(unsupported.parameter, "parse prop");
      assert.deepEqual(assertArray(unsupported.alternatives ?? []), ["tocdata", "sections"]);
    },
    LOGIN,
  );

  assertNone(describeError(new Error("not ours")));
  assertNone(describeError(null));
  assertNone(describeError(undefined));
  assertNone(describeError("a string"));

  const dump = thrown(() => dumpPagesOf(new Uint8Array([1, 2, 3]), "bzip2"));
  assert.equal(dump.kind, "dump-format");
  assert.equal(dump.dumpProblem, "unexpected-eof");
}

function rejectedConnectionShapes(wiki: Wiki, info: ErrorInfo, decoded: DecodedPage): void {
  // @ts-expect-error a level nobody has heard of
  setLogLevel("verbose");
  // @ts-expect-error a kind nobody has heard of
  const kind = info.kind === "not-found";
  // @ts-expect-error a problem nobody has heard of
  const problem = info.dumpProblem === "corrupt";
  // @ts-expect-error a decoded page is one of three kinds
  const state = decoded.kind === "deleted";
  // @ts-expect-error a throttle is in milliseconds
  const throttle: ThrottleOptions = { read: "200ms" };
  // @ts-expect-error a cache has both a get and a put
  const cache: ResponseCache = { get: () => null };
  // @ts-expect-error a look-alike is no login: it holds no password
  const login: BotPassword = { account: "Bot", botName: "task" };
  // @ts-expect-error a look-alike is no user agent: the wiki would never see it
  const agent: UserAgent = { tool: "t", version: "1", contact: "c", headerValue: "t/1" };
  void [wiki, kind, problem, state, throttle, cache, login, agent];
}
void rejectedConnectionShapes;

/** What looks at a wiki over a real session, stubbed `fetch` and all, for a password. */
async function nothingLooksAtASecret(): Promise<void> {
  const site = new FakeSite();
  const login = new BotPassword("Bot", "task", "secret");
  await withWiki(
    site,
    async (wiki) => {
      assert.ok(site.requests.some((request) => request.lgpassword === "secret"));
      for (const value of [login, wiki]) {
        assert.equal(JSON.stringify(value).includes("secret"), false);
        assert.equal(Object.values({ ...value }).join().includes("secret"), false);
      }
      for (const value of [login, wiki, wiki.lists, wiki.pagesService]) {
        assert.equal(inspect(value, { depth: null }).includes("secret"), false);
      }
    },
    login,
  );
  const token = new OAuth2("tok-very-private", "Bot");
  assert.equal(JSON.stringify(token).includes("tok-very-private"), false);
  assert.equal(inspect(token, { depth: null }).includes("tok-very-private"), false);
  assert.deepEqual(Object.keys(token).includes("password"), false);
  assert.equal(token.username, "Bot");
  const cookie = sparqlAuthWcqs("tok-very-private");
  assert.equal(JSON.stringify(cookie).includes("tok-very-private"), false);
  assert.equal(inspect(cookie, { depth: null }).includes("tok-very-private"), false);
  assert.equal(cookie.value, "tok-very-private");
}

try {
  await nothingLooksAtASecret();
  theConstants();
  whoThisLibraryIs();
  credentials();
  userAgents();
  await opening();
  await detecting();
  await listenersAndWarnings();
  await retriesPausesAndLogins();
  await caches();
  await decoding();
  await whatGoesWrongOnTheWay();
  whatTheWikiSays();
  await whatTheWikiWillNotDo();
} finally {
  setLogLevel(before);
}

console.log("connection_login: wikis opened every way, with listeners and caches, and errors said what went wrong");
