import com.fenakhay.kwikibot.client.model.dump.DumpFormatException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertFails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    private fun written(name: String, bytes: Uint8Array): String {
        val os: dynamic = js("process.getBuiltinModule('os')")
        val fs: dynamic = js("process.getBuiltinModule('fs')")
        val path = "${fs.mkdtempSync(os.tmpdir() + "/kwikibot-dump-")}/$name"
        fs.writeFileSync(path, bytes)
        return path
    }

    @Test
    fun `a dump cut short rejects the walk after the pages before the cut`() = runTest {
        if (!hasFileSystem()) return@runTest
        val whole = js("new TextEncoder()").encode(xml).unsafeCast<Uint8Array>()
        val path = written("cut.xml", whole.subarray(0, whole.length - 5))
        val iterator = dumpPages(path, workers = 2).asDynamic()[js("Symbol.asyncIterator")]()

        val titles = mutableListOf<String>()
        val failure =
            shouldThrow<DumpFormatException> {
                withContext(Dispatchers.Default) {
                    withTimeout(20_000) {
                        while (true) {
                            val step = iterator.next().unsafeCast<Promise<dynamic>>().await()
                            if (step.done == true) break
                            delay(20)
                            titles += step.value.unsafeCast<DumpPage>().title
                        }
                    }
                }
            }

        failure.code shouldBe DumpFormatException.Code.UNEXPECTED_EOF
        titles shouldBe listOf("Volcano", "Talk:Volcano")
    }

    @Test
    fun `a scan of a dump cut short rejects`() = runTest {
        if (!hasFileSystem()) return@runTest
        val whole = js("new TextEncoder()").encode(xml).unsafeCast<Uint8Array>()
        val path = written("cut-scan.xml", whole.subarray(0, whole.length - 5))
        val titles = mutableListOf<String>()

        shouldThrow<DumpFormatException> {
            scanDump(
                    path,
                    { page ->
                        titles += page.title
                        untyped(null)
                    },
                    workers = 2,
                )
                .await()
        }
        titles.sorted() shouldBe listOf("Talk:Volcano", "Volcano")
    }
}
