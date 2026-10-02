package com.fenakhay.kwikibot.testkit

import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.protocol.ApiWarning
import com.fenakhay.kwikibot.protocol.ApiWarningListener
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class FailOnDeprecationTest {

    private val request = ApiRequest.of("parse", "prop" to "sections")

    @Test
    fun `a deprecation fails the request, naming what drew it`() {
        val failure =
            assertFailsWith<AssertionError> {
                FailOnDeprecation()
                    .onWarning(request, ApiWarning("parse", "prop=sections is deprecated.", "deprecation"))
            }

        failure.message.orEmpty() shouldContain "action=parse"
        failure.message.orEmpty() shouldContain "prop=sections is deprecated."
    }

    @Test
    fun `any other warning is passed on rather than failing`() {
        val heard = mutableListOf<ApiWarning>()
        val strict = FailOnDeprecation(others = ApiWarningListener { _, warning -> heard += warning })

        strict.onWarning(request, ApiWarning("query", "Truncated.", "truncatedresult"))

        heard.single().code shouldBe "truncatedresult"
    }

    @Test
    fun `the advice that follows a deprecation is not mistaken for one`() {
        val response =
            Json.parseToJsonElement(
                    """{"warnings":[{"code":"deprecation-help","module":"main","text":"Subscribe."}]}"""
                )
                .jsonObject

        FailOnDeprecation(others = ApiWarningListener.NONE).onResponse(request, response)
    }
}
