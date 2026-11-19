package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.UserAgent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class LiftWingTest : LiftWingFixtures() {

    @Test
    fun `a damaging score is asked for by POSTing the revision as JSON and read with its probabilities`() =
        runTest {
            val result = client { respondJson(damagingAnswer) }.damaging("enwiki", einstein)

            val request = asked.single()
            request.method shouldBe HttpMethod.Post
            request.url.toString() shouldBe
                "https://api.wikimedia.org/service/lw/inference/v1/models/enwiki-damaging:predict"
            request.headers[HttpHeaders.UserAgent].orEmpty() shouldContain "TestBot/1.0"
            request.headers[HttpHeaders.Accept] shouldBe "application/json"
            request.headers[HttpHeaders.Authorization] shouldBe null
            request.body.contentType.toString() shouldBe "application/json"
            jsonOf(sent.single()) shouldBe buildJsonObject { put("rev_id", 1383620073) }
            result shouldBe
                LiftWingResult.Scored(
                    EditQualityScore(
                        revision = einstein,
                        model = "enwiki-damaging",
                        prediction = false,
                        trueProbability = 0.2464617749103113,
                        falseProbability = 0.7535382250896887,
                        modelVersion = "0.5.1",
                    )
                )
            result.scoreOrNull()?.trueProbability shouldBe 0.2464617749103113
        }

    @Test
    fun `a good-faith score is asked of the wiki's goodfaith model`() = runTest {
        val score = client {
            respondJson(goodfaithAnswer)
        }
            .goodfaith("enwiki", RevisionId(1250000000))
            .scoreOrNull()

        asked.single().url.encodedPath shouldBe "/service/lw/inference/v1/models/enwiki-goodfaith:predict"
        score?.model shouldBe "enwiki-goodfaith"
        score?.prediction shouldBe true
        score?.trueProbability shouldBe 0.9961954848663294
        score?.falseProbability shouldBe 0.0038045151336706384
    }

    @Test
    fun `a revert risk is asked with the language and read with the wiki LiftWing found it on`() = runTest {
        val service = client { request ->
            if ("multilingual" in request.url.encodedPath) {
                respondJson(multilingualAnswer)
            } else {
                respondJson(revertRiskAnswer)
            }
        }

        val agnostic = service.revertRisk("en", einstein).scoreOrNull()
        val multilingual =
            service.revertRisk("simple", RevisionId(11053038), RevertRiskModel.MULTILINGUAL).scoreOrNull()

        asked.map { it.url.encodedPath.substringAfterLast('/') } shouldBe
            listOf("revertrisk-language-agnostic:predict", "revertrisk-multilingual:predict")
        sent.map(::jsonOf) shouldBe
            listOf(
                buildJsonObject {
                    put("rev_id", 1383620073)
                    put("lang", "en")
                },
                buildJsonObject {
                    put("rev_id", 11053038)
                    put("lang", "simple")
                },
            )
        agnostic shouldBe
            RevertRiskScore(
                revision = einstein,
                model = RevertRiskModel.LANGUAGE_AGNOSTIC,
                wiki = "enwiki",
                prediction = true,
                probability = 0.9260151982307434,
                modelVersion = "3",
            )
        multilingual shouldBe
            RevertRiskScore(
                revision = RevisionId(11053038),
                model = RevertRiskModel.MULTILINGUAL,
                wiki = "simplewiki",
                prediction = false,
                probability = 0.4676410757863918,
                modelVersion = "4",
            )
    }

    @Test
    fun `article and draft quality are read as classes with the one predicted`() = runTest {
        val service = client { request ->
            if ("draftquality" in request.url.encodedPath) {
                respondJson(draftQualityAnswer)
            } else {
                respondJson(articleQualityAnswer)
            }
        }

        val article = service.articleQuality("enwiki", einstein).scoreOrNull()
        val draft = service.draftQuality("enwiki", einstein).scoreOrNull()

        article?.model shouldBe "enwiki-articlequality"
        article?.prediction shouldBe "FA"
        article?.modelVersion shouldBe "0.9.2"
        article?.probabilities?.keys shouldBe setOf("B", "C", "FA", "GA", "Start", "Stub")
        article?.probabilities?.get("GA") shouldBe 0.3548535903838616
        draft?.model shouldBe "enwiki-draftquality"
        draft?.prediction shouldBe "vandalism"
        draft?.probabilities shouldBe
            mapOf(
                "OK" to 0.2813630761335944,
                "attack" to 0.015453317380882344,
                "spam" to 0.08684249723206908,
                "vandalism" to 0.6163411092534542,
            )
        sent.map(::jsonOf).toSet() shouldBe setOf(buildJsonObject { put("rev_id", 1383620073) })
    }

    @Test
    fun `any other model is asked with the body given and answers its JSON as it is`() = runTest {
        val topics = """{"enwiki":{"models":{"articletopic":{"version":"1.3.0"}},"scores":{}}}"""
        val body = buildJsonObject {
            put("rev_id", 1383620073)
            put("extended_output", false)
        }

        val answer = client { respondJson(topics) }.predict("enwiki-articletopic", body)

        answer shouldBe jsonOf(topics)
        jsonOf(sent.single()) shouldBe body
        asked.single().url.encodedPath shouldBe "/service/lw/inference/v1/models/enwiki-articletopic:predict"
    }

    @Test
    fun `a model or a wiki LiftWing does not have is an error naming it`() = runTest {
        val json = client { respondJson(noSuchModel, HttpStatusCode.NotFound) }
        val html = client {
            respond(
                "<!DOCTYPE html><title>Wikimedia Error</title>",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }

        val wiki = shouldThrow<WikiError.Api> { json.damaging("enwiktionary", einstein) }
        wiki.code shouldBe "nosuchmodel"
        wiki.message.orEmpty() shouldContain "enwiktionary-damaging"
        val model = shouldThrow<WikiError.Api> { html.predict("xxwiki-damaging", buildJsonObject {}) }
        model.code shouldBe "nosuchmodel"
        model.message.orEmpty() shouldContain "xxwiki-damaging"
        asked.size shouldBe 2
    }

    @Test
    fun `a revision the revscoring models cannot find or read is unscorable and says why`() = runTest {
        val missingResource =
            """{"error":"Missing resource for rev-id 7: RevisionNotFound: Could not find revision"}"""
        val service = client {
            when {
                "1383648813" in bodyOf(it) -> respondJson(noInfo, HttpStatusCode.BadRequest)
                "1383426223" in bodyOf(it) -> respondJson(textDeleted, HttpStatusCode.BadRequest)
                else -> respondJson(missingResource, HttpStatusCode.BadRequest)
            }
        }

        service.damaging("enwiki", RevisionId(1383648813)) shouldBe
            LiftWingResult.Unscorable(
                RevisionId(1383648813),
                "enwiki-damaging",
                UnscorableReason.MISSING,
                jsonOf(noInfo).getValue("error").jsonPrimitive.content,
            )
        val hidden = service.articleQuality("enwiki", RevisionId(1383426223))
        (hidden as LiftWingResult.Unscorable).reason shouldBe UnscorableReason.HIDDEN
        hidden.detail shouldContain "TextDeleted"
        hidden.model shouldBe "enwiki-articlequality"
        hidden.scoreOrNull() shouldBe null
        (service.goodfaith("enwiki", RevisionId(7)) as LiftWingResult.Unscorable).reason shouldBe
            UnscorableReason.MISSING
    }

    @Test
    fun `a revision revert risk cannot score is unscorable with the reason it gave`() = runTest {
        val answers =
            mapOf(
                1383648813L to
                    "Could not make prediction for revisions dict_keys([(1383648813, 'en')]). " +
                        "Reason: ['revision_missing']",
                1383426223L to
                    "Could not make prediction for revision 1383426223 (en). " +
                        "Reason: revision_info_deleted",
                1250000000L to
                    "Could not make prediction for revision 1250000000 (en). " +
                        "Reason: parent_revision_missing",
                5L to """Could not make prediction for revision 5 (en). Reason: something_new""",
            )
        val service = client {
            val revision = jsonOf(bodyOf(it)).getValue("rev_id").toString().toLong()
            respondJson(
                buildJsonObject { put("detail", answers.getValue(revision)) }.toString(),
                UNPROCESSABLE,
            )
        }

        val reasons =
            answers.keys.map {
                (service.revertRisk("en", RevisionId(it)) as LiftWingResult.Unscorable).reason
            }

        reasons shouldBe
            listOf(
                UnscorableReason.MISSING,
                UnscorableReason.HIDDEN,
                UnscorableReason.FIRST_REVISION,
                UnscorableReason.OTHER,
            )
        (service.revertRisk("en", RevisionId(5), RevertRiskModel.MULTILINGUAL) as LiftWingResult.Unscorable)
            .model shouldBe "revertrisk-multilingual"
    }

    @Test
    fun `a body the model refuses is a refusal with what it said`() = runTest {
        val service = client { request ->
            if ("revertrisk" in request.url.encodedPath) {
                respondJson(
                    """{"detail":"lang 'xx' does not exist in the canonical Wikipedia language list."}""",
                    HttpStatusCode.BadRequest,
                )
            } else {
                respondJson("""{"error":"Expected \"rev_id\" in input data."}""", HttpStatusCode.BadRequest)
            }
        }

        val language = shouldThrow<WikiError.Transport.Refused> { service.revertRisk("xx", einstein) }
        language.status shouldBe 400
        language.detail shouldContain "lang 'xx' does not exist"
        val body =
            shouldThrow<WikiError.Transport.Refused> {
                service.predict("enwiki-damaging", JsonObject(emptyMap()))
            }
        body.detail shouldContain "Expected \"rev_id\""
        asked.size shouldBe 2
    }

    @Test
    fun `an answer that would be unscorable for a typed method is a refusal from predict`() = runTest {
        val service = client { respondJson(noInfo, HttpStatusCode.BadRequest) }

        val refused =
            shouldThrow<WikiError.Transport.Refused> {
                service.predict("enwiki-damaging", buildJsonObject { put("rev_id", 1383648813) })
            }

        refused.detail shouldContain "does not have any info"
    }

    @Test
    fun `a token is sent as a bearer header and asked for before every request`() = runTest {
        var tokens = 0
        val service =
            LiftWing(
                HttpClient(engine { respondJson(damagingAnswer) }),
                userAgent,
                RetryPolicy.NONE,
                authorization = {
                    tokens++
                    if (tokens == 1) "first-token" else null
                },
            )

        service.damaging("enwiki", einstein)
        service.damaging("enwiki", einstein)

        asked.map { it.headers[HttpHeaders.Authorization] } shouldBe listOf("Bearer first-token", null)
    }

    @Test
    fun `a token the gateway rejects is a refusal`() = runTest {
        val service = client {
            respondJson(
                """{"httpCode":401,"httpReason":"Jwt is not in the form of Header.Payload.Signature """ +
                    """with two dots and 3 sections"}""",
                HttpStatusCode.Unauthorized,
            )
        }

        val refused = shouldThrow<WikiError.Transport.Refused> { service.damaging("enwiki", einstein) }

        refused.status shouldBe 401
        asked.size shouldBe 1
    }

    @Test
    fun `a 429 is waited out for as long as Retry-After says and asked again`() = runTest {
        var calls = 0
        val service =
            client(RetryPolicy(maxRetries = 2, initialDelay = 1.seconds)) {
                calls++
                if (calls == 1) {
                    respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30"))
                } else {
                    respondJson(damagingAnswer)
                }
            }

        service.damaging("enwiki", einstein).scoreOrNull()?.prediction shouldBe false

        asked.size shouldBe 2
        sent.toSet().size shouldBe 1
        currentTime shouldBe 30.seconds.inWholeMilliseconds
    }

    @Test
    fun `a server error is asked again and one that lasts is raised once the retries are spent`() = runTest {
        var calls = 0
        val recovering =
            client(RetryPolicy(maxRetries = 2)) {
                calls++
                if (calls == 1) respond("", HttpStatusCode.ServiceUnavailable)
                else respondJson(revertRiskAnswer)
            }
        recovering.revertRisk("en", einstein).scoreOrNull()?.probability shouldBe 0.9260151982307434
        asked.size shouldBe 2

        asked.clear()
        val failing =
            client(RetryPolicy(maxRetries = 2)) {
                respondJson(
                    """{"error":"An error happened while fetching info for revision """ +
                        """from the MediaWiki API"}""",
                    HttpStatusCode.InternalServerError,
                )
            }
        val failure = shouldThrow<WikiError.Transport.ServerError> { failing.revertRisk("en", einstein) }
        failure.status shouldBe 500
        asked.size shouldBe 3
    }

    @Test
    fun `a gateway that never stops being busy is raised as a rate limit`() = runTest {
        val service = client(RetryPolicy(maxRetries = 1)) { respond("", HttpStatusCode.TooManyRequests) }

        shouldThrow<WikiError.Transport.RateLimited> { service.damaging("enwiki", einstein) }
        asked.size shouldBe 2
    }

    @Test
    fun `a connection that fails is tried again and then reported as unreachable`() = runTest {
        val service = client(RetryPolicy(maxRetries = 1)) { throw IOException("connection reset") }

        shouldThrow<WikiError.Transport.Unreachable> { service.goodfaith("enwiki", einstein) }
        asked.size shouldBe 2
    }

    @Test
    fun `a failure that says nothing about the connection is passed on as it is`() = runTest {
        val service = client(RetryPolicy(maxRetries = 3)) { throw IllegalStateException("a bug") }

        shouldThrow<IllegalStateException> { service.damaging("enwiki", einstein) }
        asked.size shouldBe 1
    }

    @Test
    fun `an answer that is not JSON or not a success is a bad response`() = runTest {
        val garbled = client { respond("<html>maintenance</html>", HttpStatusCode.OK) }
        shouldThrow<WikiError.Api> { garbled.damaging("enwiki", einstein) }.code shouldBe "badresponse"

        val moved = client { respond("", HttpStatusCode.NotModified) }
        shouldThrow<WikiError.Api> { moved.damaging("enwiki", einstein) }.code shouldBe "badresponse"
    }

    @Test
    fun `an answer in a shape the scores cannot be read from is a bad response`() = runTest {
        val shapes =
            listOf(
                """{"enwiki":{"scores":{}}}""",
                """{"elsewhere":{}}""",
                """{"enwiki":{"scores":{"1383620073":{"damaging":{"score":""" +
                    """{"probability":{"true":0.1,"false":0.9}}}}}}}""",
                """{"enwiki":{"scores":{"1383620073":{"damaging":{"score":{"prediction":true}}}}}}""",
                """{"enwiki":{"scores":{"1383620073":{"damaging":{"score":""" +
                    """{"prediction":true,"probability":{"true":0.9}}}}}}}""",
            )
        for (shape in shapes) {
            shouldThrow<WikiError.Api> { client { respondJson(shape) }.damaging("enwiki", einstein) }
                .code shouldBe "badresponse"
        }

        val quality = client {
            respondJson("""{"enwiki":{"scores":{"1383620073":{"articlequality":{"score":{}}}}}}""")
        }
        shouldThrow<WikiError.Api> { quality.articleQuality("enwiki", einstein) }.code shouldBe "badresponse"

        for (shape in
            listOf(
                """{"model_name":"revertrisk-language-agnostic"}""",
                """{"output":{"prediction":true}}""",
            )) {
            shouldThrow<WikiError.Api> { client { respondJson(shape) }.revertRisk("en", einstein) }
                .code shouldBe "badresponse"
        }
    }

    @Test
    fun `what is missing from a score that is still readable is left empty`() = runTest {
        val bare = client { request ->
            if ("revertrisk" in request.url.encodedPath) {
                respondJson("""{"output":{"prediction":false,"probabilities":{"true":0.1}}}""")
            } else {
                respondJson(
                    """{"enwiki":{"scores":{"1383620073":{"articlequality":{"score":
                           {"prediction":"B","probability":{"B":0.5,"C":"high","D":null}}}}}}}"""
                )
            }
        }

        val risk = bare.revertRisk("en", einstein).scoreOrNull()
        risk?.revision shouldBe einstein
        risk?.wiki shouldBe ""
        risk?.modelVersion shouldBe ""
        val quality = bare.articleQuality("enwiki", einstein).scoreOrNull()
        quality?.probabilities shouldBe mapOf("B" to 0.5)
        quality?.modelVersion shouldBe ""
    }

    @Test
    fun `a model name that could not be one is refused before anything is sent`() = runTest {
        val service = client { respondJson(damagingAnswer) }

        for (name in listOf("", "../enwiki-damaging", "enwiki damaging", "enwiki/damaging", "-enwiki")) {
            shouldThrow<IllegalArgumentException> { service.predict(name, JsonObject(emptyMap())) }
        }
        shouldThrow<IllegalArgumentException> { service.damaging("en wiki", einstein) }
        asked.shouldBeEmpty()
    }

    @Test
    fun `a base URL given is where the models are asked`() = runTest {
        val service =
            LiftWing(
                HttpClient(engine { respondJson(damagingAnswer) }),
                userAgent,
                RetryPolicy.NONE,
                "http://lw.test",
            )

        service.damaging("enwiki", einstein)

        asked.single().url.toString() shouldBe "http://lw.test/enwiki-damaging:predict"
    }

    private companion object {
        val UNPROCESSABLE = HttpStatusCode.UnprocessableEntity
    }
}
