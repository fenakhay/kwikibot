package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

/** Wikimedia's own LiftWing, for real English Wikipedia revisions and for one whose page was deleted. */
@Tag("live")
class LiveLiftWingTest {

    private val userAgent = UserAgent("kwikibot-livetest", "0.1.0", "https://github.com/fenakhay/kwikibot")

    private val revisions = listOf(RevisionId(1383620073), RevisionId(1381657213))

    @Test
    fun `real revisions are scored as damaging and in good faith and for revert risk`() =
        runBlocking<Unit> {
            WikiHttpClient.create().use { http ->
                val liftWing = LiftWing(http, userAgent)

                for (revision in revisions) {
                    val damaging = (liftWing.damaging("enwiki", revision) as LiftWingResult.Scored).score
                    damaging.revision shouldBe revision
                    damaging.trueProbability.shouldBeBetween(0.0, 1.0, 0.0)
                    (damaging.trueProbability + damaging.falseProbability) shouldBe (1.0 plusOrMinus 1e-9)
                    damaging.modelVersion.shouldNotBeBlank()

                    val goodfaith = (liftWing.goodfaith("enwiki", revision) as LiftWingResult.Scored).score
                    goodfaith.model shouldBe "enwiki-goodfaith"
                    goodfaith.trueProbability.shouldBeBetween(0.0, 1.0, 0.0)

                    for (model in RevertRiskModel.entries) {
                        val risk = (liftWing.revertRisk("en", revision, model) as LiftWingResult.Scored).score
                        risk.wiki shouldBe "enwiki"
                        risk.model shouldBe model
                        risk.revision shouldBe revision
                        risk.probability.shouldBeBetween(0.0, 1.0, 0.0)
                    }
                }
            }
        }

    @Test
    fun `a revision whose page was deleted is unscorable and a wiki with no model is an error`() =
        runBlocking<Unit> {
            WikiHttpClient.create().use { http ->
                val liftWing = LiftWing(http, userAgent)
                val deleted = RevisionId(1383648813)

                (liftWing.damaging("enwiki", deleted) as LiftWingResult.Unscorable).reason shouldBe
                    UnscorableReason.MISSING
                (liftWing.revertRisk("en", deleted) as LiftWingResult.Unscorable).reason shouldBe
                    UnscorableReason.MISSING
                shouldThrow<WikiError.Api> { liftWing.damaging("enwiktionary", deleted) }.code shouldBe
                    "nosuchmodel"
            }
        }
}
