import assert from "node:assert/strict";
import { type BotPassword, connectWith, type JsonValue, type Wiki } from "kwikibot";
import { type ConnectOptions } from "kwikibot/client";

export type Answer = JsonValue | ((params: Record<string, string>) => JsonValue);

const TOKENS: JsonValue = {
  query: { tokens: { logintoken: "l+\\", csrftoken: "c+\\", userrightstoken: "u+\\", rollbacktoken: "r+\\", watchtoken: "w+\\", patroltoken: "p+\\" } },
};

function siteInfo(extensions: string[]): JsonValue {
  return {
    query: {
      general: {
        wikiid: "testwiki",
        sitename: "Test Wiki",
        lang: "fr",
        timezone: "Europe/Paris",
        server: "//test.example.org",
        articlepath: "/wiki/$1",
        mainpage: "Main Page",
        generator: "MediaWiki 1.47.0",
      },
      namespaces: {
        "0": { id: 0, name: "", case: "first-letter" },
        "2": { id: 2, name: "User", canonical: "User", case: "first-letter" },
        "6": { id: 6, name: "File", canonical: "File", case: "first-letter" },
        "10": { id: 10, name: "Template", canonical: "Template", case: "first-letter" },
        "14": { id: 14, name: "Category", canonical: "Category", case: "first-letter" },
        "104": { id: 104, name: "Page", canonical: "Page", case: "first-letter" },
        "106": { id: 106, name: "Index", canonical: "Index", case: "first-letter" },
      },
      extensions: extensions.map((name) => ({ name })),
      interwikimap: [{ prefix: "w", url: "https://en.wikipedia.org/wiki/$1" }],
    },
  };
}

function moduleOf(params: Record<string, string>): string {
  if (params.action === "query") return params.list ?? params.prop ?? params.meta ?? "";
  return params.action ?? "";
}

/**
 * A wiki on the other side of a stubbed `globalThis.fetch`: real clients reach it through their real HTTP
 * code. It answers opening a wiki, tokens and logging in itself, and everything else from `answers`, by the
 * module asked for: a query's `list`, else its `prop`, else its `meta`; any other request's `action`; and `""`
 * for a request with none. `requests` keeps the parameters of every request.
 */
export class FakeSite {
  readonly requests: Array<Record<string, string>> = [];
  readonly answers: Record<string, Answer>;
  readonly extensions: string[];
  raw?: (url: string, params: Record<string, string>) => Response | undefined;
  private account: string | null = null;

  constructor(answers: Record<string, Answer> = {}, extensions: string[] = []) {
    this.answers = answers;
    this.extensions = extensions;
  }

  /** Answers every `fetch` in the process from here until [restore] is called. */
  install(): () => void {
    const original = globalThis.fetch;
    globalThis.fetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
      const url = new URL(input instanceof Request ? input.url : input.toString());
      const body = init?.body == null ? "" : init.body instanceof Uint8Array ? new TextDecoder().decode(init.body) : String(init.body);
      const params: Record<string, string> = Object.fromEntries(url.searchParams);
      if (!body.startsWith("--")) for (const [key, value] of new URLSearchParams(body)) params[key] = value;
      this.requests.push(params);
      return this.raw?.(url.toString(), params) ?? this.respond(params);
    };
    return () => {
      globalThis.fetch = original;
    };
  }

  private respond(params: Record<string, string>): Response {
    return json(this.answer(params));
  }

  private answer(params: Record<string, string>): JsonValue {
    const module = moduleOf(params);
    if (params.meta === "tokens") return TOKENS;
    if (params.action === "login") {
      if (params.lgpassword !== "secret") return { login: { result: "Failed", reason: { code: "wrongpassword", text: "Incorrect password." } } };
      this.account = (params.lgname ?? "").split("@")[0] ?? "";
      return { login: { result: "Success", lguserid: 7, lgusername: this.account } };
    }
    const answer = this.answers[module];
    if (answer !== undefined) return typeof answer === "function" ? answer(params) : answer;
    if (params.meta === "siteinfo") return siteInfo(this.extensions);
    if (params.meta === "userinfo") {
      return this.account === null
        ? { query: { userinfo: { id: 0, name: "192.0.2.1", anon: true, groups: ["*"], rights: ["read"] } } }
        : { query: { userinfo: { id: 7, name: this.account, groups: ["*", "bot", "user"], rights: ["bot", "edit"] } } };
    }
    return { error: { code: "unexpected", info: `nothing answers ${JSON.stringify(params)}` } };
  }
}

export function json(value: JsonValue, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
}

export const QUICK: ConnectOptions = { throttle: { read: 0, write: 0 }, retry: { maxRetries: 0 } };

/**
 * A real `Wiki` connected to [site] through its stubbed `fetch`, handed to [use], and closed afterwards.
 */
export async function withWiki<T>(site: FakeSite, use: (wiki: Wiki) => Promise<T>, login?: BotPassword): Promise<T> {
  const restore = site.install();
  try {
    const wiki = await connectWith("test.example.org", "https://example.org/typed", { ...QUICK, login });
    try {
      return await use(wiki);
    } finally {
      wiki.close();
    }
  } finally {
    restore();
  }
}

/** Every item of an async iterable, which also proves it iterates. */
export async function collect<T>(items: AsyncIterable<T>): Promise<T[]> {
  assert.equal(typeof items[Symbol.asyncIterator], "function", "not an AsyncIterable");
  const all: T[] = [];
  for await (const item of items) all.push(item);
  return all;
}

/** Fails unless [value] is really a JavaScript array, and not a Kotlin list. */
export function assertArray<T>(value: T[]): T[] {
  assert.ok(Array.isArray(value), `expected an array, got ${String(value)}`);
  return value;
}

/** Fails unless [value] is a plain object, as a `Record` is, with [values] of the given `typeof`. */
export function assertRecord<V>(value: Record<string, V>, values?: string): Record<string, V> {
  assert.equal(Object.getPrototypeOf(value), Object.prototype, "expected a plain object");
  if (values) for (const entry of Object.values(value)) assert.equal(typeof entry, values);
  return value;
}

/** Fails unless [value] is a `Date` that says a time. */
export function assertDate(value: Date | null | undefined): Date {
  assert.ok(value instanceof Date && !Number.isNaN(value.getTime()), `expected a date, got ${String(value)}`);
  return value;
}

/** Fails unless [value] is [nullish], for what a `Nullable` says when nothing is there: never `0` or `""`. */
export function assertNone(value: unknown): void {
  assert.ok(value === null || value === undefined, `expected nothing, got ${String(value)}`);
}
