package com.fenakhay.kwikibot.model.edit

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ActionChecksTest {

    private val checks = ActionChecks(mapOf("edit" to emptyList(), "move" to listOf("protectedpage")))

    @Test
    fun `an action with no refusals is allowed, and a refused one says why`() {
        checks.allows("edit") shouldBe true
        checks.reasons("edit") shouldBe emptyList()
        checks.allows("move") shouldBe false
        checks.reasons("move") shouldBe listOf("protectedpage")
    }

    @Test
    fun `an action that was never tested is not taken as allowed`() {
        checks.allows("delete") shouldBe false
        checks.reasons("delete") shouldBe emptyList()
    }
}
