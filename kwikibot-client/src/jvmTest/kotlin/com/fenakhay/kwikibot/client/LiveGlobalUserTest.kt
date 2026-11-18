package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.client.service.ExtensionService
import com.fenakhay.kwikibot.client.service.GlobalUserProperty
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.testkit.FailOnDeprecation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

/** CentralAuth on Wikimedia, read without logging in, for an account that has been there since 2008. */
@Tag("live")
class LiveGlobalUserTest {

    private val config =
        WikiConfig(
            userAgent = UserAgent("kwikibot-dev-check", "0.1.0", "https://github.com/fenakhay/kwikibot"),
            throttle = Throttle(read = 500.milliseconds),
            onWarning = FailOnDeprecation(),
        )

    @Test
    fun `a long-standing account reads as CentralAuth describes it and a missing one is null`(): Unit =
        runBlocking {
            WikiClient(config).use { client ->
                val wiki = client.wiki(LangCode("en"), Family.WIKIPEDIA)
                wiki.extensions.has(ExtensionService.CENTRAL_AUTH) shouldBe true

                val cheap = wiki.extensions.globalUser("Jimbo Wales").shouldNotBeNull()

                cheap.name shouldBe "Jimbo Wales"
                cheap.id shouldBe 1843L
                cheap.home shouldBe WikiId("enwiki")
                cheap.registration shouldBe Instant.parse("2008-04-12T16:22:49Z")
                cheap.groups shouldContain "founder"
                cheap.editCount.shouldNotBeNull() shouldBeGreaterThan 10_000L
                cheap.merged.shouldBeEmpty()

                val full =
                    wiki.extensions
                        .globalUser(
                            "Jimbo Wales",
                            setOf(GlobalUserProperty.MERGED, GlobalUserProperty.UNATTACHED),
                        )
                        .shouldNotBeNull()

                full.editCount.shouldBeNull()
                full.merged.size shouldBeGreaterThan 100
                val home = full.merged.single { it.wiki == WikiId("enwiki") }
                home.editCount shouldBeGreaterThan 10_000L
                home.url shouldBe "https://en.wikipedia.org"
                home.attached.shouldNotBeNull()

                wiki.extensions.globalUser("Kwikibot has no account by this name").shouldBeNull()
            }
        }
}
