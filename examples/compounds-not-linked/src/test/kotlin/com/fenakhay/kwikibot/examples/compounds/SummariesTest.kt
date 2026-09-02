package com.fenakhay.kwikibot.examples.compounds

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class SummariesTest {

    @Test
    fun `a summary counts the terms and names the todo page`() {
        val summary = Summaries.forEdit(listOf("vog", "volcanology"), emptySet())

        summary shouldBe "Bot: add 2 derived terms ([[${Summaries.TODO_PAGE}]])"
    }

    @Test
    fun `one term is singular`() {
        Summaries.forEdit(listOf("vog"), emptySet()) shouldContain "add 1 derived term ("
    }

    @Test
    fun `rewriting the list says so`() {
        val summary =
            Summaries.forEdit(
                listOf("vog"),
                setOf(TransformResult.Rule.NORMALIZE_CONTAINER),
            )

        summary shouldContain "; normalize list to {{col}}"
    }

    @Test
    fun `a plain addition does not claim a normalization`() {
        val summary =
            Summaries.forEdit(
                listOf("vog"),
                setOf(TransformResult.Rule.ADD_DERIVED_TERMS),
            )

        summary.contains("normalize") shouldBe false
    }
}
