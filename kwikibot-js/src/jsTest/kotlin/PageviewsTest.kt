import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.js.Date
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class PageviewsTest {

    private val contact = "https://example.org/TestBot"
    private val asked = mutableListOf<HttpRequestData>()

    private val counts =
        """{"items":[{"project":"en.wikipedia","article":"AC/DC","granularity":"monthly","timestamp":"2024010100",
           "access":"mobile-web","agent":"spider","views":10336}]}"""

    private val ranked =
        """{"items":[{"project":"en.wikipedia","access":"all-access","year":"2024","month":"01","day":"all-days",
           "articles":[{"article":"Main_Page","views":152225418,"rank":1}]}]}"""

    private fun engine() = MockEngine { request ->
        asked += request
        val body = if ("/top/" in request.url.encodedPath) ranked else counts
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val january = Date("2024-01-01T00:00:00Z")
    private val march = Date("2024-03-31T00:00:00Z")

    @Test
    fun `a page's views are asked for with the literals given and read back as them`() = runTest {
        val pageviews = pageviews(contact, null, engine())

        val count =
            pageviews
                .perArticle(
                    "en.wikipedia",
                    "AC/DC",
                    january,
                    march,
                    optionsOf("granularity" to "monthly", "access" to "mobile-web", "agent" to "spider"),
                )
                .await()
                .single()

        asked.single().url.encodedPath shouldContain "/en.wikipedia/mobile-web/spider/AC%2FDC/monthly/"
        count.timestamp.getTime() shouldBe january.getTime()
        count.views shouldBe 10336.0
        count.project shouldBe "en.wikipedia"
        count.access.unsafeCast<String>() shouldBe "mobile-web"
        count.agent.unsafeCast<String>() shouldBe "spider"
        count.granularity.unsafeCast<String>() shouldBe "monthly"
        count.title shouldBe "AC/DC"
        pageviews.close()
    }

    @Test
    fun `a project's views and its most viewed pages use the defaults unless told`() = runTest {
        val pageviews = pageviews(contact, optionsOf("baseUrl" to "https://aqs.test"), engine())

        pageviews.aggregate("en.wikipedia", january, march).await().single().title shouldBe null
        val top =
            pageviews.top("en.wikipedia", january, optionsOf("granularity" to "monthly")).await().single()

        asked.map { it.url.toString() } shouldBe
            listOf(
                "https://aqs.test/aggregate/en.wikipedia/all-access/user/daily/2024010100/2024033100",
                "https://aqs.test/top/en.wikipedia/all-access/2024/01/all-days",
            )
        listOf(top.title, top.views, top.rank) shouldBe listOf("Main Page", 152225418.0, 1)
        pageviews.firstDay.toISOString() shouldBe "2015-07-01T00:00:00.000Z"
        pageviews.projectOf("en.wikipedia.org") shouldBe "en.wikipedia"
        pageviews.close()
    }

    @Test
    fun `a literal the API does not take is refused saying which it could be`() = runTest {
        val pageviews = pageviews(contact, null, engine())

        val refused =
            shouldThrow<IllegalArgumentException> {
                pageviews.aggregate("en.wikipedia", january, march, optionsOf("agent" to "robot")).await()
            }

        refused.message.orEmpty() shouldContain "'all-agents'"
        pageviews.close()
    }

    @Test
    fun `a wiki that is not Wikimedia's has no pageviews`() = runTest {
        val wiki = MockWiki().open()
        val pageviews = pageviews(contact, null, engine())

        shouldThrow<IllegalArgumentException> {
            pageviews.ofPages(wiki, arrayOf("Volcano"), january, march).await()
        }
        asked.size shouldBe 0
        pageviews.close()
        wiki.close()
    }
}
