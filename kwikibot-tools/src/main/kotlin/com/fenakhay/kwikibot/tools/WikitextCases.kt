package com.fenakhay.kwikibot.tools

/**
 * The wikitext the corpus is recorded from.
 *
 * Written here rather than borrowed, and shaped by two rules the recording depends on.
 *
 * Every title is one no wiki has: `Zqx` prefixes a template, a page and a file name throughout. A template
 * that exists is expanded before `action=parse` reports anything, so `{{t|x}}` on en.wiktionary comes back
 * carrying links to `Module:parameters` and half a dozen other pages that are nowhere in the input. A title
 * nobody has created reports exactly what was written.
 *
 * And every family includes what happens when the construct is left open. `{{`, `[[` and `<ref>` with no
 * terminator are where implementations disagree, where MediaWiki's behaviour is surprising — it re-emits the
 * opening as literal text — and where a parser that guesses instead of checking silently eats the rest of a
 * page.
 */
internal object WikitextCases {

    /** One case: a name for the report, and the wikitext to record. */
    data class Case(val name: String, val input: String)

    /**
     * The cases, grouped by the construct each exercises.
     *
     * Small and single-purpose on purpose: a case that exercises three constructs at once cannot say which
     * one broke.
     */
    val ALL: List<Case> =
        listOf(
            // ------------------------------------------------------------------------------ text
            Case("text/plain", "just some words"),
            Case("text/empty", ""),
            Case("text/unicode", "café, Ωmega, 日本語, 🌋"),
            Case("text/newlines", "one\n\ntwo\nthree"),
            Case("text/lone-open-brace", "a { b"),
            Case("text/lone-open-bracket", "a [ b"),
            Case("text/ampersand", "a & b"),

            // --------------------------------------------------------------------------- comments
            Case("comment/simple", "before <!-- hidden --> after"),
            Case("comment/empty", "a <!----> b"),
            Case("comment/multiline", "a <!-- one\ntwo\nthree --> b"),
            Case("comment/unclosed", "a <!-- never ends"),
            Case("comment/around-markup", "a <!-- [[Zqx page]] --> b"),

            // --------------------------------------------------------------------------- headings
            Case("heading/level2", "== Heading =="),
            Case("heading/level3", "=== Deeper ==="),
            Case("heading/level6", "====== Deepest ======"),
            Case("heading/unbalanced", "== Lopsided ==="),
            Case("heading/not-at-line-start", "text == not a heading =="),
            Case("heading/with-markup", "== A [[Zqx page]] in a heading =="),
            Case("heading/empty", "==  =="),

            // -------------------------------------------------------------------------- wikilinks
            Case("wikilink/simple", "[[Zqx page]]"),
            Case("wikilink/piped", "[[Zqx page|shown text]]"),
            Case("wikilink/fragment", "[[Zqx page#Section]]"),
            Case("wikilink/namespaced", "[[Zqx namespace:Zqx page]]"),
            Case("wikilink/colon-prefixed", "[[:Category:Zqx category]]"),
            Case("wikilink/empty", "[[]]"),
            Case("wikilink/unclosed", "[[Zqx page"),
            Case("wikilink/two-adjacent", "[[Zqx one]][[Zqx two]]"),
            Case("wikilink/trailing-letters", "[[Zqx page]]s"),

            // -------------------------------------------------------------------------- templates
            Case("template/bare", "{{Zqx template}}"),
            Case("template/positional", "{{Zqx template|first|second}}"),
            Case("template/named", "{{Zqx template|key=value}}"),
            Case("template/mixed-params", "{{Zqx template|first|key=value|second}}"),
            Case("template/nested", "{{Zqx outer|{{Zqx inner}}}}"),
            Case("template/whitespace", "{{ Zqx template | key = value }}"),
            Case("template/empty-param", "{{Zqx template||third}}"),
            Case("template/equals-in-value", "{{Zqx template|key=a=b}}"),
            Case("template/newlines", "{{Zqx template|\n first |\n second\n}}"),
            Case("template/link-inside", "{{Zqx template|[[Zqx page]]}}"),
            Case("template/unclosed", "{{Zqx template"),
            Case("template/adjacent", "{{Zqx one}}{{Zqx two}}"),
            // Both of these came off real pages that broke the parser, and are here so they cannot
            // break it again quietly. The first hung it: an unclosed ''' inside a template sends the
            // style route scanning forward, and a page with fifty of them never finished. The second
            // reordered the page: five braces open a template whose name is the argument the next
            // three open, which is nesting rather than two constructs side by side.
            Case("template/unclosed-style", "{{Zqx template|'''}}"),
            Case("template/five-braces", "{{{{{|safesubst:}}}Zqx template}}"),

            // Brace runs, which are where the reading is least obvious and a rewrite has least to go
            // on. Two braces open a template and three an argument, but a longer run has to be cut up
            // somehow and only MediaWiki can say where. Recorded rather than reasoned about, because
            // the one case above that was reasoned about was reasoned about wrongly.
            Case("braces/four", "{{{{Zqx template}}}}"),
            Case("braces/five-balanced", "{{{{{Zqx template}}}}}"),
            Case("braces/six", "{{{{{{Zqx template}}}}}}"),
            Case("braces/five-open-three-close", "{{{{{Zqx template}}}"),
            Case("braces/argument-in-template", "{{Zqx template|{{{1|fallback}}}}}"),
            Case("braces/template-in-argument", "{{{1|{{Zqx template}}}}}"),

            // -------------------------------------------------------------------------- arguments
            Case("argument/simple", "{{{1}}}"),
            Case("argument/default", "{{{1|fallback}}}"),
            Case("argument/named", "{{{param|}}}"),
            Case("argument/in-template", "{{Zqx template|{{{1|}}}}}"),
            Case("argument/unclosed", "{{{1"),

            // --------------------------------------------------------------------------- entities
            Case("entity/named", "a &amp; b"),
            Case("entity/numeric", "a &#233; b"),
            Case("entity/hex", "a &#xE9; b"),
            Case("entity/hex-uppercase", "a &#XE9; b"),
            Case("entity/unknown", "a &notanentity; b"),
            Case("entity/bare-ampersand", "a & b &"),

            // ---------------------------------------------------------------------- external links
            Case("extlink/bare", "see https://example.org/x here"),
            Case("extlink/bracketed", "[https://example.org/x]"),
            Case("extlink/labelled", "[https://example.org/x a label]"),
            Case("extlink/in-template", "{{Zqx template|[https://example.org/x l]}}"),
            Case("extlink/unclosed", "[https://example.org/x a label"),
            Case("extlink/protocol-relative", "[//example.org/x l]"),

            // ------------------------------------------------------------------------------- tags
            Case("tag/nowiki", "<nowiki>{{Zqx template}}</nowiki>"),
            Case("tag/ref", "text<ref>a note</ref>"),
            Case("tag/ref-attribute", "text<ref name=\"a\">a note</ref>"),
            Case("tag/ref-attribute-unquoted", "text<ref name=a>a note</ref>"),
            Case("tag/self-closing", "a<br />b"),
            Case("tag/self-closing-bare", "a<br>b"),
            Case("tag/pre", "<pre>{{Zqx template}}</pre>"),
            Case("tag/unclosed", "a <ref>never ends"),
            Case("tag/nested", "<div><span>a</span></div>"),

            // ------------------------------------------------------------------ formatting markup
            Case("format/bold", "''' bold '''"),
            Case("format/italic", "'' italic ''"),
            Case("format/bold-italic", "''''' both '''''"),
            Case("format/unclosed-bold", "''' never closed"),
            Case("format/apostrophe", "it's a thing"),

            // ------------------------------------------------------------------------------ lists
            Case("list/bullets", "* one\n* two"),
            Case("list/numbered", "# one\n# two"),
            Case("list/nested", "* one\n** deeper"),
            Case("list/definition", "; term\n: definition"),
            Case("list/indent", ": indented"),

            // ------------------------------------------------------------------------ combinations
            Case("mixed/link-in-list", "* [[Zqx page]] and [[Zqx other|text]]"),
            Case("mixed/template-in-heading", "== {{Zqx template}} =="),
            Case("mixed/comment-in-template", "{{Zqx template|<!-- note -->value}}"),
            Case("mixed/entry-shape", "==English==\n\n===Noun===\n{{Zqx head}}\n\n# A [[Zqx page]].\n"),

            // The cases below each pin one rule of MediaWiki's parser.

            // ---------------------------------------------------------------------------- brackets
            // A single bracket is never an opening for the preprocessor, so it cannot hide what follows.
            Case("bracket/single-open-in-template", "{{Zqx template|x [ y}}"),
            Case("bracket/single-close-in-template", "{{Zqx template|x ] y}}"),
            Case("bracket/single-in-word", "{{Zqx template|vn:كتابة[rare]|b}}"),
            Case("bracket/triple", "[[[Zqx page]]] {{Zqx template}}"),
            Case("bracket/quadruple", "[[[[Zqx page]]]]"),
            Case("bracket/extra-close", "[[Zqx page|text]]]"),
            Case("bracket/stray-close-in-template", "{{Zqx template|a]]b}}"),
            // An unclosed pair is on the stack when the braces close, and hides them.
            Case("bracket/unclosed-hides-template", "{{Zqx template|[[Zqx page}}"),
            Case("bracket/extlink-in-link-text", "[[Zqx page|[https://example.org/x l]]]"),
            Case("bracket/protocol-target", "[[https://example.org/x]]"),
            Case("bracket/nested-link", "[[Zqx page|a [[Zqx other]] b]]"),
            Case("bracket/nested-in-file", "[[File:Zqx file.png|thumb|a [[Zqx page]] b]]"),
            Case("bracket/newline-in-text", "[[Zqx page|a\nb]]"),
            Case("bracket/newline-in-target", "[[Zqx\npage]]"),
            Case("bracket/empty-text", "[[Zqx page|]]"),
            Case("bracket/blank-target", "[[ ]]"),
            Case("bracket/template-in-target", "[[Zqx {{Zqx template}} page]]"),
            Case("bracket/pipes-in-text", "[[Zqx page|a|b]]"),
            Case("bracket/bracket-in-text", "[[Zqx page|b]c]]"),
            Case("bracket/illegal-target", "[[Zqx<page]]"),
            Case("bracket/braces-in-target", "[[Zqx}page]]"),

            // ---------------------------------------------------------------------- part separators
            // Only the preprocessor's own constructs decide where a `|` or `=` belongs: templates,
            // arguments, links, language conversions, comments and extension tags. An external link, an
            // HTML tag or bold text does not, since those are read after the templates are.
            Case("separator/equals-in-extlink", "{{Zqx template|[https://example.org/?a=b label]}}"),
            Case("separator/equals-in-bare-url", "{{Zqx template|https://example.org/?a=b}}"),
            Case("separator/equals-in-link", "{{Zqx template|[[Zqx page|a=b]]}}"),
            Case("separator/equals-in-attribute", "{{Zqx template|<span title=\"a=b\">x</span>}}"),
            Case("separator/pipe-in-tag", "{{Zqx template|<span>a|b</span>}}"),
            Case("separator/pipe-in-bold", "{{Zqx template|'''a|b'''}}"),
            Case("separator/pipe-in-conversion", "{{Zqx template|-{a|b}-}}"),
            Case("separator/pipe-in-nowiki", "{{Zqx template|<nowiki>|</nowiki>}}"),
            Case("separator/pipe-in-ref-attribute", "{{Zqx template|<ref name=\"a|b\">x</ref>}}"),
            Case("separator/pipe-in-comment", "{{Zqx template|<!-- a|b -->c}}"),
            Case("separator/equals-on-own-line", "{{Zqx template\n|a\n=b}}"),
            Case("separator/numbered", "{{Zqx template|1=a|2=b}}"),
            Case("separator/argument-extra-parts", "{{Zqx template|{{{1|a|b}}}}}"),
            Case("separator/template-straddles-tag", "{{Zqx template|<span>}}</span>"),
            Case("separator/tag-straddles-template", "<span>{{Zqx template|</span>}}"),
            Case("separator/conversion-transition", "-{{Zqx template}}-"),
            Case("separator/conversion-template", "-{a|{{Zqx template}}}-"),
            Case("separator/parser-function", "{{#if:a|{{Zqx template}}|c}}"),
            Case("separator/template-as-name", "{{{{Zqx template}}|b}}"),
            Case("separator/extra-close-brace", "{{Zqx template|a}}}"),

            // ----------------------------------------------------------------------------- headings
            Case("heading/surplus-opening", "===Zqx heading=="),
            Case("heading/trailing-comment", "== Zqx heading == <!-- c -->"),
            Case("heading/trailing-spaces", "== Zqx heading ==   "),
            Case("heading/equals-in-body", "== Zqx = heading =="),
            Case("heading/five-equals", "====="),
            Case("heading/three-equals", "==="),
            Case("heading/two-equals", "=="),
            Case("heading/seven", "======= Zqx heading ======="),
            Case("heading/level1", "=Zqx heading="),
            Case("heading/trailing-text", "== Zqx heading == x"),
            Case("heading/comment-then-text", "== Zqx heading ==<!-- c -->x"),
            Case("heading/inside-div", "<div>\n== Zqx heading ==\n</div>"),
            Case("heading/after-comment-line", "a\n<!-- c -->\n== Zqx heading =="),
            Case("heading/template-across-lines", "== Zqx {{Zqx template|\n}} heading =="),
            Case("heading/consecutive", "== Zqx one ==\n== Zqx two =="),
            Case("heading/nowiki-equals", "== <nowiki>==</nowiki> =="),
            Case("heading/in-template", "{{Zqx template|\n== Zqx heading ==\n}}"),
            Case("heading/swallows-braces", "{{Zqx template|a\n== b }}"),
            Case("heading/in-link", "[[Zqx page|\n== Zqx heading ==\n]]"),

            // --------------------------------------------------------------------------------- tags
            Case("tag/closer-padding", "<i>x</i >"),
            Case("tag/closer-case", "<I>x</i>"),
            Case("tag/raw-closer-exact", "<pre>a</prex>b</pre>"),
            Case("tag/unknown-name", "<mcRmJpmif='''{{Zqx template}}'''"),
            Case("tag/bare-attribute", "<div nowrap >x</div>"),
            Case("tag/template-in-attribute", "<span title=\"{{Zqx template}}\">x</span>"),
            Case("tag/comment-in-attributes", "<span <!-- c --> class=\"a\">x</span>"),
            Case("tag/attributes-over-lines", "<span\nclass=\"a\">x</span>"),
            Case("tag/ext-first-gt", "<ref name=\"a>b\">x</ref>"),
            Case("tag/ext-closer-padding", "<ref>x</ref >"),
            Case("tag/ext-name-case", "<REF>x</ref>"),
            Case("tag/ext-first-closer", "<nowiki>a</nowiki>b</nowiki>"),
            Case("tag/ext-nested", "<ref>a<ref>b</ref></ref>"),
            Case("tag/ext-template-inside", "<ref>{{Zqx template}}</ref>"),
            Case("tag/ext-verbatim", "<math>{{Zqx template}}</math>"),
            Case("tag/ext-short", "a<ref name=a/>b"),
            Case("tag/ext-short-spaced", "<references />"),
            Case("tag/ext-attribute-template", "<ref name=\"{{Zqx template}}\">x</ref>"),
            Case("tag/gallery", "<gallery>\nFile:Zqx file.png|[[Zqx page]]\n</gallery>"),
            Case("tag/templatedata", "<templatedata>{\"a\":\"{{Zqx template}}\"}</templatedata>"),
            Case("tag/not-allowed", "<img src=\"x\">"),
            Case("tag/mismatched", "<div>a<span>b</div>c</span>"),
            Case("tag/html-unclosed", "<b>a"),
            Case("tag/stray-closer", "a</b>"),
            Case("tag/void-closer", "a</br>b"),
            Case("tag/noinclude", "<noinclude>{{Zqx template}}</noinclude>"),
            Case("tag/noinclude-splits-template", "{{Zqx template<noinclude>|a</noinclude>}}"),
            Case("tag/includeonly", "<includeonly>{{Zqx template}}</includeonly>"),
            Case("tag/onlyinclude", "<onlyinclude>{{Zqx template}}</onlyinclude>"),

            // ------------------------------------------------------------------------ external links
            Case("extlink/two-spaces", "[https://example.org/x  two spaces]"),
            Case("extlink/ideographic-space", "[https://example.org/x　label]"),
            Case("extlink/quote-ends-url", "[https://example.org/x\"quoted\"]"),
            Case("extlink/newline-in-label", "[https://example.org/x a\nb]"),
            Case("extlink/in-heading", "==https://example.org/x=="),
            Case("extlink/before-italic", "''https://example.org/x'' z"),
            Case("extlink/before-entity", "see https://example.org/x&lt;y"),
            Case("extlink/parenthesis", "https://example.org/x(y) and (https://example.org/z)"),
            Case("extlink/new-scheme", "join matrix:#room:example.org now"),
            Case("extlink/bare-protocol-relative", "see //example.org/x here"),
            Case("extlink/no-word-boundary", "xhttps://example.org/x"),
            Case("extlink/template-in-url", "[https://example.org/{{Zqx template}} l]"),
            Case("extlink/pipe-in-bare-url", "https://example.org/a|b"),
            Case("extlink/extra-close", "[https://example.org/x]]"),
            Case("extlink/before-nbsp", "https://example.org/x&nbsp;y"),
            Case("extlink/mailto", "[mailto:a@example.org mail]"),

            // ------------------------------------------------------------------------- formatting
            Case("format/closer-inside-template", "''a {{Zqx template|''}} b''"),
            Case("format/closer-inside-link", "''a [[Zqx page|b'']] c''"),
            Case("format/interleaved", "'''a''b'''c''"),
            Case("format/across-lines", "''a\nb''"),
            Case("format/four", "''''x''''"),

            // ---------------------------------------------------------------------------- comments
            Case("comment/hides-braces", "{{Zqx template|<!--}}-->}}"),
            Case("comment/unclosed-hides", "a <!-- unclosed {{Zqx template}}"),
            Case("comment/adjacent", "a <!-- x --><!-- y --> b"),
            Case("comment/lines-before-heading", "<!--a-->\n<!--b-->\n== Zqx heading =="),
            Case("comment/in-link", "[[Zqx page<!-- c -->]]"),

            // ---------------------------------------------------------------- extension tag bodies
            Case("body/pre-wikitext", "<pre format=\"wikitext\">{{Zqx template}} [[Zqx page]]</pre>"),
            Case("body/pre-format-case", "<pre format=\"WikiText\">{{Zqx template}}</pre>"),
            Case("body/inputbox", "x<inputbox>type=search\ndefault={{Zqx template}} [[Zqx page]]</inputbox>"),
            Case(
                "body/page-list",
                "x<DynamicPageList>\ncategory={{Zqx template}}\ncount={{Zqx other}}\n</DynamicPageList>",
            ),

            // ------------------------------------------------------------------------ tag pairing
            Case("pairing/div-across-heading", "<div>alpha\n== Zqx one ==\nbeta</div> gamma"),
            Case("pairing/span-across-heading", "<span>alpha\n== Zqx one ==\nbeta</span> gamma"),
            Case("pairing/bold-across-heading", "<b>alpha\n== Zqx one ==\nbeta</b> gamma"),
            Case("pairing/small-across-heading", "<small>alpha\n== Zqx one ==\nbeta</small> gamma"),
            Case("pairing/center-across-heading", "<center>alpha\n== Zqx one ==\nbeta</center> gamma"),
            Case(
                "pairing/blockquote-across-heading",
                "<blockquote>alpha\n== Zqx one ==\nbeta</blockquote> gamma",
            ),
            Case("pairing/span-across-blank", "<span>alpha\n\nbeta</span> gamma"),
            Case("pairing/div-across-blank", "<div>alpha\n\nbeta</div> gamma"),
            Case("pairing/bold-across-blank", "<b>alpha\n\nbeta</b> gamma"),
            Case("pairing/span-in-div-across-blank", "<div><span>alpha\n\nbeta</span> gamma</div> delta"),
            Case("pairing/span-before-list", "<span>alpha\n* beta</span> gamma"),
            Case("pairing/span-before-pre", "<span>alpha\n beta</span> gamma"),
            Case("pairing/span-before-rule", "<span>alpha\n----\nbeta</span> gamma"),
            Case("pairing/span-before-table", "<span>alpha\n{|\n|beta</span> gamma\n|}"),
            Case("pairing/span-closer-in-div", "<span>alpha<div>beta</span> gamma</div> delta"),
            Case("pairing/span-closer-in-p", "<span>alpha<p>beta</span> gamma</p> delta"),
            Case("pairing/item-closes-item", "<ul><li><span>alpha<li>beta</span> gamma</ul> delta"),
            Case(
                "pairing/span-around-heading-in-div",
                "<div><span>alpha\n== Zqx one ==\nbeta</span> gamma</div> delta",
            ),
            Case("pairing/span-across-items", "* <span>alpha\n* beta</span> gamma"),
            Case("pairing/comment-line", "<span>alpha\n<!-- c -->\nbeta</span> gamma"),
            Case("pairing/span-in-ref", "x<ref><span>alpha\n\nbeta</span> gamma</ref>\n<references />"),
            Case(
                "pairing/span-in-ref-after-div",
                "x<ref><div>alpha</div>\n<span>beta\n\ngamma</span> delta</ref>\n<references />",
            ),
            Case(
                "pairing/file-thumb-line",
                "<span>alpha\n[[File:Zqx file.png|thumb|caption]]\nbeta</span> gamma",
            ),
            Case("pairing/bold-across-list", "<b>alpha\n* beta\ngamma</b> delta"),
            Case("pairing/bold-closer-in-cell", "<b>alpha\n{|\n|beta</b> gamma\n|}\ndelta</b> epsilon"),
            Case("pairing/span-in-indent-pre", " <span>alpha\n\n beta</span> gamma"),
            Case("pairing/span-in-poem", "<poem><span>alpha\n\nbeta</span> gamma</poem>"),
            Case(
                "pairing/span-in-wikitext-pre",
                "<pre format=\"wikitext\"><span>alpha\n\nbeta</span> gamma</pre>",
            ),
            Case("pairing/cell-ends-span", "{|\n|<span>alpha\n|beta</span> gamma\n|}"),
            Case("pairing/blockquote-blank", "<blockquote><span>alpha\n\nbeta</span> gamma</blockquote>"),
            Case("pairing/definition", "; <span>alpha : beta</span> gamma\n: delta"),
            Case("pairing/parameter", "{{#if:x|<span>alpha\n\nbeta</span> gamma}}"),
            Case("pairing/bold-into-div", "<b>alpha\n\n<div>beta</b> gamma</div> delta"),
            Case("pairing/bold-out-of-div", "<div><b>alpha</div>beta</b> gamma"),
            Case("pairing/self-closing-span", "<span/>alpha</span> beta"),
            Case("pairing/heading-in-span-line", "alpha <span>beta\n== Zqx one ==\ngamma</span> delta"),
        )
}
