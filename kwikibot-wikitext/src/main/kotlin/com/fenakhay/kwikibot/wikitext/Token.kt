package com.fenakhay.kwikibot.wikitext

/**
 * One unit of wikitext, as the tokenizer sees it.
 *
 * Tokens are a flat stream, not a tree: the tokenizer decides what each piece of syntax *is*,
 * and the builder assembles the tree afterwards. Splitting the job in two is what makes the
 * tokenizer a plain state machine and keeps backtracking cheap — a construct that turns out not
 * to parse is abandoned by discarding a list, not by unwinding a tree.
 *
 * The vocabulary is fixed rather than convenient: recorded token streams are replayed against
 * it by the conformance suite, so renaming or merging a token changes the contract.
 */
internal sealed interface Token {

    /** Literal text, with no markup meaning. */
    data class Text(
        /** The text itself. */
        val text: String,
    ) : Token

    /** `{{` */
    data object TemplateOpen : Token

    /** The `|` between a template name or parameter and the next parameter. */
    data object ParameterSeparator : Token

    /** The `=` that turns a positional parameter into a named one. */
    data object ParameterEquals : Token

    /** `}}` */
    data object TemplateClose : Token

    /** `{{{` */
    data object ArgumentOpen : Token

    /** The `|` before an argument's default value. */
    data object ArgumentSeparator : Token

    /** `}}}` */
    data object ArgumentClose : Token

    /** `[[` */
    data object WikiLinkOpen : Token

    /** The `|` between a wikilink target and its display text. */
    data object WikiLinkSeparator : Token

    /** `]]` */
    data object WikiLinkClose : Token

    /**
     * The start of an external link.
     *
     * @param brackets whether the link was written as `[url text]` rather than appearing bare
     *   in running text, which decides how MediaWiki renders it.
     */
    data class ExternalLinkOpen(val brackets: Boolean) : Token

    /** The space between a bracketed link's URL and its display text. */
    data object ExternalLinkSeparator : Token

    /** The end of an external link. */
    data object ExternalLinkClose : Token

    /** `&` */
    data object EntityStart : Token

    /** The `#` of a numeric entity. */
    data object EntityNumeric : Token

    /** The `x` of a hexadecimal entity, preserving the case it was written in. */
    data class EntityHex(
        /** The `x` or `X` as written, so the entity rebuilds in its own case. */
        val char: String,
    ) : Token

    /** `;` */
    data object EntityEnd : Token

    /** The `=` run that opens a heading, with the level it implies. */
    data class HeadingStart(
        /** How many equals signs opened it, which is the heading level. */
        val level: Int,
    ) : Token

    /** The `=` run that closes a heading. */
    data object HeadingEnd : Token

    /** `<!--` */
    data object CommentStart : Token

    /** `-->` */
    data object CommentEnd : Token

    /**
     * The `<` that opens a tag, or the wiki markup standing in for one.
     *
     * @param wikiMarkup the markup that produced the tag when it was not written as HTML:
     *   `'''` for bold, `*` for a list item. Absent for a real `<tag>`, which is how the two are
     *   told apart when the text is written back out.
     */
    data class OpeningTagStart(val wikiMarkup: String? = null) : Token

    /**
     * The start of one attribute inside a tag.
     *
     * The three paddings are the whitespace around the attribute, kept so the tag can be
     * rebuilt byte for byte: `<ref  name = "a">` differs from `<ref name="a">` only in these.
     */
    data class AttributeStart(
        /** The whitespace before the attribute name. */
        val padFirst: String,
        /** The whitespace before the equals sign. */
        val padBeforeEq: String,
        /** The whitespace after the equals sign. */
        val padAfterEq: String,
    ) : Token

    /** The `=` between an attribute and its value. */
    data object AttributeEquals : Token

    /** The quote around an attribute value, preserving which quote character was used. */
    data class AttributeQuote(
        /** Which quote character was used, so the attribute rebuilds as written. */
        val char: String,
    ) : Token

    /** The `>` that ends an opening tag, with any padding before it. */
    data class OpeningTagEnd(
        /** The whitespace before the `>`. */
        val padding: String? = null,
    ) : Token

    /**
     * The end of a self-closing tag, with any padding before it.
     *
     * @param padding the whitespace before the closing marker.
     * @param implicit whether the tag closed with a bare `>` rather than `/>`, as `<br>` does.
     *   Without this the two forms are indistinguishable, and a bot editing a page would
     *   silently rewrite every `<br>` as `<br/>`.
     */
    data class SelfClosingTagEnd(
        val padding: String? = null,
        val implicit: Boolean = false,
    ) : Token

    /** The `</` that opens a closing tag. */
    data object ClosingTagStart : Token

    /** The `>` that ends a closing tag. */
    data object ClosingTagEnd : Token
}
