import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class ProofreadServiceTest {

    @Test
    fun `the service reads qualities, indexes and pages`() = runTest {
        val mock = MockWiki().apply { extensions += "ProofreadPage" }
        val wiki = mock.open()
        mock.canned["proofread"] =
            """{"query":{"pages":[{"pageid":1,"ns":104,"title":"Page:Book/1",
               "proofread":{"quality":3,"index":"Book"}}]}}"""
        mock.canned["embeddedin"] = """{"query":{"embeddedin":[{"ns":104,"title":"Page:Book/1"}]}}"""

        val found = wiki.proofread.quality(arrayOf("Page:Book/1")).await().single()
        listOf(found.title, found.quality, found.level) shouldBe listOf("Page:Book/1", "proofread", 3)

        wiki.proofread.indexOf("Page:Book/1").await() shouldBe "Index:Book"

        wiki.proofread.pagesOf("Index:Book").await().toList() shouldBe listOf("Page:Book/1")
        mock.requests.last { it["list"] == "embeddedin" }["eititle"] shouldBe "Index:Book"
        wiki.close()
    }

    @Test
    fun `without the extension it rejects`() = runTest {
        val wiki = MockWiki().open()
        shouldThrow<Throwable> { wiki.proofread.indexOf("Page:Book/1").await() }.message shouldContain
            "ProofreadPage"
        wiki.close()
    }

    @Test
    fun `a page text splits and writes back as it was`() {
        val written =
            """<noinclude><pagequality level="3" user="Alice" />head</noinclude>body<noinclude>foot</noinclude>"""

        val page = parseProofreadText(written)

        listOf(page.header, page.body, page.footer, page.quality, page.proofreader) shouldBe
            listOf("head", "body", "foot", "proofread", "Alice")
        page.qualityLevel shouldBe 3
        page.serialize() shouldBe written
        page.withBody("new").body shouldBe "new"
        page.withBody("new").serialize() shouldContain "</noinclude>new<noinclude>"
    }

    @Test
    fun `a page text can be built, and a quality looked up`() {
        val page = ProofreadText("h", "b", "f", literal("validated"), "Bob")

        page.qualityLevel shouldBe 4
        ProofreadText("h", "b", "f").quality shouldBe null
        shouldThrow<IllegalArgumentException> { ProofreadText("h", "b", "f", literal("perfect")) }
            .message shouldContain "'validated'"
        proofreadQualityOf(0) shouldBe "without-text"
        proofreadQualityOf(9) shouldBe null
        parseProofreadText("plain").body shouldBe "plain"
    }
}
