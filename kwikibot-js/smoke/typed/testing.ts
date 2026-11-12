import assert from "node:assert/strict";
import { describeError, type EditResult, type JsonValue, type PageEdit } from "kwikibot";
import {
  type Choice,
  NonInteractive,
  pageSourceOfTitles,
  parsePageSource,
  Prompt,
  promptFrom,
  type PromptHandlers,
  ScriptedPrompt,
  STOP_POLICY_NONE,
  TerminalPrompt,
} from "kwikibot/bot";
import {
  createFakeWiki,
  FAKE_INITIAL_REVISION,
  FakeEdit,
  type FakeFailure,
  type FakeIdentity,
  FakePageService,
  type FakePageServiceOptions,
  type FakeRefusal,
  type FakeWikiOptions,
  MockRequest,
  MockTransport,
} from "kwikibot/testing";
import { createNamespaces, endpointOf } from "kwikibot/titles";
import { FakeSite, assertArray, assertNone, assertRecord, collect, withWiki } from "./support.js";

function service(more: FakePageServiceOptions = {}): FakePageService {
  return new FakePageService({ pages: { Volcano: "ash", "Template:Foo": "bar" }, ...more });
}

const addAMark = (page: { text: string }): PageEdit => ({ text: `${page.text}!`, summary: "why", tags: ["t"] });

async function aBotOnAFakeWiki(): Promise<void> {
  const pages = service();
  const options: FakeWikiOptions = { pages };
  const wiki = createFakeWiki(options);

  assert.equal(wiki.id, "testwiki");
  assert.equal(wiki.user.name, "TestBot");
  assert.equal((await wiki.page("Volcano"))?.text, "ash");
  assertNone(await wiki.page("Absent"));
  assert.equal((await wiki.page("Template:Foo"))?.text, "bar");

  const dry = await wiki.botRun({ source: pageSourceOfTitles(["Volcano"]), transform: addAMark });
  assert.equal(dry.pending, 1);
  assert.equal(assertArray(pages.edits).length, 0);

  const report = await wiki.botRun({
    source: pageSourceOfTitles(["Volcano", "Absent"]),
    dryRun: false,
    stopPolicy: STOP_POLICY_NONE,
    transform: addAMark,
  });
  assert.equal(report.saved, 1);
  assert.equal(report.skipped, 1);

  assert.equal(pages.text("Volcano"), "ash!");
  assertNone(pages.text("Absent"));
  assert.equal(typeof FAKE_INITIAL_REVISION, "number");
  assert.equal(pages.revision("Volcano"), FAKE_INITIAL_REVISION + 1);
  assertNone(pages.revision("Absent"));

  const edit = assertArray(pages.edits)[0];
  assert.ok(edit instanceof FakeEdit);
  assert.equal(edit.title, "Volcano");
  assert.equal(edit.text, "ash!");
  assert.equal(edit.summary, "why");
  assert.equal(edit.baseRevision, FAKE_INITIAL_REVISION);
  assert.deepEqual(assertArray(edit.tags), ["t"]);
  assert.equal(edit.minor, false);
  assert.equal(edit.createOnly, false);
  assert.equal(edit.noCreate, true);
  assert.equal(edit.bot, true);
  assertNone(edit.section);
  assertNone(edit.sectionTitle);
  assertNone(edit.prependText);
  assertNone(edit.appendText);
  assert.equal(edit.watchlist, "preferences");
  assertNone(edit.watchlistExpiry);

  const prepended = await wiki.pagesService.edit("Volcano", { prependText: "top ", summary: "around", minor: true, bot: false, section: "new", sectionTitle: "Heading", watchlist: "watch", watchlistExpiry: "1 week" });
  assert.equal(prepended.status, "saved");
  const around = pages.edits[1];
  assert.ok(around != null);
  assertNone(around.text);
  assert.equal(around.prependText, "top ");
  assertNone(around.appendText);
  assert.equal(around.minor, true);
  assert.equal(around.bot, false);
  assert.equal(around.section, "new");
  assert.equal(around.sectionTitle, "Heading");
  assert.equal(around.watchlist, "watch");
  assert.equal(around.watchlistExpiry, "1 week");
  await wiki.pagesService.edit("Volcano", { appendText: " end", summary: "after" });
  assert.equal(pages.edits[2]?.appendText, " end");
  wiki.close();
}

async function thePageServiceAnswers(): Promise<void> {
  const pages = service({ expand: (text, title) => `${text} in ${String(title)}` });
  const wiki = createFakeWiki({ pages });

  await wiki.pagesService.watch(["Volcano"]);
  assert.equal(pages.isWatched("Volcano"), true);
  assert.equal(pages.isWatched("Template:Foo"), false);
  assert.equal(await wiki.pagesService.expandText("{{x}}", "Volcano"), "{{x}} in Volcano");
  await wiki.pagesService.delete("Volcano", { reason: "gone" });
  assertNone(pages.text("Volcano"));
  wiki.close();

  const noPages = createFakeWiki();
  assert.equal((await noPages.edit("New", "text", "making it")).status, "saved");
  noPages.close();
  const empty = new FakePageService();
  assertNone(empty.text("Anything"));
  const elsewhere = new FakePageService({ wiki: "otherwiki", pages: null });
  const onIt = createFakeWiki({ pages: elsewhere });
  assert.equal(onIt.id, "testwiki");
  onIt.close();
}

async function refusals(): Promise<void> {
  const cases: Array<{ refusal: FakeRefusal; read: (result: EditResult) => unknown; expected: unknown }> = [
    { refusal: { status: "conflict", currentRevision: 7 }, read: (result) => result.currentRevision, expected: 7 },
    { refusal: { status: "protected", protectionLevel: "sysop", isCascadingProtection: true }, read: (result) => result.protectionLevel, expected: "sysop" },
    { refusal: { status: "protected", isCascadingProtection: true }, read: (result) => result.isCascadingProtection, expected: true },
    { refusal: { status: "filtered", filter: "f" }, read: (result) => result.filter, expected: "f" },
    { refusal: { status: "rate-limited", retryAfter: 5 }, read: (result) => result.retryAfter, expected: 5 },
    { refusal: { status: "captcha", captchaId: "c" }, read: (result) => result.captchaId, expected: "c" },
    { refusal: { status: "page-changed", wasDeleted: true }, read: (result) => result.wasDeleted, expected: true },
    { refusal: { status: "rejected", code: "x" }, read: (result) => result.code, expected: "x" },
    { refusal: { status: "permission-denied", detail: "no" }, read: (result) => result.detail, expected: "no" },
  ];
  for (const { refusal, read, expected } of cases) {
    const pages = service({ refuse: (title) => (title === "Volcano" ? refusal : null) });
    const wiki = createFakeWiki({ pages });
    const result = await wiki.edit("Volcano", "new", "why");
    assert.equal(result.status, refusal.status);
    assert.equal(read(result), expected);
    assert.equal((await wiki.edit("Template:Foo", "new", "why")).status, "saved");
    wiki.close();
  }
}

async function failures(): Promise<void> {
  const failure: FakeFailure = { code: "readonly", info: "down for a while" };
  const pages = service({ failWith: () => failure });
  const wiki = createFakeWiki({ pages });
  const report = await wiki.botRun({ source: pageSourceOfTitles(["Volcano"]), dryRun: false, transform: addAMark });
  assert.equal(report.failed, 1);
  assert.equal(report.stopReason, "read-only");
  const problem = assertArray(report.problems)[0];
  assert.ok(problem != null);
  assert.equal(describeError(problem.error)?.kind, "read-only");
  wiki.close();

  const unimplemented = createFakeWiki();
  await assert.rejects(collect(unimplemented.lists.backlinks("Volcano")), /does not implement lists/);
  await assert.rejects(collect(unimplemented.categoryMembers("Lava")), /does not implement lists/);
  await assert.rejects(collect(unimplemented.history("Volcano")), /does not implement revisions/);
  await assert.rejects(collect(unimplemented.users.contributions("Alice")), /does not implement users/);
  await assert.rejects(collect(unimplemented.logs.events()), /does not implement logs/);
  await assert.rejects(unimplemented.files.latest("Fire.jpg"), /does not implement files/);
  await assert.rejects(unimplemented.meta.statistics(), /does not implement meta/);
  await assert.rejects(unimplemented.render.render("Volcano"), /does not implement renderer/);
  await assert.rejects(unimplemented.proofread.quality(["Page:X"]), /does not implement proofread/);
  await assert.rejects(unimplemented.call({ action: "query" }), /transport\.call\(query\)/);
  await assert.rejects(unimplemented.botRun({ source: parsePageSource("cat:Lava"), transform: addAMark }), /does not implement lists/);
  await assert.rejects(unimplemented.pagesService.edit("Volcano", { text: "x", summary: "y", section: "1" }), /numbered sections/);
  unimplemented.close();
}

async function aMockTransport(): Promise<void> {
  const transport = new MockTransport((params): Record<string, JsonValue> | Promise<Record<string, JsonValue>> => {
    assertRecord(params, "string");
    if (params.action === "fail") throw new Error("no");
    if (params.action === "slow") return Promise.resolve({ slow: true });
    return { query: { a: 1 } };
  });
  const wiki = createFakeWiki({ transport });

  const answer = await wiki.call({ action: "query", meta: "x" });
  assert.deepEqual(answer, { query: { a: 1 } });
  assert.deepEqual(await wiki.call({ action: "slow" }), { slow: true });
  assert.equal((await collect(wiki.callPaged({ action: "query", meta: "x" }))).length, 1);
  await assert.rejects(wiki.call({ action: "fail" }), /no/);
  await wiki.call({ action: "query", meta: "x" }, { write: true, cacheable: false });

  const sent = assertArray(transport.requests);
  const first = sent[0];
  assert.ok(first instanceof MockRequest);
  assert.equal(first.action, "query");
  assert.equal(assertRecord(first.params, "string").meta, "x");
  assert.equal(first.isWrite, false);
  assert.equal(first.cacheable, true);
  const last = sent[sent.length - 1];
  assert.ok(last != null);
  assert.equal(last.isWrite, true);
  assert.equal(last.cacheable, false);
  assert.equal(wiki.endpoint.server, "test.example.org");
  wiki.close();

  const elsewhere = new MockTransport(() => ({}), endpointOf("other.example.org"));
  const other = createFakeWiki({ transport: elsewhere });
  assert.equal(other.endpoint.server, "other.example.org");
  other.close();
}

async function whoItSaysItIs(): Promise<void> {
  const identity: FakeIdentity = { name: "Me", id: 5, groups: ["user"], rights: ["edit"] };
  const wiki = createFakeWiki({
    id: "mywiki",
    namespaces: createNamespaces([
      { id: 0, localName: "" },
      { id: 2, localName: "Benutzer" },
    ]),
    identity,
  });
  assert.equal(wiki.id, "mywiki");
  assert.equal(wiki.user.name, "Me");
  assert.deepEqual(assertArray(wiki.user.groups), ["user"]);
  assert.deepEqual(assertArray(wiki.user.rights), ["edit"]);
  assert.equal(wiki.identity.id, 5);
  assert.equal(wiki.ref("Benutzer:Me"), "Benutzer:Me");
  wiki.close();

  const anonymous = createFakeWiki({ identity: { name: "192.0.2.1", id: 0 } });
  assert.equal(anonymous.user.isAnonymous, true);
  anonymous.close();
}

async function servicesFromAnotherWiki(): Promise<void> {
  const site = new FakeSite({
    categorymembers: { query: { categorymembers: [{ ns: 0, title: "Etna" }] } },
  });
  await withWiki(site, async (real) => {
    const wiki = createFakeWiki({ pages: service(), services: real });
    assert.deepEqual(await collect(wiki.categoryMembers("Volcanoes")), ["Etna"]);
    assert.equal((await wiki.page("Volcano"))?.text, "ash");
    await assert.rejects(wiki.files.latest("Fire.jpg"));
    wiki.close();
  });
}

const COLOURS: Choice[] = [
  { key: "r", label: "Red", value: "the red one" },
  { key: "b", label: "Blue", value: "the blue one" },
];

async function prompts(): Promise<void> {
  const nobody = new NonInteractive();
  assert.ok(nobody instanceof Prompt);
  assert.equal(nobody.isInteractive, false);
  assert.equal(await nobody.confirm("Go?"), false);
  assert.equal(await nobody.text("Name?"), "");
  assert.equal(await nobody.choose("Colour?", COLOURS), "the red one");
  assert.equal(typeof nobody.asked, "number");
  assert.equal(nobody.asked, 3);

  const agreeable: Prompt = new NonInteractive(true, "Alice");
  assert.equal(await agreeable.confirm("Go?"), true);
  assert.equal(await agreeable.confirm("Go?", false), true);
  assert.equal(await agreeable.text("Name?", "Bob"), "Alice");

  const written: string[] = [];
  const typed: Array<string | null> = ["y", "", "n", "x", "B", "Alice", "", null, null];
  const terminal = new TerminalPrompt(
    (text) => {
      written.push(text);
    },
    async () => typed.shift() ?? null,
  );
  assert.ok(terminal instanceof Prompt);
  assert.equal(terminal.isInteractive, true);
  assert.equal(await terminal.confirm("Go?"), true);
  assert.equal(await terminal.confirm("Go?", true), true);
  assert.equal(await terminal.confirm("Go?", true), false);
  assert.equal(await terminal.choose("Colour?", COLOURS), "the blue one");
  assert.equal(await terminal.text("Name?"), "Alice");
  assert.equal(await terminal.text("Name?", "Bob"), "Bob");
  assert.equal(await terminal.confirm("Go?", true), true);
  assert.equal(await terminal.text("Name?", "Bob"), "Bob");
  assert.ok(written.some((text) => text.includes("Go?")), written.join("|"));
  assert.ok(written.some((text) => text.includes("Red")), written.join("|"));

  const synchronous = new TerminalPrompt(() => undefined, () => "y");
  assert.equal(await synchronous.confirm("Go?"), true);
  const unset = new TerminalPrompt(null, null);
  assert.ok(unset instanceof Prompt);

  const scripted = new ScriptedPrompt(["y", "b", "Alice"]);
  assert.ok(scripted instanceof Prompt);
  assert.equal(await scripted.confirm("Go?"), true);
  assert.equal(await scripted.choose("Colour?", COLOURS), "the blue one");
  assert.equal(await scripted.text("Name?"), "Alice");
  assert.equal(await scripted.confirm("Out of answers?"), false);

  const fallingBack = new ScriptedPrompt(["", "y"], new NonInteractive(true));
  assert.equal(await fallingBack.confirm("Enter?", true), true);
  assert.equal(await fallingBack.confirm("Written?"), true);
  assert.equal(await fallingBack.confirm("Told?"), true);

  const seen: string[] = [];
  const handlers: PromptHandlers = {
    isInteractive: false,
    confirm: async (question, fallback) => {
      seen.push(`${question}:${fallback}`);
      return !fallback;
    },
    choose: (question, choices) => {
      assertArray(choices);
      seen.push(`${question}:${choices.length}`);
      const last = choices[choices.length - 1];
      assert.ok(last != null);
      assert.equal(last.key, "b");
      assert.equal(last.label, "Blue");
      return last.value;
    },
    text: async (question, fallback) => {
      seen.push(`${question}:${fallback}`);
      return fallback.toUpperCase();
    },
  };
  const mine: Prompt = promptFrom(handlers);
  assert.ok(mine instanceof Prompt);
  assert.equal(mine.isInteractive, false);
  assert.equal(await mine.confirm("Go?"), true);
  assert.equal(await mine.confirm("Go?", true), false);
  assert.equal(await mine.choose("Colour?", COLOURS), "the blue one");
  assert.equal(await mine.text("Name?", "bob"), "BOB");
  assert.deepEqual(seen, ["Go?:false", "Go?:true", "Colour?:2", "Name?:bob"]);

  const interactive = promptFrom({ confirm: () => true, choose: () => "x", text: () => "y" });
  assert.equal(interactive.isInteractive, true);
}

function rejectedTestingShapes(): void {
  // @ts-expect-error a refusal's status is one of those an edit can have
  const typo: FakeRefusal = { status: "protect" };
  // @ts-expect-error a saved edit is not a refusal
  const saved: FakeRefusal = { status: "saved" };
  // @ts-expect-error a transport answers in JSON
  const answer = new MockTransport(() => 3);
  // @ts-expect-error a prompt's handlers answer with what they ask for
  const wrong: PromptHandlers = { confirm: () => "yes", choose: () => "x", text: () => "y" };
  void [typo, saved, answer, wrong];
}
void rejectedTestingShapes;

await aBotOnAFakeWiki();
await thePageServiceAnswers();
await refusals();
await failures();
await aMockTransport();
await whoItSaysItIs();
await servicesFromAnotherWiki();
await prompts();

console.log("testing: bots ran on fake wikis, and every kind of prompt answered");
