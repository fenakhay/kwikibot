import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.js.RegExp
import kotlin.test.Test

class WikitextApiTest {

    private val page =
        """
        lead <!-- note --> &amp; [[Lava|hot]] [https://example.org label]

        == One ==
        {{Volcano|active=yes|lava}} {{{arg|fallback}}}
        <ref name="a">source</ref>
        === Two ===
        text
        [[Category:Rocks|key]]
        [[fr:Lave]]
        [[de:Lava]]
        """
            .trimIndent()

    private val code = parse(page)

    @Test
    fun `every kind of node is reached and writes itself back`() {
        val kinds = code.allNodes().map { it.kind }.toSet()
        kinds shouldBe
            setOf(
                "text",
                "comment",
                "template",
                "argument",
                "wiki-link",
                "external-link",
                "heading",
                "html-entity",
                "tag",
            )
        code
            .allNodes()
            .joinToString("") { if (it.kind.toString() == "text") "" else it.toString() }
            .isNotEmpty() shouldBe true
        code.nodes.joinToString("") { it.toString() } shouldBe page
        code.nodes.first().text shouldBe "lead "
        code.nodes.first().template shouldBe null

        val comment = code.comments().single()
        listOf(comment.contents, comment.closed) shouldBe listOf(" note ", true)
        comment.toString() shouldBe "<!-- note -->"
        comment.node.comment?.contents shouldBe " note "

        val argument = code.allNodes().firstNotNullOf { it.argument }
        listOf(argument.name.toString(), argument.defaultValue.toString()) shouldBe listOf("arg", "fallback")
        argument.node.kind shouldBe "argument"
        argument.toString() shouldBe "{{{arg|fallback}}}"

        val external = code.allNodes().firstNotNullOf { it.externalLink }
        listOf(
            external.url.toString(),
            external.title.toString(),
            external.brackets,
            external.separator,
        ) shouldBe listOf("https://example.org", "label", true, " ")
        external.node.kind shouldBe "external-link"
        external.toString() shouldBe "[https://example.org label]"

        val entity = code.allNodes().firstNotNullOf { it.htmlEntity }
        listOf(entity.value, entity.numeric, entity.hexChar) shouldBe listOf("amp", false, null)
        entity.node.kind shouldBe "html-entity"
        entity.toString() shouldBe "&amp;"

        val heading = code.headings().first()
        listOf(heading.title.toString(), heading.level) shouldBe listOf(" One ", 2)
        heading.node.heading?.level shouldBe 2
        heading.toString() shouldBe "== One =="

        code.allNodes().firstNotNullOf { it.link }.target shouldBe "Lava"
    }

    @Test
    fun `tags have their attributes and parts`() {
        val ref = code.tags("ref").single()
        listOf(ref.name, ref.contents.toString(), ref.selfClosing, ref.implicitClose, ref.verbatim) shouldBe
            listOf("ref", "source", false, false, false)
        listOf(ref.wikiMarkup, ref.padding, ref.closing) shouldBe listOf(null, "", null)
        ref.node.tag?.name shouldBe "ref"
        ref.toString() shouldBe "<ref name=\"a\">source</ref>"
        code.tags().isNotEmpty() shouldBe true

        val attribute = ref.attributes.single()
        listOf(attribute.name.toString(), attribute.value.toString(), attribute.quote) shouldBe
            listOf("name", "a", "\"")
        listOf(attribute.padFirst, attribute.padBeforeEq, attribute.padAfterEq) shouldBe listOf(" ", "", "")
        attribute.toString() shouldBe " name=\"a\""
    }

    @Test
    fun `nodes are built and put into a page`() {
        val name = literalText("Lava")
        createText("plain").text shouldBe "plain"
        createComment("c", closed = false).toString() shouldBe "<!--c"
        createArgument(name, literalText("d")).toString() shouldBe "{{{Lava|d}}}"
        createArgument(name).defaultValue shouldBe null
        createLink(name, literalText("t")).toString() shouldBe "[[Lava|t]]"
        createExternalLink(literalText("https://x.org"), literalText("x")).toString() shouldBe
            "[https://x.org x]"
        createExternalLink(literalText("https://x.org"), brackets = false).toString() shouldBe "https://x.org"
        createHeading(literalText(" H "), 3).toString() shouldBe "=== H ==="
        createHtmlEntity("41", true, literal("x")).toString() shouldBe "&#x41;"
        createHtmlEntity("amp").toString() shouldBe "&amp;"
        val attribute = createAttribute(literalText("id"), literalText("a"), literal("'"), "  ", " ", " ")
        attribute.toString() shouldBe "  id = 'a'"
        createAttribute(literalText("nowrap")).toString() shouldBe " nowrap"
        createTag("br", optionsOf("selfClosing" to true, "padding" to " ")).toString() shouldBe "<br />"
        createTag(
                "span",
                optionsOf("contents" to literalText("x"), "attributes" to arrayOf(attribute)),
            )
            .toString() shouldBe "<span  id = 'a'>x</span>"
        createTag("x").name shouldBe "x"
        createTag("b", optionsOf("wikiMarkup" to "'''", "contents" to literalText("b"))).toString() shouldBe
            "'''b'''"
        createTag(
                "nowiki",
                optionsOf(
                    "contents" to literalText("x"),
                    "verbatim" to true,
                    "closing" to "</NOWIKI>",
                    "implicitClose" to false,
                ),
            )
            .toString() shouldBe "<nowiki>x</NOWIKI>"

        val parameter = createParameter(literalText("a"), literalText("b"), showKey = true)
        val template = createTemplate(name, arrayOf(parameter))
        template.toString() shouldBe "{{Lava|a=b}}"
        createTemplate(name).withParameter("1", "x").toString() shouldBe "{{Lava|x}}"
        wikitextOf(arrayOf(template.node, createText(" tail"))).toString() shouldBe "{{Lava|a=b}} tail"
        emptyWikitext().toString() shouldBe ""
        literalText("{{not}}").templates().size shouldBe 0
    }

    @Test
    fun `a template's parts are read as a wiki reads them`() {
        val rules = createTitleRules(optionsOf("caseSensitive" to arrayOf(10)))
        val template = parse("{{ <!-- c -->volcano|a=b|x}}").templates().single()

        template.rawName shouldBe " <!-- c -->volcano"
        template.nameMarkup.comments().size shouldBe 1
        template.key shouldBe "Volcano"
        template.keyIn(rules) shouldBe "volcano"
        template.isParserFunction shouldBe false
        template.functionName shouldBe null
        template.functionArgument shouldBe null
        template.parameter("nope") shouldBe null
        val named = template.parameter("a") ?: error("no a")
        listOf(named.rawName, named.nameMarkup.toString(), named.valueMarkup.toString()) shouldBe
            listOf("a", "a", "b")
        template.parameter("1")?.rawName shouldBe null
        template.node.template?.name shouldBe "volcano"

        val function = parse("{{#invoke:sa-decl|show}}").templates().single()
        function.isParserFunction shouldBe true
        function.isParserFunctionIn(rules) shouldBe true
        listOf(function.functionName, function.functionArgument) shouldBe listOf("#invoke", "sa-decl")
        function.functionNameIn(rules) shouldBe "#invoke"
        function.functionArgumentIn(rules) shouldBe "sa-decl"
    }

    @Test
    fun `templates are found and rewritten by every way they are named`() {
        val source = parse("{{l}} {{Template:L|x}} {{other}}")
        source.templatesNamed(arrayOf("l")).size shouldBe 2
        source
            .templatesNamed(arrayOf("l"), createTitleRules(optionsOf("caseSensitive" to arrayOf(10))))
            .size shouldBe 1
        source.mapTemplatesNamed(arrayOf("l"), { it.withParameter("k", "v") }).toString() shouldBe
            "{{l|k=v}} {{Template:L|x|k=v}} {{other}}"
        source.mapAllTemplates { it.withParameter("k", "v") }.templates().all { it.has("k") } shouldBe true
    }

    @Test
    fun `nodes are replaced, removed and walked`() {
        val source = parse("a {{t}} b")
        val template = source.templates().single()
        source.replace(template.node, createText("c")).toString() shouldBe "a c b"
        source.remove(template.node).toString() shouldBe "a  b"
        source
            .mapNodes { node ->
                if (node.kind.toString() == "text") createText(node.toString().uppercase()) else node
            }
            .toString() shouldBe "A {{T}} B"
        parse("a").plus(parse("b")).toString() shouldBe "ab"
        (parse("a {{b}}") == parse("a {{b}}")) shouldBe true
        (parse("a") == parse("b")) shouldBe false
        parse("a").hashCode() shouldBe parse("a").hashCode()
    }

    @Test
    fun `ranges say where each piece was written`() {
        val text = "a {{t|k=v}} <b x=\"1\">c</b>"
        val source = parse(text)
        val ranges = source.ranges()
        fun SourceRange?.cut() = this?.let { text.substring(it.start, it.end) }

        val template = source.templates().single()
        ranges.ofNode(template.node).cut() shouldBe "{{t|k=v}}"
        val parameter = template.parameters.single()
        ranges.ofParameter(parameter).cut() shouldBe "k=v"
        ranges.ofWikitext(parameter.valueMarkup).cut() shouldBe "v"
        val tag = source.tags("b").single()
        ranges.ofAttribute(tag.attributes.single()).cut() shouldBe " x=\"1\""
        ranges.ofNode(createText("elsewhere")) shouldBe null
    }

    @Test
    fun `text is matched and replaced within a scope`() {
        val source = parse("lava {{t|lava}} <!-- lava --> [[lava]]\n== lava ==")
        source.containsText(RegExp("rock")) shouldBe false
        source.containsText(RegExp("lava")) shouldBe true
        source.textIn(optionsOf("headings" to false)).any { "lava" in it } shouldBe true
        source.textIn().size shouldBe source.textIn(TEXT_SCOPE_PROSE).size
        (source.textIn(TEXT_SCOPE_EVERYWHERE).size > source.textIn().size) shouldBe true

        source
            .replaceTextIn(RegExp("lava"), "x", optionsOf("comments" to true, "headings" to false))
            .toString() shouldBe "x {{t|lava}} <!-- x --> [[lava]]\n== lava =="
        source.replaceTextIn(RegExp("lava"), "x", TEXT_SCOPE_EVERYWHERE).toString() shouldBe
            "x {{t|x}} <!-- x --> [[x]]\n== x =="

        val seen = mutableListOf<TextMatch>()
        parse("a12")
            .replaceTextWith(
                RegExp("(\\d)(\\d)"),
                { match ->
                    seen += match
                    match.groups[2] + match.groups[1]
                },
            )
            .toString() shouldBe "a21"
        listOf(seen.single().value, seen.single().index) shouldBe listOf("12", 1)
        parse("t").replaceTextWith(RegExp("t"), { "u" }, TEXT_SCOPE_EVERYWHERE).toString() shouldBe "u"
    }

    @Test
    fun `categories and language links are read and changed under any prefix`() {
        val source = parse("x\n[[Catégorie:A|key]]\n[[Category:B]]\n[[fr:Lave]]\n[[de:Lava]]")

        source.categoryLinks().single().target shouldBe "Category:B"
        source.categoryLinks(arrayOf("Catégorie")).single().target shouldBe "Catégorie:A"
        source.categoryNamesIn(arrayOf("Catégorie", "Category")).toList() shouldBe listOf("A", "B")
        source.languageLinks(arrayOf("fr", "de")).map { it.target } shouldBe listOf("fr:Lave", "de:Lava")

        source.addCategoryAs("C", "Catégorie", "s").categoryNamesIn(arrayOf("Catégorie")).toList() shouldBe
            listOf("A", "C")
        source.addCategoryAs("C", "Catégorie").toString().contains("[[Catégorie:C]]") shouldBe true
        source.removeCategoryAs("A", "Catégorie").categoryNamesIn(arrayOf("Catégorie")).size shouldBe 0
        source.changeCategory("B", "D").categories().toList() shouldBe listOf("D")
        source.changeCategory("A", "E", "Catégorie").toString().contains("[[Catégorie:E|key]]") shouldBe true

        source.sortLanguageLinks(arrayOf("fr", "de")).languageLinks(arrayOf("fr", "de")).map {
            it.target
        } shouldBe listOf("de:Lava", "fr:Lave")
        source
            .sortLanguageLinks(arrayOf("fr", "de"), { a, b -> b.compareTo(a) })
            .languageLinks(arrayOf("fr", "de"))
            .map { it.target } shouldBe listOf("fr:Lave", "de:Lava")
    }

    @Test
    fun `sections are walked, built and put back`() {
        val outline = code.outline()
        val one = outline.find("One") ?: error("no One")
        one.heading?.level shouldBe 2
        outline.heading shouldBe null
        one.nodes.isNotEmpty() shouldBe true
        outline.all().map { it.title } shouldBe listOf(null, "One", "Two")
        outline.children("One").single().title shouldBe "One"
        outline.children("Two").size shouldBe 0

        val added = createSection(createHeading(literalText(" New "), 2), arrayOf(createText("\nbody\n")))
        outline.withSubsection(added).subsections.last().title shouldBe "New"
        outline.withSubsectionAt(0, added).subsections.first().title shouldBe "New"
        shouldThrow<Throwable> { outline.withSubsectionAt(9, added) }
        val two = one.find("Two") ?: error("no Two")
        val replaced = two.withContent(literalText("\nchanged\n"))
        outline.replace(two, replaced).toString().contains("changed") shouldBe true

        val options = createParseOptions(optionsOf("fileNamespaces" to arrayOf("Fichier")))
        code.replaceSectionWith(two, replaced, options).toString().contains("changed") shouldBe true
        createSection(null, emptyArray(), arrayOf(added)).subsections.size shouldBe 1
    }

    @Test
    fun `a parse checks it writes back, as a wiki's options read it`() {
        parseExact("a {{b}}").toString() shouldBe "a {{b}}"
        parseExact("a", defaultParseOptions()).toString() shouldBe "a"
        roundTrips("a {{b}}") shouldBe true
        roundTrips("a", defaultParseOptions()) shouldBe true
    }

    @Test
    fun `parse options and title rules are built from Wikimedia's own`() {
        val defaults = defaultParseOptions()
        ("ref" in defaults.extensionTags) shouldBe true
        ("https://" in defaults.protocols) shouldBe true

        val options =
            createParseOptions(
                optionsOf(
                    "extensionTags" to arrayOf("mytag"),
                    "parsedTags" to arrayOf("mytag"),
                    "preprocessedTags" to emptyArray<String>(),
                    "htmlTags" to arrayOf("b"),
                    "protocols" to arrayOf("x://"),
                    "languageConversion" to false,
                )
            )
        options.extensionTags.toList() shouldBe listOf("mytag")
        options.protocols.toList() shouldBe listOf("x://")
        options.languageConversion shouldBe false
        options.fileNamespaces.toList() shouldBe defaults.fileNamespaces.toList()
        options.parse("<mytag>{{t}}</mytag>").tags("mytag").size shouldBe 1

        val words = createMagicWords(arrayOf("FOO"), arrayOf("bar"))
        listOf(words.has("FOO"), words.has("foo"), words.has("BAR")) shouldBe listOf(true, false, true)
        words.caseSensitive.toList() shouldBe listOf("FOO")

        val rules =
            createTitleRules(
                optionsOf(
                    "namespaces" to recordOf("Modèle" to 10),
                    "canonicalNames" to recordOf("10" to "Modèle"),
                    "functions" to words,
                    "variables" to createMagicWords(emptyArray(), emptyArray()),
                    "substitutions" to arrayOf("subst"),
                    "modifiers" to arrayOf("msg"),
                )
            )
        rules.namespaces.toMap().keys shouldBe setOf("Modèle")
        rules.isParserFunction("FOO:x") shouldBe true
        rules.isParserFunction("PAGENAME") shouldBe false
        defaultTitleRules().isParserFunction("PAGENAME") shouldBe true
        ("LC" in coreFunctionIds() || "lc" in coreFunctionIds()) shouldBe true
    }

    @Test
    fun `cosmetic passes are chosen by name and combined`() {
        COSMETIC_TRAILING_WHITESPACE.apply(parse("a  \nb")).toString() shouldBe "a\nb"
        COSMETIC_EXTRA_BLANK_LINES.apply(parse("a\n\n\n\n\nb")).toString() shouldBe "a\n\n\nb"
        COSMETIC_HTML_EMPHASIS.apply(parse("<b>a</b>")).toString() shouldBe "'''a'''"
        COSMETIC_HTML_ENTITIES.apply(parse("&ndash;")).toString() shouldBe "–"
        COSMETIC_REDUNDANT_LINK_TEXT.apply(parse("[[a|a]]")).toString() shouldBe "[[a]]"
        COSMETIC_SELF_CLOSING_BR.apply(parse("<br>")).toString() shouldBe "<br />"
        COSMETIC_EMPTY_SECTIONS.apply(parse("a\n== b ==\n")).toString().contains("b") shouldBe false
        cosmeticReformatIsbns(true).apply(parse("ISBN 0-19-853737-9")).toString() shouldBe
            "ISBN 978-0-19-853737-3"
        cosmeticReformatIsbns().apply(parse("ISBN 0-19-853737-9")).toString() shouldBe "ISBN 0-19-853737-9"

        COSMETIC_SAFE.apply(parse("[[a|a]]  \nb")).toString() shouldBe "[[a]]\nb"
        val custom = cosmeticPass { it.plus(parse("!")) }
        val both = cosmeticChanges(arrayOf(COSMETIC_REDUNDANT_LINK_TEXT, custom))
        both.applyText("[[a|a]]") shouldBe "[[a]]!"
        both.applyText("[[a|a]]", defaultParseOptions()) shouldBe "[[a]]!"
        both.wouldChange("x") shouldBe true
        COSMETIC_SAFE.wouldChange("x", defaultParseOptions()) shouldBe false
    }

    @Test
    fun `an ISBN is checked and converted`() {
        val isbn = parseIsbn("0-19-853737-9") ?: error("not an ISBN")
        listOf(isbn.normalised, isbn.isIsbn13, isbn.toString()) shouldBe
            listOf("0198537379", false, "0198537379")
        isbn.toIsbn13().normalised shouldBe "9780198537373"
        parseIsbn("0-19-853737-0") shouldBe null
        isValidIsbn("9780198537373") shouldBe true
        isValidIsbn("nope") shouldBe false
    }
}
