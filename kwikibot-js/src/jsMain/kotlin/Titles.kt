import com.fenakhay.kwikibot.model.SignatureTime as FoundSignature
import com.fenakhay.kwikibot.model.Signatures
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title as ParsedTitle
import kotlin.js.Date

/**
 * Reads [text] as a page title the way MediaWiki does, with the namespaces every MediaWiki has, under their
 * English names. `Wiki.parseTitle` reads one with a wiki's own namespaces.
 */
@JsExport public fun parseTitle(text: String): Title = Title(ParsedTitle.parse(text), NamespaceMap.CANONICAL)

/**
 * The times of the signatures in [text], in the order they appear, read with the formats of [language] in
 * UTC. `Wiki.signatureTimes` reads them in a wiki's own time zone and with its own month names.
 */
@JsExport
public fun signatureTimes(text: String, language: String = "en"): Array<SignatureTime> =
    Signatures.forLanguage(language).findAll(text).map(::SignatureTime).toTypedArray()

/** A page title, read the way MediaWiki reads one. */
@JsExport
public class Title
internal constructor(private val title: ParsedTitle, private val namespaces: NamespaceMap) {

    /**
     * `"local"` for a page of this wiki, `"interwiki"` for one on another, `"invalid"` for no page at all.
     */
    public val kind: String
        get() =
            when (title) {
                is ParsedTitle.Local -> "local"
                is ParsedTitle.Interwiki -> "interwiki"
                is ParsedTitle.Invalid -> "invalid"
            }

    /** The namespace number, or `null` unless the title is local. */
    public val namespace: Int?
        get() = (title as? ParsedTitle.Local)?.namespace?.id

    /** The title without its namespace prefix, normalised, or `null` unless it is local. */
    public val text: String?
        get() = (title as? ParsedTitle.Local)?.text

    /** The interwiki prefix, or `null` unless the title is an interwiki one. */
    public val interwiki: String?
        get() = (title as? ParsedTitle.Interwiki)?.prefix

    /** What follows `#`, or `null`. */
    public val fragment: String?
        get() = title.fragment

    /** Why the title is invalid, such as `"illegal-character"`, or `null` when it is valid. */
    public val invalidReason: String?
        get() = (title as? ParsedTitle.Invalid)?.reason?.name?.lowercase()?.replace('_', '-')

    /**
     * The title in full, its fragment included: namespace prefix and text, or the interwiki prefix and the
     * rest, or as given when it is invalid.
     */
    override fun toString(): String =
        when (title) {
            is ParsedTitle.Local -> namespaces.format(title)
            is ParsedTitle.Interwiki -> "${title.prefix}:${title.rest}"
            is ParsedTitle.Invalid -> title.raw
        }
}

/** The time of one signature, and where it is in the text. */
@JsExport
public class SignatureTime internal constructor(private val found: FoundSignature) {

    /** When it was signed. */
    public val date: Date
        get() = Date(found.instant.toEpochMilliseconds().toDouble())

    /** The timestamp as written. */
    public val text: String
        get() = found.text

    /** Where the timestamp starts in the text, counted in UTF-16 code units as JavaScript counts them. */
    public val start: Int
        get() = found.range.first

    /** Where the timestamp ends, just past its last code unit. */
    public val end: Int
        get() = found.range.last + 1

    /** The language whose format matched. */
    public val language: String
        get() = found.language
}
