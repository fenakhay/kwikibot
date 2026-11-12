package com.fenakhay.kwikibot.bindings.wikitext

import com.fenakhay.kwikibot.wikitext.TextScope
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Where a binding searches text, and which prefixes it reads category links by. */
class TextScopesTest {

    @Test
    fun `with nothing given the scope is the prose`() {
        textScopeOf() shouldBe TextScope.PROSE
    }

    @Test
    fun `each part given takes its place in the prose`() {
        val prose = TextScope.PROSE
        textScopeOf(
            templates = !prose.templates,
            linkTargets = !prose.linkTargets,
            comments = !prose.comments,
            rawTags = !prose.rawTags,
            headings = !prose.headings,
            tagAttributes = !prose.tagAttributes,
        ) shouldBe
            TextScope(
                !prose.templates,
                !prose.linkTargets,
                !prose.comments,
                !prose.rawTags,
                !prose.headings,
                !prose.tagAttributes,
            )
    }

    @Test
    fun `a scope is named prose or everywhere`() {
        textScopeNamed("prose") shouldBe TextScope.PROSE
        textScopeNamed("everywhere") shouldBe TextScope.EVERYWHERE
        assertFailsWith<IllegalArgumentException> { textScopeNamed("nowhere") }.message shouldBe
            "unknown scope 'nowhere'; use \"prose\" or \"everywhere\""
    }

    @Test
    fun `category links are read by Category unless other prefixes are given`() {
        categoryPrefixesOf(null) shouldBe setOf("Category")
        categoryPrefixesOf(listOf("Catégorie", "Category")) shouldBe setOf("Catégorie", "Category")
    }
}
