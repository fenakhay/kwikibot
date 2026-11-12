import assert from "node:assert/strict";
import { BotPassword, type JsonValue, type Negatable } from "kwikibot";
import {
  AbuseFilter,
  AbuseFilterFlags,
  type AbuseFilterOrder,
  type AbuseFilterProperty,
  type AbuseFiltersOptions,
  type AbuseFilterState,
  Coordinate,
  ExtensionService,
  FlaggedInfo,
  LintError,
  type NewPagesOptions,
  type NewPagesOrder,
  type NewPagesReviewed,
  type NewPageStatsOptions,
  Notification,
  PageCoordinates,
  PageValue,
  TriageBacklog,
  TriagePage,
  TriageStats,
  type TriageStatus,
} from "kwikibot/client";
import {
  PageQuality,
  parseProofreadText,
  type ProofreadQuality,
  proofreadQualityOf,
  ProofreadService,
  ProofreadText,
} from "kwikibot/proofread";
import { type Answer, FakeSite, assertArray, assertDate, assertNone, assertRecord, collect, withWiki } from "./support.js";

const INSTALLED = [
  "GeoData",
  "PageImages",
  "TextExtracts",
  "WikibaseClient",
  "Linter",
  "Echo",
  "Thanks",
  "UrlShortener",
  "FlaggedRevs",
  "Abuse Filter",
  "PageTriage",
  "ProofreadPage",
];

const WHEN = new Date("2026-08-31T21:43:26Z").getTime();

function pages(...entries: JsonValue[]): JsonValue {
  return { query: { pages: entries } };
}

const FIRE_PAGE: JsonValue = { pageid: 2, ns: 0, title: "Fire" };

function volcano(fields: Record<string, JsonValue>): JsonValue {
  return { pageid: 1, ns: 0, title: "Volcano", ...fields };
}

function last(site: FakeSite, key: string, value: string): Record<string, string> {
  const found = site.requests.filter((request) => request[key] === value).at(-1);
  assert.ok(found != null, `no request with ${key}=${value}`);
  return found;
}

const answers: Record<string, Answer> = {
  paraminfo: { paraminfo: { modules: [] } },
  coordinates: pages(
    volcano({ coordinates: [{ lat: 1.5, lon: -2.5, globe: "mars", primary: "", type: "mountain" }, { lat: 3, lon: 4 }] }),
    FIRE_PAGE,
  ),
  geosearch: { query: { geosearch: [{ ns: 0, title: "Volcano" }] } },
  pageimages: pages(volcano({ pageimage: "Fire.jpg" }), FIRE_PAGE),
  extracts: pages(volcano({ extract: "Hot." }), { pageid: 2, ns: 0, title: "Fire", extract: "  " }),
  pageprops: pages(volcano({ pageprops: { wikibase_item: "Q8072" } }), FIRE_PAGE),
  linterrors: {
    query: {
      linterrors: [
        { lintId: 5, category: "obsolete-tag", ns: 0, title: "Volcano", location: [3, 9], params: { name: "font" } },
        { lintId: 6, category: "missing-end-tag" },
      ],
    },
  },
  notifications: {
    query: {
      notifications: {
        list: [
          {
            id: 4,
            type: "mention",
            title: { full: "Volcano" },
            agent: { name: "Alice" },
            timestamp: { utciso8601: "2026-08-31T21:43:26Z" },
            read: "x",
          },
          { id: 5, type: "welcome" },
        ],
      },
    },
  },
  thank: { result: { success: 1 } },
  shortenurl: { shortenurl: { shorturl: "https://w.example/a" } },
  review: { review: { revid: 9 } },
  flagged: pages(
    volcano({ flagged: { stable_revid: 8, level: 1, level_text: "stable", pending_since: "2026-08-31T21:43:26Z" } }),
    { pageid: 2, ns: 0, title: "Fire", flagged: { stable_revid: 7, level: 0 } },
    { pageid: 3, ns: 0, title: "Ash" },
  ),
  abusefilters: {
    query: {
      abusefilters: [
        {
          id: 12,
          description: "d",
          actions: ["warn"],
          pattern: "p",
          comments: "c",
          hits: 40,
          lasteditor: "Alice",
          lastedittime: "2026-08-31T21:43:26Z",
          enabled: true,
          deleted: false,
          private: true,
          protected: false,
          suppressed: false,
          hitsredacted: true,
        },
        { id: 13, actions: "tag,warn" },
      ],
    },
  },
  pagetriagelist: {
    pagetriagelist: {
      pages: [
        {
          pageid: "1",
          title: "Volcano",
          user_name: "Alice",
          creation_date_utc: "20260831214326",
          creator_is_temp_account: "0",
          patrol_status: "2",
          reviewer: "Bob",
          ptrp_reviewed_updated: "20260901000000",
          is_redirect: "0",
          linkcount: "0",
        },
        { pageid: "2", title: "Fire", user_name: "~2026-1", creator_hidden: "1", creator_is_temp_account: "1", is_redirect: "1", patrol_status: "7", linkcount: "4" },
      ],
    },
  },
  pagetriagestats: {
    pagetriagestats: {
      stats: {
        unreviewedarticle: { count: 3, oldest: "20260831214326" },
        unreviewedredirect: { count: 0, oldest: "20260831214326" },
        unrevieweddraft: { count: 1, oldest: "20260831214326" },
        reviewedarticle: { reviewed_count: 5 },
        reviewedredirect: { reviewed_count: 6 },
        filteredarticle: 7,
      },
    },
  },
  proofread: pages(
    { pageid: 1, ns: 104, title: "Page:Book/1", proofread: { quality: 3, index: "Book" } },
    { pageid: 2, ns: 104, title: "Page:Book/2" },
  ),
  embeddedin: {
    query: {
      embeddedin: [
        { ns: 104, title: "Page:Book/1" },
        { ns: 104, title: "Page:Book/2" },
      ],
    },
  },
};

function namesWhatIsInstalled(extensions: ExtensionService): void {
  assert.ok(extensions instanceof ExtensionService);
  const names: string[] = [
    extensions.geoData,
    extensions.pageImagesExtension,
    extensions.textExtracts,
    extensions.wikibaseClient,
    extensions.linter,
    extensions.echo,
    extensions.thanks,
    extensions.urlShortener,
    extensions.flaggedRevs,
    extensions.abuseFilter,
    extensions.pageTriage,
  ];
  for (const name of names) {
    assert.equal(typeof name, "string");
    assert.equal(extensions.has(name), true, name);
  }
  assert.equal(extensions.abuseFilter, "Abuse Filter");
  assert.equal(extensions.has("Nothing"), false);
  assert.equal(extensions.maxCreators, 10);
}

async function findsPlaces(extensions: ExtensionService, site: FakeSite): Promise<void> {
  const found = assertArray(await extensions.coordinates(["Volcano", "Fire"]));
  assert.equal(found.length, 1);
  const place: PageCoordinates | undefined = found[0];
  assert.ok(place instanceof PageCoordinates);
  assert.equal(place.title, "Volcano");
  const points = assertArray(place.coordinates);
  const point: Coordinate | undefined = points[0];
  const second = points[1];
  assert.ok(point instanceof Coordinate && second != null);
  assert.equal(point.latitude, 1.5);
  assert.equal(point.longitude, -2.5);
  assert.equal(point.globe, "mars");
  assert.equal(point.isPrimary, true);
  assert.equal(point.type, "mountain");
  assert.equal(second.globe, "earth");
  assert.equal(second.isPrimary, false);
  assertNone(second.type);

  assert.deepEqual(assertArray(await extensions.nearby(1.5, 2.5, 300, 4)), ["Volcano"]);
  const sent = last(site, "list", "geosearch");
  assert.equal(sent.gscoord, "1.5|2.5");
  assert.equal(sent.gsradius, "300");
  assert.equal(sent.gslimit, "4");
  await extensions.nearby(1.5, 2.5);
  assert.equal(last(site, "list", "geosearch").gsradius, "1000");
  assert.equal(last(site, "list", "geosearch").gslimit, "10");
}

async function readsOneValueAPage(extensions: ExtensionService, site: FakeSite): Promise<void> {
  const images = assertArray(await extensions.pageImages(["Volcano", "Fire"]));
  assert.equal(images.length, 1);
  const image: PageValue | undefined = images[0];
  assert.ok(image instanceof PageValue);
  assert.equal(image.title, "Volcano");
  assert.equal(image.value, "Fire.jpg");

  const extracts = assertArray(await extensions.extracts(["Volcano", "Fire"], 1));
  assert.deepEqual(
    extracts.map((extract) => [extract.title, extract.value]),
    [["Volcano", "Hot."]],
  );
  assert.equal(last(site, "prop", "extracts").exsentences, "1");
  await extensions.extracts(["Volcano"]);
  assert.equal(last(site, "prop", "extracts").exsentences, "2");
  await extensions.extracts(["Volcano"], 0);
  assert.equal("exsentences" in last(site, "prop", "extracts"), false);

  const items = assertArray(await extensions.wikibaseItems(["Volcano", "Fire"]));
  assert.equal(items.length, 1);
  assert.equal(items[0]?.value, "Q8072");
}

async function walksLintErrors(extensions: ExtensionService, site: FakeSite): Promise<void> {
  const errors = await collect(extensions.lintErrors("obsolete-tag", 2));
  assert.equal(last(site, "list", "linterrors").lntcategories, "obsolete-tag");
  assert.equal(errors.length, 2);
  const error: LintError | undefined = errors[0];
  const bare = errors[1];
  assert.ok(error instanceof LintError && bare != null);
  assert.equal(error.id, 5);
  assert.equal(error.category, "obsolete-tag");
  assert.equal(error.page, "Volcano");
  assert.equal(error.rangeStart, 3);
  assert.equal(error.rangeEnd, 9);
  assert.equal(assertRecord(error.details, "string").name, "font");
  assert.equal(bare.category, "missing-end-tag");
  assertNone(bare.page);
  assertNone(bare.rangeStart);
  assertNone(bare.rangeEnd);
  assert.deepEqual(assertRecord(bare.details), {});

  assert.equal((await collect(extensions.lintErrors(null, 1))).length, 1);
  assert.equal((await collect(extensions.lintErrors())).length, 2);
}

async function readsNotifications(extensions: ExtensionService, site: FakeSite): Promise<void> {
  const list = assertArray(await extensions.notifications(true, 2));
  const sent = last(site, "meta", "notifications");
  assert.equal(sent.notfilter, "!read");
  assert.equal(sent.notlimit, "2");
  const mention: Notification | undefined = list[0];
  const welcome = list[1];
  assert.ok(mention instanceof Notification && welcome != null);
  assert.equal(mention.id, 4);
  assert.equal(mention.type, "mention");
  assert.equal(mention.title, "Volcano");
  assert.equal(mention.agent, "Alice");
  assert.equal(mention.isRead, true);
  assert.equal(assertDate(mention.timestamp).getTime(), WHEN);
  assert.equal(welcome.type, "welcome");
  assert.equal(welcome.isRead, false);
  assertNone(welcome.title);
  assertNone(welcome.agent);
  assertNone(welcome.timestamp);

  await extensions.notifications();
  assert.equal("notfilter" in last(site, "meta", "notifications"), false);
  assert.equal(last(site, "meta", "notifications").notlimit, "25");
}

async function writes(extensions: ExtensionService, site: FakeSite): Promise<void> {
  assert.equal(await extensions.thank(9), undefined);
  assert.equal(last(site, "action", "thank").rev, "9");
  assert.equal(last(site, "action", "thank").source, "kwikibot");
  assert.equal(last(site, "action", "thank").token, "c+\\");
  assert.equal(await extensions.thank(9, "diff"), undefined);
  assert.equal(last(site, "action", "thank").source, "diff");

  assert.equal(await extensions.shortenUrl("https://example.org/long"), "https://w.example/a");
  assert.equal(last(site, "action", "shortenurl").url, "https://example.org/long");

  assert.equal(await extensions.review(9, { accuracy: 2 }, "fine"), undefined);
  const sent = last(site, "action", "review");
  assert.equal(sent.revid, "9");
  assert.equal(sent.flag_accuracy, "2");
  assert.equal(sent.comment, "fine");
  assert.equal(await extensions.review(10), undefined);
  assert.equal(last(site, "action", "review").revid, "10");
  assert.equal("comment" in last(site, "action", "review"), false);
}

async function readsReviews(extensions: ExtensionService): Promise<void> {
  const found = assertArray(await extensions.flagged(["Volcano", "Fire", "Ash"]));
  assert.equal(found.length, 2);
  const pending: FlaggedInfo | undefined = found[0];
  const settled = found[1];
  assert.ok(pending instanceof FlaggedInfo && settled != null);
  assert.equal(pending.title, "Volcano");
  assert.equal(pending.stableRevisionId, 8);
  assert.equal(pending.level, 1);
  assert.equal(pending.levelText, "stable");
  assert.equal(pending.hasPendingChanges, true);
  assert.equal(assertDate(pending.pendingSince).getTime(), WHEN);
  assert.equal(settled.hasPendingChanges, false);
  assertNone(settled.levelText);
  assertNone(settled.pendingSince);
}

async function listsFilters(extensions: ExtensionService, site: FakeSite): Promise<void> {
  const show: Array<Negatable<AbuseFilterState>> = ["enabled", "!deleted"];
  const properties: AbuseFilterProperty[] = ["pattern", "hits", "last-editor", "flags"];
  const order: AbuseFilterOrder = "highest-id-first";
  const options: AbuseFiltersOptions = { show, properties, order, limit: 3 };
  const filters = await collect(extensions.abuseFilters(options));
  const sent = last(site, "list", "abusefilters");
  assert.equal(sent.abfdir, "older");
  assert.equal(sent.abfshow, "enabled|!deleted");
  assert.ok(sent.abfprop?.includes("lasteditor"));

  const filter: AbuseFilter | undefined = filters[0];
  const other = filters[1];
  assert.ok(filter instanceof AbuseFilter && other != null);
  assert.equal(filter.id, 12);
  assert.equal(filter.description, "d");
  assert.equal(filter.pattern, "p");
  assert.equal(filter.comments, "c");
  assert.equal(filter.hits, 40);
  assert.equal(filter.lastEditor, "Alice");
  assert.equal(assertDate(filter.lastEdited).getTime(), WHEN);
  assert.deepEqual(assertArray(filter.actions), ["warn"]);
  const withheld: AbuseFilterProperty[] = filter.redacted;
  assert.deepEqual(assertArray(withheld), ["hits"]);
  const flags: AbuseFilterFlags | null | undefined = filter.flags;
  assert.ok(flags instanceof AbuseFilterFlags);
  assert.equal(flags.isEnabled, true);
  assert.equal(flags.isDeleted, false);
  assert.equal(flags.isPrivate, true);
  assert.equal(flags.isProtected, false);
  assert.equal(flags.isSuppressed, false);

  assert.deepEqual(assertArray(other.actions), ["tag", "warn"]);
  assertNone(other.description);
  assertNone(other.pattern);
  assertNone(other.comments);
  assertNone(other.hits);
  assertNone(other.lastEditor);
  assertNone(other.lastEdited);
  assert.deepEqual(assertArray(other.redacted), []);

  const unflagged = await collect(extensions.abuseFilters({ properties: ["description"], limit: 1 }));
  assertNone(unflagged[0]?.flags);
  assert.equal(last(site, "list", "abusefilters").abfdir, "newer");
  assert.equal((await collect(extensions.abuseFilters())).length, 2);
  assert.equal((await collect(extensions.abuseFilters(null))).length, 2);
}

async function patrolsNewPages(extensions: ExtensionService, site: FakeSite): Promise<void> {
  const reviewed: NewPagesReviewed = "all";
  const order: NewPagesOrder = "oldest-first";
  const options: NewPagesOptions = {
    namespace: 0,
    reviewed,
    redirects: true,
    creators: ["Alice"],
    hideOwnPages: true,
    order,
    limit: 2,
  };
  const found = await collect(extensions.newPages(options));
  const sent = last(site, "action", "pagetriagelist");
  assert.equal(sent.namespace, "0");
  assert.equal(sent.showreviewed, "1");
  assert.equal(sent.showunreviewed, "1");
  assert.equal("showothers" in sent, false);
  assert.equal(sent.username, "Alice");
  assert.equal(sent.hideownpages, "1");
  assert.equal(sent.dir, "oldestfirst");

  const page: TriagePage | undefined = found[0];
  const hidden = found[1];
  assert.ok(page instanceof TriagePage && hidden != null);
  assert.equal(page.title, "Volcano");
  assert.equal(page.creator, "Alice");
  const status: TriageStatus | null | undefined = page.status;
  assert.equal(status, "patrolled");
  assert.equal(page.reviewer, "Bob");
  assert.equal(page.creatorIsTemporary, false);
  assert.equal(page.isRedirect, false);
  assert.equal(page.isOrphan, true);
  assert.equal(assertDate(page.created).getTime(), WHEN);
  assert.equal(assertDate(page.reviewUpdated).getTime(), new Date("2026-09-01T00:00:00Z").getTime());

  assertNone(hidden.creator);
  assertNone(hidden.status);
  assertNone(hidden.reviewer);
  assertNone(hidden.created);
  assertNone(hidden.reviewUpdated);
  assert.equal(hidden.creatorIsTemporary, true);
  assert.equal(hidden.isRedirect, true);
  assert.equal(hidden.isOrphan, false);

  await collect(extensions.newPages({ limit: 2 }));
  assert.equal("showreviewed" in last(site, "action", "pagetriagelist"), false);
  await collect(extensions.newPages({ reviewed: "reviewed", limit: 2 }));
  assert.equal("showunreviewed" in last(site, "action", "pagetriagelist"), false);

  const crowd = Array.from({ length: extensions.maxCreators + 1 }, (_, index) => `User ${index}`);
  await assert.rejects(collect(extensions.newPages({ creators: crowd })), /at most 10 creators/);
}

async function measuresTheBacklog(extensions: ExtensionService, site: FakeSite): Promise<void> {
  const options: NewPageStatsOptions = { namespace: 0, creators: ["Alice"], hideOwnPages: true };
  const stats: TriageStats = await extensions.newPageStats(options);
  assert.equal(last(site, "action", "pagetriagestats").username, "Alice");
  assert.equal(last(site, "action", "pagetriagestats").hideownpages, "1");
  const articles: TriageBacklog = stats.unreviewedArticles;
  assert.ok(articles instanceof TriageBacklog);
  assert.equal(articles.count, 3);
  assert.equal(assertDate(articles.oldest).getTime(), WHEN);
  assert.equal(stats.unreviewedRedirects.count, 0);
  assertNone(stats.unreviewedRedirects.oldest);
  assert.equal(stats.unreviewedDrafts?.count, 1);
  assert.equal(stats.reviewedArticles, 5);
  assert.equal(stats.reviewedRedirects, 6);
  assert.equal(stats.matching, 7);

  site.answers.pagetriagestats = { pagetriagestats: { stats: { unrevieweddraft: [] } } };
  const sparse = await extensions.newPageStats();
  assertNone(sparse.unreviewedDrafts);
  assert.equal(sparse.unreviewedArticles.count, 0);
  assert.equal(sparse.matching, 0);
  await extensions.newPageStats(null);
  await assert.rejects(extensions.newPageStats({ creators: Array.from({ length: 11 }, (_, index) => `U${index}`) }), /at most 10/);
}

async function followsABook(proofread: ProofreadService, site: FakeSite): Promise<void> {
  assert.ok(proofread instanceof ProofreadService);
  const qualities = assertArray(await proofread.quality(["Page:Book/1", "Page:Book/2"]));
  assert.equal(qualities.length, 1);
  const page: PageQuality | undefined = qualities[0];
  assert.ok(page instanceof PageQuality);
  assert.equal(page.title, "Page:Book/1");
  const quality: ProofreadQuality = page.quality;
  assert.equal(quality, "proofread");
  assert.equal(page.level, 3);

  assert.equal(await proofread.indexOf("Page:Book/1"), "Index:Book");
  site.answers.proofread = pages({ pageid: 2, ns: 104, title: "Page:Book/2" });
  assertNone(await proofread.indexOf("Page:Book/2"));

  assert.deepEqual(assertArray(await proofread.pagesOf("Index:Book")), ["Page:Book/1", "Page:Book/2"]);
  assert.equal(last(site, "list", "embeddedin").eititle, "Index:Book");
}

function editsAScan(): void {
  const written = '<noinclude><pagequality level="3" user="Alice" />head</noinclude>body<noinclude>foot</noinclude>';
  const page = parseProofreadText(written);
  assert.ok(page instanceof ProofreadText);
  assert.equal(page.header, "head");
  assert.equal(page.body, "body");
  assert.equal(page.footer, "foot");
  assert.equal(page.quality, "proofread");
  assert.equal(page.proofreader, "Alice");
  assert.equal(page.qualityLevel, 3);
  assert.equal(page.serialize(), written);

  const edited = page.withBody("new");
  assert.ok(edited instanceof ProofreadText);
  assert.equal(edited.body, "new");
  assert.equal(edited.proofreader, "Alice");
  assert.equal(edited.quality, "proofread");
  assert.ok(edited.serialize().includes("</noinclude>new<noinclude>"));
  assert.equal(page.body, "body");

  const built = new ProofreadText("h", "b", "f", "validated", "Bob");
  assert.equal(built.qualityLevel, 4);
  assert.equal(built.quality, "validated");
  const plain = new ProofreadText("h", "b", "f");
  assertNone(plain.quality);
  assertNone(plain.qualityLevel);
  assertNone(plain.proofreader);
  assertNone(new ProofreadText("h", "b", "f", null, null).quality);

  const loose = parseProofreadText("plain");
  assert.equal(loose.body, "plain");
  assert.equal(loose.header, "");
  assert.equal(loose.footer, "");
  assertNone(loose.quality);
  assert.ok(loose.serialize().includes("plain"));

  const levels: Array<ProofreadQuality | null | undefined> = [0, 1, 2, 3, 4].map(proofreadQualityOf);
  assert.deepEqual(levels, ["without-text", "not-proofread", "problematic", "proofread", "validated"]);
  assertNone(proofreadQualityOf(9));
}

function misuses(extensions: ExtensionService): void {
  // @ts-expect-error the order is newest-first
  void extensions.newPages({ order: "newest-frist" });
  // @ts-expect-error reviewed is unreviewed, reviewed or all
  void extensions.newPages({ reviewed: "maybe" });
  // @ts-expect-error there is no filter state called enabld
  void extensions.abuseFilters({ show: ["enabld"] });
  // @ts-expect-error negating takes one leading bang and a real state
  void extensions.abuseFilters({ show: ["!!enabled"] });
  // @ts-expect-error the property is spelled last-editor
  void extensions.abuseFilters({ properties: ["lasteditor"] });
  // @ts-expect-error the order is highest-id-first
  void extensions.abuseFilters({ order: "descending" });
  // @ts-expect-error perfect is no proofreading quality
  void new ProofreadText("h", "b", "f", "perfect");
  // @ts-expect-error review flags are numbers
  void extensions.review(9, { accuracy: "high" });
  // @ts-expect-error the status of a page is one of four
  const status: TriagePage["status"] = "open";
  void status;
}
void misuses;

const site = new FakeSite(answers, INSTALLED);
await withWiki(
  site,
  async (wiki) => {
    const extensions = wiki.extensions;
    namesWhatIsInstalled(extensions);
    await findsPlaces(extensions, site);
    await readsOneValueAPage(extensions, site);
    await walksLintErrors(extensions, site);
    await readsNotifications(extensions, site);
    await writes(extensions, site);
    await readsReviews(extensions);
    await listsFilters(extensions, site);
    await patrolsNewPages(extensions, site);
    await measuresTheBacklog(extensions, site);
    await followsABook(wiki.proofread, site);
  },
  new BotPassword("Bot", "task", "secret"),
);

editsAScan();

await withWiki(new FakeSite(), async (wiki) => {
  const extensions = wiki.extensions;
  assert.equal(extensions.has(extensions.geoData), false);
  await assert.rejects(extensions.coordinates(["Volcano"]), /GeoData/);
  await assert.rejects(extensions.nearby(1, 2), /GeoData/);
  await assert.rejects(extensions.pageImages(["Volcano"]), /PageImages/);
  await assert.rejects(extensions.extracts(["Volcano"]), /TextExtracts/);
  await assert.rejects(extensions.wikibaseItems(["Volcano"]), /WikibaseClient/);
  await assert.rejects(collect(extensions.lintErrors()), /Linter/);
  await assert.rejects(extensions.notifications(), /Echo/);
  await assert.rejects(extensions.thank(1), /Thanks/);
  await assert.rejects(extensions.shortenUrl("https://example.org"), /UrlShortener/);
  await assert.rejects(extensions.flagged(["Volcano"]), /FlaggedRevs/);
  await assert.rejects(extensions.review(1), /FlaggedRevs/);
  await assert.rejects(collect(extensions.abuseFilters()), /Abuse Filter/);
  await assert.rejects(collect(extensions.newPages()), /PageTriage/);
  await assert.rejects(extensions.newPageStats(), /PageTriage/);
  await assert.rejects(wiki.proofread.quality(["Page:Book/1"]), /ProofreadPage/);
  await assert.rejects(wiki.proofread.indexOf("Page:Book/1"), /ProofreadPage/);
  await assert.rejects(wiki.proofread.pagesOf("Index:Book"), /ProofreadPage/);
});

console.log("extensions: every extension service and its results read as typed, and reject without the extension");
