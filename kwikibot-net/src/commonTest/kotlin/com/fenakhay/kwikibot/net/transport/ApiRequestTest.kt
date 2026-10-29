package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.net.RequestKind
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ApiRequestTest {

    @Test
    fun `a request without an action is refused`() {
        assertFailsWith<IllegalArgumentException> { ApiRequest(mapOf("titles" to "volcano")) }
    }

    @Test
    fun `a parameter given no value is left out`() {
        ApiRequest.of("query", "titles" to "volcano", "continue" to null).params shouldBe
            mapOf("action" to "query", "titles" to "volcano")
    }

    @Test
    fun `a write or anything carrying a secret must be posted`() {
        ApiRequest.of("query", "titles" to "volcano").requiresPost shouldBe false
        ApiRequest.of("edit", "title" to "Foo", kind = RequestKind.WRITE).requiresPost shouldBe true
        ApiRequest.of("login", "lgname" to "FenaBot", "lgpassword" to "p").requiresPost shouldBe true
        ApiRequest.of("query", "watchlisttoken" to "t").requiresPost shouldBe true
    }
}
