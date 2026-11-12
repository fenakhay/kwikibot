import com.fenakhay.kwikibot.protocol.ApiWarning as ClientApiWarning
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertFails
import kotlinx.coroutines.test.runTest

class ProtocolTest {

    private val mock = MockWiki()

    @Test
    fun `a query this library does not model is walked through its continues`() = runTest {
        val wiki = mock.open()
        mock.canned["protectedtitles"] = """{"query":{"protectedtitles":[{"title":"A"},{"title":"B"}]}}"""
        val params: Record<String, ApiValue> =
            optionsOf("action" to "query", "list" to "protectedtitles", "ptlimit" to 2)

        val batch = wiki.callPaged(params, maxBatches = 1).take().single().asDynamic()
        (batch.query.protectedtitles.length as Int) shouldBe 2
        mock.requests.last()["ptlimit"] shouldBe "2"

        wiki.callPaged(params, list = "protectedtitles").take().map {
            it.asDynamic().title as String
        } shouldBe listOf("A", "B")

        mock.canned["links"] = """{"query":{"pages":[{"ns":0,"title":"Volcano","links":[]}]}}"""
        val pagesParams: Record<String, ApiValue> = optionsOf("action" to "query", "prop" to "links")
        wiki.callPaged(pagesParams, pages = true).take().map { it.asDynamic().title as String } shouldBe
            listOf("Volcano")

        mock.canned["protectedtitles"] = """{"error":{"code":"nope","info":"no"}}"""
        shouldThrow<Throwable> { wiki.callPaged(params).take() }
        wiki.close()
    }

    @Test
    fun `an answer's entries read as this package's own values`() = runTest {
        val wiki = mock.open()
        val decoder = wiki.decoder
        val page =
            JSON.parse<Record<String, JsonValue>>(
                """{"pageid":1,"ns":0,"title":"Volcano","contentmodel":"wikitext","revisions":[
                   {"revid":7,"timestamp":"2026-08-31T21:43:26Z","slots":{"main":{"content":"ash"}}}]}"""
            )

        val existing = decoder.decodePage(page)
        existing.kind shouldBe "existing"
        existing.title shouldBe "Volcano"
        existing.contentModel shouldBe "wikitext"
        existing.isRedirect shouldBe false
        existing.latestRevision?.id shouldBe 7.0
        existing.content?.text shouldBe "ash"
        existing.reason shouldBe null
        decoder.decodePage(JSON.parse("""{"ns":0,"title":"Gone","missing":true}""")).kind shouldBe "missing"
        val invalid = decoder.decodePage(JSON.parse("""{"title":"[","invalid":true,"invalidreason":"bad"}"""))
        listOf(invalid.kind, invalid.title, invalid.reason) shouldBe listOf("invalid", "[", "bad")

        decoder.decodeContents(page).single().text shouldBe "ash"
        val redirect =
            JSON.parse<Record<String, JsonValue>>(
                """{"pageid":2,"ns":0,"title":"Fire","revisions":[{"revid":8,
                   "timestamp":"2026-08-31T21:43:26Z","slots":{"main":{"content":"#REDIRECT [[Volcano]]"}}}]}"""
            )
        decoder.decodeContents(redirect, { true }).single().redirectTarget shouldBe "Volcano"
        decoder.titleOf(page) shouldBe "Volcano"
        decoder
            .decodeRevision(JSON.parse("""{"revid":9,"timestamp":"2026-08-31T21:43:26Z","size":3}"""))
            .id shouldBe 9.0
        wiki.close()
    }

    @Test
    fun `the entries of a log, a user and their changes read as this package's own values`() = runTest {
        val wiki = mock.open()
        val decoder = wiki.decoder

        decoder
            .decodeLogEvent(
                JSON.parse(
                    """{"logid":1,"type":"delete","action":"delete","ns":0,"title":"Volcano",
                       "timestamp":"2026-08-31T21:43:26Z","params":{}}"""
                )
            )
            .page shouldBe "Volcano"
        decoder
            .decodeRecentChange(
                JSON.parse(
                    """{"type":"edit","ns":0,"title":"Volcano","rcid":1,"timestamp":"2026-08-31T21:43:26Z"}"""
                )
            )
            .page shouldBe "Volcano"
        decoder
            .decodeContribution(
                JSON.parse(
                    """{"userid":7,"user":"Alice","pageid":4,"revid":9,"parentid":8,"ns":0,"title":"Volcano",
                       "timestamp":"2026-08-31T21:43:26Z","size":30,"sizediff":30,"comment":"c","tags":[]}"""
                )
            )
            ?.page shouldBe "Volcano"
        decoder
            .decodeBlockRecord(
                JSON.parse(
                    """{"id":5,"user":"Bob","by":"Al","timestamp":"2026-08-01T00:00:00Z",
                       "expiry":"infinity"}"""
                )
            )
            .target shouldBe "Bob"
        decoder.decodeUser(JSON.parse("""{"userid":7,"name":"Alice","groups":[]}""")).name shouldBe "Alice"
        decoder.decodeCurrentUser(JSON.parse("""{"id":7,"name":"Alice","groups":[]}""")).name shouldBe "Alice"
        wiki.close()
    }

    @Test
    fun `an error in an answer can be read or thrown`() {
        val failed = JSON.parse<Record<String, JsonValue>>("""{"error":{"code":"nope","info":"not this"}}""")
        val fine = JSON.parse<Record<String, JsonValue>>("""{"query":{}}""")

        responseError(failed)?.kind shouldBe "api"
        responseError(failed)?.message.orEmpty() shouldContain "not this"
        responseError(fine) shouldBe null
        throwOnResponseError(fine) shouldBe fine
        describeError(shouldThrow<Throwable> { throwOnResponseError(failed) })?.kind shouldBe "api"
    }

    @Test
    fun `a claim, a snak and a value encode as Wikibase writes them`() {
        val value = entityValue("Q5")
        val snak = valueSnak("P31", value)

        decodeDataValue(encodeDataValue(value)).toString() shouldBe value.toString()
        decodeSnak(encodeSnak(snak)).property shouldBe "P31"
        decodeClaim(encodeClaim(claim(snak))).property shouldBe "P31"
    }

    @Test
    fun `small things the library knows can be asked`() {
        parseExpiry("infinity") shouldBe null
        parseExpiry("2026-08-31T21:43:26Z")?.getTime() shouldBe Date("2026-08-31T21:43:26Z").getTime()
        assertFails { parseExpiry("soon") }

        compareMediaWikiVersions("1.10", "1.9") shouldBe 1
        compareMediaWikiVersions("1.44.0-wmf.3", "1.44.0") shouldBe -1
        compareMediaWikiVersions("1.44", "1.44") shouldBe 0

        libraryRuntime().isNotEmpty() shouldBe true
        libraryPlatform().isNotEmpty() shouldBe true
        (availableDumpWorkers() > 0) shouldBe true

        listOf(DEFAULT_CONCURRENT_REQUESTS, DEFAULT_CONNECTIONS, DEFAULT_RELOGINS, MAX_GET_LENGTH) shouldBe
            listOf(3, 32, 3, 2000)
        listOf(DEFAULT_TIMEOUT, DEFAULT_READ_DELAY, DEFAULT_WRITE_DELAY) shouldBe
            listOf(60_000.0, 100.0, 10_000.0)
        DISK_CACHE_DEFAULT_TTL shouldBe 43_200_000.0
        DEPRECATION_HELP shouldBe "deprecation-help"
        listOf(PROTECTION_SYSOP, PROTECTION_AUTOCONFIRMED) shouldBe listOf("sysop", "autoconfirmed")
        listOf(
            CONTENT_MODEL_WIKITEXT,
            CONTENT_MODEL_JSON,
            CONTENT_MODEL_CSS,
            CONTENT_MODEL_JAVASCRIPT,
            CONTENT_MODEL_SCRIBUNTO,
            CONTENT_MODEL_SANITIZED_CSS,
        ) shouldBe listOf("wikitext", "json", "css", "javascript", "Scribunto", "sanitized-css")
    }

    @Test
    fun `a deprecation fails, and anything else goes on`() {
        val deprecation = ApiWarning(ClientApiWarning("query", "going away", "deprecation"))
        val other = ApiWarning(ClientApiWarning("query", "truncated", "truncatedresult"))
        val heard = mutableListOf<String>()

        shouldThrow<AssertionError> { failOnDeprecation()(deprecation) }.message.orEmpty() shouldContain
            "going away"
        failOnDeprecation { heard += it.text }(other)
        failOnDeprecation()(other)
        heard shouldBe listOf("truncated")
    }

    @Test
    fun `a damaged dump says what is wrong with it`() {
        val bytes = org.khronos.webgl.Uint8Array(arrayOf<Byte>(1, 2, 3))
        val info = describeError(shouldThrow<Throwable> { dumpPagesOf(bytes, literal("bzip2")) })
        info?.kind shouldBe "dump-format"
        info?.dumpProblem shouldBe "unexpected-eof"
    }

    @Test
    fun `a dump page says whether it is in the namespaces asked for`() {
        val text =
            """<mediawiki><page><title>Volcano</title><ns>0</ns><id>1</id><revision><id>2</id>
               <text>lava</text></revision></page></mediawiki>"""
        val bytes = org.khronos.webgl.Uint8Array(text.encodeToByteArray().toTypedArray())

        val page = dumpPagesOf(bytes).single()
        page.inNamespaces(arrayOf(0)) shouldBe true
        page.inNamespaces(arrayOf(1)) shouldBe false
        page.inNamespaces(emptyArray()) shouldBe true
    }
}
