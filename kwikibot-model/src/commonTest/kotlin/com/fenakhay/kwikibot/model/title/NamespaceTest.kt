package com.fenakhay.kwikibot.model.title

import com.fenakhay.kwikibot.model.WikiError
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class NamespaceTest {

    @Test
    fun `odd numbered namespaces are talk spaces`() {
        Namespace.TALK.isTalk shouldBe true
        Namespace.CATEGORY_TALK.isTalk shouldBe true
        Namespace.MAIN.isTalk shouldBe false
        Namespace.CATEGORY.isTalk shouldBe false
    }

    @Test
    fun `virtual namespaces are neither talk nor subject spaces`() {
        Namespace.SPECIAL.isVirtual shouldBe true
        Namespace.SPECIAL.isTalk shouldBe false
        Namespace.SPECIAL.talkSpace.shouldBeNull()
        Namespace.MEDIA.subjectSpace.shouldBeNull()
    }

    @Test
    fun `talk and subject spaces pair up`() {
        Namespace.MAIN.talkSpace shouldBe Namespace.TALK
        Namespace.TEMPLATE.talkSpace shouldBe Namespace.TEMPLATE_TALK
        Namespace.TEMPLATE_TALK.subjectSpace shouldBe Namespace.TEMPLATE
    }

    @Test
    fun `talk space of a talk space is itself`() {
        Namespace.USER_TALK.talkSpace shouldBe Namespace.USER_TALK
        Namespace.USER.subjectSpace shouldBe Namespace.USER
    }

    @Test
    fun `custom namespaces follow the same numbering rule`() {
        val gadget = Namespace(2300)
        gadget.isTalk shouldBe false
        gadget.talkSpace shouldBe Namespace(2301)
    }

    @Test
    fun `namespaces sort by number the virtual ones first`() {
        listOf(Namespace.CATEGORY, Namespace.MAIN, Namespace.SPECIAL, Namespace.TALK).sorted() shouldBe
            listOf(Namespace.SPECIAL, Namespace.MAIN, Namespace.TALK, Namespace.CATEGORY)
    }

    @Test
    fun `a namespace prints as its number since its name depends on the wiki`() {
        Namespace.CATEGORY.toString() shouldBe "ns:14"
    }

    @Test
    fun `a namespace answers to its local name its canonical name and its aliases`() {
        val project = NamespaceInfo(Namespace.PROJECT, "Project", "Wiktionary", aliases = listOf("WT"))

        project.prefixes shouldBe listOf("Wiktionary", "Project", "WT")
        NamespaceMap(listOf(project)).byPrefix("wt")?.id shouldBe Namespace.PROJECT
    }

    @Test
    fun `a name given twice or not at all is not a prefix`() {
        NamespaceInfo(Namespace.CATEGORY, "Category", "Category").prefixes shouldBe listOf("Category")
        NamespaceInfo(Namespace.MAIN, "", "").prefixes shouldBe emptyList()
        NamespaceInfo(Namespace(100), null, null, aliases = listOf("P")).prefixes shouldBe listOf("P")
    }

    @Test
    fun `a title is formatted with the wiki's own name for its namespace`() {
        val polish =
            NamespaceMap(
                listOf(
                    NamespaceInfo(Namespace.MAIN, "", ""),
                    NamespaceInfo(Namespace.CATEGORY, "Category", "Kategoria"),
                )
            )

        polish.format(Title.Local(Namespace.CATEGORY, "Języki")) shouldBe "Kategoria:Języki"
        polish.format(Title.Local(Namespace.MAIN, "kot", "Polski")) shouldBe "kot#Polski"
    }

    @Test
    fun `a title in a namespace the wiki does not have is refused not sent as a main-namespace title`() {
        val error =
            assertFailsWith<WikiError.Page.BadTitle> {
                NamespaceMap.CANONICAL.format(Title.Local(Namespace(828), "sa-decl"))
            }

        error.title shouldBe Title.Invalid("ns828:sa-decl", Title.Invalid.Reason.UNKNOWN_NAMESPACE)
    }

    @Test
    fun `a namespace without a local name is written with its canonical one or refused if it has none`() {
        val wiki =
            NamespaceMap(
                listOf(
                    NamespaceInfo(Namespace.MAIN, "", ""),
                    NamespaceInfo(Namespace(828), "Module", null),
                    NamespaceInfo(Namespace(100), null, null, aliases = listOf("P")),
                )
            )

        wiki.format(Title.Local(Namespace(828), "sa-decl")) shouldBe "Module:sa-decl"
        assertFailsWith<WikiError.Page.BadTitle> { wiki.format(Title.Local(Namespace(100), "x")) }
    }
}
