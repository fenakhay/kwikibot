package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.internal.ElementTable
import com.fenakhay.kwikibot.wikitext.internal.NameSet

/**
 * What a wiki's configuration changes about how its wikitext parses.
 *
 * MediaWiki's grammar is not fixed: the extensions a wiki installs add tags whose bodies the preprocessor
 * steps over, and the URL schemes that make a link are a setting. A page parses the same way on two wikis
 * only when these agree. [DEFAULT] is what en.wikipedia and en.wiktionary run; a client opened on a wiki
 * reads the wiki's own values.
 *
 * @param extensionTags the tags the preprocessor treats as opaque, as `siprop=extensiontags` lists them
 *   without the brackets. A `{{` or `|` inside one belongs to the tag, not to the template around it.
 * @param parsedTags the extension tags whose body is itself wikitext, such as `ref` and `gallery`. A `<pre>`
 *   written with `format="wikitext"` is read as wikitext too, as MediaWiki reads it.
 * @param preprocessedTags the extension tags whose body has its templates expanded but is not otherwise
 *   parsed, such as `inputbox`: templates, arguments and comments are found there, and links and bold are
 *   text. Any other extension tag's body is kept verbatim, as one text node of a tag marked `verbatim`. The
 *   exception is `<DynamicPageList>`, where only the `category`, `notcategory` and `gallerycaption` values
 *   are expanded.
 * @param htmlTags the HTML elements the wiki allows. Anything else written like a tag is text, as MediaWiki
 *   shows it.
 * @param protocols the URL schemes that make a link, as `siprop=protocols` lists them. `//` makes a bracketed
 *   link only, never a bare one.
 * @param languageConversion whether `-{ }-` is markup. It is on every Wikimedia wiki, so a `|` inside it
 *   never splits the template around it.
 * @param fileNamespaces the names of the File namespace, whose links may hold other links in their caption.
 */
public data class ParseOptions(
    val extensionTags: Set<String> = WIKIMEDIA_EXTENSION_TAGS,
    val parsedTags: Set<String> = WIKITEXT_BODIED_TAGS,
    val preprocessedTags: Set<String> = PREPROCESSED_TAGS,
    val htmlTags: Set<String> = HTML_TAGS,
    val protocols: List<String> = WIKIMEDIA_PROTOCOLS,
    val languageConversion: Boolean = true,
    val fileNamespaces: Set<String> = setOf("File", "Image"),
) {
    /** The names as the parser looks them up: in place in the text, ignoring case. */
    internal val extensionTagNames: NameSet = NameSet(extensionTags)

    internal val parsedTagNames: NameSet = NameSet(parsedTags)

    internal val preprocessedTagNames: NameSet = NameSet(preprocessedTags)

    internal val htmlTagNames: NameSet = NameSet(htmlTags)

    /** What each allowed element is to HTML's tree building, for pairing tags. */
    internal val elements: ElementTable by lazy { ElementTable(htmlTags) }

    internal val fileNamespacesLower: Set<String> =
        fileNamespaces.mapTo(HashSet()) { it.lowercase().replace('_', ' ') }

    /** The schemes each ASCII character can begin, in both cases, longest first so `sips:` beats `sip:`. */
    internal val schemesByFirst: Array<List<String>?> =
        arrayOfNulls<List<String>>(ASCII).also { table ->
            for (scheme in protocols.sortedByDescending { it.length }) {
                val first = scheme.firstOrNull() ?: continue
                for (code in setOf(first.lowercaseChar().code, first.uppercaseChar().code)) {
                    if (code < ASCII) table[code] = (table[code] ?: emptyList()) + scheme
                }
            }
        }

    /** The values en.wikipedia and en.wiktionary run, and the lists they are made from. */
    public companion object {
        private const val ASCII = 128

        /**
         * The extension tags of en.wiktionary and en.wikipedia together. Other Wikimedia wikis add their own,
         * such as `pages` on Wikisource and `quiz` on Wikiversity, which a client opened there reads.
         */
        public val WIKIMEDIA_EXTENSION_TAGS: Set<String> =
            setOf(
                "pre",
                "nowiki",
                "gallery",
                "indicator",
                "langconvert",
                "graph",
                "timeline",
                "hiero",
                "charinsert",
                "ref",
                "references",
                "inputbox",
                "imagemap",
                "source",
                "syntaxhighlight",
                "poem",
                "categorytree",
                "section",
                "score",
                "dynamicpagelist",
                "templatestyles",
                "templatedata",
                "math",
                "ce",
                "chem",
                "maplink",
                "mapframe",
                "page-collection",
                "phonos",
            )

        /**
         * The extension tags whose body is read here as wikitext, which the extension parses in turn.
         *
         * Most other tags' bodies are code, data or markup of their own: TeX, JSON, Lilypond, a category
         * name. Treating those as wikitext would find templates in a JSON string and let a tidying pass
         * rewrite source code.
         */
        public val WIKITEXT_BODIED_TAGS: Set<String> =
            setOf("ref", "references", "gallery", "poem", "indicator", "imagemap", "langconvert", "phonos")

        /** The extension tags whose body MediaWiki only expands templates in: InputBox's configuration. */
        public val PREPROCESSED_TAGS: Set<String> = setOf("inputbox")

        /** The elements MediaWiki's sanitizer lets through, from `Sanitizer::getRecognizedTagData`. */
        public val HTML_TAGS: Set<String> =
            setOf(
                "b",
                "bdi",
                "del",
                "i",
                "ins",
                "u",
                "font",
                "big",
                "small",
                "sub",
                "sup",
                "h1",
                "h2",
                "h3",
                "h4",
                "h5",
                "h6",
                "cite",
                "code",
                "em",
                "s",
                "strike",
                "strong",
                "tt",
                "var",
                "div",
                "center",
                "blockquote",
                "ol",
                "ul",
                "dl",
                "table",
                "caption",
                "pre",
                "ruby",
                "rb",
                "rp",
                "rt",
                "rtc",
                "p",
                "span",
                "abbr",
                "dfn",
                "kbd",
                "samp",
                "data",
                "time",
                "mark",
                "br",
                "wbr",
                "hr",
                "li",
                "dt",
                "dd",
                "meta",
                "link",
                "tr",
                "td",
                "th",
                "q",
                "bdo",
            )

        /** The URL schemes of Wikimedia's wikis, `$wgUrlProtocols` there. */
        public val WIKIMEDIA_PROTOCOLS: List<String> =
            listOf(
                "bitcoin:",
                "ftp://",
                "ftps://",
                "geo:",
                "git://",
                "gopher://",
                "http://",
                "https://",
                "irc://",
                "ircs://",
                "magnet:",
                "mailto:",
                "matrix:",
                "mms://",
                "news:",
                "nntp://",
                "redis://",
                "sftp://",
                "sip:",
                "sips:",
                "sms:",
                "ssh://",
                "svn://",
                "tel:",
                "telnet://",
                "urn:",
                "wikipedia://",
                "worldwind://",
                "xmpp:",
                "//",
            )

        /** What en.wikipedia and en.wiktionary run. */
        public val DEFAULT: ParseOptions = ParseOptions()
    }
}
