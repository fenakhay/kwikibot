package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.ApiRequest
import com.fenakhay.kwikibot.net.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.Continuation
import com.fenakhay.kwikibot.protocol.PageDecoder
import com.fenakhay.kwikibot.protocol.SiteInfo
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The levels the extension stores. Named at the top level rather than in a companion, because an
// enum constructor cannot see its own companion object, and a bare 3 in that position is the kind
// of number that gets edited into the wrong case.
private const val WITHOUT_TEXT_LEVEL = 0
private const val NOT_PROOFREAD_LEVEL = 1
private const val PROBLEMATIC_LEVEL = 2
private const val PROOFREAD_LEVEL = 3
private const val VALIDATED_LEVEL = 4

/**
 * How far a scanned page has got through proofreading.
 *
 * The numbers are the ones the ProofreadPage extension stores, and the order is the order of
 * progress — which is why this is comparable: a bot that only touches pages below [PROOFREAD]
 * says so as a comparison rather than as a set of numbers.
 */
public enum class ProofreadQuality(
    /** The number ProofreadPage stores, which is what makes these comparable. */
    public val level: Int,
) {
    /** No text: an image with nothing transcribed. */
    WITHOUT_TEXT(WITHOUT_TEXT_LEVEL),

    /** Text exists but nobody has checked it — usually raw OCR. */
    NOT_PROOFREAD(NOT_PROOFREAD_LEVEL),

    /** Something is wrong with it and somebody said so. */
    PROBLEMATIC(PROBLEMATIC_LEVEL),

    /** One person has proofread it against the scan. */
    PROOFREAD(PROOFREAD_LEVEL),

    /** A second person has confirmed it. */
    VALIDATED(VALIDATED_LEVEL),
    ;

    /** Reading a quality back from the number the extension stores. */
    public companion object {
        /** The quality of a level number, or `null` if the extension reports one nobody knows. */
        public fun of(level: Int): ProofreadQuality? = entries.firstOrNull { it.level == level }
    }
}

/**
 * A page in the `Page:` namespace, split into the parts a bot edits separately.
 *
 * A Wikisource page is one scanned page of a book, and its wikitext has a fixed shape: a
 * `<noinclude>` header carrying the quality marker and running heads, the body, and a
 * `<noinclude>` footer. Only the body is transcluded into the book.
 *
 * The split matters because the parts have different owners. A bot fixing OCR touches the body;
 * the quality marker records who proofread the page and when, and rewriting it would claim their
 * work. [serialize] puts back exactly what it was given for the parts that were not changed.
 */
public data class ProofreadText(
    /** Everything inside the opening `<noinclude>`, after the quality marker. */
    val header: String,
    /** The transcribed text, which is what a reader of the book sees. */
    val body: String,
    /** Everything inside the closing `<noinclude>`. */
    val footer: String,
    /** How far the page has been proofread, absent where the wiki did not say. */
    val quality: ProofreadQuality? = null,
    /** Who last set the quality. Not to be rewritten by a bot: it is a claim about a person. */
    val proofreader: String? = null,
) {

    /** This page with a different body and everything else untouched. */
    public fun withBody(text: String): ProofreadText = copy(body = text)

    /**
     * The wikitext, with the quality marker rebuilt exactly as it was.
     *
     * A page that had no marker gets none: adding one would assert an unreviewed quality.
     */
    public fun serialize(): String = buildString {
        append("<noinclude>")
        if (quality != null) {
            append("<pagequality level=\"${quality.level}\" user=\"${proofreader.orEmpty()}\" />")
        }
        append(header)
        append("</noinclude>")
        append(body)
        append("<noinclude>")
        append(footer)
        append("</noinclude>")
    }

    /** Reading and rebuilding the three parts of a `Page:` page. */
    public companion object {

        /**
         * Reads the wikitext of a `Page:` page.
         *
         * A page not in this shape — hand-written, or from a wiki without the extension —
         * comes back as all body. That reading loses nothing: rewriting the body then
         * rewrites the whole page rather than silently dropping part of it.
         */
        public fun parse(wikitext: String): ProofreadText {
            val opening = OPENING.find(wikitext) ?: return ProofreadText("", wikitext, "")
            val closing = CLOSING.find(wikitext, startIndex = opening.range.last) ?: run {
                return ProofreadText("", wikitext, "")
            }

            val quality = QUALITY.find(opening.groupValues[1])

            return ProofreadText(
                header = opening.groupValues[1].removeRange(quality?.range ?: IntRange.EMPTY),
                body = wikitext.substring(opening.range.last + 1, closing.range.first),
                footer = closing.groupValues[1],
                quality = quality?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { ProofreadQuality.of(it) },
                proofreader = quality?.groupValues?.get(2)?.takeIf { it.isNotEmpty() },
            )
        }

        private val OPENING = Regex("""^<noinclude>(.*?)</noinclude>""", RegexOption.DOT_MATCHES_ALL)
        private val CLOSING = Regex("""<noinclude>(.*?)</noinclude>\s*$""", RegexOption.DOT_MATCHES_ALL)
        private val QUALITY =
            Regex("""<pagequality level="(\d)" user="([^"]*)"\s*/>""")
    }
}

/**
 * Wikisource's scanned pages.
 *
 * Needs the ProofreadPage extension, which only Wikisource and its sisters have; every method
 * refuses without it rather than returning nothing.
 */
public interface ProofreadService {

    /** The proofreading quality of pages, keyed by the ref asked for. */
    public suspend fun quality(refs: Collection<PageRef>): Map<PageRef, ProofreadQuality>

    /** The index a scanned page belongs to, which is the book it is part of. */
    public suspend fun indexOf(ref: PageRef): PageRef?

    /**
     * The scanned pages of an index, in page order.
     *
     * Read from what the index transcludes, which is how the extension itself decides.
     */
    public suspend fun pagesOf(index: PageRef): List<PageRef>
}

internal class ApiProofreadService(
    transport: MediaWikiTransport,
    private val decoder: PageDecoder,
    private val namespaces: NamespaceMap,
    private val info: SiteInfo,
    private val batchSize: Int = DEFAULT_BATCH,
) : ProofreadService {

    private val continuation = Continuation(transport)

    override suspend fun quality(refs: Collection<PageRef>): Map<PageRef, ProofreadQuality> {
        requireExtension()
        if (refs.isEmpty()) return emptyMap()

        val found = mutableMapOf<Title.Local, ProofreadQuality>()
        for (batch in refs.map { it.title }.distinct().chunked(batchSize)) {
            continuation.pages(
                ApiRequest.of(
                    "query",
                    "prop" to "proofread",
                    "titles" to batch.joinToString("|") { namespaces.format(it) },
                ),
            ).toList().forEach { page ->
                val ref = decoder.refOf(page) ?: return@forEach
                val level = page["proofread"]?.jsonObject?.get("quality")?.jsonPrimitive?.intOrNull
                ProofreadQuality.of(level ?: return@forEach)?.let { found[ref.title] = it }
            }
        }

        return refs.mapNotNull { ref -> found[ref.title]?.let { ref to it } }.toMap()
    }

    override suspend fun indexOf(ref: PageRef): PageRef? {
        requireExtension()

        val page = continuation.pages(
            ApiRequest.of(
                "query",
                "prop" to "proofread",
                "titles" to namespaces.format(ref.title),
            ),
        ).toList().firstOrNull() ?: return null

        val index = page["proofread"]?.jsonObject?.get("index")?.jsonPrimitive?.content
        return index?.let { decoder.refOf(it, INDEX_NAMESPACE) }
    }

    override suspend fun pagesOf(index: PageRef): List<PageRef> {
        requireExtension()

        return continuation.list(
            ApiRequest.of(
                "query",
                "list" to "embeddedin",
                "eititle" to namespaces.format(index.title),
                "einamespace" to PAGE_NAMESPACE.toString(),
                "eilimit" to "max",
            ),
            "embeddedin",
        ).toList().mapNotNull { decoder.refOf(it) }
    }

    private fun requireExtension() {
        if (!info.hasExtension(EXTENSION)) {
            throw WikiError.Configuration.MissingExtension(EXTENSION)
        }
    }

    private companion object {
        const val EXTENSION = "ProofreadPage"
        const val DEFAULT_BATCH = 50

        /** The namespaces the extension defines. They are the same number on every Wikisource. */
        const val PAGE_NAMESPACE = 104
        const val INDEX_NAMESPACE = 106
    }
}
