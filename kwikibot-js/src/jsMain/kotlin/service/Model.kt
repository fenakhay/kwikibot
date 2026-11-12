import com.fenakhay.kwikibot.bindings.titles.namespaceInfoOf
import com.fenakhay.kwikibot.bindings.titles.parseTitleWithDefaults
import com.fenakhay.kwikibot.client.MediaWikiVersion
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.edit.Expiry
import com.fenakhay.kwikibot.model.page.SiteLink as ClientSiteLink
import com.fenakhay.kwikibot.model.title.InterwikiMap
import com.fenakhay.kwikibot.model.title.Namespace as ClientNamespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title as ParsedTitle
import com.fenakhay.kwikibot.model.title.TitleCase as ClientTitleCase
import kotlin.js.Date

/** Virtual: file pages addressed as media. */
@JsExport public const val NAMESPACE_MEDIA: Int = -2

/** Virtual: generated pages such as `Special:RecentChanges`. */
@JsExport public const val NAMESPACE_SPECIAL: Int = -1

/** Article space, and the only namespace whose titles carry no prefix. */
@JsExport public const val NAMESPACE_MAIN: Int = 0

/** Discussion of article-space pages. */
@JsExport public const val NAMESPACE_TALK: Int = 1

/** Personal pages, and where a bot's own subpages belong. */
@JsExport public const val NAMESPACE_USER: Int = 2

/** Where a message left for a person arrives. */
@JsExport public const val NAMESPACE_USER_TALK: Int = 3

/** The wiki about itself: policy, process, and the pages a bot is approved on. */
@JsExport public const val NAMESPACE_PROJECT: Int = 4

/** Discussion of the wiki's own pages. */
@JsExport public const val NAMESPACE_PROJECT_TALK: Int = 5

/** File description pages. */
@JsExport public const val NAMESPACE_FILE: Int = 6

/** Discussion of file description pages. */
@JsExport public const val NAMESPACE_FILE_TALK: Int = 7

/** Interface messages, editable only by administrators. */
@JsExport public const val NAMESPACE_MEDIAWIKI: Int = 8

/** Discussion of interface messages. */
@JsExport public const val NAMESPACE_MEDIAWIKI_TALK: Int = 9

/** Templates. */
@JsExport public const val NAMESPACE_TEMPLATE: Int = 10

/** Discussion of templates. */
@JsExport public const val NAMESPACE_TEMPLATE_TALK: Int = 11

/** Documentation written for readers and editors. */
@JsExport public const val NAMESPACE_HELP: Int = 12

/** Discussion of help pages. */
@JsExport public const val NAMESPACE_HELP_TALK: Int = 13

/** Category description pages. */
@JsExport public const val NAMESPACE_CATEGORY: Int = 14

/** Discussion of category description pages. */
@JsExport public const val NAMESPACE_CATEGORY_TALK: Int = 15

/** The hard limit MediaWiki places on a page title, in UTF-8 bytes. */
@JsExport public const val TITLE_MAX_BYTES: Int = ParsedTitle.MAX_BYTES

/** A namespace by its number, which is what identifies it on every wiki. */
@JsExport
public class Namespace(
    /** The number. */
    public val id: Int
) {
    private val namespace = ClientNamespace(id)

    /** Whether this is a talk namespace, which are the odd-numbered ones. */
    public val isTalk: Boolean
        get() = namespace.isTalk

    /** Whether it has no pages of its own in the database: `Media` and `Special`. */
    public val isVirtual: Boolean
        get() = namespace.isVirtual

    /** The number of its talk namespace, this one's when it is one, or `null` when it is virtual. */
    public val talkSpace: Int?
        get() = namespace.talkSpace?.id

    /** The number of its subject namespace, this one's when it is one, or `null` when it is virtual. */
    public val subjectSpace: Int?
        get() = namespace.subjectSpace?.id

    /** The number with a marker, `ns:14`. */
    override fun toString(): String = namespace.toString()
}

/** One namespace to give to `createNamespaces`. */
@JsExport
public external interface NamespaceOptions {
    /** Its number. */
    public val id: Int

    /** The English canonical name, such as `"Category"`; absent for the main namespace. */
    public val canonicalName: String?

    /** The wiki's own name for it, such as `"Kategoria"`. */
    public val localName: String?

    /** Other accepted prefixes, such as `"WP"` for `"Wikipedia"`. */
    public val aliases: Array<String>?

    /**
     * How the first letter of a title here is treated: `"first-letter"`, the default, or `"case-sensitive"`.
     */
    public val case: TitleCase?

    /** Whether a slash in a title makes a subpage here; `false` unless given. */
    public val subpages: Boolean?
}

/** The namespaces every MediaWiki has, under their English names. */
@JsExport public fun canonicalNamespaces(): Namespaces = Namespaces(NamespaceMap.CANONICAL)

/** A wiki's namespaces, as [namespaces] describes them, for reading titles before it has been opened. */
@JsExport
public fun createNamespaces(namespaces: Array<NamespaceOptions>): Namespaces =
    Namespaces(
        NamespaceMap(
            namespaces.map {
                namespaceInfoOf(
                    it.id,
                    it.canonicalName,
                    it.localName,
                    it.aliases?.asList(),
                    enumOf<ClientTitleCase>("case", it.case),
                    it.subpages,
                )
            }
        )
    )

/** A wiki's interwiki prefixes; [selfPrefixes] point back at the wiki itself, and resolve to a local page. */
@JsExport
public fun createInterwiki(prefixes: Array<String>, selfPrefixes: Array<String> = emptyArray()): Interwiki =
    Interwiki(InterwikiMap(prefixes.toList(), selfPrefixes.toList()))

/** What a title is read against. */
@JsExport
public external interface TitleOptions {
    /** The wiki's namespaces; those every MediaWiki has, in English, unless given. */
    public val namespaces: Namespaces?

    /** The wiki's interwiki prefixes; none unless given. */
    public val interwiki: Interwiki?

    /** The number of the namespace a title with no prefix is in; the main one unless given. */
    public val defaultNamespace: Int?
}

/** Reads [text] as a page title the way MediaWiki does, against what [options] gives. */
@JsExport
public fun parseTitleWith(text: String, options: TitleOptions? = null): Title {
    val namespaces = options?.namespaces?.namespaces ?: NamespaceMap.CANONICAL
    val title =
        parseTitleWithDefaults(text, namespaces, options?.interwiki?.interwiki, options?.defaultNamespace)
    return Title(title, namespaces)
}

/** Reads a MediaWiki timestamp, ISO 8601 or the compact `20260831214326`. Throws when it is neither. */
@JsExport public fun parseTimestamp(raw: String): Date = dateOf(MwTimestamp.parse(raw))

/** Reads a MediaWiki timestamp as `parseTimestamp` does, or `null` when it is neither form. */
@JsExport public fun parseTimestampOrNull(raw: String): Date? = MwTimestamp.parseOrNull(raw)?.let(::dateOf)

/** Whether [raw] is one of MediaWiki's spellings of an expiry that never arrives, such as `"infinity"`. */
@JsExport public fun isNeverTimestamp(raw: String): Boolean = MwTimestamp.isNever(raw)

/** Writes [date] as the API expects it, ISO 8601 to whole seconds. */
@JsExport public fun formatTimestamp(date: Date): String = MwTimestamp.format(instantOf(date))

/** Writes [date] in the compact form, `20260831214326`. */
@JsExport public fun formatTimestampCompact(date: Date): String = MwTimestamp.formatCompact(instantOf(date))

/** A link to a page on another wiki, such as `[[fr:Volcan]]`, to build rather than to parse. */
@JsExport
public class SiteLink(
    /** The interwiki prefix, such as `"fr"`, `"w"` or `"commons"`. */
    public val prefix: String,
    /** The title on that wiki, with its own namespace prefix when it has one. */
    public val title: String,
    /** The text to show, or `null` to show the target. */
    public val text: String? = null,
) {
    private val link = ClientSiteLink(prefix, title, text)

    /**
     * The wikitext of this link. [forced] writes the leading colon that makes it a link in the sentence
     * rather than an interlanguage link filed at the bottom of the page.
     */
    public fun render(forced: Boolean = false): String = link.render(forced)

    /** The wikitext of this link, unforced. */
    override fun toString(): String = link.toString()
}

/**
 * Reads an expiry as the API spells one, or `null` for one of its spellings of "never".
 *
 * @throws IllegalArgumentException if [raw] is neither.
 */
@JsExport
public fun parseExpiry(raw: String): Date? = (Expiry.parse(raw) as? Expiry.At)?.let { dateOf(it.instant) }

/**
 * Compares two MediaWiki versions as numbers, and a suffix loses to its absence: negative when [first] is
 * older than [second], positive when newer, 0 when the same. `1.10` is newer than `1.9`, and `1.44.0-wmf.3`
 * older than `1.44.0`.
 */
@JsExport
public fun compareMediaWikiVersions(first: String, second: String): Int =
    MediaWikiVersion.parse(first).compareTo(MediaWikiVersion.parse(second))
