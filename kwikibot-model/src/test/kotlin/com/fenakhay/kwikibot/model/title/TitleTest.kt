package com.fenakhay.kwikibot.model.title

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class TitleTest {

    private val enwiktionary = InterwikiMap(listOf("w", "wikipedia", "wikt", "de", "fr", "commons", "s"))

    @Test
    fun `a bare title lands in main space`() {
        val title = Title.parse("volcano").shouldBeInstanceOf<Title.Local>()
        title.namespace shouldBe Namespace.MAIN
        title.text shouldBe "Volcano"
        title.fragment shouldBe null
    }

    @Test
    fun `namespace prefixes resolve by canonical name and alias`() {
        Title.parse("Category:English lemmas").shouldBeInstanceOf<Title.Local>().namespace shouldBe
            Namespace.CATEGORY
        Title.parse("Image:Foo.png").shouldBeInstanceOf<Title.Local>().namespace shouldBe Namespace.FILE
        Title.parse("template talk:col").shouldBeInstanceOf<Title.Local>().namespace shouldBe
            Namespace.TEMPLATE_TALK
    }

    @Test
    fun `interwiki prefixes never become local pages`() {
        val title = Title.parse("w:Etsy", interwiki = enwiktionary).shouldBeInstanceOf<Title.Interwiki>()
        title.prefix shouldBe "w"
        title.rest shouldBe "Etsy"
    }

    @Test
    fun `an interwiki prefix is only interwiki where the wiki declares it`() {
        val title = Title.parse("w:Etsy").shouldBeInstanceOf<Title.Local>()
        title.namespace shouldBe Namespace.MAIN
        title.text shouldBe "W:Etsy"
    }

    @Test
    fun `underscores and repeated whitespace normalize to single spaces`() {
        Title.parse("New__York   City").shouldBeInstanceOf<Title.Local>().text shouldBe "New York City"
        Title.parse("  spaced  ").shouldBeInstanceOf<Title.Local>().text shouldBe "Spaced"
    }

    @Test
    fun `fragments are split off and normalized`() {
        val title = Title.parse("volcano#Derived_terms").shouldBeInstanceOf<Title.Local>()
        title.text shouldBe "Volcano"
        title.fragment shouldBe "Derived terms"
    }

    @Test
    fun `an empty fragment is dropped`() {
        Title.parse("volcano#").shouldBeInstanceOf<Title.Local>().fragment shouldBe null
    }

    @Test
    fun `a leading colon is stripped but the namespace still applies`() {
        val title = Title.parse(":Category:Foo").shouldBeInstanceOf<Title.Local>()
        title.namespace shouldBe Namespace.CATEGORY
        title.text shouldBe "Foo"
    }

    @Test
    fun `html entities are decoded`() {
        Title.parse("AT&amp;T").shouldBeInstanceOf<Title.Local>().text shouldBe "AT&T"
        Title.parse("&#x41;pple").shouldBeInstanceOf<Title.Local>().text shouldBe "Apple"
    }

    @Test
    fun `percent escapes are rejected rather than decoded`() {
        Title.parse("caf%C3%A9").shouldBeInstanceOf<Title.Invalid>().reason shouldBe
            Title.Invalid.Reason.PERCENT_ESCAPE
        Title.parse("100% cotton").shouldBeInstanceOf<Title.Local>().text shouldBe "100% cotton"
    }

    @Test
    fun `first letter casing follows the namespace rule`() {
        Title.parse("volcano").shouldBeInstanceOf<Title.Local>().text shouldBe "Volcano"

        val caseSensitive =
            NamespaceMap(listOf(NamespaceInfo(Namespace.MAIN, "", "", case = TitleCase.CASE_SENSITIVE)))
        Title.parse("volcano", namespaces = caseSensitive).shouldBeInstanceOf<Title.Local>().text shouldBe
            "volcano"
    }

    @Test
    fun `illegal characters are rejected rather than silently stripped`() {
        for (raw in listOf("foo[bar", "foo]bar", "foo{bar", "foo}bar", "foo|bar", "foo<bar", "foo>bar")) {
            Title.parse(raw).shouldBeInstanceOf<Title.Invalid>().reason shouldBe
                Title.Invalid.Reason.ILLEGAL_CHARACTER
        }
    }

    @Test
    fun `empty and whitespace-only titles are invalid`() {
        Title.parse("").shouldBeInstanceOf<Title.Invalid>().reason shouldBe Title.Invalid.Reason.EMPTY
        Title.parse("   ").shouldBeInstanceOf<Title.Invalid>().reason shouldBe Title.Invalid.Reason.EMPTY
        Title.parse("#section").shouldBeInstanceOf<Title.Invalid>().reason shouldBe Title.Invalid.Reason.EMPTY
    }

    @Test
    fun `relative paths and signatures are invalid`() {
        Title.parse("../Foo").shouldBeInstanceOf<Title.Invalid>().reason shouldBe
            Title.Invalid.Reason.RELATIVE_PATH
        Title.parse("Foo/./Bar").shouldBeInstanceOf<Title.Invalid>().reason shouldBe
            Title.Invalid.Reason.RELATIVE_PATH
        Title.parse("Foo ~~~~").shouldBeInstanceOf<Title.Invalid>().reason shouldBe
            Title.Invalid.Reason.SIGNATURE
    }

    @Test
    fun `titles longer than 255 bytes are invalid`() {
        Title.parse("a".repeat(255)).shouldBeInstanceOf<Title.Local>()
        Title.parse("a".repeat(256)).shouldBeInstanceOf<Title.Invalid>().reason shouldBe
            Title.Invalid.Reason.TOO_LONG
        Title.parse("é".repeat(200)).shouldBeInstanceOf<Title.Invalid>().reason shouldBe
            Title.Invalid.Reason.TOO_LONG
    }

    @Test
    fun `a namespace prefix inside a namespace is ordinary page text`() {
        val title = Title.parse("Category:Template:Foo").shouldBeInstanceOf<Title.Local>()
        title.namespace shouldBe Namespace.CATEGORY
        title.text shouldBe "Template:Foo"
    }

    @Test
    fun `a talk page of another namespace must not be written under Talk`() {
        Title.parse("Talk:Category:Foo").shouldBeInstanceOf<Title.Invalid>().reason shouldBe
            Title.Invalid.Reason.TALK_OF_NON_MAIN
    }

    @Test
    fun `a namespace prefix wins over an interwiki prefix of the same name`() {
        val iw = InterwikiMap(listOf("template"), selfPrefixes = emptyList())
        val title = Title.parse("Template:col", interwiki = iw).shouldBeInstanceOf<Title.Local>()
        title.namespace shouldBe Namespace.TEMPLATE
        title.text shouldBe "Col"
    }

    @Test
    fun `a prefix naming this wiki is stripped and the rest parsed locally`() {
        val iw = InterwikiMap(listOf("w", "wikt"), selfPrefixes = listOf("wikt"))
        val plain = Title.parse("wikt:volcano", interwiki = iw).shouldBeInstanceOf<Title.Local>()
        plain.namespace shouldBe Namespace.MAIN
        plain.text shouldBe "Volcano"

        val prefixed = Title.parse("wikt:Category:Foo", interwiki = iw).shouldBeInstanceOf<Title.Local>()
        prefixed.namespace shouldBe Namespace.CATEGORY
        prefixed.text shouldBe "Foo"
    }

    @Test
    fun `an interwiki prefix with an empty target stays interwiki`() {
        val title = Title.parse("w:", interwiki = enwiktionary).shouldBeInstanceOf<Title.Interwiki>()
        title.prefix shouldBe "w"
        title.rest shouldBe ""
    }

    @Test
    fun `a colon that names nothing stays part of the title`() {
        val title = Title.parse("Nineteen Eighty-Four: A Novel").shouldBeInstanceOf<Title.Local>()
        title.namespace shouldBe Namespace.MAIN
        title.text shouldBe "Nineteen Eighty-Four: A Novel"
    }

    @Test
    fun `a namespace prefix with nothing after it is invalid`() {
        Title.parse("Category:").shouldBeInstanceOf<Title.Invalid>().reason shouldBe
            Title.Invalid.Reason.EMPTY
    }

    @Test
    fun `rendering round-trips through the parser`() {
        val title = Title.parse("category:english_lemmas").shouldBeInstanceOf<Title.Local>()
        title.toString() shouldBe "Category:English lemmas"
        Title.parse(title.toString()) shouldBe title
    }

    @Test
    fun `a custom namespace renders by number rather than guessing a name`() {
        Title.Local(Namespace(118), "Proto-Indo-European/wed-").toString() shouldBe
            "ns118:Proto-Indo-European/wed-"
    }

    @Test
    fun `default namespace applies only when no prefix is given`() {
        Title.parse("col", defaultNamespace = Namespace.TEMPLATE)
            .shouldBeInstanceOf<Title.Local>()
            .namespace shouldBe Namespace.TEMPLATE
        Title.parse("Category:Foo", defaultNamespace = Namespace.TEMPLATE)
            .shouldBeInstanceOf<Title.Local>()
            .namespace shouldBe Namespace.CATEGORY
    }
}
