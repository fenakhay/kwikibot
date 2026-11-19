package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.transport.HttpSettings
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LiftWingWikiTest : LiftWingFixtures() {

    @Test
    fun `a wiki's prefix is its database name and its language is its server's`() {
        wikiAt("en.wikipedia.org").liftWingPrefix shouldBe "enwiki"
        wikiAt("en.wikipedia.org").liftWingLanguage shouldBe "en"
        wikiAt("simple.wikipedia.org", id = "simplewiki", language = "en").liftWingLanguage shouldBe "simple"
        wikiAt("en.m.wikipedia.org").liftWingLanguage shouldBe "en"
        wikiAt("www.wikidata.org", id = "wikidatawiki").liftWingPrefix shouldBe "wikidatawiki"
        wikiAt("en.wiktionary.org", id = "enwiktionary").liftWingPrefix shouldBe "enwiktionary"

        shouldThrow<IllegalArgumentException> { wikiAt("localhost:8080").liftWingPrefix }
            .message
            .orEmpty() shouldContain "localhost:8080"
        for (server in listOf("en.wiktionary.org", "www.wikidata.org", "localhost:8080")) {
            shouldThrow<IllegalArgumentException> { wikiAt(server).liftWingLanguage }
                .message
                .orEmpty() shouldContain server
        }
    }

    @Test
    fun `a wiki's revisions are each scored once in the order named under its prefix`() = runTest {
        val service = client { request ->
            val revision = jsonOf(bodyOf(request)).getValue("rev_id").toString()
            when {
                revision == "1383648813" -> respondJson(noInfo, HttpStatusCode.BadRequest)
                "revertrisk" in request.url.encodedPath -> respondJson(revertRiskAnswer)
                "goodfaith" in request.url.encodedPath ->
                    respondJson(goodfaithAnswer.replace("1250000000", revision))
                "articlequality" in request.url.encodedPath ->
                    respondJson(articleQualityAnswer.replace("1383620073", revision))
                "draftquality" in request.url.encodedPath ->
                    respondJson(draftQualityAnswer.replace("1383620073", revision))
                else -> respondJson(damagingAnswer.replace("1383620073", revision))
            }
        }
        val wiki = wikiAt("en.wikipedia.org")
        val deleted = RevisionId(1383648813)
        val other = RevisionId(1381657213)
        val revisions = listOf(other, einstein, deleted, other)

        val damaging = wiki.damaging(service, revisions)
        damaging.keys.toList() shouldBe listOf(other, einstein, deleted)
        damaging.getValue(einstein).scoreOrNull()?.trueProbability shouldBe 0.2464617749103113
        (damaging.getValue(deleted) as LiftWingResult.Unscorable).reason shouldBe UnscorableReason.MISSING
        wiki.goodfaith(service, revisions).getValue(other).scoreOrNull()?.prediction shouldBe true
        wiki.articleQuality(service, revisions).getValue(einstein).scoreOrNull()?.prediction shouldBe "FA"
        wiki.draftQuality(service, revisions).getValue(other).scoreOrNull()?.prediction shouldBe "vandalism"
        wiki
            .revertRisk(service, listOf(einstein), RevertRiskModel.MULTILINGUAL)
            .getValue(einstein)
            .scoreOrNull()
            ?.model shouldBe RevertRiskModel.MULTILINGUAL

        asked.size shouldBe 13
        asked.map { it.url.encodedPath.substringAfterLast('/').substringBefore('-') }.toSet() shouldBe
            setOf("enwiki", "revertrisk")
        jsonOf(sent.last()) shouldBe
            buildJsonObject {
                put("rev_id", 1383620073)
                put("lang", "en")
            }
    }

    @Test
    fun `a wiki LiftWing does not score is refused before anything is sent`() = runTest {
        val service = client { respondJson(damagingAnswer) }
        val elsewhere = wikiAt("wiki.example.org")
        val wiktionary = wikiAt("en.wiktionary.org", id = "enwiktionary")

        shouldThrow<IllegalArgumentException> { elsewhere.damaging(service, listOf(einstein)) }
        shouldThrow<IllegalArgumentException> { elsewhere.goodfaith(service, listOf(einstein)) }
        shouldThrow<IllegalArgumentException> { elsewhere.articleQuality(service, listOf(einstein)) }
        shouldThrow<IllegalArgumentException> { elsewhere.draftQuality(service, listOf(einstein)) }
        shouldThrow<IllegalArgumentException> { wiktionary.revertRisk(service, listOf(einstein)) }
        asked.shouldBeEmpty()
    }

    @Test
    fun `no more than four revisions are scored at once`() = runTest {
        mostInFlight(HttpClient(slowEngine())) shouldBe 4
    }

    @Test
    fun `a client's own limit on requests in flight holds for a wiki's revisions`() = runTest {
        WikiHttpClient.create(HttpSettings(maxConcurrentRequests = 2), engine = slowEngine()).use { http ->
            mostInFlight(http) shouldBeLessThanOrEqual 2
        }
    }

    @Test
    fun `the README's sample scores the stream's edits and a wiki's revisions`() = runTest {
        val printed = mutableListOf<String>()
        val stream =
            """
            data: {"wiki":"enwiki","type":"edit","title":"Albert Einstein","namespace":0,"bot":false,
            data: "revision":{"old":1383619098,"new":1383620073}}

            data: {"wiki":"dewiki","type":"edit","title":"Vulkan","namespace":0,"revision":{"old":1,"new":2}}

            data: {"wiki":"enwiki","type":"edit","title":"Malcolm Marx","namespace":0,"bot":false,
            data: "revision":{"old":1383156132,"new":1383722280}}

            data: {"wiki":"enwiki","type":"log","title":"Special:Log","namespace":-1}


            """
                .trimIndent() + "\n\n"
        val engine = engine { request ->
            when {
                "/v2/stream/" in request.url.encodedPath ->
                    respond(
                        stream,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                "1383722280" in bodyOf(request) ->
                    respondJson(
                        damagingAnswer
                            .replace("1383620073", "1383722280")
                            .replace("0.2464617749103113", "0.93")
                    )
                "revertrisk" in request.url.encodedPath -> respondJson(revertRiskAnswer)
                else -> respondJson(damagingAnswer)
            }
        }

        HttpClient(engine).use { http ->
            val liftWing = LiftWing(http, userAgent)

            EventStreams(http, userAgent)
                .recentChanges()
                .filter { it.matches("enwiki", setOf(0)) && it.type == "edit" && !it.isBot }
                .mapNotNull { event -> event.revisionId?.let { event.title to RevisionId(it) } }
                .take(2)
                .collect { (title, revision) ->
                    val damaging = liftWing.damaging("enwiki", revision).scoreOrNull() ?: return@collect
                    if (damaging.trueProbability > 0.9) printed += "$title may be damaging: $revision"
                }

            val wikipedia = wikiAt("en.wikipedia.org")
            wikipedia.revertRisk(liftWing, listOf(einstein)).forEach { (revision, result) ->
                when (result) {
                    is LiftWingResult.Scored -> printed += "$revision: ${result.score.probability}"
                    is LiftWingResult.Unscorable -> printed += "$revision: ${result.reason}"
                }
            }
        }

        printed shouldBe listOf("Malcolm Marx may be damaging: 1383722280", "1383620073: 0.9260151982307434")
    }

    private val inFlight = AtomicIntHolder()

    @OptIn(ExperimentalAtomicApi::class)
    private class AtomicIntHolder {
        val now = AtomicInt(0)
        val most = AtomicInt(0)
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun slowEngine() = MockEngine { request ->
        val revision = jsonOf(request.body.toByteArray().decodeToString()).getValue("rev_id").toString()
        val now = inFlight.now.incrementAndFetch()
        inFlight.most.update { maxOf(it, now) }
        delay(100.milliseconds)
        inFlight.now.update { it - 1 }
        respondJson(damagingAnswer.replace("1383620073", revision))
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun mostInFlight(http: HttpClient): Int {
        val wiki = wikiAt("en.wikipedia.org")
        val revisions = List(REVISIONS) { RevisionId(it + 1L) }

        val scores = wiki.damaging(LiftWing(http, userAgent, RetryPolicy.NONE), revisions)
        scores.values.map { it.scoreOrNull()?.model }.toSet() shouldBe setOf("enwiki-damaging")
        scores.size shouldBe REVISIONS
        return inFlight.most.load()
    }

    private companion object {
        const val REVISIONS = 12
    }
}
