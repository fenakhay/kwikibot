package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

/** Wikimedia's own pageviews API, for pages whose titles need escaping and for a page nobody read. */
@Tag("live")
class LivePageviewsTest {

    private val userAgent = UserAgent("kwikibot-livetest", "0.1.0", "https://github.com/fenakhay/kwikibot")

    private val first = Instant.parse("2024-01-01T00:00:00Z")
    private val third = Instant.parse("2024-01-03T00:00:00Z")

    @Test
    fun `a real page's views arrive one count a day`() =
        runBlocking<Unit> {
            WikiHttpClient.create().use { http ->
                val pageviews = Pageviews(http, userAgent)

                for (title in
                    listOf("Albert Einstein", "AC/DC", "Zürich", "Who Wants to Be a Millionaire?")) {
                    val views =
                        pageviews.perArticle(
                            "en.wikipedia",
                            title,
                            first,
                            third,
                            agent = PageviewAgent.ALL_AGENTS,
                        )

                    views.map { it.timestamp } shouldBe
                        listOf(first, Instant.parse("2024-01-02T00:00:00Z"), third)
                    views.map { it.title }.toSet() shouldBe setOf(title)
                    views.sumOf { it.views } shouldBeGreaterThan 0L
                }

                pageviews
                    .perArticle("en.wikipedia", "Kwikibot has no page here", first, third)
                    .shouldBeEmpty()
            }
        }
}
