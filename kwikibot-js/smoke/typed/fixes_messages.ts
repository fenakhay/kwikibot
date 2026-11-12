import assert from "node:assert/strict";
import { type MessageValue, parse, parseTitle, Wikitext } from "kwikibot";
import { BotPolicy, EditPermission } from "kwikibot/bot";
import {
  allFixes,
  COSMETIC_EMPTY_SECTIONS,
  COSMETIC_EXTRA_BLANK_LINES,
  COSMETIC_HTML_EMPHASIS,
  COSMETIC_HTML_ENTITIES,
  COSMETIC_REDUNDANT_LINK_TEXT,
  COSMETIC_SAFE,
  COSMETIC_SELF_CLOSING_BR,
  COSMETIC_TRAILING_WHITESPACE,
  CosmeticChanges,
  cosmeticChanges,
  CosmeticPass,
  cosmeticPass,
  cosmeticReformatIsbns,
  createFix,
  createMessages,
  defaultParseOptions,
  DIFF_DEFAULT_CONTEXT,
  Fix,
  FIX_DATE_RANGES,
  FIX_ELLIPSIS,
  FIX_EXTRA_SPACES,
  fixesNamed,
  fixNamed,
  Isbn,
  isValidIsbn,
  messageFallbacks,
  Messages,
  MESSAGES_DEFAULT_LANGUAGE,
  messagesFromJson,
  parseIsbn,
  registerFix,
  type Replacement,
  TEXT_SCOPE_EVERYWHERE,
  TEXT_SCOPE_PROSE,
  unifiedDiff,
  Unlinker,
} from "kwikibot/wikitext";
import { assertArray, assertNone, assertRecord } from "./support.js";

function aDiffShowsWhatChanges(): void {
  assert.equal(DIFF_DEFAULT_CONTEXT, 3);
  assert.equal(unifiedDiff("a\nb\n", "a\nc\n", "Page"), "--- old/Page\n+++ new/Page\n@@ -1,3 +1,3 @@\n a\n-b\n+c\n ");
  assert.equal(unifiedDiff("same", "same", "Page"), "");
  assert.match(unifiedDiff("a\nb\nc", "a\nx\nc", "Page", 0), /@@ -2,1 \+2,1 @@/);
}

function aFixReplacesWithinItsScope(): void {
  const spelling: Replacement = { pattern: /volcanoe/, replacement: "volcano" };
  const fix: Fix = createFix("volcano", "Spells it", [spelling]);
  assert.ok(fix instanceof Fix);
  assert.equal(fix.name, "volcano");
  assert.equal(fix.description, "Spells it");

  const kept = assertArray(fix.replacements);
  assert.equal(kept.length, 1);
  const first = kept[0];
  assert.ok(first != null);
  assert.equal(first.replacement, "volcano");
  assert.ok(first.pattern instanceof RegExp);
  assert.match(first.pattern.source, /volcanoe/);
  assert.equal(fix.scope.templates, TEXT_SCOPE_PROSE.templates);
  assert.equal(typeof fix.scope.linkTargets, "boolean");

  assert.equal(fix.apply("a volcanoe {{volcanoe}}"), "a volcano {{volcanoe}}");
  assert.equal(fix.apply("a volcanoe", defaultParseOptions()), "a volcano");
  const applied: Wikitext = fix.applyParsed(parse("a volcanoe"));
  assert.ok(applied instanceof Wikitext);
  assert.equal(applied.toString(), "a volcano");
  assert.equal(fix.wouldChange("a volcanoe"), true);
  assert.equal(fix.wouldChange("a volcano", defaultParseOptions()), false);

  const everywhere = createFix("all", "All", [spelling], TEXT_SCOPE_EVERYWHERE);
  assert.equal(everywhere.apply("{{volcanoe}}"), "{{volcano}}");
  assert.equal(everywhere.scope.templates, true);

  const anyCase = createFix("case", "Any case", [{ pattern: /VOLCANOE/gi, replacement: "volcano" }]);
  assert.equal(anyCase.apply("a volcanoe and a Volcanoe"), "a volcano and a volcano");
  assert.throws(() => createFix("dotall", "Refused", [{ pattern: /a/s, replacement: "b" }]));
}

function theFixesThatShipAreNamed(): void {
  assert.equal(FIX_EXTRA_SPACES.apply("a   b"), "a b");
  assert.equal(FIX_DATE_RANGES.apply("1990-1995"), "1990–1995");
  assert.equal(FIX_ELLIPSIS.apply("wait..."), "wait…");

  assert.equal(fixNamed("ellipsis")?.name, "ellipsis");
  assertNone(fixNamed("nothing"));
  assert.deepEqual(
    assertArray(fixesNamed(["ellipsis", "date-ranges"])).map((fix) => fix.name),
    ["ellipsis", "date-ranges"],
  );
  assert.throws(() => fixesNamed(["nothing"]), /available/);

  registerFix(createFix("mine", "Mine", [{ pattern: /a/, replacement: "b" }]));
  assert.equal(fixNamed("mine")?.description, "Mine");
  const all = assertRecord(allFixes());
  assert.ok(all.mine instanceof Fix);
  assert.equal(all.mine.name, "mine");
  assert.ok(all.ellipsis instanceof Fix);
  assertNone(all.nothing);
}

function messagesFillPlaceholdersAndCount(): void {
  const bundles: Record<string, Record<string, string>> = {
    en: { added: "{{PLURAL:$1|$1 term|$1 terms}} added" },
    fr: { added: "{{PLURAL:$1|$1 terme|$1 termes}} ajoutés" },
    ar: { only: "$1 $2" },
  };
  const messages: Messages = createMessages(bundles);
  assert.ok(messages instanceof Messages);

  const three: MessageValue[] = [3];
  assert.equal(messages.get("added", "fr", three), "3 termes ajoutés");
  assert.equal(messages.get("added", "en", [1]), "1 term added");
  assert.equal(messages.get("added", "de", [2]), "2 terms added");
  assert.equal(messages.get("only", "arz", ["a", "b"]), "a b");
  assert.equal(messages.get("unknown", "en"), "unknown");
  assert.equal(messages.has("added", "de"), true);
  assert.equal(messages.has("unknown", "de"), false);
  assert.deepEqual(new Set(assertArray(messages.languages)), new Set(["en", "fr", "ar"]));
  assert.deepEqual(assertArray(messages.chain("arz")), ["arz", "ar", "en"]);

  assert.equal(MESSAGES_DEFAULT_LANGUAGE, "en");
  const fallbacks = assertRecord(messageFallbacks());
  const arz = fallbacks.arz;
  assert.ok(arz !== undefined);
  assert.deepEqual(assertArray(arz), ["ar"]);

  const told = createMessages(bundles, { de: ["fr"] });
  assert.deepEqual(told.chain("de"), ["de", "fr", "en"]);
  assert.equal(told.get("added", "de", [2]), "2 termes ajoutés");

  const fromJson = messagesFromJson({ en: '{"@metadata":{"x":"y"},"hi":"Hello $1"}' });
  assert.equal(fromJson.get("hi", "en", ["you"]), "Hello you");
  assert.equal(fromJson.has("@metadata", "en"), false);
}

function linksToAPageComeOut(): void {
  const unlinker = new Unlinker();
  const target = parseTitle("Volcano");

  assert.equal(unlinker.unlink("[[volcano|volcanoes]] and [[Volcano]] and [[Etna]]", target), "volcanoes and Volcano and [[Etna]]");
  const unlinked = unlinker.unlinkParsed(parse("[[Volcano]]"), target);
  assert.ok(unlinked instanceof Wikitext);
  assert.equal(unlinked.toString(), "Volcano");
  assert.equal(unlinker.linksTo("see [[volcano#English]]", target), true);
  assert.equal(unlinker.linksToParsed(parse("see [[Etna]]"), target), false);

  const wiktionary = new Unlinker(false);
  assert.equal(wiktionary.linksTo("[[volcano]]", parseTitle("Volcano")), false);

  assert.throws(() => unlinker.unlink("x", parseTitle("[bad")));
}

function anExclusionDeniesABotWithTheReason(): void {
  const policy = new BotPolicy("FenaBot");

  const allowed: EditPermission = policy.check("no template");
  assert.ok(allowed instanceof EditPermission);
  assert.equal(allowed.isAllowed, true);
  assertNone(allowed.reason);

  const denied = policy.check("{{nobots}}");
  assert.equal(denied.isAllowed, false);
  assert.equal(denied.reason, "{{nobots}}");

  assert.equal(policy.check("{{bots|allow=OtherBot}}").isAllowed, false);
  assert.equal(policy.checkParsed(parse("{{bots|allow=FenaBot}}")).isAllowed, true);
  assert.equal(new BotPolicy("FenaBot", "nosummary").check("{{bots|optout=nosummary}}").isAllowed, false);
  assert.equal(new BotPolicy("FenaBot", null).check("{{bots|optout=nosummary}}").isAllowed, true);
  assert.equal(policy.check("{{bots|optout=nosummary}}").isAllowed, true);
}

function cosmeticPassesAreChosenAndCombined(): void {
  const tidied = (pass: CosmeticPass, text: string): string => pass.apply(parse(text)).toString();

  assert.ok(COSMETIC_TRAILING_WHITESPACE instanceof CosmeticPass);
  assert.ok(COSMETIC_TRAILING_WHITESPACE.apply(parse("a")) instanceof Wikitext);
  assert.equal(tidied(COSMETIC_TRAILING_WHITESPACE, "a  \nb"), "a\nb");
  assert.equal(tidied(COSMETIC_EXTRA_BLANK_LINES, "a\n\n\n\n\nb"), "a\n\n\nb");
  assert.equal(tidied(COSMETIC_HTML_EMPHASIS, "<b>a</b>"), "'''a'''");
  assert.equal(tidied(COSMETIC_HTML_ENTITIES, "&ndash;"), "–");
  assert.equal(tidied(COSMETIC_REDUNDANT_LINK_TEXT, "[[a|a]]"), "[[a]]");
  assert.equal(tidied(COSMETIC_SELF_CLOSING_BR, "<br>"), "<br />");
  assert.ok(!tidied(COSMETIC_EMPTY_SECTIONS, "a\n== b ==\n").includes("b"));
  assert.equal(tidied(cosmeticReformatIsbns(true), "ISBN 0-19-853737-9"), "ISBN 978-0-19-853737-3");
  assert.equal(tidied(cosmeticReformatIsbns(), "ISBN 0-19-853737-9"), "ISBN 0-19-853737-9");

  assert.ok(COSMETIC_SAFE instanceof CosmeticChanges);
  assert.equal(COSMETIC_SAFE.apply(parse("[[a|a]]  \nb")).toString(), "[[a]]\nb");
  assert.equal(COSMETIC_SAFE.applyText("[[a|a]]  \nb"), "[[a]]\nb");
  assert.equal(COSMETIC_SAFE.applyText("[[a|a]]", defaultParseOptions()), "[[a]]");
  assert.equal(COSMETIC_SAFE.wouldChange("[[a|a]]"), true);
  assert.equal(COSMETIC_SAFE.wouldChange("x", defaultParseOptions()), false);

  const exclaim = cosmeticPass((code) => code.plus(parse("!")));
  const both = cosmeticChanges([COSMETIC_REDUNDANT_LINK_TEXT, exclaim]);
  assert.equal(both.apply(parse("[[a|a]]")).toString(), "[[a]]!");
  assert.equal(cosmeticChanges([]).applyText("[[a|a]]"), "[[a|a]]");
}

function anIsbnIsCheckedAndConverted(): void {
  const isbn = parseIsbn("0-19-853737-9");
  assert.ok(isbn instanceof Isbn);
  assert.equal(isbn.normalised, "0198537379");
  assert.equal(isbn.isIsbn13, false);
  assert.equal(isbn.toString(), "0198537379");
  const longer = isbn.toIsbn13();
  assert.ok(longer instanceof Isbn);
  assert.equal(longer.normalised, "9780198537373");
  assert.equal(longer.isIsbn13, true);
  assertNone(parseIsbn("0-19-853737-0"));
  assert.equal(isValidIsbn("9780198537373"), true);
  assert.equal(isValidIsbn("nope"), false);
}

function misuseDoesNotCompile(): void {
  // @ts-expect-error a replacement's pattern is a RegExp, not a string
  const stringly: Replacement = { pattern: "volcanoe", replacement: "volcano" };
  // @ts-expect-error a message value is a string or a number
  const flag: MessageValue = true;
  // @ts-expect-error scopes are options here, not the name of one
  const named = createFix("x", "y", [], "prose");
  // @ts-expect-error a target is a parsed title, not its text
  const linked = new Unlinker().linksTo("[[a]]", "a");
  void [stringly, flag, named, linked];
}
void misuseDoesNotCompile;

aDiffShowsWhatChanges();
aFixReplacesWithinItsScope();
theFixesThatShipAreNamed();
messagesFillPlaceholdersAndCount();
linksToAPageComeOut();
anExclusionDeniesABotWithTheReason();
cosmeticPassesAreChosenAndCombined();
anIsbnIsCheckedAndConverted();

console.log("fixes_messages: fixes, diffs, messages, unlinking, exclusion and cosmetic passes behave as typed");
