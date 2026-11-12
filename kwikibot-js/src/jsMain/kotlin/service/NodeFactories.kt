import com.fenakhay.kwikibot.bindings.wikitext.tagOf
import com.fenakhay.kwikibot.wikitext.node.Argument as ParsedArgument
import com.fenakhay.kwikibot.wikitext.node.Attribute as ParsedAttribute
import com.fenakhay.kwikibot.wikitext.node.Comment as ParsedComment
import com.fenakhay.kwikibot.wikitext.node.ExternalLink as ParsedExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading as ParsedHeading
import com.fenakhay.kwikibot.wikitext.node.HtmlEntity as ParsedHtmlEntity
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink as ParsedLink

/** Plain text as a node. */
@JsExport public fun createText(text: String): Node = Node(TextNode(text))

/** A comment holding [contents]. */
@JsExport
public fun createComment(contents: String, closed: Boolean = true): Comment =
    Comment(ParsedComment(contents, closed))

/** A template's argument called [name], with what to use when it is not supplied. */
@JsExport
public fun createArgument(name: Wikitext, defaultValue: Wikitext? = null): Argument =
    Argument(ParsedArgument(name.markup, defaultValue?.markup))

/** A link to the page [target], showing [text] when it is given. */
@JsExport
public fun createLink(target: Wikitext, text: Wikitext? = null): Link =
    Link(ParsedLink(target.markup, text?.markup))

/** A link off the wiki to [url], labelled [title]; [brackets] is off for a bare URL in running text. */
@JsExport
public fun createExternalLink(
    url: Wikitext,
    title: Wikitext? = null,
    brackets: Boolean = true,
    separator: String = " ",
): ExternalLink = ExternalLink(ParsedExternalLink(url.markup, title?.markup, brackets, separator))

/** A heading with [level] equals signs on each side. */
@JsExport
public fun createHeading(title: Wikitext, level: Int): Heading = Heading(ParsedHeading(title.markup, level))

/** An entity with the body [value]; [hexChar] is the `x` or `X` of a hexadecimal one. */
@JsExport
public fun createHtmlEntity(value: String, numeric: Boolean = false, hexChar: HexMarker? = null): HtmlEntity =
    HtmlEntity(ParsedHtmlEntity(value, numeric, hexChar.unsafeCast<String?>()))

/** An attribute called [name]; [quote] is the quote character, and the rest the whitespace around it. */
@JsExport
public fun createAttribute(
    name: Wikitext,
    value: Wikitext? = null,
    quote: QuoteMark? = null,
    padFirst: String = " ",
    padBeforeEq: String = "",
    padAfterEq: String = "",
): Attribute =
    Attribute(
        ParsedAttribute(
            name.markup,
            value?.markup,
            quote.unsafeCast<String?>(),
            padFirst,
            padBeforeEq,
            padAfterEq,
        )
    )

/** What a tag is made with beyond its name. */
@JsExport
public external interface TagOptions {
    /** What sits between the opening and closing tags. */
    public val contents: Wikitext?

    /** Its attributes. */
    public val attributes: Array<Attribute>?

    /** Whether it is written as `<br />` rather than as a pair. */
    public val selfClosing: Boolean?

    /** The markup to write in place of HTML: `'''` for bold, `*` for a list item. */
    public val wikiMarkup: String?

    /** The whitespace before the closing `>`. */
    public val padding: String?

    /** Whether the closing tag is absent. */
    public val implicitClose: Boolean?

    /** The closing tag to write when it differs from `</name>`. */
    public val closing: String?

    /** Whether [contents] is the body as written rather than parsed. */
    public val verbatim: Boolean?
}

/** A tag called [name]. */
@JsExport
public fun createTag(name: String, options: TagOptions? = null): Tag =
    Tag(
        tagOf(
            name,
            options?.contents?.markup,
            options?.attributes?.map { it.attribute },
            options?.selfClosing,
            options?.wikiMarkup,
            options?.padding,
            options?.implicitClose,
            options?.closing,
            options?.verbatim,
        )
    )
