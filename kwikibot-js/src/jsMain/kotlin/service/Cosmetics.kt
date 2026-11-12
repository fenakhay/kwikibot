import com.fenakhay.kwikibot.wikitext.CosmeticChanges as ClientCosmeticChanges
import com.fenakhay.kwikibot.wikitext.CosmeticPass as ClientCosmeticPass
import com.fenakhay.kwikibot.wikitext.Isbn as ClientIsbn
import com.fenakhay.kwikibot.wikitext.ParseOptions as ClientParseOptions
import com.fenakhay.kwikibot.wikitext.ReformatIsbns

/**
 * One tidying pass over a page. Each is separate and named so a bot can choose, since what counts as tidy
 * differs between projects.
 */
@JsExport
public class CosmeticPass internal constructor(internal val pass: ClientCosmeticPass) {

    /** The page after this pass. */
    public fun apply(code: Wikitext): Wikitext = Wikitext(pass.apply(code.markup))
}

/** A pass that is [transform], which returns the page unchanged when it finds nothing. */
@JsExport
public fun cosmeticPass(transform: (code: Wikitext) -> Wikitext): CosmeticPass =
    CosmeticPass(ClientCosmeticPass { transform(Wikitext(it)).markup })

/** Trailing whitespace at the end of lines, outside what MediaWiki does not parse. */
@JsExport
public val COSMETIC_TRAILING_WHITESPACE: CosmeticPass =
    CosmeticPass(ClientCosmeticChanges.TRAILING_WHITESPACE)

/** Three or more blank lines in a row, reduced to two. */
@JsExport
public val COSMETIC_EXTRA_BLANK_LINES: CosmeticPass = CosmeticPass(ClientCosmeticChanges.EXTRA_BLANK_LINES)

/** `<b>` and `<i>` written as the wiki markup that means the same thing. */
@JsExport public val COSMETIC_HTML_EMPHASIS: CosmeticPass = CosmeticPass(ClientCosmeticChanges.HTML_EMPHASIS)

/** Entities decoded, apart from those that mean something as markup and `&nbsp;`. */
@JsExport public val COSMETIC_HTML_ENTITIES: CosmeticPass = CosmeticPass(ClientCosmeticChanges.HTML_ENTITIES)

/** A link whose display text is its own target: `[[foo|foo]]` becomes `[[foo]]`. */
@JsExport
public val COSMETIC_REDUNDANT_LINK_TEXT: CosmeticPass =
    CosmeticPass(ClientCosmeticChanges.REDUNDANT_LINK_TEXT)

/** `<br>` written as `<br />`. */
@JsExport
public val COSMETIC_SELF_CLOSING_BR: CosmeticPass = CosmeticPass(ClientCosmeticChanges.SELF_CLOSING_BR)

/** Sections with a heading and nothing under them. */
@JsExport
public val COSMETIC_EMPTY_SECTIONS: CosmeticPass = CosmeticPass(ClientCosmeticChanges.EMPTY_SECTIONS)

/**
 * Rewrites the ISBNs in the page's prose: with [toIsbn13], a valid ten-digit one becomes thirteen digits,
 * written the same way. One whose check digit does not add up is left exactly as written.
 */
@JsExport
public fun cosmeticReformatIsbns(toIsbn13: Boolean = false): CosmeticPass =
    CosmeticPass(ReformatIsbns(toIsbn13))

/** Tidying passes over a page. None is on by default: many wikis prohibit cosmetic-only edits. */
@JsExport
public class CosmeticChanges internal constructor(private val changes: ClientCosmeticChanges) {

    /** The page with every pass applied, in order. */
    public fun apply(code: Wikitext): Wikitext = Wikitext(changes.apply(code.markup))

    /**
     * The text with every pass applied, parsed the way a wiki with [options] reads it, when they are given.
     */
    public fun applyText(wikitext: String, options: ParseOptions? = null): String =
        changes.apply(wikitext, options?.options ?: ClientParseOptions.DEFAULT)

    /** Whether any pass would change the text, parsed the way a wiki with [options] reads it. */
    public fun wouldChange(wikitext: String, options: ParseOptions? = null): Boolean =
        changes.wouldChange(wikitext, options?.options ?: ClientParseOptions.DEFAULT)
}

/** A tidier applying [passes], in order. */
@JsExport
public fun cosmeticChanges(passes: Array<CosmeticPass>): CosmeticChanges =
    CosmeticChanges(ClientCosmeticChanges(passes.map { it.pass }))

/** The passes with no project-specific behaviour: whitespace, blank lines, and redundant link text. */
@JsExport public val COSMETIC_SAFE: CosmeticChanges = CosmeticChanges(ClientCosmeticChanges.SAFE)

/** An ISBN whose check digit adds up. */
@JsExport
public class Isbn internal constructor(private val isbn: ClientIsbn) {

    /** The digits, without hyphens or spaces, with any check character in upper case. */
    public val normalised: String
        get() = isbn.normalised

    /** Whether this is the thirteen-digit form. */
    public val isIsbn13: Boolean
        get() = isbn.isIsbn13

    /** This ISBN as thirteen digits. */
    public fun toIsbn13(): Isbn = Isbn(isbn.toIsbn13())

    /** The digits. */
    override fun toString(): String = isbn.toString()
}

/** Reads an ISBN, or `null` when it is the wrong length or its check digit does not add up. */
@JsExport public fun parseIsbn(raw: String): Isbn? = ClientIsbn.parse(raw)?.let(::Isbn)

/** Whether [raw] is a well-formed ISBN, check digit included. */
@JsExport public fun isValidIsbn(raw: String): Boolean = ClientIsbn.isValid(raw)
