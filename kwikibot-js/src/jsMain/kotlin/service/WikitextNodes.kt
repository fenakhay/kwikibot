import com.fenakhay.kwikibot.wikitext.SourceRanges as ClientSourceRanges
import com.fenakhay.kwikibot.wikitext.node.Argument as ParsedArgument
import com.fenakhay.kwikibot.wikitext.node.Attribute as ParsedAttribute
import com.fenakhay.kwikibot.wikitext.node.Comment as ParsedComment
import com.fenakhay.kwikibot.wikitext.node.ExternalLink as ParsedExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading as ParsedHeading
import com.fenakhay.kwikibot.wikitext.node.HtmlEntity as ParsedHtmlEntity
import com.fenakhay.kwikibot.wikitext.node.Node as ParsedNode
import com.fenakhay.kwikibot.wikitext.node.Tag as ParsedTag
import com.fenakhay.kwikibot.wikitext.node.Template as ParsedTemplate
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink as ParsedLink

/** Which text a replacement may touch. Prose's is what is left out: only headings are in unless opened up. */
@JsExport
public external interface TextScopeOptions {
    /** Text inside `{{template}}` names and parameters, and the names of `{{{arguments}}}`. */
    public val templates: Boolean?

    /** The target of a `[[link|text]]`, as opposed to its display text. */
    public val linkTargets: Boolean?

    /** Text inside `<!-- comments -->`. */
    public val comments: Boolean?

    /** Text inside `<nowiki>`, `<pre>` and other tags MediaWiki does not parse. */
    public val rawTags: Boolean?

    /** Text in `== headings ==`; in unless this says otherwise. */
    public val headings: Boolean?

    /** Text inside tag attributes. */
    public val tagAttributes: Boolean?
}

/** One match of a regular expression in the text, as a replacement is given it. */
@JsExport
public class TextMatch internal constructor(private val match: MatchResult) {

    /** The text matched. */
    public val value: String
        get() = match.value

    /** Each group's text, the whole match first; `""` for a group that took no part. */
    public val groups: Array<String>
        get() = match.groupValues.toTypedArray()

    /** Where the match starts in the piece of text it was found in. */
    public val index: Int
        get() = match.range.first
}

/**
 * One piece of parsed wikitext. [kind] says which it is, and so which field is set; every node writes itself
 * back exactly as it was read.
 */
@JsExport
public class Node internal constructor(internal val node: ParsedNode) {

    /**
     * `"text"`, `"comment"`, `"template"`, `"argument"`, `"wiki-link"`, `"external-link"`, `"heading"`,
     * `"html-entity"` or `"tag"`.
     */
    public val kind: NodeKind
        get() =
            when (node) {
                is TextNode -> "text"
                is ParsedComment -> "comment"
                is ParsedTemplate -> "template"
                is ParsedArgument -> "argument"
                is ParsedLink -> "wiki-link"
                is ParsedExternalLink -> "external-link"
                is ParsedHeading -> "heading"
                is ParsedHtmlEntity -> "html-entity"
                is ParsedTag -> "tag"
            }.unsafeCast<NodeKind>()

    /** A `"text"`'s text. */
    public val text: String?
        get() = (node as? TextNode)?.text

    /** A `"comment"`. */
    public val comment: Comment?
        get() = (node as? ParsedComment)?.let(::Comment)

    /** A `"template"`. */
    public val template: Template?
        get() = (node as? ParsedTemplate)?.let(::Template)

    /** An `"argument"`. */
    public val argument: Argument?
        get() = (node as? ParsedArgument)?.let(::Argument)

    /** A `"wiki-link"`. */
    public val link: Link?
        get() = (node as? ParsedLink)?.let(::Link)

    /** An `"external-link"`. */
    public val externalLink: ExternalLink?
        get() = (node as? ParsedExternalLink)?.let(::ExternalLink)

    /** A `"heading"`. */
    public val heading: Heading?
        get() = (node as? ParsedHeading)?.let(::Heading)

    /** An `"html-entity"`. */
    public val htmlEntity: HtmlEntity?
        get() = (node as? ParsedHtmlEntity)?.let(::HtmlEntity)

    /** A `"tag"`. */
    public val tag: Tag?
        get() = (node as? ParsedTag)?.let(::Tag)

    /** This node as wikitext. */
    override fun toString(): String = node.serialize()
}

/** A comment, `<!-- ... -->`. */
@JsExport
public class Comment internal constructor(internal val comment: ParsedComment) {

    /** What sits between the markers. */
    public val contents: String
        get() = comment.contents

    /** Whether it ends with `-->`. One that does not runs to the end of the page. */
    public val closed: Boolean
        get() = comment.closed

    /** This comment as a node. */
    public val node: Node
        get() = Node(comment)

    /** This comment as wikitext. */
    override fun toString(): String = comment.serialize()
}

/** A template's argument, `{{{name|default}}}`. */
@JsExport
public class Argument internal constructor(internal val argument: ParsedArgument) {

    /** The argument's name, as written between the braces. */
    public val name: Wikitext
        get() = Wikitext(argument.name)

    /** What to use when the argument is not supplied, or `null` when the markup names nothing. */
    public val defaultValue: Wikitext?
        get() = argument.default?.let(::Wikitext)

    /** This argument as a node. */
    public val node: Node
        get() = Node(argument)

    /** This argument as wikitext. */
    override fun toString(): String = argument.serialize()
}

/** A link off the wiki, `[url title]`, or a bare URL in running text. */
@JsExport
public class ExternalLink internal constructor(internal val link: ParsedExternalLink) {

    /** The URL, which may hold a template its expansion completes. */
    public val url: Wikitext
        get() = Wikitext(link.url)

    /** The label after the URL, or `null`. */
    public val title: Wikitext?
        get() = link.title?.let(::Wikitext)

    /** Whether it was bracketed. A bare URL in running text was not. */
    public val brackets: Boolean
        get() = link.brackets

    /** What is written between the URL and a label, `" "` when there is none to separate. */
    public val separator: String
        get() = link.separator

    /** This link as a node. */
    public val node: Node
        get() = Node(link)

    /** This link as wikitext. */
    override fun toString(): String = link.serialize()
}

/** A heading, `== Heading ==`. */
@JsExport
public class Heading internal constructor(internal val heading: ParsedHeading) {

    /** The text between the equals signs. */
    public val title: Wikitext
        get() = Wikitext(heading.title)

    /** How many equals signs are on each side: 2 for a top-level section. */
    public val level: Int
        get() = heading.level

    /** This heading as a node. */
    public val node: Node
        get() = Node(heading)

    /** This heading as wikitext. */
    override fun toString(): String = heading.serialize()
}

/** An entity, `&amp;`, `&#65;` or `&#x41;`. */
@JsExport
public class HtmlEntity internal constructor(internal val entity: ParsedHtmlEntity) {

    /** The entity's body: a name, or the digits of a numeric one. */
    public val value: String
        get() = entity.value

    /** Whether it was written as a number rather than as a name. */
    public val numeric: Boolean
        get() = entity.numeric

    /** The `x` or `X` as written in a hexadecimal one, or `null`. */
    public val hexChar: HexMarker?
        get() = entity.hexChar.unsafeCast<HexMarker?>()

    /** This entity as a node. */
    public val node: Node
        get() = Node(entity)

    /** This entity as wikitext. */
    override fun toString(): String = entity.serialize()
}

/** One attribute of a tag, with the whitespace that surrounded it. */
@JsExport
public class Attribute internal constructor(internal val attribute: ParsedAttribute) {

    /** The attribute's name as written. */
    public val name: Wikitext
        get() = Wikitext(attribute.name)

    /** Its value, or `null` for a bare attribute such as `nowrap`. */
    public val value: Wikitext?
        get() = attribute.value?.let(::Wikitext)

    /** The quote character used, or `null` where the value was unquoted. */
    public val quote: QuoteMark?
        get() = attribute.quote.unsafeCast<QuoteMark?>()

    /** The whitespace before the name. */
    public val padFirst: String
        get() = attribute.padFirst

    /** The whitespace before the equals sign. */
    public val padBeforeEq: String
        get() = attribute.padBeforeEq

    /** The whitespace after the equals sign. */
    public val padAfterEq: String
        get() = attribute.padAfterEq

    /** This attribute as it was written. */
    override fun toString(): String = attribute.serialize()
}

/** A tag: `<ref>...</ref>`, `<br />`, or the wiki markup that stands in for one. */
@JsExport
public class Tag internal constructor(internal val tag: ParsedTag) {

    /** The tag's name as written; MediaWiki compares it ignoring case. */
    public val name: String
        get() = tag.name

    /** What sits between the opening and closing tags, or `null`. */
    public val contents: Wikitext?
        get() = tag.contents?.let(::Wikitext)

    /** Its attributes, in the order written. */
    public val attributes: Array<Attribute>
        get() = tag.attributes.map(::Attribute).toTypedArray()

    /** Whether it was written as `<br />` rather than as a pair. */
    public val selfClosing: Boolean
        get() = tag.selfClosing

    /** The markup that made the tag when it was not written as HTML: `'''` for bold, `*` for a list item. */
    public val wikiMarkup: String?
        get() = tag.wikiMarkup

    /** The whitespace before the closing `>`. */
    public val padding: String
        get() = tag.padding

    /** Whether the closing tag was absent: a `<br>`, or an opening tag MediaWiki ends somewhere else. */
    public val implicitClose: Boolean
        get() = tag.implicitClose

    /** The closing tag as written when it differs from `</name>`, or `null`. */
    public val closing: String?
        get() = tag.closing

    /** Whether [contents] is the body as written rather than parsed, as in `<nowiki>` or `<pre>`. */
    public val verbatim: Boolean
        get() = tag.verbatim

    /** This tag as a node. */
    public val node: Node
        get() = Node(tag)

    /** This tag as wikitext. */
    override fun toString(): String = tag.serialize()
}

/** Where a piece of wikitext sits in the text it writes back as: from [start] up to just before [end]. */
@JsExport
public class SourceRange
internal constructor(
    /** Where it starts, counted in UTF-16 code units as JavaScript counts them. */
    public val start: Int,
    /** Where it ends, just past its last code unit. */
    public val end: Int,
)

/** Where the pieces of parsed wikitext sit in its text, from `Wikitext.ranges`. */
@JsExport
public class SourceRanges internal constructor(private val ranges: ClientSourceRanges) {

    /** Where [node] was written, or `null` when it is not part of the wikitext these came from. */
    public fun ofNode(node: Node): SourceRange? = rangeOf(ranges[node.node])

    /** Where [parameter] was written, its name and `=` included for a named one. */
    public fun ofParameter(parameter: Parameter): SourceRange? = rangeOf(ranges[parameter.parameter])

    /** Where [attribute] was written, the whitespace before it included. */
    public fun ofAttribute(attribute: Attribute): SourceRange? = rangeOf(ranges[attribute.attribute])

    /** Where [wikitext], such as a parameter's value, was written. */
    public fun ofWikitext(wikitext: Wikitext): SourceRange? = rangeOf(ranges[wikitext.markup])

    private fun rangeOf(range: IntRange?): SourceRange? = range?.let { SourceRange(it.first, it.last + 1) }
}
