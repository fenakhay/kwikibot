import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class LiftWingTest {

    private val contact = "https://example.org/TestBot"
    private val asked = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    private val damaging =
        """{"enwiki":{"models":{"damaging":{"version":"0.5.1"}},"scores":{"7":{"damaging":{"score":
           {"prediction":false,"probability":{"false":0.75,"true":0.25}}}}}}}"""

    private val goodfaith =
        """{"enwiki":{"models":{"goodfaith":{"version":"0.5.1"}},"scores":{"7":{"goodfaith":{"score":
           {"prediction":true,"probability":{"false":0.01,"true":0.99}}}}}}}"""

    private val revertRisk =
        """{"model_name":"revertrisk-multilingual","model_version":"4","wiki_db":"enwiki","revision_id":7,
           "output":{"prediction":true,"probabilities":{"true":0.92,"false":0.08}}}"""

    private val articleQuality =
        """{"enwiki":{"models":{"articlequality":{"version":"0.9.2"}},"scores":{"7":{"articlequality":{"score":
           {"prediction":"GA","probability":{"GA":0.6,"FA":0.4}}}}}}}"""

    private val draftQuality =
        """{"enwiki":{"models":{"draftquality":{"version":"0.2.1"}},"scores":{"7":{"draftquality":{"score":
           {"prediction":"OK","probability":{"OK":0.9,"spam":0.1}}}}}}}"""

    private val deleted =
        """{"error":"Missing resource for rev-id 8: TextDeleted: Text deleted (datasource.revision.text)"}"""

    private fun engine() = MockEngine { request ->
        asked += request
        val body = request.body.toByteArray().decodeToString()
        bodies += body
        val path = request.url.encodedPath
        val (answer, status) =
            when {
                "\"rev_id\":8" in body -> deleted to HttpStatusCode.BadRequest
                "enwiktionary" in path -> """{"httpCode":404}""" to HttpStatusCode.NotFound
                "revertrisk" in path -> revertRisk to HttpStatusCode.OK
                "goodfaith" in path -> goodfaith to HttpStatusCode.OK
                "articlequality" in path -> articleQuality to HttpStatusCode.OK
                "draftquality" in path -> draftQuality to HttpStatusCode.OK
                "articletopic" in path -> """{"topics":["STEM.Physics"]}""" to HttpStatusCode.OK
                else -> damaging to HttpStatusCode.OK
            }
        respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    fun `a revision's scores are asked for and read back with the literals the package names`() = runTest {
        val liftWing = liftWing(contact, optionsOf("accessToken" to "a-token"), engine())

        val score = liftWing.damaging("enwiki", 7.0).await()
        score.revision shouldBe 7.0
        score.unscorable shouldBe null
        val damage = score.score!!
        listOf(
            damage.model,
            damage.prediction,
            damage.trueProbability,
            damage.falseProbability,
            damage.modelVersion,
        ) shouldBe listOf("enwiki-damaging", false, 0.25, 0.75, "0.5.1")
        asked.single().url.toString() shouldBe "$WIKIMEDIA_LIFT_WING/enwiki-damaging:predict"
        asked.single().headers[HttpHeaders.Authorization] shouldBe "Bearer a-token"
        bodies.single() shouldBe """{"rev_id":7}"""

        liftWing.goodfaith("enwiki", 7.0).await().score?.trueProbability shouldBe 0.99

        val risk =
            liftWing.revertRisk("en", 7.0, "multilingual".unsafeCast<RevertRiskModel>()).await().score!!
        risk.model.unsafeCast<String>() shouldBe "multilingual"
        listOf(risk.wiki, risk.prediction, risk.probability, risk.modelVersion) shouldBe
            listOf("enwiki", true, 0.92, "4")
        bodies.last() shouldBe """{"rev_id":7,"lang":"en"}"""
        liftWing.revertRisk("en", 7.0).await()
        asked.last().url.encodedPath shouldContain "revertrisk-language-agnostic"

        val article = liftWing.articleQuality("enwiki", 7.0).await().score!!
        listOf(article.model, article.prediction, article.modelVersion) shouldBe
            listOf("enwiki-articlequality", "GA", "0.9.2")
        article.probabilities.asDynamic()["FA"].unsafeCast<Double>() shouldBe 0.4
        liftWing.draftQuality("enwiki", 7.0).await().score?.prediction shouldBe "OK"

        val topics = liftWing.predict("enwiki-articletopic", optionsOf("rev_id" to 7)).await()
        JSON.stringify(topics) shouldBe """{"topics":["STEM.Physics"]}"""
        liftWing.close()
    }

    @Test
    fun `a revision that cannot be scored resolves to why and a missing model rejects`() = runTest {
        val liftWing = liftWing(contact, optionsOf("baseUrl" to "https://lw.test"), engine())

        val hidden = liftWing.damaging("enwiki", 8.0).await()
        hidden.score shouldBe null
        val why = hidden.unscorable!!
        listOf(why.model, why.reason.unsafeCast<String>()) shouldBe listOf("enwiki-damaging", "hidden")
        why.detail shouldContain "TextDeleted"
        asked.single().url.host shouldBe "lw.test"

        shouldThrow<Throwable> { liftWing.damaging("enwiktionary", 7.0).await() }
            .message
            .orEmpty() shouldContain "enwiktionary-damaging"
        shouldThrow<IllegalArgumentException> {
                liftWing.revertRisk("en", 7.0, "robotic".unsafeCast<RevertRiskModel>()).await()
            }
            .message
            .orEmpty() shouldContain "'language-agnostic'"
        asked.size shouldBe 2
        liftWing.close()
    }

    @Test
    fun `a wiki's revisions are scored in order under its prefix and language`() = runTest {
        val site = MockWiki()
        site.canned["siteinfo"] =
            """{"query":{"general":{"wikiid":"enwiki","sitename":"Wikipedia","lang":"en",
               "server":"//en.wikipedia.org","articlepath":"/wiki/${'$'}1","mainpage":"Main Page",
               "generator":"MediaWiki 1.47.0"},"namespaces":{"0":{"id":0,"name":"","case":"first-letter"}}}}"""
        val wiki = site.open()
        val liftWing = liftWing(contact, null, engine())
        val revisions = arrayOf(7.0, 8.0, 7.0)

        liftWing.prefixOf(wiki) shouldBe "enwiki"
        liftWing.languageOf(wiki) shouldBe "en"
        val scores = liftWing.damagingOf(wiki, revisions).await()
        scores.map { it.revision } shouldBe listOf(7.0, 8.0)
        scores[0].score?.trueProbability shouldBe 0.25
        scores[1].unscorable?.reason?.unsafeCast<String>() shouldBe "hidden"
        liftWing.goodfaithOf(wiki, revisions).await()[0].score?.prediction shouldBe true
        liftWing.revertRiskOf(wiki, arrayOf(7.0)).await().single().score?.probability shouldBe 0.92
        liftWing.revertRiskOf(wiki, arrayOf(7.0), "multilingual".unsafeCast<RevertRiskModel>()).await()
        liftWing.articleQualityOf(wiki, revisions).await()[0].score?.prediction shouldBe "GA"
        liftWing.draftQualityOf(wiki, revisions).await()[0].score?.prediction shouldBe "OK"
        asked.size shouldBe 10
        liftWing.close()
        wiki.close()
    }

    @Test
    fun `a wiki that is not Wikimedia's is refused before anything is sent`() = runTest {
        val wiki = MockWiki().open()
        val liftWing = liftWing(contact, null, engine())

        shouldThrow<IllegalArgumentException> { liftWing.prefixOf(wiki) }
        shouldThrow<IllegalArgumentException> { liftWing.languageOf(wiki) }
        shouldThrow<IllegalArgumentException> { liftWing.damagingOf(wiki, arrayOf(7.0)).await() }
        asked.size shouldBe 0
        liftWing.close()
        wiki.close()
    }
}
