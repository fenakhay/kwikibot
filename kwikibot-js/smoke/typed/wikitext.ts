import assert from "node:assert/strict";
import { parse, Template, Wikitext } from "kwikibot";
import {
  Argument,
  Attribute,
  Comment,
  coreFunctionIds,
  createArgument,
  createAttribute,
  createComment,
  createExternalLink,
  createHeading,
  createHtmlEntity,
  createLink,
  createMagicWords,
  createParameter,
  createParseOptions,
  createSection,
  createTag,
  createTemplate,
  createText,
  createTitleRules,
  defaultParseOptions,
  defaultTitleRules,
  emptyWikitext,
  ExternalLink,
  Heading,
  HtmlEntity,
  Link,
  literalText,
  MagicWords,
  Node,
  type NodeKind,
  Parameter,
  parseExact,
  ParseOptions,
  type ParseOptionsInit,
  roundTrips,
  Section,
  SourceRange,
  SourceRanges,
  Tag,
  type TagOptions,
  TEXT_SCOPE_EVERYWHERE,
  TEXT_SCOPE_PROSE,
  TextMatch,
  type TextScopeOptions,
  TitleRules,
  type TitleRulesInit,
  wikitextOf,
} from "kwikibot/wikitext";
import { assertArray, assertNone, assertRecord } from "./support.js";

const ENTRY = [
  "==English==",
  "{{en-noun|es}}",
  "A [[mountain|hill]] that erupts.",
  "",
  "===Derived terms===",
  "{{col|en|supervolcano}}",
  "",
  "[[Category:Landforms]]",
].join("\n");

const PAGE = [
  'lead <!-- note --> &amp; [[Lava|hot]] [https://example.org label]',
  "",
  "== One ==",
  "{{Volcano|active=yes|lava}} {{{arg|fallback}}}",
  '<ref name="a">source</ref>',
  "=== Two ===",
  "text",
  "[[Category:Rocks|key]]",
  "[[fr:Lave]]",
  "[[de:Lava]]",
].join("\n");

function only<T>(items: T[]): T {
  assertArray(items);
  assert.equal(items.length, 1);
  const item = items[0];
  assert.ok(item !== undefined);
  return item;
}

function firstOf<T>(nodes: Node[], read: (node: Node) => T | null | undefined): T {
  for (const node of nodes) {
    const found = read(node);
    if (found != null) return found;
  }
  assert.fail("no node of that kind");
}

function everyKindOfNode(code: Wikitext): void {
  assert.ok(code instanceof Wikitext);
  const walked = assertArray(code.allNodes());
  const kinds: NodeKind[] = walked.map((node) => node.kind);
  assert.deepEqual(new Set(kinds), new Set<NodeKind>(["text", "comment", "template", "argument", "wiki-link", "external-link", "heading", "html-entity", "tag"]));
  assert.equal(assertArray(code.nodes).map((node) => node.toString()).join(""), PAGE);
  for (const node of walked) assert.ok(node instanceof Node);

  const first = code.nodes[0];
  assert.ok(first != null);
  assert.equal(first.kind, "text");
  assert.equal(first.text, "lead ");
  assertNone(first.comment);
  assertNone(first.template);
  assertNone(first.argument);
  assertNone(first.link);
  assertNone(first.externalLink);
  assertNone(first.heading);
  assertNone(first.htmlEntity);
  assertNone(first.tag);

  const comment = only(code.comments());
  assert.ok(comment instanceof Comment);
  assert.equal(comment.contents, " note ");
  assert.equal(comment.closed, true);
  assert.equal(comment.toString(), "<!-- note -->");
  assert.equal(comment.node.comment?.contents, " note ");
  assertNone(comment.node.text);

  const argument = firstOf(walked, (node) => node.argument);
  assert.ok(argument instanceof Argument);
  assert.equal(argument.name.toString(), "arg");
  assert.equal(argument.defaultValue?.toString(), "fallback");
  assert.equal(argument.node.kind, "argument");
  assert.equal(argument.toString(), "{{{arg|fallback}}}");

  const external = firstOf(walked, (node) => node.externalLink);
  assert.ok(external instanceof ExternalLink);
  assert.equal(external.url.toString(), "https://example.org");
  assert.equal(external.title?.toString(), "label");
  assert.equal(external.brackets, true);
  assert.equal(external.separator, " ");
  assert.equal(external.node.kind, "external-link");
  assert.equal(external.toString(), "[https://example.org label]");

  const entity = firstOf(walked, (node) => node.htmlEntity);
  assert.ok(entity instanceof HtmlEntity);
  assert.equal(entity.value, "amp");
  assert.equal(entity.numeric, false);
  assertNone(entity.hexChar);
  assert.equal(entity.node.kind, "html-entity");
  assert.equal(entity.toString(), "&amp;");
  const numeric = firstOf(parse("&#x41;").nodes, (node) => node.htmlEntity);
  assert.equal(numeric.value, "41");
  assert.equal(numeric.numeric, true);
  assert.equal(numeric.hexChar, "x");

  const heading = code.headings()[0];
  assert.ok(heading instanceof Heading);
  assert.equal(heading.title.toString(), " One ");
  assert.equal(heading.level, 2);
  assert.equal(heading.node.heading?.level, 2);
  assert.equal(heading.toString(), "== One ==");

  const link = firstOf(walked, (node) => node.link);
  assert.ok(link instanceof Link);
  assert.equal(link.target, "Lava");
  assert.equal(link.text, "hot");
  assert.equal(link.targetMarkup.toString(), "Lava");
  assert.equal(link.textMarkup?.toString(), "hot");
  assert.equal(link.node.kind, "wiki-link");
  assert.equal(link.toString(), "[[Lava|hot]]");
  const plainLink = only(parse("[[ash]]").links());
  assertNone(plainLink.text);
  assertNone(plainLink.textMarkup);
  assert.equal(firstOf(walked, (node) => node.template).name, "Volcano");

  const bare = firstOf(parse("see https://x.org now").nodes, (node) => node.externalLink);
  assert.equal(bare.brackets, false);
  assertNone(bare.title);
  assert.equal(bare.separator, " ");
  assertNone(firstOf(parse("{{{only}}}").nodes, (node) => node.argument).defaultValue);
  assert.equal(only(parse("<!-- open").comments()).closed, false);
}

function tagsAndAttributes(code: Wikitext): void {
  const ref = only(code.tags("ref"));
  assert.ok(ref instanceof Tag);
  assert.equal(ref.name, "ref");
  assert.equal(ref.contents?.toString(), "source");
  assert.equal(ref.selfClosing, false);
  assert.equal(ref.implicitClose, false);
  assert.equal(ref.verbatim, false);
  assertNone(ref.wikiMarkup);
  assert.equal(ref.padding, "");
  assertNone(ref.closing);
  assert.equal(ref.node.tag?.name, "ref");
  assert.equal(ref.toString(), '<ref name="a">source</ref>');
  assert.ok(code.tags().length > 0);
  assert.deepEqual(assertArray(code.tags("nothing")), []);

  const attribute = only(ref.attributes);
  assert.ok(attribute instanceof Attribute);
  assert.equal(attribute.name.toString(), "name");
  assert.equal(attribute.value?.toString(), "a");
  assert.equal(attribute.quote, '"');
  assert.equal(attribute.padFirst, " ");
  assert.equal(attribute.padBeforeEq, "");
  assert.equal(attribute.padAfterEq, "");
  assert.equal(attribute.toString(), ' name="a"');

  const flag = only(only(parse("<td nowrap>x</td>").tags("td")).attributes);
  assertNone(flag.value);
  assertNone(flag.quote);

  const breakLine = only(parse("<br />").tags("br"));
  assert.equal(breakLine.selfClosing, true);
  assert.equal(breakLine.padding, " ");
  assertNone(breakLine.contents);
  assert.deepEqual(assertArray(breakLine.attributes), []);

  const bold = only(parse("'''b'''").tags("b"));
  assert.equal(bold.wikiMarkup, "'''");
  assert.equal(bold.contents?.toString(), "b");

  const shouty = only(parse("<nowiki>x</NOWIKI>").tags("nowiki"));
  assert.equal(shouty.verbatim, true);
  assert.equal(shouty.closing, "</NOWIKI>");

  const item = only(parse("<ul><li>x\n</ul>").tags("li"));
  assert.equal(item.implicitClose, true);
}

function buildNodes(): void {
  const name = literalText("Lava");
  const plain = createText("plain");
  assert.ok(plain instanceof Node);
  assert.equal(plain.text, "plain");
  assert.equal(createComment("c", false).toString(), "<!--c");
  assert.equal(createComment("c").toString(), "<!--c-->");
  assert.equal(createArgument(name, literalText("d")).toString(), "{{{Lava|d}}}");
  assertNone(createArgument(name).defaultValue);
  assertNone(createArgument(name, null).defaultValue);
  assert.equal(createLink(name, literalText("t")).toString(), "[[Lava|t]]");
  assertNone(createLink(name).text);
  assert.equal(createExternalLink(literalText("https://x.org"), literalText("x")).toString(), "[https://x.org x]");
  assert.equal(createExternalLink(literalText("https://x.org"), null, false).toString(), "https://x.org");
  assert.equal(createExternalLink(literalText("https://x.org"), literalText("x"), true, "  ").separator, "  ");
  assert.equal(createHeading(literalText(" H "), 3).toString(), "=== H ===");
  assert.equal(createHtmlEntity("41", true, "x").toString(), "&#x41;");
  assert.equal(createHtmlEntity("amp").toString(), "&amp;");

  const attribute = createAttribute(literalText("id"), literalText("a"), "'", "  ", " ", " ");
  assert.equal(attribute.toString(), "  id = 'a'");
  assert.equal(createAttribute(literalText("nowrap")).toString(), " nowrap");

  const breakLine: TagOptions = { selfClosing: true, padding: " " };
  assert.equal(createTag("br", breakLine).toString(), "<br />");
  const span: TagOptions = { contents: literalText("x"), attributes: [attribute] };
  assert.equal(createTag("span", span).toString(), "<span  id = 'a'>x</span>");
  assert.equal(createTag("x").name, "x");
  assert.equal(createTag("x", null).name, "x");
  const bold: TagOptions = { wikiMarkup: "'''", contents: literalText("b") };
  assert.equal(createTag("b", bold).toString(), "'''b'''");
  const raw: TagOptions = { contents: literalText("x"), verbatim: true, closing: "</NOWIKI>", implicitClose: false };
  assert.equal(createTag("nowiki", raw).toString(), "<nowiki>x</NOWIKI>");

  const parameter = createParameter(literalText("a"), literalText("b"), true);
  assert.ok(parameter instanceof Parameter);
  const template = createTemplate(name, [parameter]);
  assert.ok(template instanceof Template);
  assert.equal(template.toString(), "{{Lava|a=b}}");
  assert.equal(createTemplate(name).withParameter("1", "x").toString(), "{{Lava|x}}");
  assert.equal(wikitextOf([template.node, createText(" tail")]).toString(), "{{Lava|a=b}} tail");
  assert.equal(emptyWikitext().toString(), "");
  assert.deepEqual(emptyWikitext().nodes, []);
  assert.equal(literalText("{{not}}").templates().length, 0);
}

function readATemplate(): void {
  const code = parse(ENTRY);
  const noun = only(code.templates("en-noun"));
  assert.equal(noun.name, "en-noun");
  assert.equal(noun.key, "En-noun");
  assert.equal(noun.get("1"), "es");
  assert.equal(noun.getRaw("1"), "es");
  assert.equal(noun.has("1"), true);
  assert.equal(noun.has("2"), false);
  assertNone(noun.get("2"));
  assertNone(noun.getRaw("2"));
  assert.equal(noun.toString(), "{{en-noun|es}}");
  assert.deepEqual(code.templates().map((template) => template.name), ["en-noun", "col"]);
  assert.deepEqual(code.templates(null).length, 2);

  const positional = only(assertArray(noun.parameters));
  assert.equal(positional.name, "1");
  assert.equal(positional.named, false);
  assertNone(positional.rawName);
  assert.equal(positional.value, "es");

  const rules: TitleRules = createTitleRules({ caseSensitive: [10] });
  const template = only(parse("{{ <!-- c -->volcano| a = b |x}}").templates());
  assert.equal(template.rawName, " <!-- c -->volcano");
  assert.equal(template.nameMarkup.comments().length, 1);
  assert.equal(template.key, "Volcano");
  assert.equal(template.keyIn(rules), "volcano");
  assert.equal(template.isParserFunction, false);
  assertNone(template.functionName);
  assertNone(template.functionArgument);
  assertNone(template.parameter("nope"));
  assert.equal(template.get("a"), "b");
  assert.equal(template.getRaw("a"), " b ");
  const named = template.parameter("a");
  assert.ok(named instanceof Parameter);
  assert.equal(named.named, true);
  assert.equal(named.rawName, " a ");
  assert.equal(named.rawValue, " b ");
  assert.equal(named.nameMarkup.toString(), " a ");
  assert.equal(named.valueMarkup.toString(), " b ");
  assert.equal(named.toString(), " a = b ");
  assert.equal(template.node.template?.name, "volcano");

  const dynamic = only(parse("{{ {{{a}}} |b}}").templates());
  assertNone(dynamic.key);
  assertNone(dynamic.keyIn(rules));

  const invoke = only(parse("{{#invoke:sa-decl|show}}").templates());
  assert.equal(invoke.isParserFunction, true);
  assert.equal(invoke.isParserFunctionIn(rules), true);
  assert.equal(invoke.functionName, "#invoke");
  assert.equal(invoke.functionArgument, "sa-decl");
  assert.equal(invoke.functionNameIn(rules), "#invoke");
  assert.equal(invoke.functionArgumentIn(rules), "sa-decl");
  assertNone(template.functionNameIn(rules));
  assertNone(template.functionArgumentIn(rules));
}

function rewriteTemplates(): void {
  const code = parse(ENTRY);
  const updated = code.mapTemplates("col", (template) => template.withParameter("3", "hypervolcano"));
  assert.equal(updated.toString(), ENTRY.replace("{{col|en|supervolcano}}", "{{col|en|supervolcano|hypervolcano}}"));
  assert.equal(code.toString(), ENTRY);

  const noun = only(code.templates("en-noun"));
  assert.equal(code.replaceTemplate(noun, noun.withoutParameter("1")).toString(), ENTRY.replace("{{en-noun|es}}", "{{en-noun}}"));

  const source = parse("{{l}} {{Template:L|x}} {{other}}");
  assert.equal(source.templatesNamed(["l"]).length, 2);
  assert.equal(source.templatesNamed(["l"], createTitleRules({ caseSensitive: [10] })).length, 1);
  assert.equal(source.mapTemplatesNamed(["l"], (template) => template.withParameter("k", "v")).toString(), "{{l|k=v}} {{Template:L|x|k=v}} {{other}}");
  assert.equal(source.mapTemplatesNamed(["l"], (template) => template.withParameter("k", "v"), createTitleRules({ caseSensitive: [10] })).toString(), "{{l|k=v}} {{Template:L|x}} {{other}}");
  assert.ok(source.mapAllTemplates((template) => template.withParameter("k", "v")).templates().every((template) => template.has("k")));
}

function replaceAndWalkNodes(): void {
  const source = parse("a {{t}} b");
  const template = only(source.templates());
  assert.equal(source.replace(template.node, createText("c")).toString(), "a c b");
  assert.equal(source.remove(template.node).toString(), "a  b");
  assert.equal(source.mapNodes((node) => (node.kind === "text" ? createText(node.toString().toUpperCase()) : node)).toString(), "A {{T}} B");
  assert.equal(parse("a").plus(parse("b")).toString(), "ab");
  assert.equal(parse("a {{b}}").equals(parse("a {{b}}")), true);
  assert.equal(parse("a").equals(parse("b")), false);
  assert.equal(parse("a").equals(null), false);
  assert.equal(parse("a").hashCode(), parse("a").hashCode());
  assert.equal(typeof parse("a").hashCode(), "number");
  assert.equal(`${parse("x {{y}}")}`, "x {{y}}");
}

function sourceRanges(): void {
  const text = 'a {{t|k=v}} <b x="1">c</b>';
  const source = parse(text);
  const ranges = source.ranges();
  assert.ok(ranges instanceof SourceRanges);
  const cut = (range: SourceRange | null | undefined): string | undefined => {
    if (range == null) return undefined;
    assert.ok(range instanceof SourceRange);
    assert.equal(typeof range.start, "number");
    assert.equal(typeof range.end, "number");
    return text.substring(range.start, range.end);
  };

  const template = only(source.templates());
  assert.equal(cut(ranges.ofNode(template.node)), "{{t|k=v}}");
  const parameter = only(template.parameters);
  assert.equal(cut(ranges.ofParameter(parameter)), "k=v");
  assert.equal(cut(ranges.ofWikitext(parameter.valueMarkup)), "v");
  const tag = only(source.tags("b"));
  assert.equal(cut(ranges.ofAttribute(only(tag.attributes))), ' x="1"');

  assertNone(ranges.ofNode(createText("elsewhere")));
  assertNone(ranges.ofParameter(createParameter(literalText("a"), literalText("b"), true)));
  assertNone(ranges.ofAttribute(createAttribute(literalText("a"))));
  assertNone(ranges.ofWikitext(literalText("elsewhere")));
}

function textInAScope(): void {
  const source = parse("lava {{t|lava}} <!-- lava --> [[lava]]\n== lava ==");
  assert.equal(source.containsText(/rock/), false);
  assert.equal(source.containsText(/lava/), true);
  assert.equal(source.containsText(/lava/, null), true);
  assert.equal(parse("{{t|lava}}").containsText(/lava/, TEXT_SCOPE_PROSE), false);
  assert.equal(parse("{{t|lava}}").containsText(/lava/, TEXT_SCOPE_EVERYWHERE), true);

  const again = /lava/g;
  assert.equal(source.containsText(again), true);
  assert.equal(source.containsText(again), true);

  const noHeadings: TextScopeOptions = { headings: false };
  assert.ok(assertArray(source.textIn(noHeadings)).some((text) => text.includes("lava")));
  assert.equal(source.textIn().length, source.textIn(TEXT_SCOPE_PROSE).length);
  assert.ok(source.textIn(TEXT_SCOPE_EVERYWHERE).length > source.textIn(null).length);

  assert.equal(source.replaceTextIn(/lava/, "x", { comments: true, headings: false }).toString(), "x {{t|lava}} <!-- x --> [[lava]]\n== lava ==");
  assert.equal(source.replaceTextIn(/lava/, "x", TEXT_SCOPE_EVERYWHERE).toString(), "x {{t|x}} <!-- x --> [[x]]\n== x ==");
  assert.equal(source.replaceTextIn(/lava/, "x", { templates: true }).toString(), "x {{t|x}} <!-- lava --> [[lava]]\n== x ==");
  assert.equal(source.replaceTextIn(/lava/, "x", { linkTargets: true }).toString(), "x {{t|lava}} <!-- lava --> [[x]]\n== x ==");
  const tagged = parse('<span title="lava">lava</span> <nowiki>lava</nowiki>');
  assert.equal(tagged.replaceTextIn(/lava/, "x", { tagAttributes: true }).toString(), '<span title="x">x</span> <nowiki>lava</nowiki>');
  assert.equal(tagged.replaceTextIn(/lava/, "x", { rawTags: true }).toString(), '<span title="lava">x</span> <nowiki>x</nowiki>');
  assert.equal(TEXT_SCOPE_EVERYWHERE.templates, true);
  assert.equal(TEXT_SCOPE_EVERYWHERE.linkTargets, true);
  assert.equal(TEXT_SCOPE_EVERYWHERE.comments, true);
  assert.equal(TEXT_SCOPE_EVERYWHERE.rawTags, true);
  assert.equal(TEXT_SCOPE_EVERYWHERE.headings, true);
  assert.equal(TEXT_SCOPE_EVERYWHERE.tagAttributes, true);
  assert.equal(TEXT_SCOPE_PROSE.templates, false);
  assert.equal(TEXT_SCOPE_PROSE.linkTargets, false);
  assert.equal(TEXT_SCOPE_PROSE.comments, false);
  assert.equal(TEXT_SCOPE_PROSE.rawTags, false);
  assert.equal(TEXT_SCOPE_PROSE.headings, true);
  assert.equal(TEXT_SCOPE_PROSE.tagAttributes, false);

  const volcano = parse("[[volcano]] volcano {{volcano}}");
  assert.equal(volcano.replaceText(/volcano/, "mountain").toString(), "[[volcano]] mountain {{volcano}}");
  assert.equal(volcano.replaceText(/volcano/, "mountain", "prose").toString(), "[[volcano]] mountain {{volcano}}");
  assert.equal(volcano.replaceText(/volcano/, "mountain", "everywhere").toString(), "[[mountain]] mountain {{mountain}}");
  assert.equal(parse("Volcano volcano").replaceText(/volcano/i, "vent").toString(), "vent vent");
  assert.equal(parse("ash").replaceText(/(a)sh/, "$1").toString(), "$1");
  assert.throws(() => parse("a").replaceText(/a/s, "b"));

  const seen: TextMatch[] = [];
  const swapped = parse("a12").replaceTextWith(/(\d)(\d)/, (match) => {
    seen.push(match);
    return `${match.groups[2]}${match.groups[1]}`;
  });
  assert.equal(swapped.toString(), "a21");
  const match = only(seen);
  assert.ok(match instanceof TextMatch);
  assert.equal(match.value, "12");
  assert.equal(match.index, 1);
  assert.deepEqual(assertArray(match.groups), ["12", "1", "2"]);
  assert.equal(parse("t").replaceTextWith(/t/, () => "u", TEXT_SCOPE_EVERYWHERE).toString(), "u");

  const optional: string[][] = [];
  parse("b").replaceTextWith(/(a)|(b)/, (found) => {
    optional.push(found.groups);
    return found.value;
  });
  assert.deepEqual(only(optional).map((group) => typeof group), ["string", "string", "string"]);
}

function categoriesAndLanguageLinks(): void {
  const code = parse(ENTRY);
  assert.deepEqual(assertArray(code.categories()), ["Landforms"]);
  assert.deepEqual(code.addCategory("Volcanoes").categories(), ["Landforms", "Volcanoes"]);
  assert.equal(code.addCategory("Volcanoes", "Etna").toString(), `${ENTRY}\n[[Category:Volcanoes|Etna]]`);
  assert.deepEqual(code.removeCategory("Landforms").categories(), []);

  const source = parse("x\n[[Catégorie:A|key]]\n[[Category:B]]\n[[fr:Lave]]\n[[de:Lava]]");
  assert.equal(only(source.categoryLinks()).target, "Category:B");
  assert.equal(only(source.categoryLinks(["Catégorie"])).target, "Catégorie:A");
  assert.deepEqual(assertArray(source.categoryNamesIn(["Catégorie", "Category"])), ["A", "B"]);
  assert.deepEqual(assertArray(source.languageLinks(["fr", "de"])).map((link) => link.target), ["fr:Lave", "de:Lava"]);

  assert.deepEqual(source.addCategoryAs("C", "Catégorie", "s").categoryNamesIn(["Catégorie"]), ["A", "C"]);
  assert.ok(source.addCategoryAs("C", "Catégorie").toString().includes("[[Catégorie:C]]"));
  assert.equal(source.removeCategoryAs("A", "Catégorie").categoryNamesIn(["Catégorie"]).length, 0);
  assert.deepEqual(source.changeCategory("B", "D").categories(), ["D"]);
  assert.ok(source.changeCategory("A", "E", "Catégorie").toString().includes("[[Catégorie:E|key]]"));

  assert.deepEqual(source.sortLanguageLinks(["fr", "de"]).languageLinks(["fr", "de"]).map((link) => link.target), ["de:Lava", "fr:Lave"]);
  assert.deepEqual(source.sortLanguageLinks(["fr", "de"], (a, b) => b.localeCompare(a)).languageLinks(["fr", "de"]).map((link) => link.target), ["fr:Lave", "de:Lava"]);
}

function sections(): void {
  const entry = parse(ENTRY);
  const top = entry.outline();
  assert.ok(top instanceof Section);
  assertNone(top.title);
  assertNone(top.heading);
  assert.equal(top.level, 0);
  const english = top.find("English", 2);
  assert.ok(english != null);
  assert.equal(english.level, 2);
  assert.deepEqual(assertArray(english.subsections).map((section) => section.title), ["Derived terms"]);
  assert.deepEqual(english.content.templates().map((template) => template.name), ["en-noun"]);
  assertNone(top.find("English", 3));
  assertNone(top.find("Nothing"));
  assert.equal(english.toString(), ENTRY);

  const derived = top.find("Derived terms", null);
  assert.ok(derived != null);
  const content = parse(derived.content.toString().replace("supervolcano", "hypervolcano"));
  assert.equal(entry.replaceSection(derived, derived.withContent(content)).toString(), ENTRY.replace("supervolcano", "hypervolcano"));

  const outline = parse(PAGE).outline();
  const one = outline.find("One");
  assert.ok(one != null);
  assert.equal(one.heading?.level, 2);
  assert.ok(assertArray(one.nodes).length > 0);
  assert.deepEqual(assertArray(outline.all()).map((section) => section.title ?? null), [null, "One", "Two"]);
  assert.equal(only(outline.children("One")).title, "One");
  assert.deepEqual(assertArray(outline.children("Two")), []);

  const added = createSection(createHeading(literalText(" New "), 2), [createText("\nbody\n")]);
  assert.equal(outline.withSubsection(added).subsections.at(-1)?.title, "New");
  assert.equal(outline.withSubsectionAt(0, added).subsections[0]?.title, "New");
  assert.throws(() => outline.withSubsectionAt(9, added));
  const two = one.find("Two");
  assert.ok(two != null);
  const replaced = two.withContent(literalText("\nchanged\n"));
  assert.ok(outline.replace(two, replaced).toString().includes("changed"));

  const options = createParseOptions({ fileNamespaces: ["Fichier"] });
  assert.ok(parse(PAGE).replaceSectionWith(two, replaced, options).toString().includes("changed"));
  assert.equal(createSection(null, [], [added]).subsections.length, 1);
}

function plainText(): void {
  const code = parse("A [[mountain|hill]] and [[ash]].");
  assert.deepEqual(code.links().map((link) => link.target), ["mountain", "ash"]);
  assert.equal(code.text, "A hill and ash.");
  assert.equal(parse(ENTRY).toString(), ENTRY);
}

function parseOptionsAndRules(): void {
  assert.equal(parseExact("a {{b}}").toString(), "a {{b}}");
  assert.equal(parseExact("a", defaultParseOptions()).toString(), "a");
  assert.equal(parseExact("a", null).toString(), "a");
  assert.equal(roundTrips("a {{b}}"), true);
  assert.equal(roundTrips("a", defaultParseOptions()), true);

  const defaults = defaultParseOptions();
  assert.ok(defaults instanceof ParseOptions);
  assert.ok(assertArray(defaults.extensionTags).includes("ref"));
  assert.ok(assertArray(defaults.protocols).includes("https://"));

  const init: ParseOptionsInit = {
    extensionTags: ["mytag"],
    parsedTags: ["mytag"],
    preprocessedTags: [],
    htmlTags: ["b"],
    protocols: ["x://"],
    languageConversion: false,
    fileNamespaces: ["Fichier"],
  };
  const options = createParseOptions(init);
  assert.deepEqual(options.extensionTags, ["mytag"]);
  assert.deepEqual(options.parsedTags, ["mytag"]);
  assert.deepEqual(options.preprocessedTags, []);
  assert.deepEqual(options.htmlTags, ["b"]);
  assert.deepEqual(options.protocols, ["x://"]);
  assert.equal(options.languageConversion, false);
  assert.deepEqual(options.fileNamespaces, ["Fichier"]);
  assert.equal(options.parse("<mytag>{{t}}</mytag>").tags("mytag").length, 1);
  assert.deepEqual(createParseOptions({ extensionTags: null }).fileNamespaces, defaults.fileNamespaces);

  const words: MagicWords = createMagicWords(["FOO"], ["bar"]);
  assert.equal(words.has("FOO"), true);
  assert.equal(words.has("foo"), false);
  assert.equal(words.has("BAR"), true);
  assert.deepEqual(words.caseSensitive, ["FOO"]);
  assert.deepEqual(words.caseInsensitive, ["bar"]);

  const init2: TitleRulesInit = {
    namespaces: { Modèle: 10 },
    canonicalNames: { "10": "Modèle" },
    caseSensitive: [10],
    functions: words,
    variables: createMagicWords([], []),
    substitutions: ["subst"],
    modifiers: ["msg"],
  };
  const rules = createTitleRules(init2);
  assert.deepEqual(assertRecord(rules.namespaces), { Modèle: 10 });
  assert.deepEqual(assertRecord(rules.canonicalNames), { "10": "Modèle" });
  assert.deepEqual(rules.caseSensitive, [10]);
  assert.ok(rules.functions instanceof MagicWords);
  assert.deepEqual(rules.variables.caseSensitive, []);
  assert.deepEqual(rules.substitutions, ["subst"]);
  assert.deepEqual(rules.modifiers, ["msg"]);
  assert.equal(rules.isParserFunction("FOO:x"), true);
  assert.equal(rules.isParserFunction("PAGENAME"), false);
  assert.ok(defaultTitleRules() instanceof TitleRules);
  assert.equal(defaultTitleRules().isParserFunction("PAGENAME"), true);
  assert.ok(assertArray(coreFunctionIds()).some((id) => id === "lc" || id === "LC"));
}

function misuses(code: Wikitext, node: Node): void {
  // @ts-expect-error a quote is a quote character
  void createAttribute(literalText("a"), null, "x");
  // @ts-expect-error a hex marker is x or X
  void createHtmlEntity("41", true, "q");
  // @ts-expect-error a scope is prose or everywhere
  code.replaceText(/a/, "b", "templates");
  // @ts-expect-error a typo in a node kind
  if (node.kind === "wikilink") return;
  // @ts-expect-error a template is not a string
  code.replaceTemplate("{{a}}", "{{b}}");
  // @ts-expect-error a pattern has to be a RegExp
  code.containsText("lava");
  // @ts-expect-error a scope option is a known name
  code.textIn({ template: true });
}
void misuses;

const page = parse(PAGE);
everyKindOfNode(page);
tagsAndAttributes(page);
buildNodes();
readATemplate();
rewriteTemplates();
replaceAndWalkNodes();
sourceRanges();
textInAScope();
categoriesAndLanguageLinks();
sections();
plainText();
parseOptionsAndRules();

console.log("wikitext: parsing, every node kind, templates, sections, categories, scopes and ranges keep what their types say");
