import assert from "node:assert/strict";
import {
  BotPassword,
  connect,
  detect,
  EditResult,
  type JsonValue,
  Page,
  Revision,
  User,
  Wiki,
} from "kwikibot";
import { BotReport, pageSourceOfTitles, RunReport, StopPolicy } from "kwikibot/bot";
import {
  type CallOptions,
  CategoryInfo,
  Contributor,
  type DeleteOptions,
  type EditOptions,
  type EditStatus,
  ExpandedText,
  type ExpandTextsOptions,
  ExtensionService,
  FileService,
  Identity,
  type ImportPageOptions,
  Interwiki,
  ListService,
  LogService,
  type MergeHistoryOptions,
  MetaService,
  type MoveOptions,
  Namespaces,
  PageActions,
  PageContributors,
  PageProtections,
  PageService,
  ParamInfo,
  type ProtectionRequest,
  type ProtectOptions,
  RelatedPages,
  RenderService,
  ResponseDecoder,
  RevisionService,
  type RollbackOptions,
  SiteInfo,
  SiteMatrix,
  TokenStore,
  type UndeleteOptions,
  type UndoOptions,
  UserService,
  type WatchMode,
} from "kwikibot/client";
import { ProofreadService } from "kwikibot/proofread";
import { createFakeWiki, FakePageService, type FakeRefusal } from "kwikibot/testing";
import { DayFormat, Endpoint, Signatures } from "kwikibot/titles";
import { Entity, EntityService } from "kwikibot/wikibase";
import { ParseOptions, TitleRules } from "kwikibot/wikitext";
import { type Answer, FakeSite, assertArray, assertDate, assertNone, assertRecord, collect, withWiki } from "./support.js";

const CONTACT = "https://example.org/typed";
const BOT = new BotPassword("TestBot", "task", "secret");

const MONTHS: JsonValue = {
  query: {
    allmessages: ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"].flatMap(
      (content, index) => {
        const name = ["january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"][index] ?? "";
        return [
          { name, content },
          { name: `${name}-gen`, content },
        ];
      },
    ),
  },
};

const HISTORY: JsonValue = {
  query: {
    pages: [
      {
        pageid: 1,
        ns: 0,
        title: "Volcano",
        revisions: [
          { revid: 9001, parentid: 9000, user: "Alice", timestamp: "2026-08-31T21:43:26Z", comment: "adding ash", size: 120, minor: true, bot: true, tags: ["mobile edit"], sha1: "abc123" },
          { revid: 9000, parentid: 8999, userhidden: true, commenthidden: true, timestamp: "2026-08-30T12:00:00Z", size: 100, tags: [] },
          { revid: 8999, parentid: 0, user: "Bob", timestamp: "2026-08-30T08:00:00Z", comment: "created", size: 80, tags: [] },
        ],
      },
    ],
  },
};

const ENTITY: JsonValue = {
  entities: {
    Q42: { type: "item", id: "Q42", lastrevid: 7, labels: { en: { language: "en", value: "Douglas Adams" } }, descriptions: {}, aliases: {}, claims: {} },
  },
};

const SITE_MATRIX: JsonValue = {
  sitematrix: {
    count: 1,
    "0": { code: "en", name: "English", site: [{ url: "https://en.wiktionary.org", dbname: "enwiktionary", code: "wiktionary" }] },
    specials: [],
  },
};

function wikiError(code: string, info = "no"): JsonValue {
  return { error: { code, info } };
}

function pagesAnswer(...entries: JsonValue[]): JsonValue {
  return { query: { pages: entries } };
}

async function finishes(promise: Promise<void>): Promise<void> {
  const value: unknown = await promise;
  assert.equal(value, undefined);
}

function lastOf(site: FakeSite, wanted: (params: Record<string, string>) => boolean): Record<string, string> {
  const found = site.requests.filter(wanted).at(-1);
  assert.ok(found != null, "no such request was sent");
  return found;
}

function sentAction(site: FakeSite, action: string): Record<string, string> {
  return lastOf(site, (params) => params.action === action);
}

function field(value: JsonValue | undefined, ...path: string[]): JsonValue | undefined {
  let here = value;
  for (const key of path) {
    if (here === null || typeof here !== "object" || Array.isArray(here)) return undefined;
    here = here[key];
  }
  return here;
}

/** A small wiki whose pages can be read and saved, each at one revision that every save advances. */
function pagesSite(texts: Record<string, string>, more: Record<string, Answer> = {}, extensions: string[] = []): FakeSite {
  let revision = 9001;
  const answers: Record<string, Answer> = {
    "info|revisions": (params) =>
      pagesAnswer(
        ...(params.titles ?? "").split("|").map((title): JsonValue => {
          const text = texts[title];
          const ns = title.startsWith("User:") ? 2 : 0;
          if (text === undefined) return { ns, title, missing: true };
          return {
            pageid: 1,
            ns,
            title,
            contentmodel: "wikitext",
            revisions: [{ revid: revision, timestamp: "2026-08-31T21:43:26Z", slots: { main: { content: text } } }],
          };
        }),
      ),
    edit: (params): JsonValue => {
      const title = params.title ?? "";
      if (params.baserevid !== undefined && Number(params.baserevid) !== revision) return wikiError("editconflict", "Edit conflict.");
      if (params.text !== undefined && texts[title] === params.text) return { edit: { result: "Success", pageid: 1, title, nochange: "", oldrevid: revision } };
      const previous = revision;
      revision += 1;
      texts[title] = params.text ?? `${params.prependtext ?? ""}${texts[title] ?? ""}${params.appendtext ?? ""}`;
      return { edit: { result: "Success", pageid: 1, title, oldrevid: previous, newrevid: revision } };
    },
    ...more,
  };
  return new FakeSite(answers, extensions);
}

async function openingAWiki(): Promise<void> {
  const site = pagesSite({});
  const restore = site.install();
  try {
    const anonymous = await connect("test.example.org", CONTACT, null);
    assert.equal(anonymous.user.isAnonymous, true);
    anonymous.close();

    const shortForm = await connect("test.example.org", CONTACT);
    assert.equal(shortForm.endpoint.scriptPath, "/w");
    shortForm.close();

    const wiki: Wiki = await connect("test.example.org", CONTACT, BOT, "/w");
    assert.ok(wiki instanceof Wiki);
    assert.equal(wiki.user.name, "TestBot");
    wiki.close();

    await assert.rejects(connect("test.example.org", CONTACT, new BotPassword("TestBot", "task", "wrong")));

    assert.equal(BOT.account, "TestBot");
    assert.equal(BOT.botName, "task");

    site.raw = (): Response =>
      new Response('<!DOCTYPE html><html><head><link rel="EditURI" href="//wiki.example.org/w/api.php?action=rsd"></head><body></body></html>', {
        status: 200,
        headers: { "Content-Type": "text/html" },
      });
    const endpoint = await detect("https://wiki.example.org/wiki/Main_Page", CONTACT);
    assert.ok(endpoint != null);
    assert.ok(endpoint instanceof Endpoint);
    assert.equal(endpoint.apiUrl, "https://wiki.example.org/w/api.php");
    assert.equal(endpoint.server, "wiki.example.org");
    assert.equal(endpoint.scriptPath, "/w");
    assert.equal(endpoint.secure, true);
    assert.equal(endpoint.indexUrl, "https://wiki.example.org/w/index.php");
    assert.match(endpoint.articleUrl("Main Page"), /^https:\/\/wiki\.example\.org\/.*Main/);

    site.raw = (): Response => new Response("<html><head></head></html>", { status: 200, headers: { "Content-Type": "text/html" } });
    assertNone(await detect("https://example.org/", CONTACT));

    site.raw = (): Response => {
      throw new Error("connection refused");
    };
    assertNone(await detect("https://example.org/", CONTACT));
  } finally {
    restore();
  }
}

async function whoAndWhatTheWikiIs(): Promise<void> {
  const site = pagesSite({}, { allmessages: MONTHS, redirects: pagesAnswer({ pageid: 3, ns: 10, title: "Template:L", redirects: [{ pageid: 4, ns: 10, title: "Template:Link" }] }), wbgetentities: ENTITY, sitematrix: SITE_MATRIX }, ["Echo"]);
  await withWiki(
    site,
    async (wiki) => {
      assert.equal(wiki.id, "testwiki");
      assert.equal(wiki.siteName, "Test Wiki");
      assert.equal(wiki.mediaWikiVersion, "1.47.0");
      assert.ok(wiki.info instanceof SiteInfo);
      assert.ok(wiki.identity instanceof Identity);
      assert.ok(wiki.namespaces instanceof Namespaces);
      assert.ok(wiki.interwiki instanceof Interwiki);
      assert.ok(wiki.endpoint instanceof Endpoint);
      assert.equal(wiki.endpoint.server, "test.example.org");
      assert.ok(wiki.decoder instanceof ResponseDecoder);
      assert.ok(wiki.lists instanceof ListService);
      assert.ok(wiki.revisions instanceof RevisionService);
      assert.ok(wiki.logs instanceof LogService);
      assert.ok(wiki.users instanceof UserService);
      assert.ok(wiki.pagesService instanceof PageService);
      assert.ok(wiki.files instanceof FileService);
      assert.ok(wiki.meta instanceof MetaService);
      assert.ok(wiki.render instanceof RenderService);
      assert.ok(wiki.extensions instanceof ExtensionService);
      assert.ok(wiki.proofread instanceof ProofreadService);
      assert.ok(wiki.paramInfo instanceof ParamInfo);
      assert.ok(wiki.tokens instanceof TokenStore);
      assert.ok(wiki.entities instanceof EntityService);

      const seen: User = wiki.user;
      assert.ok(seen instanceof User);
      assert.equal(seen.name, "TestBot");
      assert.equal(seen.isAnonymous, false);
      assert.deepEqual(assertArray(seen.groups), ["*", "bot", "user"]);
      assert.deepEqual(assertArray(seen.rights), ["bot", "edit"]);
      const now = await wiki.whoami();
      assert.deepEqual(assertArray(now.groups), ["*", "bot", "user"]);
      assert.deepEqual(assertArray(now.rights), ["bot", "edit"]);

      assert.equal(wiki.ref("template:infobox"), "Template:Infobox");
      assert.equal(wiki.ref("infobox", 10), "Template:Infobox");
      assert.equal(wiki.ref("volcano", null), "Volcano");
      assert.throws(() => wiki.ref("w:Etna"));
      assert.equal(wiki.parseTitle("template:infobox").toString(), "Template:Infobox");
      assert.equal(wiki.parseTitle("w:Etna").kind, "interwiki");
      assert.equal(wiki.parseTitleIn("infobox", 10).toString(), "Template:Infobox");

      assert.equal(await wiki.hasRight("edit"), true);
      assert.equal(await wiki.hasRight("delete"), false);
      await finishes(wiki.requireRight("edit"));
      await assert.rejects(wiki.requireRight("delete"));
      wiki.requireGroup("bot");
      assert.throws(() => wiki.requireGroup("sysop"));
      assert.equal(wiki.hasExtension("Echo"), true);
      assert.equal(wiki.hasExtension("Nope"), false);
      wiki.requireExtension("Echo");
      assert.throws(() => wiki.requireExtension("Nope"));
      assert.equal(wiki.hasVersion("1.47.0"), true);
      assert.equal(wiki.hasVersion("1.48.0"), false);
      wiki.requireVersion("1.40");
      assert.throws(() => wiki.requireVersion("1.48"));

      const months = assertRecord(await wiki.monthNames(), "number");
      assert.equal(months["août"], 8);
      const signatures = await wiki.signatures();
      assert.ok(signatures instanceof Signatures);
      const times = assertArray(await wiki.signatureTimes("Alice 31 août 2026 à 21:43 (CEST)"));
      assert.equal(times.length, 1);
      assert.equal(assertDate(times[0]?.date).toISOString(), "2026-08-31T19:43:00.000Z");
      const format = await wiki.dayTitleFormat();
      assert.ok(format instanceof DayFormat);
      assert.equal(format.months[0], "janvier");
      assert.deepEqual(assertArray(await wiki.templateNames("l")), ["L", "Link"]);

      const entity = await wiki.entity("Q42");
      assert.ok(entity instanceof Entity);
      assert.equal(entity.id, "Q42");
      site.answers.wbgetentities = { entities: { Q404: { id: "Q404", missing: "" } } };
      assertNone(await wiki.entity("Q404"));
      const matrix = await wiki.siteMatrix();
      assert.ok(matrix instanceof SiteMatrix);
      assert.equal(matrix.wikis[0]?.id, "enwiktionary");

      const stop = wiki.stopPage();
      assert.ok(stop instanceof StopPolicy);
      assert.equal(await stop.mayContinue(), false);
      assert.equal(lastOf(site, (params) => params.prop === "info|revisions").titles, "User:TestBot/Stop");
      assert.ok(wiki.stopPage(null) instanceof StopPolicy);
      site.answers["info|revisions"] = pagesAnswer({ pageid: 9, ns: 2, title: "User:TestBot/Halt", revisions: [{ revid: 3, slots: { main: { content: "false" } } }] });
      assert.equal(await wiki.stopPage("User:TestBot/Halt").mayContinue(), true);

      site.answers.siteinfo = {
        query: {
          extensiontags: ["<custom>"],
          protocols: ["https://"],
          general: { langconversion: false },
          namespaces: { "10": { id: 10, name: "Modèle", canonical: "Template", case: "case-sensitive" } },
          magicwords: [{ name: "lc", aliases: ["lc:"], "case-sensitive": false }, { name: "subst", aliases: ["subst:"] }, { name: "msg", aliases: ["msg:"] }],
          functionhooks: ["lc"],
          variables: [],
        },
      };
      const parsing = await wiki.parseOptions();
      assert.ok(parsing instanceof ParseOptions);
      assert.equal(parsing.parse("<custom>{{x}}</custom>").templates().length, 0);
      const rules = await wiki.titleRules();
      assert.ok(rules instanceof TitleRules);
      assert.equal(rules.namespaces["Modèle"], 10);
    },
    BOT,
  );
}

async function readingPages(): Promise<void> {
  const site = pagesSite({ Volcano: "==English==\n{{en-noun}}", Etna: "lava" });
  await withWiki(site, async (wiki) => {
    const page = await wiki.page("Volcano");
    assert.ok(page instanceof Page);
    assert.equal(page.title, "Volcano");
    assert.equal(page.text, "==English==\n{{en-noun}}");
    assert.equal(page.revision, 9001);
    assert.equal(assertDate(page.timestamp).toISOString(), "2026-08-31T21:43:26.000Z");
    assert.equal(page.isRedirect, false);
    assertNone(page.redirectTarget);
    assert.equal(page.contentModel, "wikitext");
    assert.equal(page.pageId, 1);
    assert.equal(page.wikitext().templates()[0]?.name, "en-noun");
    assertNone(await wiki.page("Nope"));

    const many = assertArray(await wiki.pages(["Etna", "Nope", "Volcano"]));
    assert.deepEqual(many.map((found) => found.title), ["Etna", "Volcano"]);

    site.answers["info|revisions"] = pagesAnswer({
      ns: 0,
      title: "Stromboli",
      redirect: true,
      contentmodel: "wikitext",
      revisions: [{ revid: 5, slots: { main: { content: "#REDIRECT [[w:Etna]]" } } }],
    });
    const redirect = await wiki.page("Stromboli");
    assert.ok(redirect != null);
    assert.equal(redirect.isRedirect, true);
    assert.equal(redirect.redirectTarget?.toLowerCase(), "w:etna");
    assertNone(redirect.timestamp);
    assertNone(redirect.pageId);

    site.answers.revisions = HISTORY;
    const revisions = await collect(wiki.history("Volcano"));
    assert.deepEqual(revisions.map((revision) => revision.id), [9001, 9000, 8999]);
    const [latest, hidden, first] = [revisions[0], revisions[1], revisions[2]];
    assert.ok(latest instanceof Revision && hidden instanceof Revision && first instanceof Revision);
    assert.equal(latest.parentId, 9000);
    assert.equal(assertDate(latest.timestamp).toISOString(), "2026-08-31T21:43:26.000Z");
    assert.equal(latest.user, "Alice");
    assert.equal(latest.comment, "adding ash");
    assert.equal(latest.size, 120);
    assert.equal(latest.minor, true);
    assert.equal(latest.bot, true);
    assert.deepEqual(assertArray(latest.tags), ["mobile edit"]);
    assert.equal(latest.sha1, "abc123");
    assert.equal(latest.isUserHidden, false);
    assert.equal(latest.isCommentHidden, false);
    assertNone(hidden.user);
    assertNone(hidden.comment);
    assert.equal(hidden.isUserHidden, true);
    assert.equal(hidden.isCommentHidden, true);
    assertNone(hidden.sha1);
    assert.equal(hidden.minor, false);
    assertNone(first.parentId);
    assert.deepEqual(assertArray(first.tags), []);

    site.answers.categorymembers = { query: { categorymembers: [{ ns: 0, title: "Etna" }, { ns: 0, title: "Fuji" }, { ns: 0, title: "Vesuvius" }] } };
    assert.deepEqual(await collect(wiki.categoryMembers("Volcanoes")), ["Etna", "Fuji", "Vesuvius"]);
    for await (const title of wiki.categoryMembers("Category:Volcanoes")) {
      assert.equal(title, "Etna");
      break;
    }
    assert.equal(lastOf(site, (params) => params.list === "categorymembers").cmtitle, "Category:Volcanoes");
    site.answers.search = { query: { search: [{ ns: 0, title: "Volcano" }, { ns: 0, title: "Volcanic ash" }] } };
    assert.deepEqual(await collect(wiki.search("ash")), ["Volcano", "Volcanic ash"]);
    site.answers.embeddedin = { query: { embeddedin: [{ ns: 0, title: "Volcano" }] } };
    assert.deepEqual(await collect(wiki.embeddedIn("Infobox volcano")), ["Volcano"]);
    assert.equal(lastOf(site, (params) => params.list === "embeddedin").eititle, "Template:Infobox volcano");
  });
}

async function savingWithWikiEdit(): Promise<void> {
  const site = pagesSite({ Volcano: "ash" });
  await withWiki(
    site,
    async (wiki) => {
      const page = await wiki.page("Volcano");
      assert.ok(page != null);

      const saved = await wiki.edit("Volcano", "tephra", "naming it", page.revision, true);
      assert.ok(saved instanceof EditResult);
      const status: EditStatus = saved.status;
      assert.equal(status, "saved");
      assert.equal(saved.revision, 9002);
      assert.equal(saved.previousRevision, 9001);
      assertNone(saved.detail);
      assert.equal(saved.isRetryable, false);
      assertNone(saved.currentRevision);
      assertNone(saved.protectionLevel);
      assertNone(saved.isCascadingProtection);
      assertNone(saved.filter);
      assertNone(saved.retryAfter);
      assertNone(saved.captchaId);
      assertNone(saved.wasDeleted);
      assertNone(saved.code);
      const sent = sentAction(site, "edit");
      assert.equal(sent.summary, "naming it");
      assert.equal(sent.baserevid, "9001");
      assert.equal(sent.minor, "1");
      assert.equal(sent.bot, "1");

      const stale = await wiki.edit("Volcano", "tephra", "naming it", 8000);
      assert.equal(stale.status, "conflict");
      assertNone(stale.revision);
      assertNone(stale.previousRevision);
      assert.equal(stale.detail, "Edit conflict.");
      assert.equal(stale.isRetryable, true);
      assertNone(stale.currentRevision);

      const same = await wiki.edit("Volcano", "tephra", "again");
      assert.equal(same.status, "unchanged");
      assert.equal(same.revision, 9002);
      assertNone(same.previousRevision);
      assert.equal(sentAction(site, "edit").baserevid, undefined);

      site.answers.edit = { edit: { result: "Success", pageid: 1, title: "Volcano", nochange: "" } };
      const quiet = await wiki.edit("Volcano", "tephra", "again", null, false);
      assert.equal(quiet.status, "unchanged");
      assertNone(quiet.revision);
    },
    BOT,
  );
}

async function refusalsOverTheWire(): Promise<void> {
  const site = new FakeSite();
  await withWiki(
    site,
    async (wiki) => {
      const pages = wiki.pagesService;
      const editOnce = async (code: string): Promise<EditResult> => {
        site.answers.edit = wikiError(code, "the wiki said no");
        return pages.edit("Volcano", { text: "x" });
      };

      const filtered = await editOnce("abusefilter-disallowed");
      assert.equal(filtered.status, "filtered");
      assert.equal(filtered.isRetryable, false);
      assert.equal(filtered.detail, "the wiki said no");
      assertNone(filtered.filter);

      const conflict = await editOnce("editconflict");
      assert.equal(conflict.status, "conflict");
      assert.equal(conflict.isRetryable, true);
      assertNone(conflict.currentRevision);

      const protectedPage = await editOnce("protectedpage");
      assert.equal(protectedPage.status, "protected");
      assert.equal(protectedPage.isCascadingProtection, false);
      assertNone(protectedPage.protectionLevel);

      const cascading = await editOnce("cascadeprotected");
      assert.equal(cascading.status, "protected");
      assert.equal(cascading.isCascadingProtection, true);

      const denied = await editOnce("permissiondenied");
      assert.equal(denied.status, "permission-denied");

      const limited = await editOnce("ratelimited");
      assert.equal(limited.status, "rate-limited");
      assert.equal(limited.isRetryable, true);

      const captcha = await editOnce("captcha");
      assert.equal(captcha.status, "captcha");
      assertNone(captcha.captchaId);

      const deleted = await editOnce("pagedeleted");
      assert.equal(deleted.status, "page-changed");
      assert.equal(deleted.wasDeleted, true);

      const created = await editOnce("articleexists");
      assert.equal(created.status, "page-changed");
      assert.equal(created.wasDeleted, false);

      const tooBig = await editOnce("contenttoobig");
      assert.equal(tooBig.status, "rejected");
      assert.equal(tooBig.code, "contenttoobig");

      site.answers.edit = { edit: { result: "Failure" } };
      const failed = await pages.edit("Volcano", { text: "x" });
      assert.equal(failed.status, "rejected");
      assert.equal(failed.code, "failure");
      assertNone(failed.filter);

      await assert.rejects(editOnce("somethingnobodymodels"));
    },
    BOT,
  );
}

async function refusedOnAFakeWiki(refusal: FakeRefusal): Promise<EditResult> {
  const pages = new FakePageService({ pages: { Volcano: "lava" }, refuse: (title) => (title === "Volcano" ? refusal : null) });
  const wiki = createFakeWiki({ pages });
  try {
    const result = await wiki.pagesService.edit("Volcano", { text: "x" });
    assert.equal(result.status, refusal.status);
    assert.equal(result.detail, refusal.detail ?? "");
    return result;
  } finally {
    wiki.close();
  }
}

async function refusalsThatSayMoreOnAFakeWiki(): Promise<void> {
  assert.equal((await refusedOnAFakeWiki({ status: "conflict", currentRevision: 5 })).currentRevision, 5);
  const guarded = await refusedOnAFakeWiki({ status: "protected", detail: "no", protectionLevel: "sysop", isCascadingProtection: true });
  assert.equal(guarded.protectionLevel, "sysop");
  assert.equal(guarded.isCascadingProtection, true);
  assert.equal((await refusedOnAFakeWiki({ status: "protected" })).isCascadingProtection, false);
  assert.equal((await refusedOnAFakeWiki({ status: "filtered", filter: "spam" })).filter, "spam");
  const limited = await refusedOnAFakeWiki({ status: "rate-limited", retryAfter: 2000 });
  assert.equal(limited.retryAfter, 2000);
  assert.equal(limited.isRetryable, true);
  assert.equal((await refusedOnAFakeWiki({ status: "captcha", captchaId: "c1" })).captchaId, "c1");
  assert.equal((await refusedOnAFakeWiki({ status: "page-changed", wasDeleted: false })).wasDeleted, false);
  assert.equal((await refusedOnAFakeWiki({ status: "rejected", code: "failure" })).code, "failure");
}

async function rawCalls(): Promise<void> {
  const site = new FakeSite({
    purge: { purge: [] },
    nope: wikiError("badvalue", "bad value"),
    protectedtitles: (params): JsonValue => (params.ptcontinue === undefined ? { continue: { ptcontinue: "2", continue: "-||" }, query: { protectedtitles: [{ title: "A" }] } } : { query: { protectedtitles: [{ title: "B" }] } }),
    links: pagesAnswer({ ns: 0, title: "Volcano", links: [] }),
    allpages: wikiError("nope", "no"),
  });
  await withWiki(site, async (wiki) => {
    const sending: CallOptions = { write: true, cacheable: false };
    const when = new Date("2026-01-02T03:04:05Z");
    const purged = await wiki.call({ action: "purge", titles: ["A", "B"], forcelinkupdate: true, skipped: false, nothing: null, gone: undefined, limit: 5, when, token: "c+\\" }, sending);
    assertRecord(purged);
    assert.deepEqual(assertArray(toArray(purged.purge)), []);
    const sent = sentAction(site, "purge");
    assert.equal(sent.titles, "A|B");
    assert.equal(sent.forcelinkupdate, "1");
    assert.equal(sent.limit, "5");
    assert.match(sent.when ?? "", /^2026-01-02T03:04:05/);
    assert.equal(sent.skipped, undefined);
    assert.equal(sent.nothing, undefined);
    assert.equal(sent.gone, undefined);

    const refused = await wiki.call({ action: "nope" }, null);
    assert.equal(field(refused, "error", "code"), "badvalue");

    const batches = await collect(wiki.callPaged({ action: "query", list: "protectedtitles", ptlimit: 2 }));
    assert.equal(batches.length, 2);
    assertRecord(batches[0] ?? {});
    const oneBatch = await collect(wiki.callPaged({ action: "query", list: "protectedtitles" }, 1));
    assert.equal(oneBatch.length, 1);
    const entries = await collect(wiki.callPaged({ action: "query", list: "protectedtitles" }, null, "protectedtitles"));
    assert.deepEqual(entries.map((entry) => entry.title), ["A", "B"]);
    const pageEntries = await collect(wiki.callPaged({ action: "query", prop: "links" }, null, null, true));
    assert.deepEqual(pageEntries.map((entry) => entry.title), ["Volcano"]);
    await assert.rejects(collect(wiki.callPaged({ action: "query", list: "allpages" })));
  });
}

function toArray(value: JsonValue | undefined): JsonValue[] {
  assert.ok(Array.isArray(value));
  return value;
}

async function botsThatRun(): Promise<void> {
  const tephra = (text: string): string | null => (text.includes("ash") ? text.replace("ash", "tephra") : null);

  const dry = pagesSite({ Volcano: "ash\n", Etna: "lava\n" });
  await withWiki(
    dry,
    async (wiki) => {
      const changes: Array<{ title: string; diff: string; revision: number | null | undefined }> = [];
      const report = await wiki.run(["Volcano", "Etna", "Nope"], "naming it", tephra, undefined, (change) => changes.push({ title: change.title, diff: change.diff, revision: change.revision }));
      assert.ok(report instanceof RunReport);
      assert.equal(report.processed, 3);
      assert.equal(report.pending, 1);
      assert.equal(report.saved, 0);
      assert.equal(report.skipped, 2);
      assert.equal(report.failed, 0);
      assertNone(report.stopReason);
      assert.equal(changes.length, 1);
      assert.equal(changes[0]?.title, "Volcano");
      assert.match(changes[0]?.diff ?? "", /-ash/);
      assertNone(changes[0]?.revision);
      assert.equal(dry.requests.some((params) => params.action === "edit"), false);
    },
    BOT,
  );

  const saving = pagesSite({ Volcano: "ash\n", Etna: "lava\n" });
  await withWiki(
    saving,
    async (wiki) => {
      const saved: Array<number | null | undefined> = [];
      const report = await wiki.run(["Volcano", "Etna"], "naming it", tephra, true, (change) => saved.push(change.revision));
      assert.equal(report.saved, 1);
      assert.deepEqual(saved, [9002]);
      assert.equal(sentAction(saving, "edit").summary, "naming it");
    },
    BOT,
  );

  const deferred = pagesSite({ Etna: "lava\n" });
  await withWiki(
    deferred,
    async (wiki) => {
      const byPromise = await wiki.run(["Etna"], "adding", async (text, title) => `${text}[[${title}]]\n`);
      assert.equal(byPromise.pending, 1);
      const settled: PromiseLike<string> = { then: (resolve, reject) => Promise.resolve("settled\n").then(resolve, reject) };
      const byThenable = await wiki.run(["Etna"], "adding", () => settled, true);
      assert.equal(byThenable.saved, 1);
      assert.equal(sentAction(deferred, "edit").text, "settled\n");
      await assert.rejects(
        wiki.run(["Etna"], "adding", () => {
          throw new Error("this transform gives up");
        }),
        /this transform gives up/,
      );
    },
    BOT,
  );

  const capped = pagesSite({ Volcano: "ash\n", Etna: "ash\n", "User:TestBot/Stop": "false" });
  await withWiki(
    capped,
    async (wiki) => {
      const guarded = await wiki.run(["Volcano"], "naming it", tephra, true, null, "User:TestBot/Stop", 1);
      assert.equal(guarded.saved, 1);
      assertNone(guarded.stopReason);
      assert.ok(capped.requests.some((params) => params.titles === "User:TestBot/Stop"));

      const report = await wiki.run(["Etna", "Volcano"], "naming it", (text) => `${text}more\n`, true, null, null, 1);
      assert.equal(report.saved, 1);
      assert.equal(report.notAttempted, 1);
      assert.equal(report.stopReason, "policy");
    },
    BOT,
  );

  const halted = pagesSite({ Volcano: "ash\n", "User:TestBot/Stop": "true" });
  await withWiki(
    halted,
    async (wiki) => {
      const report = await wiki.run(["Volcano"], "naming it", tephra, true, null, "User:TestBot/Stop");
      assert.equal(report.saved, 0);
      assert.equal(report.stopReason, "policy");
    },
    BOT,
  );

  const whole = pagesSite({ Volcano: "ash\n" });
  await withWiki(
    whole,
    async (wiki) => {
      const report = await wiki.botRun({
        source: pageSourceOfTitles(["Volcano"]),
        transform: (page) => ({ text: page.text.replace("ash", "tephra"), summary: "naming it" }),
      });
      assert.ok(report instanceof BotReport);
      assert.equal(report.pending, 1);
    },
    BOT,
  );
}

async function everythingAnEditCanSay(): Promise<void> {
  const site = pagesSite({ Volcano: "lava", Ash: "grey" }, { info: pagesAnswer({ pageid: 1, ns: 0, title: "Volcano", lastrevid: 5, contentmodel: "wikitext" }) });
  await withWiki(
    site,
    async (wiki) => {
      const pages = wiki.pagesService;

      const volcano = await pages.content("Volcano");
      assert.ok(volcano instanceof Page);
      assert.equal(volcano.text, "lava");
      assertNone(await pages.content("Nothing"));
      assert.deepEqual(assertArray(await pages.contents(["Ash", "Nothing", "Volcano"])).map((page) => page.title), ["Ash", "Volcano"]);
      assert.equal((await pages.freshContents(["Volcano"]))[0]?.text, "lava");

      assert.equal(await pages.exists("Volcano"), true);
      site.answers.info = pagesAnswer({ ns: 0, title: "Nothing", missing: true });
      assert.equal(await pages.exists("Nothing"), false);

      const everything: EditOptions = {
        appendText: "more",
        summary: "why",
        minor: true,
        bot: false,
        baseRevision: 9001,
        noCreate: true,
        tags: ["a", "b"],
        section: "new",
        sectionTitle: "Heading",
        watchlist: "no-change",
        watchlistExpiry: "1 month",
      };
      const appended = await pages.edit("Volcano", everything);
      assert.equal(appended.status, "saved");
      const sent = sentAction(site, "edit");
      assert.equal(sent.appendtext, "more");
      assert.equal(sent.summary, "why");
      assert.equal(sent.minor, "1");
      assert.equal(sent.baserevid, "9001");
      assert.equal(sent.nocreate, "1");
      assert.equal(sent.tags, "a|b");
      assert.equal(sent.section, "new");
      assert.equal(sent.sectiontitle, "Heading");
      assert.equal(sent.watchlist, "nochange");
      assert.equal(sent.watchlistexpiry, "1 month");
      assert.equal(sent.bot, undefined);

      const replaced = await pages.edit("Ash", { text: "new", createOnly: true, summary: null, tags: null, watchlist: null });
      assert.equal(replaced.status, "saved");
      assert.equal(sentAction(site, "edit").createonly, "1");
      assert.equal(sentAction(site, "edit").bot, "1");
      assert.equal(sentAction(site, "edit").notminor, "1");

      const prepended = await pages.edit("Ash", { prependText: "top" });
      assert.equal(prepended.status, "saved");
      assert.equal(sentAction(site, "edit").prependtext, "top");

      const editsBefore = site.requests.filter((params) => params.action === "edit").length;
      await assert.rejects(pages.edit("Volcano", {}), /text/);
      await assert.rejects(pages.edit("Volcano", { text: "x", appendText: "y" }));
      await assert.rejects(pages.edit("Volcano", { text: "x", noCreate: true, createOnly: true }));
      await assert.rejects(pages.edit("Volcano", { text: "x", sectionTitle: "Heading" }));
      // @ts-expect-error not a watch mode, which fails at runtime too
      const maybe: WatchMode = "maybe";
      await assert.rejects(pages.edit("Volcano", { text: "x", watchlist: maybe }), /'no-change'/);
      assert.equal(site.requests.filter((params) => params.action === "edit").length, editsBefore);

      site.answers.move = { move: { from: "Volcano", to: "Vulcan" } };
      const moving: MoveOptions = { reason: "why", leaveRedirect: false, moveTalk: false, moveSubpages: true, watchlist: "watch" };
      assert.equal(await pages.move("Volcano", "vulcan", moving), "Vulcan");
      const moved = sentAction(site, "move");
      assert.equal(moved.from, "Volcano");
      assert.equal(moved.to, "Vulcan");
      assert.equal(moved.reason, "why");
      assert.equal(moved.noredirect, "1");
      assert.equal(moved.movesubpages, "1");
      assert.equal(moved.watchlist, "watch");
      assert.equal(moved.movetalk, undefined);
      assert.equal(await pages.move("Volcano", "Vulcan"), "Vulcan");
      assert.equal(sentAction(site, "move").movetalk, "1");
      site.answers.move = wikiError("cantmove", "no");
      await assert.rejects(pages.move("Volcano", "Vulcan", null));

      site.answers.delete = { delete: { title: "Volcano" } };
      const deleting: DeleteOptions = { reason: "why", deleteTalk: true, watchlist: "unwatch" };
      await finishes(pages.delete("Volcano", deleting));
      const deleteSent = sentAction(site, "delete");
      assert.equal(deleteSent.title, "Volcano");
      assert.equal(deleteSent.reason, "why");
      assert.equal(deleteSent.deletetalk, "1");
      assert.equal(deleteSent.watchlist, "unwatch");

      site.answers.undelete = { undelete: { title: "Volcano" } };
      const restoring: UndeleteOptions = { reason: "back", undeleteTalk: true, watchlist: "watch" };
      await finishes(pages.undelete("Volcano", restoring));
      const undeleteSent = sentAction(site, "undelete");
      assert.equal(undeleteSent.reason, "back");
      assert.equal(undeleteSent.undeletetalk, "1");
      assert.equal(undeleteSent.watchlist, "watch");

      site.answers.info = pagesAnswer({
        pageid: 1,
        ns: 0,
        title: "Volcano",
        protection: [
          { type: "edit", level: "sysop", expiry: "infinity" },
          { type: "move", level: "autoconfirmed", expiry: "2030-01-01T00:00:00Z", cascade: true },
        ],
      });
      const guarded = assertArray(await pages.protections(["Volcano"]))[0];
      assert.ok(guarded instanceof PageProtections);
      assert.equal(guarded.title, "Volcano");
      assert.deepEqual(assertArray(guarded.protections).map((protection) => protection.action), ["edit", "move"]);
      assertNone(guarded.protections[0]?.expiry);
      assert.equal(assertDate(guarded.protections[1]?.expiry).toISOString(), "2030-01-01T00:00:00.000Z");
      assert.equal(guarded.protections[1]?.cascading, true);

      site.answers.info = pagesAnswer({ pageid: 1, ns: 0, title: "Volcano", actions: { edit: [], move: [{ code: "protectedpage" }] } });
      const allowed = assertArray(await pages.testActions(["Volcano"], ["edit", "move"]))[0];
      assert.ok(allowed instanceof PageActions);
      assert.equal(allowed.title, "Volcano");
      assert.equal(allowed.allows("edit"), true);
      assert.equal(allowed.allows("move"), false);
      assert.equal(allowed.allows("delete"), false);
      assert.deepEqual(assertArray(allowed.reasons("move")), ["protectedpage"]);
      assert.deepEqual(assertArray(allowed.reasons("edit")), []);
      assert.deepEqual(assertArray(allowed.reasons("delete")), []);
      const refusals = assertRecord(allowed.refusals);
      assert.deepEqual(assertArray(refusals.move ?? []), ["protectedpage"]);
      assert.deepEqual(assertArray(refusals.edit ?? []), []);
      assert.equal(lastOf(site, (params) => params.prop === "info").intestactions, "edit|move");
      await pages.testActions(["Volcano"]);
      assert.equal(lastOf(site, (params) => params.prop === "info").intestactions, "edit");
      await pages.testActions(["Volcano"], null);

      site.answers.contributors = pagesAnswer({ pageid: 1, ns: 0, title: "Volcano", anoncontributors: 3, contributors: [{ userid: 7, name: "Alice" }, { name: "Bob" }] });
      const editors = assertArray(await pages.contributors(["Volcano"]))[0];
      assert.ok(editors instanceof PageContributors);
      assert.equal(editors.title, "Volcano");
      assert.equal(editors.anonymous, 3);
      const alice = assertArray(editors.users)[0];
      assert.ok(alice instanceof Contributor);
      assert.equal(alice.name, "Alice");
      assert.equal(alice.id, 7);
      assert.equal(editors.users[1]?.id, 0);

      site.answers.categoryinfo = pagesAnswer({ pageid: 1, ns: 14, title: "Category:Fire", categoryinfo: { size: 6, pages: 3, files: 2, subcats: 1, hidden: true } });
      const fire = assertArray(await pages.categoryInfo(["Fire"]))[0];
      assert.ok(fire instanceof CategoryInfo);
      assert.equal(fire.title, "Category:Fire");
      assert.equal(fire.size, 6);
      assert.equal(fire.pages, 3);
      assert.equal(fire.files, 2);
      assert.equal(fire.subcategories, 1);
      assert.equal(fire.hidden, true);
      assert.equal(lastOf(site, (params) => params.prop === "categoryinfo").titles, "Category:Fire");

      const pointing = (module: string, title: string, ns: number): JsonValue => pagesAnswer({ pageid: 1, ns, title, [module]: [{ ns: 0, title: "Ash" }] });
      site.answers.linkshere = pointing("linkshere", "Volcano", 0);
      const linking = assertArray(await pages.backlinksOf(["Volcano"]))[0];
      assert.ok(linking instanceof RelatedPages);
      assert.equal(linking.title, "Volcano");
      assert.deepEqual(assertArray(linking.pages), ["Ash"]);
      site.answers.transcludedin = pointing("transcludedin", "Template:Fire", 10);
      assert.equal((await pages.transclusionsOf(["Template:Fire"]))[0]?.title, "Template:Fire");
      site.answers.fileusage = pointing("fileusage", "File:Fire.jpg", 6);
      assert.equal((await pages.fileUsageOf(["Fire.jpg"]))[0]?.title, "File:Fire.jpg");
      assert.equal(lastOf(site, (params) => params.prop === "fileusage").titles, "File:Fire.jpg");
      site.answers.redirects = pointing("redirects", "Volcano", 0);
      assert.deepEqual((await pages.redirectsTo(["Volcano"], [0, 2]))[0]?.pages, ["Ash"]);
      assert.equal(lastOf(site, (params) => params.prop === "redirects").rdnamespace, "0|2");
      assert.equal((await pages.redirectsTo(["Volcano"], null)).length, 1);
      site.answers.linkshere = pagesAnswer({ pageid: 1, ns: 0, title: "Volcano" });
      assert.deepEqual(await pages.backlinksOf(["Volcano"]), []);

      site.answers.protect = { protect: { title: "Volcano" } };
      const requests: ProtectionRequest[] = [
        { action: "edit", level: "sysop" },
        { action: "move", level: "autoconfirmed", expiry: new Date("2030-01-01T00:00:00Z"), cascading: true },
      ];
      const protecting: ProtectOptions = { reason: "why", cascade: true, watchlist: "no-change" };
      await finishes(pages.protect("Volcano", requests, protecting));
      const protectSent = sentAction(site, "protect");
      assert.equal(protectSent.protections, "edit=sysop|move=autoconfirmed");
      assert.match(protectSent.expiry ?? "", /^infinity\|2030-01-01/);
      assert.equal(protectSent.reason, "why");
      assert.equal(protectSent.cascade, "1");
      assert.equal(protectSent.watchlist, "nochange");
      await pages.protect("Volcano", []);

      site.answers.rollback = { rollback: { revid: 10, old_revid: 9 } };
      const rollingBack: RollbackOptions = { summary: "why", markBot: false, watchlist: "watch" };
      const rolled = await pages.rollback("Volcano", "Alice", rollingBack);
      assert.equal(rolled.status, "saved");
      assert.equal(rolled.revision, 10);
      assert.equal(rolled.previousRevision, 9);
      const rollbackSent = sentAction(site, "rollback");
      assert.equal(rollbackSent.user, "Alice");
      assert.equal(rollbackSent.summary, "why");
      assert.equal(rollbackSent.watchlist, "watch");
      assert.equal(rollbackSent.markbot, undefined);
      assert.equal((await pages.rollback("Volcano", "Alice")).status, "saved");
      assert.equal(sentAction(site, "rollback").markbot, "1");

      site.answers.edit = { edit: { result: "Success", newrevid: 11, oldrevid: 10 } };
      const undoing: UndoOptions = { summary: "back", through: 8 };
      assert.equal((await pages.undo("Volcano", 10, undoing)).revision, 11);
      const undoSent = sentAction(site, "edit");
      assert.equal(undoSent.undo, "10");
      assert.equal(undoSent.undoafter, "8");
      assert.equal(undoSent.summary, "back");
      await pages.undo("Volcano", 10);
      site.answers.edit = wikiError("undofailure", "cannot");
      assert.equal((await pages.undo("Volcano", 10, null)).status, "conflict");

      site.answers.purge = { purge: [] };
      await finishes(pages.purge(["Volcano", "Ash"], true));
      assert.equal(sentAction(site, "purge").titles, "Volcano|Ash");
      assert.equal(sentAction(site, "purge").forcelinkupdate, "1");
      await pages.purge(["Volcano"]);
      assert.equal(sentAction(site, "purge").forcelinkupdate, undefined);

      site.answers.watch = { watch: [] };
      await finishes(pages.watch(["Volcano"], false, "1 month"));
      assert.equal(sentAction(site, "watch").unwatch, "1");
      assert.equal(sentAction(site, "watch").expiry, "1 month");
      await pages.watch(["Volcano"]);
      assert.equal(sentAction(site, "watch").unwatch, undefined);

      site.answers.mergehistory = { mergehistory: {} };
      const merging: MergeHistoryOptions = { upTo: new Date("2026-01-01T00:00:00Z"), reason: "why" };
      await finishes(pages.mergeHistory("Ash", "Volcano", merging));
      assert.equal(sentAction(site, "mergehistory").from, "Ash");
      assert.equal(sentAction(site, "mergehistory").to, "Volcano");
      assert.equal(sentAction(site, "mergehistory").reason, "why");
      assert.match(sentAction(site, "mergehistory").timestamp ?? "", /^2026-01-01/);

      site.answers.import = { import: [] };
      const importing: ImportPageOptions = { fullHistory: false, includeTemplates: true, rootPage: "Imports", summary: "why" };
      await finishes(pages.importPage("commons", "Volcano", importing));
      const importSent = sentAction(site, "import");
      assert.equal(importSent.interwikisource, "commons");
      assert.equal(importSent.interwikipage, "Volcano");
      assert.equal(importSent.templates, "1");
      assert.equal(importSent.rootpage, "Imports");
      assert.equal(importSent.fullhistory, undefined);

      site.answers.setpagelanguage = { setpagelanguage: {} };
      await finishes(pages.setLanguage("Volcano", "fr", "why"));
      assert.equal(sentAction(site, "setpagelanguage").lang, "fr");
      assert.equal(sentAction(site, "setpagelanguage").reason, "why");
      await pages.setLanguage("Volcano", "fr");

      site.answers.changecontentmodel = { changecontentmodel: {} };
      await finishes(pages.changeContentModel("Volcano", "Scribunto", "why"));
      assert.equal(sentAction(site, "changecontentmodel").model, "Scribunto");
      await pages.changeContentModel("Volcano", "wikitext");

      site.answers.expandtemplates = { expandtemplates: { wikitext: "done", categories: [{ category: "Red_rocks" }] } };
      assert.equal(await pages.expandText("{{x}}", "Volcano"), "done");
      assert.equal(sentAction(site, "expandtemplates").title, "Volcano");
      assert.equal(await pages.expandText("{{x}}"), "done");
      assert.equal(await pages.expandText("{{x}}", null), "done");

      const expanding: ExpandTextsOptions = { title: "Volcano", categories: true, revision: 5, sandbox: { prefixes: ["User:Me/sandbox"] } };
      const expanded = assertArray(await pages.expandTexts(["{{x}}"], expanding))[0];
      assert.ok(expanded instanceof ExpandedText);
      assert.equal(expanded.text, "done");
      assert.deepEqual(assertArray(expanded.categories), ["Red rocks"]);
      assert.equal(sentAction(site, "expandtemplates").revid, "5");
      assert.equal(sentAction(site, "expandtemplates").templatesandboxprefix, "User:Me/sandbox");

      site.answers.expandtemplates = (params) => ({ expandtemplates: { wikitext: (params.text ?? "").replaceAll("{{x}}", "X") } });
      const batched = assertArray(await pages.expandTexts(["{{x}} one", "two {{x}}"]));
      assert.deepEqual(batched.map((text) => text.text), ["X one", "two X"]);
      assert.deepEqual(assertArray(batched[0]?.categories ?? []), []);
      assert.equal(site.requests.filter((params) => params.action === "expandtemplates" && (params.text ?? "").includes("one") && (params.text ?? "").includes("two")).length, 1);
      assert.equal((await pages.expandTexts(["{{x}}"], null)).length, 1);
    },
    BOT,
  );
}

async function aClosedWiki(): Promise<void> {
  const site = pagesSite({ Volcano: "ash" });
  const restore = site.install();
  try {
    const wiki = await connect("test.example.org", CONTACT);
    wiki.close();
    await assert.rejects(wiki.page("Volcano"));
  } finally {
    restore();
  }
}

function misuses(wiki: Wiki): void {
  const pages = wiki.pagesService;
  // @ts-expect-error a typo in a watch mode does not compile
  void pages.edit("Volcano", { text: "x", watchlist: "wacth" });
  // @ts-expect-error a move takes a watch mode too
  void pages.move("Volcano", "Vulcan", { watchlist: "maybe" });
  // @ts-expect-error a revision is a number, not its digits
  void wiki.edit("Volcano", "x", "why", "9001");
  // @ts-expect-error a transform answers with text or null, not a number
  void wiki.run(["Volcano"], "why", () => 42);
  // @ts-expect-error tags are an array
  void pages.edit("Volcano", { text: "x", tags: "bot" });
  // @ts-expect-error a status is one of the ones the wiki reports
  const typo: EditStatus = "sved";
  void typo;
  // @ts-expect-error a protection needs its level
  void pages.protect("Volcano", [{ action: "edit" }]);
}
void misuses;

await openingAWiki();
await whoAndWhatTheWikiIs();
await readingPages();
await savingWithWikiEdit();
await refusalsOverTheWire();
await refusalsThatSayMoreOnAFakeWiki();
await rawCalls();
await botsThatRun();
await everythingAnEditCanSay();
await aClosedWiki();
console.log("pages_edits: a wiki opens and logs in, pages read and save through Wiki and PageService, refusals and raw calls say what their types promise");
