package com.fenakhay.kwikibot.net.transport

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ApiEndpointTest {

    @Test
    fun `a wikimedia project keeps its entry points under w`() {
        val endpoint = ApiEndpoint.wikimedia("en", "wiktionary.org")

        endpoint.apiUrl shouldBe "https://en.wiktionary.org/w/api.php"
        endpoint.indexUrl shouldBe "https://en.wiktionary.org/w/index.php"
        endpoint.articleUrl("Wiktionary:Main Page") shouldBe
            "https://en.wiktionary.org/wiki/Wiktionary:Main_Page"
    }

    @Test
    fun `a local wiki can be spoken to over plain HTTP, from the root`() {
        val local = ApiEndpoint("localhost:8080", scriptPath = "", secure = false)

        local.apiUrl shouldBe "http://localhost:8080/api.php"
    }

    @Test
    fun `an endpoint that could not name a wiki is refused`() {
        assertFailsWith<IllegalArgumentException> { ApiEndpoint(" ") }
        assertFailsWith<IllegalArgumentException> { ApiEndpoint("https://en.wiktionary.org") }
        assertFailsWith<IllegalArgumentException> { ApiEndpoint("en.wiktionary.org", scriptPath = "w") }
    }
}
