package com.fenakhay.kwikibot.bindings.connect

import com.fenakhay.kwikibot.bindings.common.Setting
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.net.Kwikibot
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.cache.ResponseCache
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.HttpSettings
import com.fenakhay.kwikibot.net.transport.TransportListener
import com.fenakhay.kwikibot.protocol.ApiWarningListener
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How a binding opens wikis: what it configures with nothing given, and with everything given. */
class ConnectTest {

    private val contact = "bot@example.org"
    private val library = UserAgent(Kwikibot.NAME, Kwikibot.version, contact)
    private val same = Throttle()

    @Test
    fun `with nothing given a wiki opens with the library's defaults and its own name`() {
        userAgent(contact) shouldBe library
        val config = configOf(contact)
        config.copy(throttle = same) shouldBe WikiConfig(library, throttle = same)
        listOf(config.throttle.read, config.throttle.write) shouldBe
            listOf(Throttle.DEFAULT_READ, Throttle.DEFAULT_WRITE)
        val throttle = throttleOf()
        listOf(throttle.read, throttle.write) shouldBe listOf(Throttle.DEFAULT_READ, Throttle.DEFAULT_WRITE)
        retryOf() shouldBe RetryPolicy()
        httpOf() shouldBe HttpSettings()
    }

    @Test
    fun `every setting given reaches the configuration`() {
        val agent = UserAgent("MyBot", "2.0", contact)
        val throttle = throttleOf(50.milliseconds, 2.seconds)
        val retry = retryOf(1, 10.milliseconds, 20.milliseconds)
        val http = httpOf(2, 3, 4.seconds)
        val warnings = ApiWarningListener { _, _ -> }
        val listener = object : TransportListener {}
        val config =
            configOf(
                contact,
                agent,
                throttle,
                retry,
                Setting.Value(9),
                ResponseCache.NONE,
                warnings,
                1,
                http,
                listener,
            )
        config shouldBe WikiConfig(agent, throttle, retry, 9, ResponseCache.NONE, warnings, 1, http, listener)
        userAgent(contact, agent) shouldBe agent
        throttle.read shouldBe 50.milliseconds
        throttle.write shouldBe 2.seconds
        retry shouldBe RetryPolicy(1, 10.milliseconds, 20.milliseconds)
        http shouldBe HttpSettings(2, 3, 4.seconds)
    }

    @Test
    fun `maxlag is the default unless given and left out when turned off`() {
        configOf(contact, maxlag = Setting.Default).maxlag shouldBe WikiConfig(library).maxlag
        configOf(contact, maxlag = Setting.Off).maxlag shouldBe null
        configOf(contact, maxlag = Setting.Value(1)).maxlag shouldBe 1
    }

    @Test
    fun `a wiki is under w over HTTPS unless told`() {
        endpointOf("example.org") shouldBe ApiEndpoint("example.org", "/w", true)
        endpointOf("example.org", "", false) shouldBe ApiEndpoint("example.org", "", false)
    }
}
