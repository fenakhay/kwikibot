import assert from "node:assert/strict";
import { BotPassword, type JsonValue, parse, type Wiki } from "kwikibot";
import {
  type ApplyTagsOptions,
  type CallOptions,
  Identity,
  Interwiki,
  LanguageInfo,
  LimitReport,
  MetaService,
  ModuleDescription,
  type ModuleGroup,
  NamespaceInfo,
  Namespaces,
  ParamDescription,
  ParamInfo,
  ParsedPage,
  type ParseProperty,
  type ParseRequest,
  RenderedSection,
  RenderService,
  ResolvedLink,
  SiteInfo,
  type TagOperation,
  TempAccountConfig,
  type TemplateSandboxOptions,
  TokenStore,
} from "kwikibot/client";
import { MagicWords, ParseOptions, TitleRules } from "kwikibot/wikitext";
import { type Answer, FakeSite, assertArray, assertNone, assertRecord, withWiki } from "./support.js";

const BLOCKS: Record<string, JsonValue> = {
  general: {
    wikiid: "testwiki",
    sitename: "Test Wiki",
    lang: "fr",
    timezone: "Europe/Paris",
    timeoffset: 120,
    server: "//test.example.org",
    articlepath: "/wiki/$1",
    mainpage: "Main Page",
    generator: "MediaWiki 1.47.0-wmf.17",
    langconversion: false,
  },
  namespaces: {
    "0": { id: 0, name: "", case: "first-letter" },
    "2": { id: 2, name: "Utilisateur", canonical: "User", case: "first-letter", subpages: true },
    "6": { id: 6, name: "Fichier", canonical: "File", case: "first-letter" },
    "10": { id: 10, name: "Modèle", canonical: "Template", case: "case-sensitive" },
    "14": { id: 14, name: "Catégorie", canonical: "Category", case: "first-letter" },
  },
  namespacealiases: [{ id: 14, alias: "Cat" }],
  interwikimap: [
    { prefix: "w", url: "https://en.wikipedia.org/wiki/$1" },
    { prefix: "self", url: "https://test.example.org/wiki/$1" },
  ],
  extensions: [{ name: "Echo" }],
  autocreatetempuser: { enabled: true, matchPatterns: ["~2$1"] },
  extensiontags: ["<custom>"],
  protocols: ["https://"],
  functionhooks: ["lc"],
  variables: ["pagename"],
  magicwords: [
    { name: "redirect", aliases: ["#REDIRECT", "#REDIRECTION"] },
    { name: "lc", aliases: ["lc:"], "case-sensitive": false },
    { name: "pagename", aliases: ["PAGENAME"], "case-sensitive": true },
    { name: "subst", aliases: ["subst:"] },
    { name: "msg", aliases: ["msg:"] },
  ],
  statistics: { pages: 12, articles: 3, edits: 99, users: 4, activeusers: 1 },
  rightsinfo: { url: "https://example.org", count: [1, 2] },
};

function siteinfo(params: Record<string, string>): JsonValue {
  const blocks: Record<string, JsonValue> = {};
  for (const name of (params.siprop ?? "").split("|")) {
    const block = BLOCKS[name];
    if (block !== undefined) blocks[name] = block;
  }
  return { query: blocks };
}

const USERCONTRIBS: JsonValue = {
  name: "usercontribs",
  path: "query+usercontribs",
  prefix: "uc",
  source: "MediaWiki",
  sourcename: "MW",
  group: "list",
  internal: true,
  deprecated: true,
  mustbeposted: true,
  parameters: [
    { name: "limit", type: "limit", max: 500, highmax: 5000, default: "10", required: true },
    {
      name: "prop",
      type: ["ids", "title"],
      multi: true,
      limit: 50,
      highlimit: 500,
      deprecatedvalues: ["title"],
      internalvalues: ["ids"],
      deprecated: true,
      sensitive: true,
    },
  ],
};

const PLAIN: JsonValue = { name: "plain", path: "query+plain", parameters: [{ name: "q", type: "string" }] };

function paraminfo(params: Record<string, string>): JsonValue {
  const modules: JsonValue[] = [];
  for (const name of (params.modules ?? "").split("|")) {
    if (name === "query+usercontribs" || name === "query+*") modules.push(USERCONTRIBS);
    if (name === "query+plain" || name === "query+*") modules.push(PLAIN);
    if (name === "query+nothing") modules.push({ name: "nothing", path: "query+nothing", missing: true });
  }
  return { paraminfo: { modules } };
}

const PARSED: JsonValue = {
  parse: {
    text: "<p>hi</p>",
    revid: 9,
    links: [
      { ns: 0, title: "Volcano", exists: true },
      { ns: 0, title: "Red", exists: false },
    ],
    templates: [{ ns: 10, title: "Template:Fire", exists: true }],
    categories: [{ category: "Red_rocks" }],
    images: ["Fire_pit.jpg"],
    externallinks: ["https://example.org"],
    limitreportdata: [
      { name: "limitreport-expensivefunctioncount", "0": 3, "1": 500 },
      { name: "scribunto-limitreport-timeusage", "0": "0.25" },
      { name: "scribunto-limitreport-memusage", "0": 1024 },
    ],
    sections: [
      { index: "1", line: "Top", level: "2", toclevel: 1, number: "1", anchor: "Top", byteoffset: 10 },
      { index: "T-1", line: "Borrowed", level: "3", toclevel: 2, number: "1.1", anchor: "Borrowed" },
    ],
  },
};

function parseAnswer(params: Record<string, string>): JsonValue {
  if (params.page === "Empty") return { parse: { title: "Empty" } };
  if (params.page === "Nothing") return {};
  if (params.page === "Plain") return { parse: { limitreportdata: [] } };
  if (params.page === "Modern") {
    return {
      parse: {
        tocdata: {
          sections: [{ index: "1", line: "Top", hLevel: 2, tocLevel: 1, number: "1", anchor: "Top", codepointOffset: 10 }],
        },
      },
    };
  }
  return PARSED;
}

function last(site: FakeSite, key: string, value: string): Record<string, string> {
  const found = site.requests.filter((request) => request[key] === value).at(-1);
  assert.ok(found != null, `no request with ${key}=${value}`);
  return found;
}

function field(value: JsonValue | undefined, key: string): JsonValue | undefined {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return undefined;
  return value[key];
}

async function readsTheSite(wiki: Wiki): Promise<void> {
  const info: SiteInfo = wiki.info;
  assert.ok(info instanceof SiteInfo);
  assert.equal(info.id, "testwiki");
  assert.equal(info.siteName, "Test Wiki");
  assert.equal(info.language, "fr");
  assert.equal(info.server, "test.example.org");
  assert.equal(info.articlePath, "/wiki/$1");
  assert.equal(info.mainPage, "Main Page");
  assert.equal(info.generator, "MediaWiki 1.47.0-wmf.17");
  assert.equal(info.version, "1.47.0-wmf.17");
  assert.equal(info.timezone, "Europe/Paris");
  assert.equal(info.timeOffsetMinutes, 120);
  assert.deepEqual(assertArray(info.extensions), ["Echo"]);
  assert.equal(info.hasExtension("echo"), true);
  assert.equal(info.hasExtension("Wikibase"), false);

  const temporary: TempAccountConfig = info.tempAccounts;
  assert.equal(temporary.enabled, true);
  assert.deepEqual(assertArray(temporary.matchPatterns), ["~2$1"]);
  assert.equal(temporary.matches("~2026-1"), true);
  assert.equal(temporary.matches("Alice"), false);

  const namespaces: Namespaces = info.namespaces;
  assert.deepEqual(
    assertArray(namespaces.all).map((namespace) => namespace.id),
    [0, 2, 6, 10, 14],
  );
  const main = namespaces.get(0);
  assert.ok(main != null);
  assert.equal(main.canonicalName, "");
  assert.equal(main.localName, "");
  assert.equal(main.case, "first-letter");
  assertNone(namespaces.get(999));
  assertNone(namespaces.byPrefix("nothing"));

  const category: NamespaceInfo | null | undefined = namespaces.byPrefix("cat");
  assert.ok(category != null);
  assert.equal(category.id, 14);
  assert.equal(category.canonicalName, "Category");
  assert.equal(category.localName, "Catégorie");
  assert.deepEqual(assertArray(category.aliases), ["Cat"]);
  assert.equal(category.subpages, false);
  assert.deepEqual([...assertArray(category.prefixes)].sort(), ["Cat", "Category", "Catégorie"]);

  const template = namespaces.get(10);
  assert.ok(template != null);
  assert.equal(template.case, "case-sensitive");
  assert.equal(namespaces.get(2)?.subpages, true);
  assert.equal(namespaces.format(wiki.parseTitleIn("Fire", 10)), "Modèle:Fire");
  assert.throws(() => namespaces.format(wiki.parseTitleIn("w:Fire", 0)), /only a title of this wiki/);

  const interwiki: Interwiki = info.interwiki;
  assert.equal(interwiki.has("w"), true);
  assert.equal(interwiki.has("zz"), false);
  assert.equal(interwiki.isSelf("w"), false);
  assert.equal(interwiki.isSelf("self"), true);
}

async function knowsWhoItIs(wiki: Wiki): Promise<void> {
  const identity: Identity = wiki.identity;
  assert.equal(identity.name, "Bot");
  assert.equal(identity.id, 7);
  assert.equal(identity.isBot, true);
  assert.equal(identity.isAnonymous, false);
  assert.deepEqual(assertArray(identity.groups), ["*", "bot", "user"]);
  assert.deepEqual(assertArray(identity.rights), ["bot", "edit"]);
  assert.equal(identity.batchLimit, 50);
  assert.equal(identity.has("edit"), true);
  assert.equal(identity.has("delete"), false);
}

async function asksAboutTheWiki(meta: MetaService, site: FakeSite): Promise<void> {
  assert.deepEqual(await meta.property("rightsinfo"), { url: "https://example.org", count: [1, 2] });
  assertNone(await meta.property("nothing"));

  const messages = assertRecord(await meta.messages(["edit", "gone"], "fr"), "string");
  assert.equal(messages.edit, "Modifier");
  assert.equal("gone" in messages, false);
  assert.equal(last(site, "meta", "allmessages").amlang, "fr");
  assert.equal(await meta.message("edit"), "Modifier");
  assert.equal(await meta.message("edit", null), "Modifier");
  assertNone(await meta.message("gone"));

  const words = assertRecord(await meta.magicWords());
  const redirect = words.redirect;
  assert.ok(redirect != null);
  assert.deepEqual(assertArray(redirect), ["#REDIRECT", "#REDIRECTION"]);

  const statistics = assertRecord(await meta.statistics(), "number");
  assert.equal(statistics.edits, 99);
  assert.equal(statistics.pages, 12);

  const languages = assertRecord(await meta.languages(["ar", "xx"]));
  assert.equal(last(site, "meta", "languageinfo").licode, "ar|xx");
  const arabic = languages.ar;
  assert.ok(arabic instanceof LanguageInfo);
  assert.equal(arabic.code, "ar");
  assert.equal(arabic.name, "Arabic");
  assert.equal(arabic.autonym, "العربية");
  assert.equal(arabic.direction, "right-to-left");
  assert.deepEqual(assertArray(arabic.fallbacks), ["en"]);
  assert.equal(arabic.bcp47, "ar");
  const unknown = languages.xx;
  assert.ok(unknown != null);
  assert.equal(unknown.direction, "left-to-right");
  assert.deepEqual(assertArray(unknown.fallbacks), []);
  assertNone(unknown.bcp47);

  await meta.languages();
  assert.equal("licode" in last(site, "meta", "languageinfo"), false);
}

async function tagsThings(meta: MetaService, site: FakeSite): Promise<void> {
  const everything: ApplyTagsOptions = {
    add: ["a", "b"],
    remove: ["c"],
    revisions: [1],
    recentChanges: [2],
    logEntries: [3],
    reason: "why",
  };
  assert.equal(await meta.applyTags(everything), undefined);
  const sent = last(site, "action", "tag");
  assert.equal(sent.add, "a|b");
  assert.equal(sent.remove, "c");
  assert.equal(sent.revid, "1");
  assert.equal(sent.rcid, "2");
  assert.equal(sent.logid, "3");
  assert.equal(sent.reason, "why");

  await assert.rejects(meta.applyTags({ revisions: [1] }), /must add or remove/);
  await assert.rejects(meta.applyTags({ add: ["a"] }), /needs revisions/);

  const retiring: TagOperation = "deactivate";
  assert.equal(await meta.manageTag("t", retiring, "why"), undefined);
  assert.equal(last(site, "action", "managetags").operation, "deactivate");
  assert.equal(await meta.manageTag("t", "create"), undefined);
  assert.equal(last(site, "action", "managetags").reason, "");
}

async function learnsTheApi(params: ParamInfo, site: FakeSite): Promise<void> {
  assert.equal(params.maxModules, 50);

  const found = await params.module("query+usercontribs");
  assert.ok(found instanceof ModuleDescription);
  assert.equal(found.name, "usercontribs");
  assert.equal(found.path, "query+usercontribs");
  assert.equal(found.prefix, "uc");
  assert.equal(found.source, "MediaWiki");
  assert.equal(found.sourceName, "MW");
  const group: ModuleGroup | null | undefined = found.group;
  assert.equal(group, "list");
  assert.equal(found.isWrite, true);
  assert.equal(found.mustBePosted, true);
  assert.equal(found.deprecated, true);
  assert.equal(found.internal, true);
  assert.equal(found.has("limit"), true);
  assert.equal(found.has("nothing"), false);
  assertNone(found.get("nothing"));

  const limit = found.get("limit");
  assert.ok(limit instanceof ParamDescription);
  assert.equal(limit.name, "limit");
  assert.equal(limit.type, "limit");
  assert.equal(limit.limit, 500);
  assert.equal(limit.highLimit, 5000);
  assert.equal(limit.required, true);
  assert.equal(limit.default, "10");
  assert.deepEqual(assertArray(limit.values), []);
  assertNone(limit.valueLimit);
  assertNone(limit.highValueLimit);

  const prop = assertRecord(found.parameters).prop;
  assert.ok(prop instanceof ParamDescription);
  assertNone(prop.type);
  assert.deepEqual(assertArray(prop.values), ["ids", "title"]);
  assert.equal(prop.multiValued, true);
  assert.equal(prop.deprecated, true);
  assert.equal(prop.sensitive, true);
  assert.equal(prop.valueLimit, 50);
  assert.equal(prop.highValueLimit, 500);
  assert.deepEqual(assertArray(prop.deprecatedValues), ["title"]);
  assert.deepEqual(assertArray(prop.internalValues), ["ids"]);
  assertNone(prop.limit);
  assertNone(prop.highLimit);
  assertNone(prop.default);
  assert.equal(prop.required, false);

  assert.equal(await params.limit("query+usercontribs", "limit", true), 5000);
  assert.equal(await params.limit("query+usercontribs", "limit", false), 500);
  assertNone(await params.limit("query+usercontribs", "prop", false));
  assert.equal(await params.supports("query+usercontribs", "prop"), true);
  assert.equal(await params.supports("query+usercontribs", "nothing"), false);
  assert.deepEqual(assertArray((await params.values("query+usercontribs", "prop")) ?? []), ["ids", "title"]);
  assertNone(await params.values("query+usercontribs", "limit"));
  assert.equal(await params.isDeprecated("query+usercontribs", "prop", "title"), true);
  assert.equal(await params.isDeprecated("query+usercontribs", "prop", "ids"), false);

  assert.equal(await params.prefetch(["query+plain", "query+nothing"]), undefined);
  const asked = site.requests.length;
  assertNone(await params.module("query+nothing"));
  const plain = await params.module("query+plain");
  assert.equal(site.requests.length, asked);
  assert.ok(plain != null);
  assert.equal(plain.prefix, "");
  assert.equal(plain.isWrite, false);
  assert.equal(plain.mustBePosted, false);
  assertNone(plain.source);
  assertNone(plain.sourceName);
  assertNone(plain.group);

  const all = assertArray(await params.modules(["query+*"]));
  assert.deepEqual(
    all.map((module) => module.name),
    ["usercontribs", "plain"],
  );
}

async function holdsTokens(wiki: Wiki, site: FakeSite): Promise<void> {
  const tokens: TokenStore = wiki.tokens;
  assert.deepEqual(
    [tokens.csrf, tokens.login, tokens.patrol, tokens.rollback, tokens.userRights, tokens.watch],
    ["csrf", "login", "patrol", "rollback", "userrights", "watch"],
  );
  assert.equal(await tokens.token(), "c+\\");
  assert.equal(await tokens.token(tokens.csrf), "c+\\");
  assert.equal(await tokens.token(tokens.patrol), "p+\\");

  const fetched = (): number => site.requests.filter((request) => request.meta === "tokens").length;
  const before = fetched();
  await tokens.token();
  assert.equal(fetched(), before);
  assert.equal(await tokens.invalidate(), undefined);
  await tokens.token();
  assert.equal(fetched(), before + 1);
  assert.equal(await tokens.invalidate(tokens.patrol), undefined);
  await tokens.token(tokens.patrol);
  assert.equal(fetched(), before + 2);
  assert.equal(await tokens.clear(), undefined);
  await tokens.token();
  assert.equal(fetched(), before + 3);

  let rounds = 0;
  site.answers.frobnicate = (): JsonValue => {
    rounds += 1;
    return rounds === 1 ? { error: { code: "badtoken", info: "stale" } } : { frobnicate: { ok: [1, 2] } };
  };
  const write: CallOptions = { write: true, cacheable: false };
  const answer = await tokens.withFreshToken((token) => wiki.call({ action: "frobnicate", token }, write));
  assert.equal(rounds, 2);
  assert.deepEqual(answer.frobnicate, { ok: [1, 2] });
  assert.equal(last(site, "action", "frobnicate").token, "c+\\");

  const five: number = await tokens.withFreshToken(() => 5, "csrf");
  assert.equal(five, 5);
  const none: null = await tokens.withFreshToken(() => null);
  assert.equal(none, null);
  const patrol: string = await tokens.withFreshToken((token) => Promise.resolve(token), tokens.patrol);
  assert.equal(patrol, "p+\\");

  await assert.rejects(
    tokens.withFreshToken(() => wiki.call({ action: "stale" })),
    /csrf/,
  );

  const methods: string[] = [];
  const stubbed = globalThis.fetch;
  globalThis.fetch = (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    methods.push(init?.method ?? "GET");
    return stubbed(input, init);
  };
  try {
    await wiki.call({ action: "frobnicate" });
    await wiki.call({ action: "frobnicate" }, { cacheable: true });
    await wiki.call({ action: "frobnicate" }, { write: true });
  } finally {
    globalThis.fetch = stubbed;
  }
  assert.deepEqual(methods, ["GET", "GET", "POST"]);
}

async function readsAsTheWikiDoes(wiki: Wiki): Promise<void> {
  const options = await wiki.parseOptions();
  assert.ok(options instanceof ParseOptions);
  assert.deepEqual(assertArray(options.extensionTags), ["custom"]);
  assert.deepEqual(assertArray(options.protocols), ["https://"]);
  assert.equal(options.languageConversion, false);
  assert.ok(assertArray(options.parsedTags).includes("ref"));
  assert.deepEqual(assertArray(options.preprocessedTags), ["inputbox"]);
  assert.ok(assertArray(options.htmlTags).length > 0);
  assert.deepEqual([...assertArray(options.fileNamespaces)].sort(), ["Fichier", "File"]);
  assert.equal(options.parse("<custom>{{x}}</custom>").templates().length, 0);
  assert.equal(parse("<custom>{{x}}</custom>").templates().length, 1);

  const rules = await wiki.titleRules();
  assert.ok(rules instanceof TitleRules);
  assert.equal(assertRecord(rules.namespaces, "number")["Modèle"], 10);
  assert.equal(assertRecord(rules.namespaces, "number").Cat, 14);
  assert.equal(assertRecord(rules.canonicalNames, "string")["10"], "Template");
  assert.deepEqual(assertArray(rules.caseSensitive), [10]);
  assert.deepEqual(assertArray(rules.substitutions), ["subst"]);
  assert.deepEqual(assertArray(rules.modifiers), ["msg"]);

  const functions: MagicWords = rules.functions;
  assert.equal(functions.has("LC"), true);
  assert.deepEqual(assertArray(functions.caseInsensitive), ["lc"]);
  assert.deepEqual(assertArray(functions.caseSensitive), []);
  const variables: MagicWords = rules.variables;
  assert.equal(variables.has("PAGENAME"), true);
  assert.equal(variables.has("pagename"), false);
  assert.deepEqual(assertArray(variables.caseSensitive), ["PAGENAME"]);

  assert.equal(rules.isParserFunction("PAGENAME"), true);
  assert.equal(rules.isParserFunction("PAGENAME", true), false);
  assert.equal(rules.key("foo"), "foo");
  assertNone(rules.key("PAGENAME"));
  assert.equal(rules.key("PAGENAME", true), "PAGENAME");

  const text = parse("{{foo}}{{Foo}}{{Modèle:foo}}{{PAGENAME}}");
  const templates = text.templates();
  const first = templates[0];
  const variable = templates[3];
  assert.ok(first != null && variable != null);
  assert.equal(rules.keyOf(first), "foo");
  assertNone(rules.keyOf(variable));
  assert.equal(assertArray(rules.templatesIn(text, ["foo"])).length, 2);
}

async function rendersPages(render: RenderService, site: FakeSite): Promise<void> {
  assert.deepEqual(assertArray(render.contents), ["links", "templates", "categories", "images", "external-links"]);
  assert.ok(assertArray(render.all).includes("toc-data"));
  assert.equal(render.all.includes("sections"), false);

  const headings = assertArray(await render.sections("Volcano"));
  const top: RenderedSection | undefined = headings[0];
  const borrowed: RenderedSection | undefined = headings[1];
  assert.ok(top != null && borrowed != null);
  assert.equal(top.index, "1");
  assert.equal(top.heading, "Top");
  assert.equal(top.level, 2);
  assert.equal(top.tocLevel, 1);
  assert.equal(top.number, "1");
  assert.equal(top.anchor, "Top");
  assert.equal(top.byteOffset, 10);
  assert.equal(borrowed.index, "T-1");
  assertNone(borrowed.byteOffset);
  assert.equal(last(site, "action", "parse").page, "Volcano");

  const modern = (await render.sections("Modern"))[0];
  assert.ok(modern != null);
  assert.equal(modern.level, 2);
  assert.equal(modern.tocLevel, 1);
  assert.equal(modern.byteOffset, 10);

  assert.equal(await render.render("Volcano"), "<p>hi</p>");
  assert.equal(last(site, "action", "parse").prop, "text");
  assert.equal(await render.renderText("hi", "Volcano"), "<p>hi</p>");
  assert.equal(last(site, "action", "parse").text, "hi");
  assert.equal(last(site, "action", "parse").title, "Volcano");

  const wanted: ParseProperty[] = ["links", "external-links"];
  const page: ParsedPage = await render.resolve("Volcano", wanted);
  assert.equal(last(site, "action", "parse").prop, "links|externallinks");
  assert.equal(page.html, "<p>hi</p>");
  assert.equal(page.revision, 9);
  const links = assertArray(page.links);
  const volcano: ResolvedLink | undefined = links[0];
  const red: ResolvedLink | undefined = links[1];
  assert.ok(volcano != null && red != null);
  assert.equal(volcano.page, "Volcano");
  assert.equal(volcano.exists, true);
  assert.equal(red.page, "Red");
  assert.equal(red.exists, false);
  assert.deepEqual(
    assertArray(page.templates).map((template) => template.page),
    ["Modèle:Fire"],
  );
  assert.deepEqual(assertArray(page.categories), ["Catégorie:Red rocks"]);
  assert.deepEqual(assertArray(page.images), ["Fichier:Fire pit.jpg"]);
  assert.deepEqual(assertArray(page.externalLinks), ["https://example.org"]);
  assert.equal(assertArray(page.sections)[0]?.heading, "Top");

  const report = page.limitReport;
  assert.ok(report instanceof LimitReport);
  assert.equal(report.expensiveFunctions, 3);
  assert.equal(report.luaTime, 250);
  assert.equal(report.luaMemory, 1024);
  assert.deepEqual(assertArray(assertRecord(report.entries)["limitreport-expensivefunctioncount"] ?? []), ["3", "500"]);

  await render.resolveText("hi", "Volcano");
  assert.equal(last(site, "action", "parse").prop, "links|templates|categories|images|externallinks");
  await render.resolveText("hi", "Volcano", render.contents);
  await render.resolve("Volcano", null);
  assert.equal(last(site, "action", "parse").prop, "links|templates|categories|images|externallinks");

  const empty = await render.resolve("Empty");
  assertNone(empty.html);
  assertNone(empty.revision);
  assertNone(empty.limitReport);
  assert.deepEqual(assertArray(empty.sections), []);
  assert.deepEqual(assertArray(empty.links), []);
  assert.deepEqual(assertArray(empty.templates), []);
  assert.deepEqual(assertArray(empty.categories), []);
  assert.deepEqual(assertArray(empty.images), []);
  assert.deepEqual(assertArray(empty.externalLinks), []);
  assert.equal(await render.render("Empty"), "");

  const nothing = await render.resolve("Nothing");
  assertNone(nothing.html);

  const blank = (await render.resolve("Plain")).limitReport;
  assert.ok(blank != null);
  assert.deepEqual(assertRecord(blank.entries), {});
  assertNone(blank.luaTime);
  assertNone(blank.luaMemory);
  assertNone(blank.expensiveFunctions);

  const sandbox: TemplateSandboxOptions = {
    prefixes: ["User:Me/sandbox"],
    title: "Template:Fire",
    text: "new",
    contentModel: "wikitext",
  };
  const request: ParseRequest = {
    text: "{{x}}",
    context: "Volcano",
    properties: ["text", "limit-report"],
    sandbox,
    revision: 7,
    parser: "parsoid",
  };
  await render.parse(request);
  const sent = last(site, "action", "parse");
  assert.equal(sent.prop, "text|limitreportdata");
  assert.equal(sent.templatesandboxprefix, "User:Me/sandbox");
  assert.equal(sent.templatesandboxtitle, "Modèle:Fire");
  assert.equal(sent.templatesandboxtext, "new");
  assert.equal(sent.templatesandboxcontentmodel, "wikitext");
  assert.equal(sent.revid, "7");
  assert.equal(sent.parser, "parsoid");

  const saved: ParseRequest = { page: "Volcano" };
  assert.equal((await render.parse(saved)).revision, 9);
  assert.equal(last(site, "action", "parse").page, "Volcano");

  await assert.rejects(render.parse({ text: "x" }), /context/);
  await assert.rejects(render.parse({}));
  await assert.rejects(render.parse({ page: "Volcano", text: "x", context: "Volcano" }));
}

function misuses(meta: MetaService, render: RenderService): void {
  // @ts-expect-error burn is no operation on a tag
  void meta.manageTag("t", "burn");
  // @ts-expect-error a parse property is spelled external-links
  void render.resolve("Volcano", ["externallinks"]);
  // @ts-expect-error there is no parser called new
  const request: ParseRequest = { page: "Volcano", parser: "new" };
  void request;
  // @ts-expect-error revision ids are numbers
  const tags: ApplyTagsOptions = { revisions: ["1"] };
  void tags;
  // @ts-expect-error the direction of a language is spelled out
  const direction: LanguageInfo["direction"] = "rtl";
  void direction;
  // @ts-expect-error first-letter is hyphenated
  const titleCase: NamespaceInfo["case"] = "first letter";
  void titleCase;
}
void misuses;

const answers: Record<string, Answer> = {
  siteinfo,
  paraminfo,
  parse: parseAnswer,
  allmessages: {
    query: {
      allmessages: [
        { name: "edit", content: "Modifier" },
        { name: "gone", missing: true },
      ],
    },
  },
  languageinfo: {
    query: {
      languageinfo: {
        ar: { name: "Arabic", autonym: "العربية", dir: "rtl", fallbacks: ["en"], bcp47: "ar" },
        xx: { name: "Unknown", autonym: "xx", dir: "ltr" },
      },
    },
  },
  tag: { tag: [] },
  managetags: { managetags: {} },
  stale: { error: { code: "badtoken", info: "stale" } },
};

const site = new FakeSite(answers, ["Echo"]);

await withWiki(
  site,
  async (wiki) => {
    await readsTheSite(wiki);
    await knowsWhoItIs(wiki);
    await asksAboutTheWiki(wiki.meta, site);
    await tagsThings(wiki.meta, site);
    await learnsTheApi(wiki.paramInfo, site);
    await holdsTokens(wiki, site);
    await readsAsTheWikiDoes(wiki);
    await rendersPages(wiki.render, site);
  },
  new BotPassword("Bot", "task", "secret"),
);

const bare = new FakeSite();
await withWiki(bare, async (wiki) => {
  assert.equal(wiki.identity.isAnonymous, true);
  assert.equal(wiki.identity.id, 0);
  assert.equal(wiki.identity.isBot, false);
  assert.equal(wiki.identity.name, "192.0.2.1");
  assert.equal(wiki.info.tempAccounts.enabled, false);
  assert.deepEqual(assertArray(wiki.info.tempAccounts.matchPatterns), []);
  assert.equal(wiki.info.tempAccounts.matches("~2026-1"), false);
  assert.equal(wiki.info.timeOffsetMinutes, 0);
  assert.deepEqual(assertArray(wiki.info.extensions), []);
  assert.equal(wiki.namespaces.all[0]?.id, 0);
  assert.equal(wiki.interwiki.has("w"), true);
  bare.raw = (_url, params) => (params.meta === "tokens" ? Response.json({ query: { tokens: { csrftoken: "+\\" } } }) : undefined);
  await assert.rejects(wiki.tokens.token());
  bare.raw = undefined;
  const answer = field(await wiki.call({ action: "nothing" }), "error");
  assert.equal(field(answer, "code"), "unexpected");
});

console.log("meta_render: site information, identity, namespaces, tokens, paraminfo, parser rules and rendering read as typed");
