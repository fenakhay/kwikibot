import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.bindings.connect.userAgent
import com.fenakhay.kwikibot.bindings.services.liftWingWithDefaults
import com.fenakhay.kwikibot.bindings.services.revertRiskModelOf
import com.fenakhay.kwikibot.client.model.EditQualityScore as ClientEditQualityScore
import com.fenakhay.kwikibot.client.model.LiftWing as ClientLiftWing
import com.fenakhay.kwikibot.client.model.LiftWingResult as ClientLiftWingResult
import com.fenakhay.kwikibot.client.model.QualityScore as ClientQualityScore
import com.fenakhay.kwikibot.client.model.RevertRiskModel as ClientRevertRiskModel
import com.fenakhay.kwikibot.client.model.RevertRiskScore as ClientRevertRiskScore
import com.fenakhay.kwikibot.client.model.articleQuality
import com.fenakhay.kwikibot.client.model.damaging
import com.fenakhay.kwikibot.client.model.draftQuality
import com.fenakhay.kwikibot.client.model.goodfaith
import com.fenakhay.kwikibot.client.model.liftWingLanguage
import com.fenakhay.kwikibot.client.model.liftWingPrefix
import com.fenakhay.kwikibot.client.model.revertRisk
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.promise

/** LiftWing's models, through Wikimedia's API gateway. */
@JsExport public const val WIKIMEDIA_LIFT_WING: String = ClientLiftWing.WIKIMEDIA

/** What a LiftWing client is made with. */
@JsExport
public external interface LiftWingOptions {
    /** Where the models are served, Wikimedia's gateway unless given. */
    public val baseUrl: String?

    /** How often to ask again, and how long to wait, while LiftWing is busy or failing. */
    public val retry: RetryOptions?

    /** Who to say is asking; the library's name with the contact unless given. */
    public val userAgent: UserAgent?

    /**
     * An OAuth 2.0 access token, sent as `Authorization: Bearer` with every request, which raises the rate
     * limit from 50,000 requests an hour to 100,000. None unless given.
     */
    public val accessToken: String?
}

/**
 * Makes a client for LiftWing, Wikimedia's machine-learning service, asking as the tool whose operator
 * [contact] reaches. Close it when done.
 */
@JsExport
public fun liftWing(contact: String, options: LiftWingOptions? = null): LiftWing =
    liftWing(contact, options, engine = null)

/** [liftWing], through [engine], or the platform's own when it is `null`. */
internal fun liftWing(contact: String, options: LiftWingOptions?, engine: HttpClientEngine?): LiftWing {
    withoutLoggingBanner()
    val http = WikiHttpClient.create(engine = engine)
    val token = options?.accessToken
    val client =
        liftWingWithDefaults(
            http,
            contact,
            options?.userAgent?.agent,
            options?.retry?.let(::retryOf),
            options?.baseUrl,
            token,
        )
    return LiftWing(client, http)
}

/**
 * Scores of revisions from LiftWing, which replaced ORES. Every score is advisory: a model trained on what
 * editors did before, and wrong often enough that a person should look before a bot acts on one. A revision
 * LiftWing cannot score resolves to why, in `unscorable`; a model or a wiki it does not have rejects, naming
 * it. A busy gateway is waited out as the retry options say.
 */
@JsExport
public class LiftWing internal constructor(private val client: ClientLiftWing, private val http: HttpClient) {

    private val scope = CoroutineScope(SupervisorJob())

    /** Whether [revision] on [wiki], a database name such as `"enwiki"`, is damaging. */
    public fun damaging(wiki: String, revision: Double): Promise<LiftWingResult<EditQualityScore>> =
        scope.promise {
            editQualityResult(revision, client.damaging(wiki, revisionIdOf(revision)))
        }

    /** Whether [revision] on [wiki], a database name such as `"enwiki"`, was made in good faith. */
    public fun goodfaith(wiki: String, revision: Double): Promise<LiftWingResult<EditQualityScore>> =
        scope.promise {
            editQualityResult(revision, client.goodfaith(wiki, revisionIdOf(revision)))
        }

    /**
     * How likely [revision] on the Wikipedia in [language], such as `"en"`, is to be reverted, by
     * `"language-agnostic"` revert risk unless [model] says `"multilingual"`.
     */
    public fun revertRisk(
        language: String,
        revision: Double,
        model: RevertRiskModel? = null,
    ): Promise<LiftWingResult<RevertRiskScore>> = scope.promise {
        revertRiskResult(
            revision,
            client.revertRisk(language, revisionIdOf(revision), revertRiskModel(model)),
        )
    }

    /** The quality of the article at [revision] on [wiki], a database name such as `"enwiki"`. */
    public fun articleQuality(wiki: String, revision: Double): Promise<LiftWingResult<QualityScore>> =
        scope.promise {
            qualityResult(revision, client.articleQuality(wiki, revisionIdOf(revision)))
        }

    /** Whether the new page at [revision] on [wiki] is fit to keep, or spam, vandalism or an attack. */
    public fun draftQuality(wiki: String, revision: Double): Promise<LiftWingResult<QualityScore>> =
        scope.promise {
            qualityResult(revision, client.draftQuality(wiki, revisionIdOf(revision)))
        }

    /** What [model], such as `"enwiki-articletopic"`, answers for [body], as it answers it. */
    public fun predict(model: String, body: Record<String, JsonValue>): Promise<Record<String, JsonValue>> =
        scope.promise {
            plainOf(client.predict(model, jsonObjectOf(body)))
        }

    /**
     * Whether each of [revisions] on [wiki], one of Wikimedia's, is damaging, a few at a time, in the order
     * given and each once. Rejects, sending nothing, for a wiki that is not Wikimedia's.
     */
    public fun damagingOf(
        wiki: Wiki,
        revisions: Array<Double>,
    ): Promise<Array<LiftWingResult<EditQualityScore>>> = scope.promise {
        wiki.wiki
            .damaging(client, revisions.map(::revisionIdOf))
            .map { (id, result) ->
                editQualityResult(id.value.toDouble(), result)
            }
            .toTypedArray()
    }

    /**
     * Whether each of [revisions] on [wiki], one of Wikimedia's, was made in good faith, a few at a time, in
     * the order given and each once.
     */
    public fun goodfaithOf(
        wiki: Wiki,
        revisions: Array<Double>,
    ): Promise<Array<LiftWingResult<EditQualityScore>>> = scope.promise {
        wiki.wiki
            .goodfaith(client, revisions.map(::revisionIdOf))
            .map { (id, result) ->
                editQualityResult(id.value.toDouble(), result)
            }
            .toTypedArray()
    }

    /**
     * How likely each of [revisions] on [wiki], a Wikipedia, is to be reverted, a few at a time, in the order
     * given and each once. Rejects, sending nothing, for a wiki that is not a Wikipedia.
     */
    public fun revertRiskOf(
        wiki: Wiki,
        revisions: Array<Double>,
        model: RevertRiskModel? = null,
    ): Promise<Array<LiftWingResult<RevertRiskScore>>> = scope.promise {
        wiki.wiki
            .revertRisk(client, revisions.map(::revisionIdOf), revertRiskModel(model))
            .map { (id, result) ->
                revertRiskResult(id.value.toDouble(), result)
            }
            .toTypedArray()
    }

    /** The quality of the article at each of [revisions] on [wiki], a few at a time, in the order given. */
    public fun articleQualityOf(
        wiki: Wiki,
        revisions: Array<Double>,
    ): Promise<Array<LiftWingResult<QualityScore>>> = scope.promise {
        wiki.wiki
            .articleQuality(client, revisions.map(::revisionIdOf))
            .map { (id, result) ->
                qualityResult(id.value.toDouble(), result)
            }
            .toTypedArray()
    }

    /** Whether the new page at each of [revisions] on [wiki] is fit to keep, a few at a time, in order. */
    public fun draftQualityOf(
        wiki: Wiki,
        revisions: Array<Double>,
    ): Promise<Array<LiftWingResult<QualityScore>>> = scope.promise {
        wiki.wiki
            .draftQuality(client, revisions.map(::revisionIdOf))
            .map { (id, result) ->
                qualityResult(id.value.toDouble(), result)
            }
            .toTypedArray()
    }

    /**
     * The prefix of [wiki]'s models, its database name, such as `"enwiki"`. Throws for one not Wikimedia's.
     */
    public fun prefixOf(wiki: Wiki): String = wiki.wiki.liftWingPrefix

    /**
     * The language revert risk takes for [wiki], such as `"simple"`. Throws for one that is not a Wikipedia.
     */
    public fun languageOf(wiki: Wiki): String = wiki.wiki.liftWingLanguage

    /** Ends what is still being asked and releases the connections. */
    public fun close() {
        scope.cancel()
        http.close()
    }

    private fun revertRiskModel(value: RevertRiskModel?): ClientRevertRiskModel =
        revertRiskModelOf(enumOf("model", value))
}

/** What LiftWing answered for one revision: its score, or why it has none. */
@JsExport
public class LiftWingResult<out T>
internal constructor(
    /** The revision asked about. */
    public val revision: Double,
    /** The score, or `null` when the revision could not be scored. */
    public val score: T?,
    /** Why the revision could not be scored, or `null` when it was. */
    public val unscorable: Unscorable?,
)

/** Why LiftWing could not score a revision. */
@JsExport
public class Unscorable internal constructor(private val unscorable: ClientLiftWingResult.Unscorable) {

    /** The model asked, such as `"enwiki-damaging"`. */
    public val model: String
        get() = unscorable.model

    /** `"missing"`, `"hidden"`, `"first-revision"` or `"other"`. */
    public val reason: UnscorableReason
        get() = unscorable.reason.argumentName().unsafeCast<UnscorableReason>()

    /** LiftWing's own explanation. */
    public val detail: String
        get() = unscorable.detail
}

/** Whether an edit is damaging, or was made in good faith, as a revscoring model judges it. */
@JsExport
public class EditQualityScore internal constructor(private val score: ClientEditQualityScore) {

    /** The model, such as `"enwiki-damaging"`. */
    public val model: String
        get() = score.model

    /** What the model predicts at its own threshold. */
    public val prediction: Boolean
        get() = score.prediction

    /** How likely the model thinks the answer is yes, from 0 to 1. */
    public val trueProbability: Double
        get() = score.trueProbability

    /** How likely it thinks the answer is no. */
    public val falseProbability: Double
        get() = score.falseProbability

    /** The model's version, such as `"0.5.1"`. */
    public val modelVersion: String
        get() = score.modelVersion
}

/** How likely an edit to a Wikipedia article is to be reverted. */
@JsExport
public class RevertRiskScore internal constructor(private val score: ClientRevertRiskScore) {

    /** `"language-agnostic"` or `"multilingual"`. */
    public val model: RevertRiskModel
        get() = score.model.argumentName().unsafeCast<RevertRiskModel>()

    /** The database name of the wiki it is on, such as `"enwiki"`. */
    public val wiki: String
        get() = score.wiki

    /** Whether the model predicts a revert. */
    public val prediction: Boolean
        get() = score.prediction

    /** How likely it thinks a revert is, from 0 to 1. */
    public val probability: Double
        get() = score.probability

    /** The model's version, such as `"3"`. */
    public val modelVersion: String
        get() = score.modelVersion
}

/** The quality of an article, or of a new page as a draft, at a revision. */
@JsExport
public class QualityScore internal constructor(private val score: ClientQualityScore) {

    /** The model, such as `"enwiki-articlequality"`. */
    public val model: String
        get() = score.model

    /** The class the model thinks most likely, such as `"FA"`. */
    public val prediction: String
        get() = score.prediction

    /** How likely it thinks each class is, from 0 to 1. */
    public val probabilities: Record<String, Double>
        get() = score.probabilities.toRecord { it }

    /** The model's version, such as `"0.9.2"`. */
    public val modelVersion: String
        get() = score.modelVersion
}

private fun <T, R> resultOf(
    revision: Double,
    result: ClientLiftWingResult<T>,
    score: (T) -> R,
): LiftWingResult<R> =
    when (result) {
        is ClientLiftWingResult.Scored -> LiftWingResult(revision, score(result.score), null)
        is ClientLiftWingResult.Unscorable -> LiftWingResult(revision, null, Unscorable(result))
    }

private fun editQualityResult(
    revision: Double,
    result: ClientLiftWingResult<ClientEditQualityScore>,
): LiftWingResult<EditQualityScore> = resultOf(revision, result, ::EditQualityScore)

private fun revertRiskResult(
    revision: Double,
    result: ClientLiftWingResult<ClientRevertRiskScore>,
): LiftWingResult<RevertRiskScore> = resultOf(revision, result, ::RevertRiskScore)

private fun qualityResult(
    revision: Double,
    result: ClientLiftWingResult<ClientQualityScore>,
): LiftWingResult<QualityScore> = resultOf(revision, result, ::QualityScore)
