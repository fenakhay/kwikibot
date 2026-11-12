import assert from "node:assert/strict";
import { BotPassword, type JsonValue } from "kwikibot";
import {
  Claim,
  claim,
  type ClaimOptions,
  coordinateValue,
  DataValue,
  decodeClaim,
  decodeDataValue,
  decodeEntities,
  decodeEntity,
  decodeSnak,
  encodeAliases,
  encodeClaim,
  encodeClaims,
  encodeDataValue,
  encodeLanguageValues,
  encodeSiteLinks,
  encodeSnak,
  Entity,
  ENTITY_BATCH_SIZE,
  ENTITY_SEARCH_LIMIT,
  type EntityEditOptions,
  type EntityKind,
  entityKind,
  EntityMatch,
  EntityOfPage,
  entityRedirectTarget,
  type EntityService,
  EntitySiteLink,
  entityValue,
  LanguageValue,
  LexemeForm,
  LexemeSense,
  type MergeConflict,
  monolingualValue,
  MULTIPLE_LANGUAGES,
  type NewEntityKind,
  noValueSnak,
  quantityValue,
  Reference,
  Snak,
  type SnakKind,
  someValueSnak,
  type StatementRank,
  textValue,
  TIME_PRECISION_BILLION_YEARS,
  TIME_PRECISION_CENTURY,
  TIME_PRECISION_DAY,
  TIME_PRECISION_DECADE,
  TIME_PRECISION_MONTH,
  TIME_PRECISION_YEAR,
  timeValue,
  UNITLESS,
  unknownValue,
  valueSnak,
} from "kwikibot/wikibase";
import { FakeSite, assertArray, assertNone, assertRecord, withWiki } from "./support.js";

const GREGORIAN = "http://www.wikidata.org/entity/Q1985727";
const EARTH = "http://www.wikidata.org/entity/Q2";

const statedIn: Record<string, JsonValue> = {
  snaktype: "value",
  property: "P248",
  datatype: "wikibase-item",
  datavalue: { value: { "entity-type": "item", "numeric-id": 36578, id: "Q36578" }, type: "wikibase-entityid" },
};

const instanceOf: Record<string, JsonValue> = {
  mainsnak: {
    snaktype: "value",
    property: "P31",
    datatype: "wikibase-item",
    datavalue: { value: { "entity-type": "item", "numeric-id": 5, id: "Q5" }, type: "wikibase-entityid" },
  },
  type: "statement",
  id: "Q42$1",
  rank: "normal",
  qualifiers: {
    P580: [{ snaktype: "somevalue", property: "P580" }],
  },
  "qualifiers-order": ["P580"],
  references: [{ snaks: { P248: [statedIn] }, "snaks-order": ["P248"] }],
};

const adams: Record<string, JsonValue> = {
  type: "item",
  id: "Q42",
  lastrevid: 2300000000,
  labels: {
    en: { language: "en", value: "Douglas Adams" },
    mul: { language: "mul", value: "Douglas Noel Adams" },
  },
  descriptions: { en: { language: "en", value: "English writer" } },
  aliases: { en: [{ language: "en", value: "DNA" }, { language: "en", value: "Bop Ad" }] },
  claims: {
    P31: [instanceOf],
    P569: [
      { mainsnak: { snaktype: "novalue", property: "P569" }, type: "statement", rank: "deprecated", id: "Q42$2" },
      { mainsnak: { snaktype: "somevalue", property: "P569" }, type: "statement", rank: "preferred", id: "Q42$3" },
      { mainsnak: { snaktype: "novalue", property: "P569" }, type: "statement", rank: "normal", id: "Q42$4" },
    ],
  },
  sitelinks: { enwiki: { site: "enwiki", title: "Douglas Adams", badges: ["Q17437798"] } },
};

const lexeme: Record<string, JsonValue> = {
  id: "L1",
  type: "lexeme",
  language: "Q1860",
  lexicalCategory: "Q1084",
  lemmas: { en: { language: "en", value: "lava" } },
  forms: [
    {
      id: "L1-F1",
      representations: { en: { language: "en", value: "lavas" } },
      grammaticalFeatures: ["Q146786"],
      claims: { P1: [{ mainsnak: { snaktype: "novalue", property: "P1" }, type: "statement" }] },
    },
  ],
  senses: [
    {
      id: "L1-S1",
      glosses: { en: { language: "en", value: "molten rock" } },
      claims: { P2: [{ mainsnak: { snaktype: "novalue", property: "P2" }, type: "statement" }] },
    },
  ],
};

function readAnItem(item: Entity): void {
  assert.ok(item instanceof Entity);
  assert.equal(item.id, "Q42");
  const kind: EntityKind = item.type;
  assert.equal(kind, "item");
  assert.equal(item.lastRevision, 2300000000);
  assert.equal(typeof item.lastRevision, "number");

  assert.equal(item.label("en"), "Douglas Adams");
  assert.equal(item.label("de"), "Douglas Noel Adams");
  assert.equal(item.labelIn("en"), "Douglas Adams");
  assertNone(item.labelIn("de"));
  assert.equal(item.description("en"), "English writer");
  assertNone(item.description("de"));

  assert.deepEqual(assertArray(item.aliases("en")), ["DNA", "Bop Ad"]);
  assert.deepEqual(assertArray(item.aliases("de")), []);

  const labels = assertRecord(item.labels);
  const english = labels.en;
  assert.ok(english instanceof LanguageValue);
  assert.equal(english.language, "en");
  assert.equal(english.value, "Douglas Adams");
  assert.equal(assertRecord(item.descriptions).en?.value, "English writer");
  const everyAlias = assertRecord(item.allAliases);
  assert.equal(assertArray(everyAlias.en ?? [])[1]?.value, "Bop Ad");

  assert.deepEqual(assertArray(item.properties), ["P31", "P569"]);
  const statements = assertRecord(item.statements);
  assert.equal(assertArray(statements.P31 ?? []).length, 1);
  assert.equal(assertArray(item.claims("P569")).length, 3);
  assert.deepEqual(assertArray(item.claims("P1")), []);
  assert.equal(item.bestClaim("P569")?.id, "Q42$3");
  assertNone(item.bestClaim("P1"));

  const links = item.siteLinks;
  assert.ok(links != null);
  assertRecord(links);
  const link = item.siteLink("enwiki");
  assert.ok(link instanceof EntitySiteLink);
  assert.equal(link.site, "enwiki");
  assert.equal(link.title, "Douglas Adams");
  assert.deepEqual(assertArray(link.badges), ["Q17437798"]);
  assertNone(item.siteLink("dewiki"));

  assertNone(item.dataType);
  assertNone(item.lemmas);
  assertNone(item.language);
  assertNone(item.lexicalCategory);
  assertNone(item.forms);
  assertNone(item.senses);
  assertNone(item.lemma("en"));
  assertNone(item.caption("en"));
}

function readAStatement(statement: Claim): void {
  assert.ok(statement instanceof Claim);
  assert.equal(statement.id, "Q42$1");
  assert.equal(statement.property, "P31");
  const rank: StatementRank = statement.rank;
  assert.equal(rank, "normal");
  const type: SnakKind = statement.snakType;
  assert.equal(type, "value");
  assert.equal(statement.dataType, "wikibase-item");

  const main = statement.mainSnak;
  assert.ok(main instanceof Snak);
  assert.equal(main.kind, "value");
  assert.equal(main.property, "P31");
  assert.equal(main.dataType, "wikibase-item");
  assert.equal(main.value?.id, "Q5");

  const raw = statement.value;
  assert.ok(raw != null);
  assertRecord(raw);
  assert.deepEqual(raw, { value: { "entity-type": "item", "numeric-id": 5, id: "Q5" }, type: "wikibase-entityid" });

  const value = statement.dataValue;
  assert.ok(value instanceof DataValue);
  assert.equal(value.kind, "entity-ref");
  assert.equal(value.id, "Q5");

  assert.equal(assertArray(statement.qualifiers).length, 1);
  const qualifier = statement.qualifiersFor("P580")[0];
  assert.ok(qualifier != null);
  assert.equal(qualifier.kind, "somevalue");
  assertNone(qualifier.value);
  assertNone(qualifier.dataType);
  assert.deepEqual(assertArray(statement.qualifiersFor("P585")), []);

  const source = assertArray(statement.references)[0];
  assert.ok(source instanceof Reference);
  assert.equal(assertArray(source.snaks).length, 1);
  assert.equal(assertArray(source.get("P248"))[0]?.value?.id, "Q36578");
  assert.deepEqual(assertArray(source.get("P854")), []);

  assert.deepEqual(statement.toJSON(), instanceOf);
  assert.deepEqual(encodeClaim(statement), instanceOf);
  assert.deepEqual(JSON.parse(JSON.stringify(statement)), instanceOf);
}

function everyKindOfValue(): void {
  const isbn = textValue("ISBN");
  assert.equal(isbn.kind, "text");
  assert.equal(isbn.value, "ISBN");
  assert.deepEqual(isbn.toJSON(), { type: "string", value: "ISBN" });
  assertNone(isbn.id);
  assertNone(isbn.time);
  assertNone(isbn.precision);
  assertNone(isbn.calendarModel);
  assertNone(isbn.before);
  assertNone(isbn.after);
  assertNone(isbn.timezone);
  assertNone(isbn.amount);
  assertNone(isbn.unit);
  assertNone(isbn.upperBound);
  assertNone(isbn.lowerBound);
  assertNone(isbn.latitude);
  assertNone(isbn.longitude);
  assertNone(isbn.globe);
  assertNone(isbn.text);
  assertNone(isbn.language);
  assertNone(isbn.type);
  assertNone(isbn.json);

  const human = entityValue("Q5");
  assert.equal(human.kind, "entity-ref");
  assert.equal(human.id, "Q5");
  assertNone(human.value);

  const born = timeValue("+1952-03-11T00:00:00Z", TIME_PRECISION_DAY, GREGORIAN, 1, 2, 60);
  assert.equal(born.kind, "time");
  assert.equal(born.time, "+1952-03-11T00:00:00Z");
  assert.equal(born.precision, 11);
  assert.equal(born.calendarModel, GREGORIAN);
  assert.equal(born.before, 1);
  assert.equal(born.after, 2);
  assert.equal(born.timezone, 60);
  assert.deepEqual(decodeDataValue(born.toJSON()).toJSON(), born.toJSON());
  const plain = timeValue("+1952-00-00T00:00:00Z", TIME_PRECISION_YEAR, GREGORIAN);
  assert.equal(plain.precision, 9);
  assert.equal(plain.before, 0);
  assert.equal(plain.after, 0);
  assert.equal(plain.timezone, 0);

  const height = quantityValue("+1.96", "http://www.wikidata.org/entity/Q11573", "+1.97", "+1.95");
  assert.equal(height.kind, "quantity");
  assert.equal(height.amount, "+1.96");
  assert.equal(height.unit, "http://www.wikidata.org/entity/Q11573");
  assert.equal(height.upperBound, "+1.97");
  assert.equal(height.lowerBound, "+1.95");
  const count = quantityValue("+3");
  assert.equal(count.unit, UNITLESS);
  assertNone(count.upperBound);
  assertNone(count.lowerBound);

  const cambridge = coordinateValue(52.2, 0.11, 0.1, EARTH);
  assert.equal(cambridge.kind, "globe-coordinate");
  assert.equal(cambridge.latitude, 52.2);
  assert.equal(cambridge.longitude, 0.11);
  assert.equal(cambridge.precision, 0.1);
  assert.equal(cambridge.globe, EARTH);
  assertNone(coordinateValue(1.5, -2.5, null, EARTH).precision);

  const motto = monolingualValue("Fiat lux", "la");
  assert.equal(motto.kind, "monolingual");
  assert.equal(motto.text, "Fiat lux");
  assert.equal(motto.language, "la");

  const novel = unknownValue("novel", { a: 1 });
  assert.equal(novel.kind, "unknown");
  assert.equal(novel.type, "novel");
  assert.deepEqual(novel.toJSON(), { type: "novel", value: { a: 1 } });
  const arrived = decodeDataValue({ type: "novel", value: { a: 1 } });
  assert.equal(arrived.kind, "unknown");
  assert.equal(arrived.type, "novel");
  assert.deepEqual(arrived.json, { a: 1 });
  assert.deepEqual(novel.json, { a: 1 });
  assert.deepEqual(unknownValue("odd", "x").toJSON(), { type: "odd", value: "x" });

  for (const value of [isbn, human, born, height, cambridge, motto, novel]) {
    assert.ok(value instanceof DataValue);
    assert.deepEqual(encodeDataValue(value), value.toJSON());
    assert.equal(decodeDataValue(value.toJSON()).kind, value.kind);
  }
}

function theNamedNumbers(): void {
  assert.equal(TIME_PRECISION_BILLION_YEARS, 0);
  assert.equal(TIME_PRECISION_CENTURY, 7);
  assert.equal(TIME_PRECISION_DECADE, 8);
  assert.equal(TIME_PRECISION_YEAR, 9);
  assert.equal(TIME_PRECISION_MONTH, 10);
  assert.equal(TIME_PRECISION_DAY, 11);
  assert.equal(MULTIPLE_LANGUAGES, "mul");
  assert.equal(UNITLESS, "1");
  assert.equal(ENTITY_BATCH_SIZE, 50);
  assert.equal(ENTITY_SEARCH_LIMIT, 7);
  const kinds: EntityKind[] = ["Q1", "P1", "L1", "L1-F1", "L1-S1", "M1", "X1"].map((id) => entityKind(id));
  assert.deepEqual(kinds, ["item", "property", "lexeme", "form", "sense", "mediainfo", "unknown"]);
}

function buildAStatement(): void {
  const main = valueSnak("P31", entityValue("Q5"), "wikibase-item");
  assert.equal(main.kind, "value");
  assert.equal(main.dataType, "wikibase-item");
  assertNone(valueSnak("P31", entityValue("Q5")).dataType);
  assert.equal(someValueSnak("P1").kind, "somevalue");
  assert.equal(noValueSnak("P1").kind, "novalue");
  assert.deepEqual(noValueSnak("P1").toJSON(), { snaktype: "novalue", property: "P1" });
  assert.deepEqual(encodeSnak(main), main.toJSON());
  assert.equal(decodeSnak(main.toJSON()).property, "P31");

  const source = new Reference([valueSnak("P854", textValue("https://example.org"))]);
  const options: ClaimOptions = {
    id: "Q1$7",
    rank: "preferred",
    qualifiers: [noValueSnak("P580"), noValueSnak("P582")],
    references: [source],
  };
  const built = claim(main, options);
  assert.equal(built.id, "Q1$7");
  assert.equal(built.rank, "preferred");
  assert.deepEqual(built.qualifiers.map((snak) => snak.property), ["P580", "P582"]);
  assert.equal(built.references[0]?.get("P854")[0]?.value?.value, "https://example.org");
  assert.equal(built.dataValue?.id, "Q5");

  const bare = claim(noValueSnak("P9"));
  assertNone(bare.id);
  assert.equal(bare.rank, "normal");
  assertNone(bare.dataType);
  assertNone(bare.value);
  assertNone(bare.dataValue);
  assert.deepEqual(assertArray(bare.qualifiers), []);
  assert.deepEqual(assertArray(bare.references), []);
  assert.equal(claim(noValueSnak("P9"), null).rank, "normal");
  assert.equal(claim(noValueSnak("P9"), { rank: null, id: null, qualifiers: null, references: null }).rank, "normal");

  assertNone(built.copy({ id: null }).id);
  assert.deepEqual(built.copy({ qualifiers: null }).qualifiers, []);
  const copied = built.copy({ rank: "deprecated", id: "Q1$8" });
  assert.equal(copied.rank, "deprecated");
  assert.equal(copied.id, "Q1$8");
  assert.equal(copied.qualifiers.length, 2);
  assert.equal(built.rank, "preferred");
  assert.equal(built.withMainSnak(noValueSnak("P9")).snakType, "novalue");
  assert.equal(built.withMainSnak(noValueSnak("P9")).qualifiers.length, 2);
}

function readAndWriteBack(): void {
  const read = decodeClaim(instanceOf);
  readAStatement(read);
  assert.deepEqual(decodeSnak(read.mainSnak.toJSON()).toJSON(), read.mainSnak.toJSON());
  const value = read.dataValue;
  assert.ok(value != null);
  assert.equal(decodeDataValue(value.toJSON()).id, "Q5");

  const entries = decodeEntities({ entities: { Q42: adams, Q0: { id: "Q0", missing: "" } } });
  assertRecord(entries);
  assert.deepEqual(Object.keys(entries), ["Q42"]);
  assert.equal(entries.Q42?.id, "Q42");

  assert.equal(entityRedirectTarget({ id: "Q1", redirects: { from: "Q1", to: "Q2" } }), "Q2");
  assertNone(entityRedirectTarget(adams));

  const claims = encodeClaims({ P31: [read] });
  assertRecord(claims);
  assert.deepEqual(claims, { P31: [instanceOf] });
  assert.deepEqual(encodeLanguageValues({ en: new LanguageValue("en", "a") }), { en: { language: "en", value: "a" } });
  assert.deepEqual(encodeAliases({ en: [new LanguageValue("en", "a")] }), { en: [{ language: "en", value: "a" }] });
  assert.deepEqual(encodeSiteLinks({ enwiki: new EntitySiteLink("enwiki", "A") }), { enwiki: { site: "enwiki", title: "A", badges: [] } });
  assert.deepEqual(assertArray(new EntitySiteLink("enwiki", "A").badges), []);
}

function otherEntityKinds(): void {
  const property = decodeEntity({ id: "P1", type: "property", datatype: "url" });
  assert.equal(property.type, "property");
  assert.equal(property.dataType, "url");
  assertNone(property.lastRevision);
  assertNone(property.siteLinks);

  const file = decodeEntity({ id: "M1", type: "mediainfo", labels: { en: { language: "en", value: "Lava flow" } } });
  assert.equal(file.type, "mediainfo");
  assert.equal(file.caption("en"), "Lava flow");
  assertNone(file.caption("fr"));

  const word = decodeEntity(lexeme);
  assert.equal(word.type, "lexeme");
  assert.equal(word.lemma("en"), "lava");
  assertNone(word.lemma("fr"));
  const lemmas = word.lemmas;
  assert.ok(lemmas != null);
  assert.equal(assertRecord(lemmas).en?.value, "lava");
  assert.equal(word.language, "Q1860");
  assert.equal(word.lexicalCategory, "Q1084");

  const forms = word.forms;
  assert.ok(forms != null);
  const form = assertArray(forms)[0];
  assert.ok(form instanceof LexemeForm);
  assert.equal(form.id, "L1-F1");
  assert.equal(assertRecord(form.representations).en?.value, "lavas");
  assert.deepEqual(assertArray(form.grammaticalFeatures), ["Q146786"]);
  assert.deepEqual(Object.keys(assertRecord(form.statements)), ["P1"]);

  const senses = word.senses;
  assert.ok(senses != null);
  const sense = assertArray(senses)[0];
  assert.ok(sense instanceof LexemeSense);
  assert.equal(sense.id, "L1-S1");
  assert.equal(assertRecord(sense.glosses).en?.value, "molten rock");
  assert.deepEqual(Object.keys(assertRecord(sense.statements)), ["P2"]);
}

async function aRepositoryBot(entities: EntityService, site: FakeSite): Promise<void> {
  const last = (action: string): Record<string, string> => {
    const found = site.requests.filter((request) => request.action === action).at(-1);
    assert.ok(found != null, `nobody asked for ${action}`);
    return found;
  };

  const one = await entities.entity("Q42");
  assert.ok(one != null);
  readAnItem(one);
  assert.equal(last("wbgetentities").ids, "Q42");
  assertNone(await entities.entity("Q0"));

  const many = await entities.entities(["Q42", "Q0"]);
  assert.deepEqual(Object.keys(assertRecord(many)), ["Q42"]);
  assert.equal(last("wbgetentities").ids, "Q42|Q0");

  const pages: EntityOfPage[] = await entities.forPages("enwiki", ["Douglas Adams", "Nobody"]);
  assert.equal(assertArray(pages).length, 1);
  const page = pages[0];
  assert.ok(page instanceof EntityOfPage);
  assert.equal(page.title, "Douglas Adams");
  assert.equal(page.entity.id, "Q42");
  assert.equal(last("wbgetentities").sites, "enwiki");

  const hits: EntityMatch[] = await entities.search("instance", "fr", "property", 3);
  assert.equal(assertArray(hits).length, 2);
  const hit = hits[0];
  assert.ok(hit instanceof EntityMatch);
  assert.equal(hit.id, "P31");
  assert.equal(hit.label, "instance of");
  assert.equal(hit.description, "that class");
  assertNone(hits[1]?.label);
  assertNone(hits[1]?.description);
  assert.equal(last("wbsearchentities").type, "property");
  await entities.search("x");
  assert.equal(last("wbsearchentities").type, "item");
  assert.equal(last("wbsearchentities").limit, String(ENTITY_SEARCH_LIMIT));


  const everything: EntityEditOptions = {
    labels: { en: "Lava" },
    descriptions: { en: "Molten rock" },
    aliases: { en: ["Magma"] },
    siteLinks: { enwiki: new EntitySiteLink("enwiki", "Lava", ["Q1"]) },
    statements: { P9: [claim(noValueSnak("P9"))] },
    summary: "tidy",
    bot: false,
    baseRevision: 11,
    clear: true,
  };
  const edited = await entities.edit("Q7", everything);
  assert.equal(edited.lastRevision, 12);
  const sent = last("wbeditentity");
  assert.equal(sent.id, "Q7");
  assert.equal(sent.summary, "tidy");
  assert.equal(sent.baserevid, "11");
  assert.equal(sent.clear, "1");
  assert.equal(sent.bot, undefined);
  const data: JsonValue = JSON.parse(sent.data ?? "");
  assert.deepEqual(data, {
    labels: { en: { language: "en", value: "Lava" } },
    descriptions: { en: { language: "en", value: "Molten rock" } },
    aliases: { en: [{ language: "en", value: "Magma" }] },
    sitelinks: { enwiki: { site: "enwiki", title: "Lava", badges: ["Q1"] } },
    claims: { P9: [{ mainsnak: { snaktype: "novalue", property: "P9" }, type: "statement", rank: "normal" }] },
  });

  const newKind: NewEntityKind = "item";
  const created = await entities.create(newKind, { labels: { en: "Lava" } });
  assert.equal(created.id, "Q7");
  assert.equal(last("wbeditentity").new, "item");
  assert.equal(last("wbeditentity").bot, "1");

  const emptied = await entities.edit("Q7", { labels: null, descriptions: null, aliases: null, siteLinks: null, statements: null, summary: null, bot: null, baseRevision: null, clear: null });
  assert.equal(emptied.id, "Q7");

  const set = await entities.setStatement("Q7", claim(noValueSnak("P9")), "add", 11);
  assert.ok(set instanceof Claim);
  assert.equal(set.id, "Q7$9");
  assert.equal(last("wbsetclaim").summary, "add");
  assert.equal(last("wbsetclaim").baserevid, "11");

  assert.equal(await entities.removeStatements(["Q7$9", "Q7$10"], "gone", 12), undefined);
  assert.equal(last("wbremoveclaims").claim, "Q7$9|Q7$10");

  const conflicts: MergeConflict[] = ["description", "sitelink"];
  await entities.mergeItems("Q1", "Q2", conflicts, "dupe");
  assert.equal(last("wbmergeitems").ignoreconflicts, "description|sitelink");
  await entities.mergeItems("Q1", "Q2");
  await entities.mergeLexemes("L1", "L2", "same word");
  assert.equal(last("wblmergelexemes").source, "L1");
  await entities.redirect("Q3", "Q2");
  assert.equal(last("wbcreateredirect").to, "Q2");

  const parsed = await entities.parseValues("quantity", ["1952"]);
  assert.equal(assertArray(parsed).length, 1);
  assert.equal(parsed[0]?.kind, "quantity");
  assert.equal(parsed[0]?.amount, "+1952");
}

function misuses(entities: EntityService, statement: Claim): void {
  // @ts-expect-error a search kind must be an entity kind
  void entities.search("x", "en", "word");
  // @ts-expect-error a media file cannot be searched for
  void entities.search("x", "en", "mediainfo");
  // @ts-expect-error a new entity cannot be of an unknown kind
  void entities.create("unknown", {});
  // @ts-expect-error a typo in a merge conflict
  void entities.mergeItems("Q1", "Q2", ["sitelinks"]);
  // @ts-expect-error a rank nobody has heard of
  claim(noValueSnak("P1"), { rank: "best" });
  // @ts-expect-error an option with a typo in its name
  claim(noValueSnak("P1"), { ranks: "normal" });
  // @ts-expect-error a claim's value kind is a closed set
  const kind: "time" | "typo" = statement.dataValue?.kind ?? "typo";
  void kind;
}
void misuses;

theNamedNumbers();
everyKindOfValue();
buildAStatement();
readAndWriteBack();
readAnItem(decodeEntity(adams));
otherEntityKinds();

const saved: JsonValue = { entity: { type: "item", id: "Q7", lastrevid: 12, labels: { en: { language: "en", value: "Lava" } } }, success: 1 };
const site = new FakeSite({
  wbgetentities: (params) => {
    if (params.titles != null) return { entities: { Q42: adams } };
    const ids = (params.ids ?? "").split("|");
    const found: Record<string, JsonValue> = {};
    for (const id of ids) found[id] = id === "Q42" ? adams : { id, missing: "" };
    return { entities: found };
  },
  wbsearchentities: { search: [{ id: "P31", label: "instance of", description: "that class" }, { id: "P32" }] },
  wbeditentity: saved,
  wbsetclaim: { claim: { mainsnak: { snaktype: "novalue", property: "P9" }, type: "statement", id: "Q7$9", rank: "normal" }, success: 1 },
  wbremoveclaims: { claims: ["Q7$9"], success: 1 },
  wbmergeitems: { success: 1 },
  wblmergelexemes: { success: 1 },
  wbcreateredirect: { success: 1 },
  wbparsevalue: { results: [{ raw: "1952", type: "quantity", value: { amount: "+1952", unit: "1" } }] },
});
await withWiki(site, (wiki) => aRepositoryBot(wiki.entities, site), new BotPassword("Bot", "task", "secret"));

console.log("wikibase: entities, statements, snaks, values and the repository service keep what their types say");
