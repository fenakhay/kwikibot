import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFails
import org.khronos.webgl.Uint8Array

class DumpTest {

    private val xml =
        "<mediawiki><page><title>Volcano</title><ns>0</ns><id>1</id><revision><id>2</id>" +
            "<timestamp>2026-10-01T12:00:00Z</timestamp><contributor><username>U</username></contributor>" +
            "<comment>c</comment><text>lava</text></revision></page>" +
            "<page><title>Talk:Volcano</title><ns>1</ns><redirect title=\"Volcano\" /></page></mediawiki>"

    private val bytes: Uint8Array = js("new TextEncoder()").encode(xml).unsafeCast<Uint8Array>()

    @Test
    fun `a dump in memory reads as pages`() {
        val pages = dumpPagesOf(bytes)

        pages.map { it.title } shouldBe listOf("Volcano", "Talk:Volcano")
        val first = pages.first()
        listOf(first.namespace, first.pageId, first.revisionId) shouldBe listOf<Any>(0, 1.0, 2.0)
        first.text shouldBe "lava"
        first.contributor shouldBe "U"
        first.comment shouldBe "c"
        first.timestamp?.toISOString() shouldBe "2026-10-01T12:00:00.000Z"
        first.isRedirect shouldBe false
        first.redirectTarget shouldBe null
        pages.last().redirectTarget shouldBe "Volcano"
        dumpPagesOf(bytes, namespaces = arrayOf(1)).single().title shouldBe "Talk:Volcano"
    }

    @Test
    fun `a compression nobody has heard of is refused and so is a damaged dump`() {
        assertFails { dumpPagesOf(bytes, literal("zip")) }
        assertFails { dumpPagesOf(bytes, literal("bzip2")) }
        assertFails { dumpPagesOf(bytes, literal("gzip")) }
    }
}
