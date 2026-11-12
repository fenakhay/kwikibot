package com.fenakhay.kwikibot.bindings.fleet

import com.fenakhay.kwikibot.bindings.QUICK
import com.fenakhay.kwikibot.bot.CommonOptions
import com.fenakhay.kwikibot.bot.run.StopPolicy
import com.fenakhay.kwikibot.fleet.Accounts
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

/** A fleet's defaults: where its wiki is, how it starts runs, what stops it, and what it records. */
class FleetsTest {

    @Test
    fun `a fleet's wiki is under w unless told and a run starts with the default flags unless given`() {
        fleetEndpointOf("example.org") shouldBe ApiEndpoint("example.org", "/w")
        fleetEndpointOf("example.org", "") shouldBe ApiEndpoint("example.org", "")
        runOptionsOf() shouldBe CommonOptions()
        runOptionsOf(CommonOptions(save = true)) shouldBe CommonOptions(save = true)
    }

    @Test
    fun `a fleet is built with nothing given and with everything given`() {
        val accounts = Accounts(QUICK, credentials = { Credentials.Anonymous })
        val scope = CoroutineScope(SupervisorJob())
        val endpoint = fleetEndpointOf("example.org")
        val plain = fleetWithDefaults(emptyList(), accounts, endpoint, scope, { _, _ -> false }, {})
        plain.registry.all shouldBe emptyList()
        val given =
            fleetWithDefaults(
                emptyList(),
                accounts,
                endpoint,
                scope,
                { _, _ -> true },
                {},
                stop = StopPolicy.NONE,
                directory = Path("runs"),
                clock = Clock.System,
                heartbeat = 1.seconds,
            )
        given.registry.all shouldBe emptyList()
        scope.cancel()
        accounts.close()
    }

    @Test
    fun `a recording context is stopped by nothing with the default flags and no arguments unless told`() {
        val wiki = FakeWiki()
        val plain = recordingContextWithDefaults(wiki, { false })
        plain.stopPolicy shouldBe StopPolicy.NONE
        plain.options shouldBe CommonOptions()
        plain.arguments shouldBe emptyList()
        plain.directory shouldBe null
        val stop = StopPolicy { true }
        val given =
            recordingContextWithDefaults(
                wiki,
                { true },
                stop,
                CommonOptions(limit = 2),
                listOf("a"),
                Path("d"),
            )
        given.stopPolicy shouldBe stop
        given.options shouldBe CommonOptions(limit = 2)
        given.arguments shouldBe listOf("a")
        given.directory shouldBe Path("d")
    }

    @Test
    fun `a shared stop reads its text and lets bots run while it says false`() = runTest {
        sharedStopWithDefaults({ "false" }).mayContinue() shouldBe true
        sharedStopWithDefaults({ "true" }, 1.seconds, 2.seconds, Clock.System).mayContinue() shouldBe false
    }
}
