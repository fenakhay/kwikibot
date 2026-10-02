package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.net.auth.Identity
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject

class TransportListenerTest {

    private class Recording(private val name: String, private val heard: MutableList<String>) :
        TransportListener {
        override fun onResponse(request: ApiRequest, response: JsonObject) {
            heard += "$name response ${request.action}"
        }

        override fun onRetry(request: ApiRequest, attempt: Int, wait: Duration, reason: String) {
            heard += "$name retry $attempt $wait $reason"
        }

        override fun onPenalty(wait: Duration) {
            heard += "$name penalty $wait"
        }

        override fun onRelogin(identity: Identity) {
            heard += "$name relogin ${identity.name}"
        }
    }

    @Test
    fun `listeners joined together each hear everything, in order`() {
        val heard = mutableListOf<String>()
        val both = TransportListener.of(Recording("a", heard), Recording("b", heard))
        val request = ApiRequest.of("query")

        both.onResponse(request, JsonObject(emptyMap()))
        both.onRetry(request, 1, 2.seconds, "rate limited")
        both.onPenalty(30.seconds)
        both.onRelogin(Identity("Bot", 1))

        heard shouldBe
            listOf(
                "a response query",
                "b response query",
                "a retry 1 2s rate limited",
                "b retry 1 2s rate limited",
                "a penalty 30s",
                "b penalty 30s",
                "a relogin Bot",
                "b relogin Bot",
            )
    }

    @Test
    fun `the listener that hears nothing ignores everything`() {
        val none = TransportListener.NONE

        none.onResponse(ApiRequest.of("query"), JsonObject(emptyMap()))
        none.onRetry(ApiRequest.of("query"), 1, 1.seconds, "x")
        none.onPenalty(1.seconds)
        none.onRelogin(Identity("Bot", 1))
    }

    @Test
    fun `an account with high limits names ten times as many titles`() {
        Identity("Bot", 1, rights = setOf("apihighlimits")).batchLimit shouldBe 500
        Identity("Someone", 2).batchLimit shouldBe 50
    }
}
