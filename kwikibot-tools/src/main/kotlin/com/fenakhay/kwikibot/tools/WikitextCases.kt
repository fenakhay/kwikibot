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
        )
}
