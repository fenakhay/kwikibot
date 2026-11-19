package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.identifyAs
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import com.fenakhay.kwikibot.net.transport.statusFailure
import com.fenakhay.kwikibot.net.transport.transportFailure
import com.fenakhay.kwikibot.net.transport.withRequestPermit
import com.fenakhay.kwikibot.protocol.decode.boolean
import com.fenakhay.kwikibot.protocol.decode.double
import com.fenakhay.kwikibot.protocol.decode.long
import com.fenakhay.kwikibot.protocol.decode.obj
import com.fenakhay.kwikibot.protocol.decode.string
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Which of LiftWing's revert risk models scores a revision. */
public enum class RevertRiskModel(internal val modelName: String) {
    /**
     * `revertrisk-language-agnostic`: features any Wikipedia has, so it scores every language edition, and
     * scores several revisions in one request. Its model card suggests a probability above 0.9 where a
     * mistake is costly and 0.5 where missing a bad edit is.
     */
    LANGUAGE_AGNOSTIC("revertrisk-language-agnostic"),

    /**
     * `revertrisk-multilingual`: reads the text with a multilingual language model, which its model card says
     * does better on edits by unregistered editors. Trained on 47 language editions, slower, and more often
     * out of time.
     */
    MULTILINGUAL("revertrisk-multilingual"),
}

/** Why LiftWing could not score a revision. */
public enum class UnscorableReason {
    /** No such revision, or its page was deleted: LiftWing answers the two alike. */
    MISSING,

    /** Its text, its summary or its editor is hidden, by revision deletion or by suppression. */
    HIDDEN,

    /** It created its page, so there is no revision before it to compare it with. Revert risk only. */
    FIRST_REVISION,

    /** Some other reason LiftWing gave, which [LiftWingResult.Unscorable.detail] says in its words. */
    OTHER,
}

/** What LiftWing answered for one revision: a score, or why there is none. */
public sealed interface LiftWingResult<out T> {

    /** The score, or `null` when the revision could not be scored. */
    public fun scoreOrNull(): T?

    /** The revision was scored. */
    public data class Scored<out T>(
        /** What the model said of it. */
        val score: T
    ) : LiftWingResult<T> {
        override fun scoreOrNull(): T = score
    }

    /**
     * The model could not score the revision. A routine answer on a busy wiki, where revisions are deleted
     * and hidden every hour, so it is a value rather than an error.
     */
    public data class Unscorable(
        /** The revision asked about. */
        val revision: RevisionId,
        /** The model asked, `enwiki-damaging`. */
        val model: String,
        /** Why, as far as LiftWing's answer says. */
        val reason: UnscorableReason,
        /** LiftWing's own explanation, as it gave it. */
        val detail: String,
    ) : LiftWingResult<Nothing> {
        override fun scoreOrNull(): Nothing? = null
    }
}

/**
 * Whether an edit is damaging, or was made in good faith, as one of the revscoring models judges it.
 *
 * Both are probabilities from a classifier trained on past edits that editors labelled, and both are
 * advisory: a high damaging score is a reason for a person to look at an edit, not proof of vandalism.
 */
public data class EditQualityScore(
    /** The revision scored. */
    val revision: RevisionId,
    /** The model that scored it, `enwiki-damaging` or `enwiki-goodfaith`. */
    val model: String,
    /** What the model predicts at its own threshold: `true` for damaging, or for made in good faith. */
    val prediction: Boolean,
    /** How likely the model thinks it is that the answer is yes, from 0 to 1. */
    val trueProbability: Double,
    /** How likely it thinks the answer is no, which is the rest. */
    val falseProbability: Double,
    /** The version of the model, `0.5.1`. */
    val modelVersion: String,
)

/**
 * How likely an edit to a Wikipedia article is to be reverted, as a revert risk model judges it.
 *
 * Advisory: a revert is what editors did to edits like this one, not a verdict on this one.
 */
public data class RevertRiskScore(
    /** The revision scored. */
    val revision: RevisionId,
    /** The model that scored it. */
    val model: RevertRiskModel,
    /** The database name of the wiki it is on, as LiftWing read it: `enwiki`. */
    val wiki: String,
    /** Whether the model predicts a revert, at its own threshold of 0.5. */
    val prediction: Boolean,
    /** How likely it thinks a revert is, from 0 to 1. */
    val probability: Double,
    /** The version of the model, `3`. */
    val modelVersion: String,
)

/**
 * The quality of an article, or of a new page as a draft, as one of the revscoring models judges the text at
 * a revision.
 *
 * The classes are the wiki's own: `Stub`, `Start`, `C`, `B`, `GA` and `FA` for an article on the English
 * Wikipedia, and `OK`, `spam`, `vandalism` and `attack` for a draft. Advisory, like every score.
 */
public data class QualityScore(
    /** The revision scored. */
    val revision: RevisionId,
    /** The model that scored it, `enwiki-articlequality` or `enwiki-draftquality`. */
    val model: String,
    /** The class the model thinks most likely. */
    val prediction: String,
    /** How likely it thinks each class is, from 0 to 1, by the class's name. */
    val probabilities: Map<String, Double>,
    /** The version of the model, `0.9.2`. */
    val modelVersion: String,
)

/**
 * Scores of revisions from LiftWing, Wikimedia's machine-learning inference service, which replaced ORES.
 *
 * Separate from [Wiki] because the scores come from another server, `api.wikimedia.org`, with no session and
 * its own limits. Each model has a name and is asked with a JSON body, one revision a request:
 *
 * - `{wiki}-damaging` and `{wiki}-goodfaith`, through [damaging] and [goodfaith], for 33 wikis, `enwiki`,
 *   `dewiki`, `wikidatawiki` and `eswikiquote` among them. The models LiftWing took over from ORES, which
 *   Wikimedia plans to retire in favour of revert risk.
 * - `revertrisk-language-agnostic` and `revertrisk-multilingual`, through [revertRisk], for Wikipedia's
 *   articles, by language. Neither scores a page's first revision.
 * - `{wiki}-articlequality` and `{wiki}-draftquality`, through [articleQuality] and [draftQuality], for a
 *   dozen wikis and two.
 * - anything else, the topic models, `{wiki}-reverted`, `revertrisk-wikidata` and those still to come,
 *   through [predict], which answers the model's JSON as it is.
 *
 * Every score is advisory. A model is a classifier trained on what editors did before, and it is wrong often
 * enough that a bot should act on one alone only with a cautious threshold and a person in the loop.
 *
 * ```
 * val liftWing = LiftWing(http, userAgent)
 * when (val result = liftWing.damaging("enwiki", RevisionId(1383620073))) {
 *     is LiftWingResult.Scored -> if (result.score.trueProbability > 0.9) report(result.score)
 *     is LiftWingResult.Unscorable -> Unit
 * }
 * ```
 *
 * A revision LiftWing cannot score, because it does not exist, its page was deleted, its text is hidden or it
 * created its page, comes back as [LiftWingResult.Unscorable]. A model that does not exist, which is also
 * what asking for a wiki a model does not cover comes to, throws [WikiError.Api] with the code `nosuchmodel`,
 * naming it. A `429` or a `5xx` is waited out as [retry] says, honouring `Retry-After`.
 *
 * Wikimedia's documentation gives LiftWing's limits through the gateway as 50,000 requests an hour and 15 a
 * second without a token, counted by address or user agent; 100,000 an hour and 100 a second with an OAuth
 * 2.0 access token; and 200,000 an hour and 200 a second for known clients, such as tools on Wikimedia Cloud
 * Services and approved bots. A token raises the limit, so a busy bot should send one, as a bearer header
 * that [authorization] supplies. A client [WikiHttpClient] made with an OAuth 2.0 token already sends that
 * token everywhere, LiftWing included.
 *
 * @param client the client to send through. One made by [WikiHttpClient] limits how many requests are in
 *   flight at once, and these count among them.
 * @param userAgent who to say is asking. Wikimedia refuses a request without a user agent.
 * @param retry how often to ask again, and how long to wait, while LiftWing is busy or failing.
 * @param baseUrl where the models are served, Wikimedia's gateway unless given.
 * @param authorization the OAuth 2.0 access token to send as `Authorization: Bearer`, asked for before each
 *   request so a token can be refreshed; `null`, or a source that answers `null`, sends none.
 */
public class LiftWing(
    private val client: HttpClient,
    private val userAgent: UserAgent,
    private val retry: RetryPolicy = RetryPolicy(),
    private val baseUrl: String = WIKIMEDIA,
    private val authorization: (suspend () -> String?)? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Whether [revision] on [wiki] is damaging, as `{wiki}-damaging` judges it.
     *
     * @param wiki the wiki's database name, `enwiki`; see [Wiki.liftWingPrefix].
     * @param revision the revision.
     * @return the score, or why there is none.
     * @throws WikiError.Api with the code `nosuchmodel` when LiftWing has no damaging model for [wiki].
     */
    public suspend fun damaging(wiki: String, revision: RevisionId): LiftWingResult<EditQualityScore> =
        editQuality(wiki, DAMAGING, revision)

    /**
     * Whether [revision] on [wiki] was made in good faith, as `{wiki}-goodfaith` judges it, however much harm
     * it does.
     *
     * @param wiki the wiki's database name, `enwiki`; see [Wiki.liftWingPrefix].
     * @param revision the revision.
     * @return the score, or why there is none.
     * @throws WikiError.Api with the code `nosuchmodel` when LiftWing has no good-faith model for [wiki].
     */
    public suspend fun goodfaith(wiki: String, revision: RevisionId): LiftWingResult<EditQualityScore> =
        editQuality(wiki, GOODFAITH, revision)

    /**
     * How likely [revision] on the Wikipedia in [language] is to be reverted.
     *
     * Meant for articles: the models were trained on namespace 0, and score any other namespace less well.
     *
     * @param language the Wikipedia's language code, `en`, or `simple`; see [Wiki.liftWingLanguage].
     * @param revision the revision.
     * @param model which revert risk model, the language-agnostic one unless given.
     * @return the score, or why there is none, which for a page's first revision is
     *   [UnscorableReason.FIRST_REVISION].
     * @throws WikiError.Transport.Refused when LiftWing knows no Wikipedia in [language].
     */
    public suspend fun revertRisk(
        language: String,
        revision: RevisionId,
        model: RevertRiskModel = RevertRiskModel.LANGUAGE_AGNOSTIC,
    ): LiftWingResult<RevertRiskScore> {
        val body = buildJsonObject {
            put(REV_ID, revision.value)
            put(LANG, language)
        }
        return when (val answer = send(model.modelName, body, revision)) {
            is LiftWingResult.Unscorable -> answer
            is LiftWingResult.Scored -> {
                val reply = answer.score
                val output = reply.obj("output")
                LiftWingResult.Scored(
                    RevertRiskScore(
                        revision = reply.long("revision_id")?.let(::RevisionId) ?: revision,
                        model = model,
                        wiki = reply.string("wiki_db").orEmpty(),
                        prediction = output?.boolean("prediction") ?: badShape(model.modelName, reply),
                        probability =
                            output.obj("probabilities")?.double(TRUE) ?: badShape(model.modelName, reply),
                        modelVersion = reply.string("model_version").orEmpty(),
                    )
                )
            }
        }
    }

    /**
     * The quality of the article at [revision] on [wiki], as `{wiki}-articlequality` judges its text.
     *
     * @param wiki the wiki's database name, `enwiki`; see [Wiki.liftWingPrefix].
     * @param revision the revision.
     * @return the score, or why there is none.
     * @throws WikiError.Api with the code `nosuchmodel` when LiftWing has no article quality model for
     *   [wiki].
     */
    public suspend fun articleQuality(wiki: String, revision: RevisionId): LiftWingResult<QualityScore> =
        quality(wiki, ARTICLE_QUALITY, revision)

    /**
     * Whether the new page at [revision] on [wiki] is fit to keep, or spam, vandalism or an attack, as
     * `{wiki}-draftquality` judges its text.
     *
     * @param wiki the wiki's database name, `enwiki`; see [Wiki.liftWingPrefix].
     * @param revision the revision.
     * @return the score, or why there is none.
     * @throws WikiError.Api with the code `nosuchmodel` when LiftWing has no draft quality model for [wiki].
     */
    public suspend fun draftQuality(wiki: String, revision: RevisionId): LiftWingResult<QualityScore> =
        quality(wiki, DRAFT_QUALITY, revision)

    /**
     * What [model] answers for [body], as it answers it, for the models this class has no method for.
     *
     * ```
     * liftWing.predict("enwiki-articletopic", buildJsonObject { put("rev_id", 1383620073) })
     * ```
     *
     * @param model the model's name, `enwiki-articletopic` or `revertrisk-wikidata`.
     * @param body what the model takes, which its reference documentation lists: most take `rev_id`, and the
     *   revert risk models a `lang` as well.
     * @return the model's answer.
     * @throws WikiError.Api with the code `nosuchmodel` when there is no such model.
     * @throws WikiError.Transport.Refused when the model refuses the body, with what it said, a revision it
     *   cannot score included.
     */
    public suspend fun predict(model: String, body: JsonObject): JsonObject =
        (send(model, body, revision = null) as LiftWingResult.Scored).score

    private suspend fun editQuality(
        wiki: String,
        kind: String,
        revision: RevisionId,
    ): LiftWingResult<EditQualityScore> =
        revscoring(wiki, kind, revision) { model, score, version ->
            val probability = score.obj(PROBABILITY)
            EditQualityScore(
                revision = revision,
                model = model,
                prediction = score.boolean(PREDICTION) ?: badShape(model, score),
                trueProbability = probability?.double(TRUE) ?: badShape(model, score),
                falseProbability = probability.double(FALSE) ?: badShape(model, score),
                modelVersion = version,
            )
        }

    private suspend fun quality(
        wiki: String,
        kind: String,
        revision: RevisionId,
    ): LiftWingResult<QualityScore> =
        revscoring(wiki, kind, revision) { model, score, version ->
            QualityScore(
                revision = revision,
                model = model,
                prediction = score.string(PREDICTION) ?: badShape(model, score),
                probabilities =
                    score
                        .obj(PROBABILITY)
                        .orEmpty()
                        .mapNotNull { (name, value) ->
                            (value as? JsonPrimitive)?.doubleOrNull?.let { name to it }
                        }
                        .toMap(),
                modelVersion = version,
            )
        }

    /**
     * One of the models LiftWing took over from ORES, which answer in ORES's shape: the score under the wiki,
     * the revision and the model's kind, and the version beside it.
     */
    private suspend fun <T> revscoring(
        wiki: String,
        kind: String,
        revision: RevisionId,
        read: (model: String, score: JsonObject, version: String) -> T,
    ): LiftWingResult<T> {
        val model = "$wiki-$kind"
        val body = buildJsonObject { put(REV_ID, revision.value) }

        return when (val answer = send(model, body, revision)) {
            is LiftWingResult.Unscorable -> answer
            is LiftWingResult.Scored -> {
                val reply = answer.score
                val site = reply.obj(wiki)
                val score =
                    site?.obj("scores")?.obj(revision.value.toString())?.obj(kind)?.obj("score")
                        ?: badShape(model, reply)
                val version = site.obj("models")?.obj(kind)?.string("version").orEmpty()
                LiftWingResult.Scored(read(model, score, version))
            }
        }
    }

    /**
     * What [model] answered for [body], asking again while LiftWing is busy or failing. A revision it says it
     * cannot score is [LiftWingResult.Unscorable] when [revision] is given, and a refusal otherwise.
     */
    private suspend fun send(
        model: String,
        body: JsonObject,
        revision: RevisionId?,
    ): LiftWingResult<JsonObject> {
        require(MODEL_NAME.matches(model)) { "'$model' is not the name of a LiftWing model" }
        val url = "$baseUrl/$model:predict"
        var attempt = 0

        while (true) {
            val answer = runCatching { post(url, model, body, revision) }
            val failure =
                answer.exceptionOrNull()?.let {
                    it as? WikiError.Transport ?: transportFailure(url, it) ?: throw it
                } ?: return answer.getOrThrow()

            if (!failure.isTransient || attempt >= retry.maxRetries) throw failure

            attempt++
            delay(retry.delayFor(attempt, (failure as? WikiError.Transport.RateLimited)?.retryAfter))
        }
    }

    /** One request, sent once there is room for it among the client's requests in flight. */
    private suspend fun post(
        url: String,
        model: String,
        body: JsonObject,
        revision: RevisionId?,
    ): LiftWingResult<JsonObject> = client.withRequestPermit {
        val token = authorization?.invoke()
        val response =
            client.post(url) {
                identifyAs(userAgent)
                header(HttpHeaders.Accept, "application/json")
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        val text = response.bodyAsText()
        val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val detail = parsed?.string("error") ?: parsed?.string("detail")
        val reason = if (revision != null && detail != null) reasonOf(response.status, detail) else null

        when {
            response.status.isSuccess() ->
                LiftWingResult.Scored(
                    parsed
                        ?: throw WikiError.Api(
                            BAD_RESPONSE,
                            "LiftWing answered $model with something other than JSON: ${text.take(EXCERPT)}",
                            MODULE,
                        )
                )

            response.status == HttpStatusCode.NotFound ->
                throw WikiError.Api(
                    NO_SUCH_MODEL,
                    "LiftWing has no model called $model; a revscoring model covers only some wikis",
                    MODULE,
                )

            revision != null && reason != null ->
                LiftWingResult.Unscorable(revision, model, reason, detail.orEmpty())

            else ->
                throw statusFailure(url, response, detail ?: text)
                    ?: WikiError.Api(BAD_RESPONSE, "LiftWing answered ${response.status.value}", MODULE)
        }
    }

    /** What a model answered, as an error that says it is not in the shape this class reads. */
    private fun badShape(model: String, reply: JsonObject): Nothing =
        throw WikiError.Api(
            BAD_RESPONSE,
            "LiftWing answered $model in a shape this library does not read: " +
                reply.toString().take(EXCERPT),
            MODULE,
        )

    /** Where LiftWing is, and how it names a wiki. */
    public companion object {
        /** LiftWing's models, through Wikimedia's API gateway. */
        public const val WIKIMEDIA: String = "https://api.wikimedia.org/service/lw/inference/v1/models"

        /**
         * Why a model could not score a revision, from the [status] and the [detail] it answered with, or
         * `null` when the answer is not one of those.
         *
         * The revscoring models answer `400` with a sentence; the revert risk models answer `422` with a
         * reason code in their sentence.
         */
        internal fun reasonOf(status: HttpStatusCode, detail: String): UnscorableReason? =
            when (status) {
                HttpStatusCode.UnprocessableEntity ->
                    when {
                        "parent_revision_missing" in detail -> UnscorableReason.FIRST_REVISION
                        "revision_info_deleted" in detail -> UnscorableReason.HIDDEN
                        "revision_missing" in detail -> UnscorableReason.MISSING
                        else -> UnscorableReason.OTHER
                    }

                HttpStatusCode.BadRequest ->
                    when {
                        "does not have any info related to the rev-id" in detail -> UnscorableReason.MISSING
                        "Missing resource" in detail && "Deleted" in detail -> UnscorableReason.HIDDEN
                        "Missing resource" in detail -> UnscorableReason.MISSING
                        else -> null
                    }

                else -> null
            }

        /**
         * The language code the revert risk models take for the Wikipedia at [server]: `en` for
         * `en.wikipedia.org`, and `simple` for `simple.wikipedia.org`, whose content language is `en`.
         *
         * @throws IllegalArgumentException for any wiki that is not a Wikipedia.
         */
        internal fun languageOf(server: String): String {
            val project = runCatching { Pageviews.projectOf(server) }.getOrNull()
            require(project != null && project.endsWith(WIKIPEDIA)) {
                "LiftWing's revert risk scores Wikipedia's revisions only, and $server is not a Wikipedia"
            }
            return project.removeSuffix(WIKIPEDIA)
        }

        private val MODEL_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9_.-]*""")
        private const val WIKIPEDIA = ".wikipedia"
        private const val DAMAGING = "damaging"
        private const val GOODFAITH = "goodfaith"
        private const val ARTICLE_QUALITY = "articlequality"
        private const val DRAFT_QUALITY = "draftquality"
        private const val REV_ID = "rev_id"
        private const val LANG = "lang"
        private const val PREDICTION = "prediction"
        private const val PROBABILITY = "probability"
        private const val TRUE = "true"
        private const val FALSE = "false"
        private const val MODULE = "liftwing"
        private const val BAD_RESPONSE = "badresponse"
        private const val NO_SUCH_MODEL = "nosuchmodel"
        private const val EXCERPT = 200
    }
}

/**
 * The prefix of this wiki's LiftWing models, which is its database name: `enwiki`, as in `enwiki-damaging`.
 *
 * @throws IllegalArgumentException for a wiki that is not one of Wikimedia's, which LiftWing does not score.
 */
public val Wiki.liftWingPrefix: String
    get() {
        require(runCatching { Pageviews.projectOf(info.server) }.isSuccess) {
            "LiftWing scores Wikimedia's wikis only, and ${info.server} is not one"
        }
        return id.dbName
    }

/**
 * The language code LiftWing's revert risk models take for this wiki, from its server rather than its content
 * language: `en` for the English Wikipedia, and `simple` for the Simple English one.
 *
 * @throws IllegalArgumentException for a wiki that is not a Wikipedia, which revert risk does not score.
 */
public val Wiki.liftWingLanguage: String
    get() = LiftWing.languageOf(info.server)

/**
 * Whether each of [revisions] on this wiki is damaging, one request each, a few at once.
 *
 * @param liftWing the client to ask through.
 * @param revisions the revisions, each asked about once however often it is named.
 * @return each revision's score, or why there is none, in the order the revisions were named.
 * @throws IllegalArgumentException for a wiki that is not one of Wikimedia's, before anything is sent.
 * @see LiftWing.damaging
 */
public suspend fun Wiki.damaging(
    liftWing: LiftWing,
    revisions: Collection<RevisionId>,
): Map<RevisionId, LiftWingResult<EditQualityScore>> {
    val prefix = liftWingPrefix
    return scoreEach(revisions) { liftWing.damaging(prefix, it) }
}

/**
 * Whether each of [revisions] on this wiki was made in good faith, one request each, a few at once.
 *
 * @param liftWing the client to ask through.
 * @param revisions the revisions, each asked about once however often it is named.
 * @return each revision's score, or why there is none, in the order the revisions were named.
 * @throws IllegalArgumentException for a wiki that is not one of Wikimedia's, before anything is sent.
 * @see LiftWing.goodfaith
 */
public suspend fun Wiki.goodfaith(
    liftWing: LiftWing,
    revisions: Collection<RevisionId>,
): Map<RevisionId, LiftWingResult<EditQualityScore>> {
    val prefix = liftWingPrefix
    return scoreEach(revisions) { liftWing.goodfaith(prefix, it) }
}

/**
 * How likely each of [revisions] on this Wikipedia is to be reverted, one request each, a few at once.
 *
 * @param liftWing the client to ask through.
 * @param revisions the revisions, each asked about once however often it is named.
 * @param model which revert risk model, the language-agnostic one unless given.
 * @return each revision's score, or why there is none, in the order the revisions were named.
 * @throws IllegalArgumentException for a wiki that is not a Wikipedia, before anything is sent.
 * @see LiftWing.revertRisk
 */
public suspend fun Wiki.revertRisk(
    liftWing: LiftWing,
    revisions: Collection<RevisionId>,
    model: RevertRiskModel = RevertRiskModel.LANGUAGE_AGNOSTIC,
): Map<RevisionId, LiftWingResult<RevertRiskScore>> {
    val language = liftWingLanguage
    return scoreEach(revisions) { liftWing.revertRisk(language, it, model) }
}

/**
 * The quality of the article at each of [revisions] on this wiki, one request each, a few at once.
 *
 * @param liftWing the client to ask through.
 * @param revisions the revisions, each asked about once however often it is named.
 * @return each revision's score, or why there is none, in the order the revisions were named.
 * @throws IllegalArgumentException for a wiki that is not one of Wikimedia's, before anything is sent.
 * @see LiftWing.articleQuality
 */
public suspend fun Wiki.articleQuality(
    liftWing: LiftWing,
    revisions: Collection<RevisionId>,
): Map<RevisionId, LiftWingResult<QualityScore>> {
    val prefix = liftWingPrefix
    return scoreEach(revisions) { liftWing.articleQuality(prefix, it) }
}

/**
 * Whether the new page at each of [revisions] on this wiki is fit to keep, one request each, a few at once.
 *
 * @param liftWing the client to ask through.
 * @param revisions the revisions, each asked about once however often it is named.
 * @return each revision's score, or why there is none, in the order the revisions were named.
 * @throws IllegalArgumentException for a wiki that is not one of Wikimedia's, before anything is sent.
 * @see LiftWing.draftQuality
 */
public suspend fun Wiki.draftQuality(
    liftWing: LiftWing,
    revisions: Collection<RevisionId>,
): Map<RevisionId, LiftWingResult<QualityScore>> {
    val prefix = liftWingPrefix
    return scoreEach(revisions) { liftWing.draftQuality(prefix, it) }
}

/**
 * [score] of each of [revisions], each once, [REVISIONS_AT_ONCE] at most at once, and each waiting for room
 * among its client's requests in flight as every request does.
 */
private suspend fun <T> scoreEach(
    revisions: Collection<RevisionId>,
    score: suspend (RevisionId) -> LiftWingResult<T>,
): Map<RevisionId, LiftWingResult<T>> {
    val room = Semaphore(REVISIONS_AT_ONCE)
    return coroutineScope {
        revisions
            .distinct()
            .map { revision -> async { room.withPermit { revision to score(revision) } } }
            .awaitAll()
            .toMap()
    }
}

/**
 * How many revisions the [Wiki] extensions score at once: enough to keep a client's three requests in flight
 * busy, few enough that a long list never floods LiftWing.
 */
private const val REVISIONS_AT_ONCE = 4
