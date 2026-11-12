import com.fenakhay.kwikibot.bindings.client.titleOf
import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.bindings.common.enumArgument
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.client.service.ProofreadQuality as ClientProofreadQuality
import com.fenakhay.kwikibot.client.service.ProofreadText as ClientProofreadText
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise

/**
 * Wikisource's scanned pages, reached as `wiki.proofread`. Every method rejects without the ProofreadPage
 * extension rather than answering with nothing.
 */
@JsExport
public class ProofreadService
internal constructor(private val wiki: ClientWiki, private val scope: CoroutineScope) {

    /** The proofreading quality of each of [titles] that has one. */
    public fun quality(titles: Array<String>): Promise<Array<PageQuality>> = scope.promise {
        wiki.proofread
            .quality(titles.map { wiki.ref(it) })
            .map { (ref, quality) -> PageQuality(wiki.titleOf(ref), quality) }
            .toTypedArray()
    }

    /** The index the scanned page [title] belongs to, which is the book it is part of, or `null`. */
    public fun indexOf(title: String): Promise<String?> = scope.promise {
        wiki.proofread.indexOf(wiki.ref(title))?.let(wiki::titleOf)
    }

    /** The titles of the scanned pages of [index], in page order. */
    public fun pagesOf(index: String): Promise<Array<String>> = scope.promise {
        wiki.proofread.pagesOf(wiki.ref(index)).map(wiki::titleOf).toTypedArray()
    }
}

/** How far one scanned page has got through proofreading. */
@JsExport
public class PageQuality
internal constructor(
    /** The page. */
    public val title: String,
    private val found: ClientProofreadQuality,
) {
    /** `"without-text"`, `"not-proofread"`, `"problematic"`, `"proofread"` or `"validated"`. */
    public val quality: ProofreadQuality
        get() = found.argumentName().unsafeCast<ProofreadQuality>()

    /** The number ProofreadPage stores, from 0 to 4, in the order of progress. */
    public val level: Int
        get() = found.level
}

/**
 * The quality named by the [level] ProofreadPage stores, as `PageQuality` spells it, or `null` when no
 * quality has that number.
 */
@JsExport
public fun proofreadQualityOf(level: Int): ProofreadQuality? =
    ClientProofreadQuality.of(level)?.argumentName()?.unsafeCast<ProofreadQuality>()

/**
 * Reads the wikitext of a `Page:` page. A page not in the usual shape comes back as all body, and writing
 * that back gives the page as it was.
 */
@JsExport
public fun parseProofreadText(wikitext: String): ProofreadText {
    val parsed = ClientProofreadText.parse(wikitext)
    return ProofreadText(
        parsed.header,
        parsed.body,
        parsed.footer,
        parsed.quality?.argumentName()?.unsafeCast<ProofreadQuality>(),
        parsed.proofreader,
    )
}

/**
 * A page in the `Page:` namespace, split into the parts a bot edits separately.
 *
 * @param header everything inside the opening `<noinclude>`, after the quality marker.
 * @param body the transcribed text, which is what a reader of the book sees.
 * @param footer everything inside the closing `<noinclude>`.
 * @param quality `"without-text"`, `"not-proofread"`, `"problematic"`, `"proofread"` or `"validated"`, absent
 *   where the wiki did not say.
 * @param proofreader who last set the quality.
 */
@JsExport
public class ProofreadText(
    header: String,
    body: String,
    footer: String,
    quality: ProofreadQuality? = null,
    proofreader: String? = null,
) {
    private val text =
        ClientProofreadText(
            header,
            body,
            footer,
            quality?.let { enumArgument<ClientProofreadQuality>("quality", it.unsafeCast<String>()) },
            proofreader,
        )

    /** Everything inside the opening `<noinclude>`, after the quality marker. */
    public val header: String
        get() = text.header

    /** The transcribed text, which is what a reader of the book sees. */
    public val body: String
        get() = text.body

    /** Everything inside the closing `<noinclude>`. */
    public val footer: String
        get() = text.footer

    /** How far the page has been proofread, as the constructor takes it, or `null`. */
    public val quality: ProofreadQuality?
        get() = text.quality?.argumentName().unsafeCast<ProofreadQuality?>()

    /** The number ProofreadPage stores for the quality, or `null`. */
    public val qualityLevel: Int?
        get() = text.quality?.level

    /** Who last set the quality. A bot should not rewrite it: it is a claim about a person. */
    public val proofreader: String?
        get() = text.proofreader

    /** This page with a different body and everything else untouched. */
    public fun withBody(body: String): ProofreadText =
        ProofreadText(text.header, body, text.footer, quality, text.proofreader)

    /** The wikitext. A page read and not changed comes back exactly as it was. */
    public fun serialize(): String = text.serialize()
}
