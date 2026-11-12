import assert from "node:assert/strict";
import { parseTitle, Title } from "kwikibot";
import { decodeSiteMatrix, MatrixWiki, SiteMatrix } from "kwikibot/client";
import {
  allSignatures,
  canonicalNamespaces,
  compareMediaWikiVersions,
  createInterwiki,
  createNamespaces,
  customFamily,
  DayFormat,
  dayFormat,
  daysInMonth,
  dayTitle,
  dayTitleLanguages,
  endpointFromHtml,
  endpointOf,
  Family,
  FAMILY_COMMONS,
  FAMILY_MEDIAWIKI,
  FAMILY_META,
  FAMILY_TEST,
  FAMILY_WIKIBOOKS,
  FAMILY_WIKIDATA,
  FAMILY_WIKINEWS,
  FAMILY_WIKIPEDIA,
  FAMILY_WIKIQUOTE,
  FAMILY_WIKISOURCE,
  FAMILY_WIKIVERSITY,
  FAMILY_WIKIVOYAGE,
  FAMILY_WIKTIONARY,
  familyAt,
  type FamilyKind,
  familyNamed,
  familyOf,
  formatTimestamp,
  formatTimestampCompact,
  isNeverTimestamp,
  MonthDay,
  Namespace,
  NAMESPACE_CATEGORY,
  NAMESPACE_CATEGORY_TALK,
  NAMESPACE_FILE,
  NAMESPACE_FILE_TALK,
  NAMESPACE_HELP,
  NAMESPACE_HELP_TALK,
  NAMESPACE_MAIN,
  NAMESPACE_MEDIA,
  NAMESPACE_MEDIAWIKI,
  NAMESPACE_MEDIAWIKI_TALK,
  NAMESPACE_PROJECT,
  NAMESPACE_PROJECT_TALK,
  NAMESPACE_SPECIAL,
  NAMESPACE_TALK,
  NAMESPACE_TEMPLATE,
  NAMESPACE_TEMPLATE_TALK,
  NAMESPACE_USER,
  NAMESPACE_USER_TALK,
  type NamespaceOptions,
  parseDayTitle,
  parseExpiry,
  parseTimestamp,
  parseTimestampOrNull,
  parseTitleWith,
  registerDayFormat,
  SignatureFormat,
  signatureFormat,
  Signatures,
  signaturesFor,
  signaturesOf,
  SignatureTime,
  signatureTimes,
  SiteLink,
  TITLE_MAX_BYTES,
  type TitleKind,
  type TitleOptions,
  type TitleProblem,
  wikimediaEndpoint,
} from "kwikibot/titles";
import { assertArray, assertDate, assertNone } from "./support.js";

function readTitles(): void {
  const local = parseTitle("category:volcanoes_of Italy#Etna");
  assert.ok(local instanceof Title);
  const kind: TitleKind = local.kind;
  assert.equal(kind, "local");
  assert.equal(local.namespace, NAMESPACE_CATEGORY);
  assert.equal(local.text, "Volcanoes of Italy");
  assert.equal(local.fragment, "Etna");
  assertNone(local.interwiki);
  assertNone(local.rest);
  assertNone(local.invalidReason);
  assertNone(local.raw);
  assert.equal(local.toString(), "Category:Volcanoes of Italy#Etna");
  assert.equal(`${local}`, "Category:Volcanoes of Italy#Etna");
  assertNone(parseTitle("Lava").fragment);

  const invalid = parseTitle("a[b]");
  assert.equal(invalid.kind, "invalid");
  assertNone(invalid.namespace);
  assertNone(invalid.text);
  const problem: TitleProblem | null | undefined = invalid.invalidReason;
  assert.equal(problem, "illegal-character");
  assert.equal(invalid.raw, "a[b]");
  assert.equal(invalid.toString(), "a[b]");
  assertNone(invalid.render());
  assertNone(invalid.renderPiped());

  const film = parseTitle("Category:Volcano (film)");
  assert.equal(film.render(), "[[Category:Volcano (film)]]");
  assert.equal(film.render("seen", true), "[[:Category:Volcano (film)|seen]]");
  assert.equal(film.render(null, true), "[[:Category:Volcano (film)]]");
  assert.equal(film.renderPiped(), "[[Category:Volcano (film)|Volcano]]");
}

function namespacesAndInterwiki(): void {
  const numbers: number[] = [
    NAMESPACE_MEDIA,
    NAMESPACE_SPECIAL,
    NAMESPACE_MAIN,
    NAMESPACE_TALK,
    NAMESPACE_USER,
    NAMESPACE_USER_TALK,
    NAMESPACE_PROJECT,
    NAMESPACE_PROJECT_TALK,
    NAMESPACE_FILE,
    NAMESPACE_FILE_TALK,
    NAMESPACE_MEDIAWIKI,
    NAMESPACE_MEDIAWIKI_TALK,
    NAMESPACE_TEMPLATE,
    NAMESPACE_TEMPLATE_TALK,
    NAMESPACE_HELP,
    NAMESPACE_HELP_TALK,
    NAMESPACE_CATEGORY,
    NAMESPACE_CATEGORY_TALK,
  ];
  assert.deepEqual(numbers, [-2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]);
  assert.equal(TITLE_MAX_BYTES, 255);

  const template = new Namespace(NAMESPACE_TEMPLATE);
  assert.ok(template instanceof Namespace);
  assert.equal(template.id, 10);
  assert.equal(template.isTalk, false);
  assert.equal(template.isVirtual, false);
  assert.equal(template.talkSpace, 11);
  assert.equal(template.subjectSpace, 10);
  assert.equal(template.toString(), "ns:10");
  const talk = new Namespace(11);
  assert.equal(talk.isTalk, true);
  assert.equal(talk.talkSpace, 11);
  assert.equal(talk.subjectSpace, 10);
  const special = new Namespace(NAMESPACE_SPECIAL);
  assert.equal(special.isVirtual, true);
  assertNone(special.talkSpace);
  assertNone(special.subjectSpace);

  const category: NamespaceOptions = {
    id: 14,
    canonicalName: "Category",
    localName: "Catégorie",
    aliases: ["Cat"],
    case: "case-sensitive",
    subpages: true,
  };
  const main: NamespaceOptions = { id: 0, canonicalName: "", localName: "" };
  const namespaces = createNamespaces([main, category]);
  const interwiki = createInterwiki(["w", "fr"], ["fr"]);
  assert.equal(interwiki.has("w"), true);
  assert.equal(interwiki.has("zz"), false);
  assert.equal(interwiki.isSelf("fr"), true);
  assert.equal(interwiki.isSelf("w"), false);
  assert.equal(createInterwiki(["w"]).isSelf("w"), false);
  assert.equal(namespaces.byPrefix("cat")?.id, 14);
  assert.equal(namespaces.get(14)?.case, "case-sensitive");
  assert.equal(namespaces.get(14)?.subpages, true);

  const options: TitleOptions = { namespaces, interwiki };
  const read = parseTitleWith("cat:lave", options);
  assert.equal(read.namespace, 14);
  assert.equal(read.text, "lave");
  assert.equal(namespaces.format(read), "Catégorie:lave");
  assert.equal(read.toString(), "Catégorie:lave");

  const elsewhere = parseTitleWith("w:Lave", { interwiki });
  assert.equal(elsewhere.kind, "interwiki");
  assert.equal(elsewhere.interwiki, "w");
  assert.equal(elsewhere.rest, "Lave");
  assertNone(elsewhere.namespace);
  assertNone(elsewhere.text);
  assertNone(elsewhere.render());
  assertNone(elsewhere.renderPiped());
  assert.throws(() => namespaces.format(elsewhere));

  assert.equal(parseTitleWith("Lava", { defaultNamespace: NAMESPACE_TEMPLATE }).namespace, 10);
  assert.equal(parseTitleWith("Lava").namespace, 0);
  assert.equal(parseTitleWith("Lava", null).namespace, 0);
  assert.equal(parseTitleWith("Lava", { namespaces: null, interwiki: null, defaultNamespace: null }).namespace, 0);
  assert.deepEqual(canonicalNamespaces().get(NAMESPACE_FILE)?.prefixes, ["File", "Image"]);
}

function timestamps(): void {
  const iso = "2026-08-31T21:43:26Z";
  const expected = new Date(iso).getTime();
  assert.equal(assertDate(parseTimestamp(iso)).getTime(), expected);
  assert.equal(assertDate(parseTimestamp("20260831214326")).getTime(), expected);
  assertNone(parseTimestampOrNull("soon"));
  assert.equal(assertDate(parseTimestampOrNull(iso)).getTime(), expected);
  assert.throws(() => parseTimestamp("soon"));
  assert.equal(isNeverTimestamp("infinity"), true);
  assert.equal(isNeverTimestamp(iso), false);
  assert.equal(formatTimestamp(new Date("2026-08-31T21:43:26.500Z")), iso);
  assert.equal(formatTimestampCompact(new Date(iso)), "20260831214326");
  assert.equal(assertDate(parseExpiry(iso)).getTime(), expected);
  assertNone(parseExpiry("infinity"));
  assert.throws(() => parseExpiry("soon"));
  assert.ok(compareMediaWikiVersions("1.9", "1.10") < 0);
  assert.ok(compareMediaWikiVersions("1.44.0", "1.44.0-wmf.3") > 0);
  assert.equal(compareMediaWikiVersions("1.44", "1.44"), 0);
}

function interwikiLinks(): void {
  const link = new SiteLink("fr", "Volcan", "ici");
  assert.equal(link.prefix, "fr");
  assert.equal(link.title, "Volcan");
  assert.equal(link.text, "ici");
  assert.equal(link.render(), "[[fr:Volcan|ici]]");
  assert.equal(link.render(true), "[[:fr:Volcan|ici]]");
  const plain = new SiteLink("fr", "Volcan");
  assertNone(plain.text);
  assert.equal(plain.toString(), "[[fr:Volcan]]");
  assertNone(new SiteLink("fr", "Volcan", null).text);
}

function dayPages(): void {
  assert.equal(dayTitle(1, 1, "fr"), "1er janvier");
  assert.equal(dayTitle(1, 31), "January 31");
  assertNone(dayTitle(1, 1, "xx"));
  assert.throws(() => dayTitle(2, 30));

  const read = parseDayTitle("1er janvier", "fr");
  assert.ok(read instanceof MonthDay);
  assert.equal(read.month, 1);
  assert.equal(read.day, 1);
  assertNone(parseDayTitle("Lava"));

  assert.equal(daysInMonth(2), 29);
  assert.equal(daysInMonth(4), 30);
  assert.ok(assertArray(dayTitleLanguages()).includes("en"));

  assertNone(dayFormat("xx"));
  const french = dayFormat("fr");
  assert.ok(french instanceof DayFormat);
  assert.equal(french.pattern, "$ordinal $month");
  assert.equal(french.firstOrdinal, "er");
  assert.equal(assertArray(french.months)[0], "janvier");
  assertNone(dayFormat("en")?.firstOrdinal);

  const months = ["m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9", "m10", "m11", "m12"];
  registerDayFormat("zz", new DayFormat(months, "$day-$month"));
  assert.equal(dayTitle(3, 4, "zz"), "4-m3");
  assert.equal(parseDayTitle("4-m3", "zz")?.month, 3);
  assertNone(new DayFormat(months, "$day-$month", null).firstOrdinal);
  assert.ok(dayTitleLanguages().includes("zz"));
}

function signatures(): void {
  const thanks = "Thanks. [[User:Alice|Alice]] 21:43, 31 August 2026 (UTC)";
  const found = signatureTimes(thanks);
  assert.equal(assertArray(found).length, 1);
  const only = found[0];
  assert.ok(only instanceof SignatureTime);
  assert.equal(assertDate(only.date).toISOString(), "2026-08-31T21:43:00.000Z");
  assert.equal(only.text, "21:43, 31 August 2026 (UTC)");
  assert.equal(thanks.substring(only.start, only.end), only.text);
  assert.equal(only.language, "en");
  assert.equal(signatureTimes(thanks, "en").length, 1);

  const text = "first 21:43, 31 August 2026 (UTC) then 22:43, 31 August 2026 (UTC)";
  const english = signaturesFor("en");
  assert.ok(english instanceof Signatures);
  assert.equal(assertArray(english.findAll(text)).length, 2);
  assert.equal(english.latest(text)?.text, "22:43, 31 August 2026 (UTC)");
  assert.equal(english.earliest(text)?.text, "21:43, 31 August 2026 (UTC)");
  assertNone(english.latest("nothing"));
  assertNone(english.earliest("nothing"));
  assert.equal(assertArray(allSignatures().formats).length, english.formats.length + 3);
  assert.equal(signaturesFor("fr").plus(english).formats.length, english.formats.length + 1);
  const pattern = assertArray(english.patterns())[0];
  assert.ok(pattern instanceof RegExp);

  const first = english.formats[0];
  assert.ok(first instanceof SignatureFormat);
  assert.equal(assertArray(first.findAll(text)).length, 2);
  assert.ok(first.pattern instanceof RegExp);
  assert.ok(first.pattern.toString().includes("UTC"));

  const lunar = signatureFormat("xx", /(?<day>\d+)\/(?<month>\p{L}+)\/(?<year>\d{4}) (?<hour>\d\d):(?<minute>\d\d) XT/u, { lune: 3 }, "Europe/Paris");
  const read = signaturesOf([lunar]).findAll("say 4/lune/2026 12:00 XT");
  assert.equal(read[0]?.language, "xx");
  assert.equal(read[0]?.date.toISOString(), "2026-03-04T11:00:00.000Z");
  assert.equal(signatureFormat("yy", /(?<day>\d+)\/(?<month>\p{L}+)\/(?<year>\d{4}) (?<hour>\d\d):(?<minute>\d\d) YT/u, { lune: 3 }).findAll("4/lune/2026 12:00 YT")[0]?.date.toISOString(), "2026-03-04T12:00:00.000Z");
}

function families(): void {
  assert.ok(FAMILY_WIKTIONARY instanceof Family);
  const kind: FamilyKind = FAMILY_WIKTIONARY.kind;
  assert.equal(kind, "wikimedia");
  assert.equal(FAMILY_WIKTIONARY.name, "wiktionary");
  assert.equal(FAMILY_WIKTIONARY.domain, "wiktionary.org");
  assertNone(FAMILY_WIKTIONARY.server);
  assertNone(FAMILY_WIKTIONARY.scriptPath);
  assert.equal(FAMILY_WIKTIONARY.endpoint("fr").server, "fr.wiktionary.org");

  assert.equal(FAMILY_COMMONS.kind, "single-site");
  assert.equal(FAMILY_COMMONS.server, "commons.wikimedia.org");
  assert.equal(FAMILY_COMMONS.scriptPath, "/w");
  assertNone(FAMILY_COMMONS.domain);
  assert.equal(FAMILY_COMMONS.endpoint("fr").server, "commons.wikimedia.org");

  const wikimediaFamilies: Family[] = [FAMILY_WIKIPEDIA, FAMILY_WIKISOURCE, FAMILY_WIKIQUOTE, FAMILY_WIKIBOOKS, FAMILY_WIKINEWS, FAMILY_WIKIVERSITY, FAMILY_WIKIVOYAGE];
  for (const family of wikimediaFamilies) {
    assert.equal(family.kind, "wikimedia");
    assert.equal(familyNamed(family.name)?.name, family.name);
  }
  for (const family of [FAMILY_WIKIDATA, FAMILY_META, FAMILY_MEDIAWIKI, FAMILY_TEST]) {
    assert.equal(family.kind, "single-site");
    assert.equal(familyNamed(family.name)?.name, family.name);
  }
  assertNone(familyNamed("nope"));

  const custom = customFamily("mine", "wiki.example.org", "");
  assert.equal(custom.name, "mine");
  assert.equal(custom.server, "wiki.example.org");
  assert.equal(custom.scriptPath, "");
  assert.equal(customFamily("mine", "wiki.example.org").scriptPath, "/w");

  assert.equal(familyAt(endpointOf("a.example.org", "/x")).name, "a.example.org");
  assert.equal(familyAt(endpointOf("a.example.org"), "named").name, "named");
  assert.equal(familyAt(endpointOf("a.example.org"), null).name, "a.example.org");

  const local = endpointOf("localhost:8080", "", false);
  assert.equal(local.apiUrl, "http://localhost:8080/api.php");
  assert.equal(local.secure, false);
  assert.equal(wikimediaEndpoint("en", "wiktionary.org").server, "en.wiktionary.org");

  const html = '<link rel="EditURI" type="application/rsd+xml" href="//a.example.org/w/api.php?action=rsd">';
  assert.equal(endpointFromHtml(html, "https://a.example.org/wiki/X")?.scriptPath, "/w");
  assertNone(endpointFromHtml("<html></html>", "https://a.example.org/wiki/X"));
}

function siteMatrix(): void {
  const matrix = decodeSiteMatrix({
    sitematrix: {
      count: 3,
      "0": {
        code: "en",
        name: "English",
        site: [
          { url: "https://en.wiktionary.org", dbname: "enwiktionary", code: "wiktionary" },
          { url: "https://en.wikipedia.org", dbname: "enwiki", code: "wikipedia", closed: true },
        ],
      },
      specials: [{ url: "https://secret.wikimedia.org", dbname: "secretwiki", code: "secret", private: true }],
    },
  });
  assert.ok(matrix instanceof SiteMatrix);
  assert.equal(assertArray(matrix.wikis).length, 3);
  assert.deepEqual(assertArray(matrix.of("wiktionary")).map((wiki) => wiki.id), ["enwiktionary"]);
  assert.deepEqual(assertArray(matrix.open()).map((wiki) => wiki.id), ["enwiktionary"]);
  assert.deepEqual(assertArray(matrix.languagesOf("wikipedia")), ["en"]);
  assertNone(matrix.get("fr", "wikipedia"));
  assertNone(matrix.byId("nope"));

  const wiktionary = matrix.get("en", "wiktionary");
  assert.ok(wiktionary instanceof MatrixWiki);
  assert.equal(wiktionary.id, "enwiktionary");
  assert.equal(wiktionary.url, "https://en.wiktionary.org");
  assert.equal(wiktionary.language, "en");
  assert.equal(wiktionary.project, "wiktionary");
  assert.equal(wiktionary.isClosed, false);
  assert.equal(wiktionary.isPrivate, false);
  assert.equal(wiktionary.endpoint.server, "en.wiktionary.org");
  assert.equal(familyOf(wiktionary).name, "wiktionary");

  assert.equal(matrix.byId("enwiki")?.isClosed, true);
  const secret = matrix.byId("secretwiki");
  assert.ok(secret != null);
  assert.equal(secret.isPrivate, true);
  assertNone(secret.language);
  assert.equal(secret.project, "secret");
  assert.equal(secret.endpoint.server, "secret.wikimedia.org");
}

function misuses(title: Title, family: Family): void {
  // @ts-expect-error a typo in a title's kind
  if (title.kind === "locale") return;
  // @ts-expect-error a typo in a title's problem
  if (title.invalidReason === "illegal_character") return;
  // @ts-expect-error a typo in a family's kind
  if (family.kind === "wikimedia-site") return;
  // @ts-expect-error a typo in a namespace's case
  createNamespaces([{ id: 1, case: "shouting" }]);
  // @ts-expect-error a namespace needs its number
  createNamespaces([{ canonicalName: "Talk" }]);
  // @ts-expect-error a namespace is built from a number
  new Namespace("Talk");
}
void misuses;

readTitles();
namespacesAndInterwiki();
timestamps();
interwikiLinks();
dayPages();
signatures();
families();
siteMatrix();

console.log("titles_model: titles, namespaces, timestamps, day pages, signatures, families and the site matrix keep what their types say");
