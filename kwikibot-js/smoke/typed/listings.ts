import assert from "node:assert/strict";
import { describeError, type JsonValue } from "kwikibot";
import {
  type AllCategoriesOptions,
  type AllFileUsagesOptions,
  type AllPagesOptions,
  type AllTargetsOptions,
  type BacklinksOptions,
  type CategoryMembersOptions,
  type CategoryMemberType,
  type ExternalLinkUsageOptions,
  InterwikiLink,
  LanguageLink,
  type ListOptions,
  type ListService,
  type SearchOptions,
} from "kwikibot/client";
import { FakeSite, assertNone, collect, withWiki } from "./support.js";

function listAnswer(module: string, ...titles: string[]): JsonValue {
  return { query: { [module]: titles.map((title) => ({ ns: 0, title })) } };
}

function propAnswer(module: string, ...entries: JsonValue[]): JsonValue {
  return { query: { pages: [{ pageid: 1, ns: 0, title: "Volcano", [module]: entries }] } };
}

function sent(site: FakeSite, module: string): Record<string, string> {
  const found = site.requests.filter((params) => params.list === module || params.prop === module).at(-1);
  assert.ok(found != null, `nothing asked for ${module}`);
  return found;
}

async function titlesFrom(titles: AsyncIterable<string>): Promise<string[]> {
  const found = await collect(titles);
  for (const title of found) assert.equal(typeof title, "string");
  return found;
}

async function whatIsInACategory(lists: ListService, site: FakeSite): Promise<void> {
  site.answers.categorymembers = listAnswer("categorymembers", "Etna");
  const subcategories: CategoryMembersOptions = { type: "subcategory", namespaces: [14], sort: "timestamp", startSortKey: "E", limit: 3 };
  assert.deepEqual(await titlesFrom(lists.categoryMembers("Volcanoes", subcategories)), ["Etna"]);
  const asked = sent(site, "categorymembers");
  assert.equal(asked.cmtitle, "Category:Volcanoes");
  assert.equal(asked.cmtype, "subcat");
  assert.equal(asked.cmnamespace, "14");
  assert.equal(asked.cmsort, "timestamp");
  assert.equal(asked.cmstartsortkeyprefix, "E");
  assert.equal(asked.cmlimit, "3");

  assert.deepEqual(await titlesFrom(lists.categoryMembers("Category:Volcanoes")), ["Etna"]);
  assert.equal(sent(site, "categorymembers").cmtype, "page");
  assert.equal(sent(site, "categorymembers").cmsort, "sortkey");
  assert.equal(sent(site, "categorymembers").cmnamespace, undefined);
  await titlesFrom(lists.categoryMembers("Volcanoes", null));
  await titlesFrom(lists.categoryMembers("Volcanoes", { type: null, sort: null, namespaces: null, startSortKey: null, limit: null }));

  site.answers.categorymembers = { query: { categorymembers: [{ ns: 14, title: "Category:Active" }] } };
  assert.deepEqual(await titlesFrom(lists.categoryMembers("Volcanoes")), ["Category:Active"]);

  // @ts-expect-error a plural is a typo, which fails at runtime too
  const plural: CategoryMemberType = "pages";
  assert.throws(() => lists.categoryMembers("Volcanoes", { type: plural }), /'page', 'subcategory', 'file'/);
  assert.throws(() => lists.backlinks("a|b"));
}

async function whatPointsAtAPage(lists: ListService, site: FakeSite): Promise<void> {
  site.answers.backlinks = listAnswer("backlinks", "Ash");
  assert.deepEqual(await titlesFrom(lists.backlinks("Volcano")), ["Ash"]);
  assert.equal(sent(site, "backlinks").blredirect, "1");
  const direct: BacklinksOptions = { includeRedirects: false, namespaces: [0, 2], limit: 1 };
  await titlesFrom(lists.backlinks("Volcano", direct));
  assert.equal(sent(site, "backlinks").bltitle, "Volcano");
  assert.equal(sent(site, "backlinks").blredirect, undefined);
  assert.equal(sent(site, "backlinks").blnamespace, "0|2");
  assert.equal(sent(site, "backlinks").bllimit, "1");

  const firstTwo: ListOptions = { namespaces: [0], limit: 2 };
  site.answers.embeddedin = listAnswer("embeddedin", "Etna");
  assert.deepEqual(await titlesFrom(lists.transclusions("Infobox", firstTwo)), ["Etna"]);
  assert.equal(sent(site, "embeddedin").eititle, "Template:Infobox");
  assert.equal(sent(site, "embeddedin").einamespace, "0");
  assert.equal(sent(site, "embeddedin").eilimit, "2");

  site.answers.exturlusage = listAnswer("exturlusage", "Etna");
  const https: ExternalLinkUsageOptions = { protocol: "https", namespaces: [0], limit: 2 };
  assert.deepEqual(await titlesFrom(lists.externalLinkUsage("example.org", https)), ["Etna"]);
  assert.equal(sent(site, "exturlusage").euquery, "example.org");
  assert.equal(sent(site, "exturlusage").euprotocol, "https");
  assert.equal(sent(site, "exturlusage").eunamespace, "0");
  assert.equal(sent(site, "exturlusage").eulimit, "2");
}

async function whatIsOnAPage(lists: ListService, site: FakeSite): Promise<void> {
  site.answers.links = propAnswer("links", { ns: 0, title: "Ash" });
  assert.deepEqual(await titlesFrom(lists.linksFrom("Volcano", { namespaces: [0], limit: 2 })), ["Ash"]);
  assert.equal(sent(site, "links").titles, "Volcano");
  assert.equal(sent(site, "links").plnamespace, "0");
  assert.equal(sent(site, "links").pllimit, "2");

  site.answers.templates = propAnswer("templates", { ns: 10, title: "Template:Infobox" });
  assert.deepEqual(await titlesFrom(lists.templatesOn("Volcano", 1)), ["Template:Infobox"]);
  assert.equal(sent(site, "templates").tllimit, "1");
  site.answers.categories = propAnswer("categories", { ns: 14, title: "Category:Volcanoes" });
  assert.deepEqual(await titlesFrom(lists.categoriesOf("Volcano", 2)), ["Category:Volcanoes"]);
  assert.equal(sent(site, "categories").cllimit, "2");
  site.answers.images = propAnswer("images", { ns: 0, title: "Etna.jpg" });
  assert.deepEqual(await titlesFrom(lists.filesOn("Volcano", 3)), ["Etna.jpg"]);
  assert.equal(sent(site, "images").imlimit, "3");
  site.answers.extlinks = propAnswer("extlinks", { url: "https://example.org/" });
  assert.deepEqual(await titlesFrom(lists.externalLinksOn("Volcano", 4)), ["https://example.org/"]);
  assert.equal(sent(site, "extlinks").ellimit, "4");
  assert.deepEqual(await titlesFrom(lists.templatesOn("Volcano")), ["Template:Infobox"]);
  assert.deepEqual(await titlesFrom(lists.templatesOn("Volcano", null)), ["Template:Infobox"]);

  site.answers.langlinks = propAnswer(
    "langlinks",
    { lang: "fr", title: "Volcan", url: "https://fr.example.org/Volcan", autonym: "français", langname: "French" },
    { lang: "de", title: "Vulkan" },
  );
  const [french, german] = await collect(lists.languageLinksOn("Volcano", 5));
  assert.ok(french instanceof LanguageLink);
  assert.equal(french.code, "fr");
  assert.equal(french.title, "Volcan");
  assert.equal(french.url, "https://fr.example.org/Volcan");
  assert.equal(french.autonym, "français");
  assert.equal(french.name, "French");
  assert.equal(sent(site, "langlinks").lllimit, "5");
  assert.ok(german instanceof LanguageLink);
  assert.equal(german.code, "de");
  assertNone(german.url);
  assertNone(german.autonym);
  assertNone(german.name);

  site.answers.iwlinks = propAnswer("iwlinks", { prefix: "w", title: "Etsy", url: "https://en.wikipedia.org/wiki/Etsy" }, { prefix: "w", title: "Etna" });
  const [etsy, etna] = await collect(lists.interwikiLinksOn("Volcano", 6));
  assert.ok(etsy instanceof InterwikiLink);
  assert.equal(etsy.prefix, "w");
  assert.equal(etsy.title, "Etsy");
  assert.equal(etsy.url, "https://en.wikipedia.org/wiki/Etsy");
  assert.equal(sent(site, "iwlinks").iwlimit, "6");
  assert.ok(etna instanceof InterwikiLink);
  assertNone(etna.url);
}

async function walkingTheWholeWiki(lists: ListService, site: FakeSite): Promise<void> {
  site.answers.allpages = listAnswer("allpages", "Etna", "Fuji", "Hekla");
  assert.deepEqual(await titlesFrom(lists.allPages()), ["Etna", "Fuji", "Hekla"]);
  assert.equal(sent(site, "allpages").apnamespace, "0");
  assert.equal(sent(site, "allpages").apfilterredir, undefined);
  const backwards: AllPagesOptions = { namespace: 2, from: "A", to: "B", prefix: "Ab", descending: true, redirects: "no-redirects", limit: 7 };
  await titlesFrom(lists.allPages(backwards));
  const asked = sent(site, "allpages");
  assert.equal(asked.apnamespace, "2");
  assert.equal(asked.apfrom, "A");
  assert.equal(asked.apto, "B");
  assert.equal(asked.apprefix, "Ab");
  assert.equal(asked.apdir, "descending");
  assert.equal(asked.apfilterredir, "nonredirects");
  assert.equal(asked.aplimit, "7");
  assert.deepEqual(await titlesFrom(lists.allPages({ limit: 2 })), ["Etna", "Fuji"]);

  let steps = 0;
  site.answers.allpages = (params): JsonValue => {
    steps += 1;
    return params.apcontinue === undefined ? { continue: { apcontinue: "Fuji", continue: "-||" }, query: { allpages: [{ ns: 0, title: "Etna" }, { ns: 0, title: "Erebus" }] } } : { query: { allpages: [{ ns: 0, title: "Fuji" }] } };
  };
  for await (const title of lists.allPages()) {
    assert.equal(title, "Etna");
    break;
  }
  assert.equal(steps, 1, "ending a walk early stops the paging");
  assert.deepEqual(await titlesFrom(lists.allPages()), ["Etna", "Erebus", "Fuji"]);

  site.answers.allcategories = { query: { allcategories: [{ category: "Active volcanoes" }] } };
  const someCategories: AllCategoriesOptions = { from: "A", to: "B", prefix: "Ac", min: 1, max: 9, limit: 8 };
  assert.deepEqual(await titlesFrom(lists.allCategories(someCategories)), ["Category:Active volcanoes"]);
  const categories = sent(site, "allcategories");
  assert.equal(categories.acfrom, "A");
  assert.equal(categories.acto, "B");
  assert.equal(categories.acprefix, "Ac");
  assert.equal(categories.acmin, "1");
  assert.equal(categories.acmax, "9");
  assert.equal(categories.aclimit, "8");
  await titlesFrom(lists.allCategories());

  site.answers.trackingcategories = { query: { trackingcategories: [{ category: "Broken files" }] } };
  assert.deepEqual(await titlesFrom(lists.trackingCategories(2)), ["Category:Broken files"]);
  assert.equal(sent(site, "trackingcategories").tclimit, "2");

  const targets: AllTargetsOptions = { namespace: 2, from: "A", to: "B", prefix: "Ab", unique: false, limit: 4 };
  const walks = [
    { module: "alllinks", prefix: "al", walk: (options?: AllTargetsOptions) => lists.allLinkTargets(options), namespace: "0" },
    { module: "allredirects", prefix: "ar", walk: (options?: AllTargetsOptions) => lists.allRedirectTargets(options), namespace: "0" },
    { module: "alltransclusions", prefix: "at", walk: (options?: AllTargetsOptions) => lists.allTransclusionTargets(options), namespace: "10" },
  ];
  for (const { module, prefix, walk, namespace } of walks) {
    site.answers[module] = listAnswer(module, "Etna");
    assert.deepEqual(await titlesFrom(walk(targets)), ["Etna"]);
    const told = sent(site, module);
    assert.equal(told[`${prefix}namespace`], "2");
    assert.equal(told[`${prefix}from`], "A");
    assert.equal(told[`${prefix}to`], "B");
    assert.equal(told[`${prefix}prefix`], "Ab");
    assert.equal(told[`${prefix}unique`], undefined);
    assert.equal(told[`${prefix}limit`], "4");
    await titlesFrom(walk());
    assert.equal(sent(site, module)[`${prefix}namespace`], namespace);
    assert.equal(sent(site, module)[`${prefix}unique`], "1");
  }

  site.answers.allfileusages = listAnswer("allfileusages", "Etna.jpg");
  const fileUses: AllFileUsagesOptions = { from: "A", to: "B", prefix: "Ab", unique: false, limit: 4 };
  assert.deepEqual(await titlesFrom(lists.allFileUsages(fileUses)), ["Etna.jpg"]);
  const files = sent(site, "allfileusages");
  assert.equal(files.affrom, "A");
  assert.equal(files.afto, "B");
  assert.equal(files.afprefix, "Ab");
  assert.equal(files.afunique, undefined);
  assert.equal(files.aflimit, "4");
  await titlesFrom(lists.allFileUsages());
  assert.equal(sent(site, "allfileusages").afunique, "1");
}

async function searchingAndTheRest(lists: ListService, site: FakeSite): Promise<void> {
  site.answers.prefixsearch = listAnswer("prefixsearch", "Etna");
  site.answers.search = listAnswer("search", "Etna");
  site.answers.random = listAnswer("random", "Etna");

  assert.deepEqual(await titlesFrom(lists.prefixSearch("Et")), ["Etna"]);
  assert.equal(sent(site, "prefixsearch").pssearch, "Et");
  assert.equal(sent(site, "prefixsearch").psnamespace, "0");
  assert.deepEqual(await titlesFrom(lists.search("lava")), ["Etna"]);
  assert.equal(sent(site, "search").srsearch, "lava");
  assert.equal(sent(site, "search").srnamespace, "0");
  assert.equal(sent(site, "search").srwhat, "text");
  assert.equal(sent(site, "search").srsort, "relevance");
  assert.deepEqual(await titlesFrom(lists.randomPages()), ["Etna"]);
  assert.equal(sent(site, "random").rnnamespace, "0");

  const inUserSpace: ListOptions = { namespaces: [2], limit: 3 };
  await titlesFrom(lists.prefixSearch("Et", inUserSpace));
  assert.equal(sent(site, "prefixsearch").psnamespace, "2");
  assert.equal(sent(site, "prefixsearch").pslimit, "3");
  const nearby: SearchOptions = { namespaces: [2, 10], scope: "near-match", sort: "last-edit-descending", limit: 3 };
  await titlesFrom(lists.search("lava", nearby));
  assert.equal(sent(site, "search").srnamespace, "2|10");
  assert.equal(sent(site, "search").srwhat, "nearmatch");
  assert.equal(sent(site, "search").srsort, "last_edit_desc");
  assert.equal(sent(site, "search").srlimit, "3");
  await titlesFrom(lists.randomPages(inUserSpace));
  assert.equal(sent(site, "random").rnnamespace, "2");
  assert.equal(sent(site, "random").rnlimit, "3");

  site.answers.querypage = { query: { querypage: { name: "Lonelypages", results: [{ ns: 0, title: "Etna" }] } } };
  assert.deepEqual(await titlesFrom(lists.specialPage("Lonelypages", 2)), ["Etna"]);
  assert.equal(sent(site, "querypage").qppage, "Lonelypages");
  assert.equal(sent(site, "querypage").qplimit, "2");

  const firstTwo: ListOptions = { namespaces: [0], limit: 2 };
  site.answers.watchlistraw = listAnswer("watchlistraw", "Etna");
  assert.deepEqual(await titlesFrom(lists.watchlist(firstTwo)), ["Etna"]);
  assert.equal(sent(site, "watchlistraw").wrnamespace, "0");
  assert.equal(sent(site, "watchlistraw").wrlimit, "2");
  site.answers.pageswithprop = listAnswer("pageswithprop", "Etna");
  assert.deepEqual(await titlesFrom(lists.pagesWithProperty("disambiguation", 2)), ["Etna"]);
  assert.equal(sent(site, "pageswithprop").pwppropname, "disambiguation");
  assert.equal(sent(site, "pageswithprop").pwplimit, "2");
  site.answers.protectedtitles = listAnswer("protectedtitles", "Etna");
  assert.deepEqual(await titlesFrom(lists.protectedTitles(firstTwo)), ["Etna"]);
  assert.equal(sent(site, "protectedtitles").ptnamespace, "0");
  assert.equal(sent(site, "protectedtitles").ptlimit, "2");

  site.answers.protectedtitles = { error: { code: "nope", info: "not today" } };
  await assert.rejects(collect(lists.protectedTitles()), (error: unknown) => {
    assert.equal(describeError(error)?.kind, "api");
    return true;
  });
}

function misuses(lists: ListService): void {
  // @ts-expect-error a member type is one of three, in the singular
  void lists.categoryMembers("Volcanoes", { type: "pages" });
  // @ts-expect-error a category sort is by key or by time
  void lists.categoryMembers("Volcanoes", { sort: "newest-frist" });
  // @ts-expect-error a redirect filter spells its values with a hyphen
  void lists.allPages({ redirects: "nonredirects" });
  // @ts-expect-error a search scope is spelled as this package spells it
  void lists.search("lava", { scope: "nearmatch" });
  // @ts-expect-error a search sort is spelled as this package spells it
  void lists.search("lava", { sort: "last_edit_desc" });
  // @ts-expect-error namespaces are numbers
  void lists.backlinks("Volcano", { namespaces: ["Talk"] });
  // @ts-expect-error a limit is a number
  void lists.templatesOn("Volcano", "5");
  // @ts-expect-error allPages walks one namespace, not several
  void lists.allPages({ namespaces: [0] });
}
void misuses;

const site = new FakeSite();
await withWiki(site, async (wiki) => {
  const lists = wiki.lists;
  await whatIsInACategory(lists, site);
  await whatPointsAtAPage(lists, site);
  await whatIsOnAPage(lists, site);
  await walkingTheWholeWiki(lists, site);
  await searchingAndTheRest(lists, site);
});
console.log("listings: every ListService listing iterates, sends what its options say, and gives titles and links as its types promise");
