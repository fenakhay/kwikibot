package com.fenakhay.kwikibot.net.transport

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CorsOriginTest {

    private val wiki = ApiEndpoint("en.wiktionary.org")

    @Test
    fun `outside a browser a request names no origin`() {
        corsOrigin(null, wiki).shouldBeNull()
    }

    @Test
    fun `a page on the wiki's own site names no origin and so sends the reader's session`() {
        corsOrigin("https://en.wiktionary.org", wiki).shouldBeNull()
    }

    @Test
    fun `a page on another site asks to be answered anonymously`() {
        corsOrigin("https://example.org", wiki) shouldBe "*"
    }

    @Test
    fun `the same host over another scheme is another site`() {
        corsOrigin("https://localhost:8080", ApiEndpoint("localhost:8080", secure = false)) shouldBe "*"
        corsOrigin("http://localhost:8080", ApiEndpoint("localhost:8080", secure = false)).shouldBeNull()
    }
}
