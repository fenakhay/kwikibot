import com.fenakhay.kwikibot.bot.fix.Diffs
import com.fenakhay.kwikibot.bot.fix.Fix as ClientFix
import com.fenakhay.kwikibot.bot.fix.Fixes
import com.fenakhay.kwikibot.bot.fix.Messages as ClientMessages
import com.fenakhay.kwikibot.bot.fix.Replacement as ClientReplacement
import com.fenakhay.kwikibot.bot.fix.Unlinker as ClientUnlinker
import com.fenakhay.kwikibot.model.title.Title as ParsedTitle
import com.fenakhay.kwikibot.wikitext.ParseOptions as ClientParseOptions
import kotlin.js.RegExp

/** How many unchanged lines `unifiedDiff` shows around each change unless told. */
@JsExport public const val DIFF_DEFAULT_CONTEXT: Int = Diffs.DEFAULT_CONTEXT

/**
 * A unified diff of one page edit, for review before anything is saved, or `""` when nothing changes.
 *
 * @param before the text as it stands.
 * @param after the text as it would be.
 * @param title the page, for the header.
 * @param context how many unchanged lines to show around each change.
 */
@JsExport
public fun unifiedDiff(
    before: String,
    after: String,
    title: String,
    context: Int = Diffs.DEFAULT_CONTEXT,
): String = Diffs.unified(before, after, title, context)

/** One search and replace within a fix. */
@JsExport
public external interface Replacement {
    /** What to look for. Its `i` and `m` flags apply; `s` and `y` are refused. */
    public val pattern: RegExp

    /** What to put in its place, taken as it is: `$1` in it is a dollar sign and a one. */
    public val replacement: String
}

/** A named bundle of replacements. */
@JsExport
public class Fix internal constructor(internal val fix: ClientFix) {

    /** How the fix is named on a command line. */
    public val name: String
        get() = fix.name

    /** What it does, shown when the fixes are listed. */
    public val description: String
        get() = fix.description

    /** The substitutions it makes, applied in order. */
    public val replacements: Array<Replacement>
        get() =
            fix.replacements
                .map { replacement ->
                    val plain: dynamic = js("({})")
                    plain.pattern = regExpOf(replacement.pattern)
                    plain.replacement = replacement.replacement
                    plain.unsafeCast<Replacement>()
                }
                .toTypedArray()

    /** Which text the replacements may touch. */
    public val scope: TextScopeOptions
        get() = textScopeOptions(fix.scope)

    /** [wikitext] with this fix applied, parsed as a wiki with [options] parses it, when given. */
    public fun apply(wikitext: String, options: ParseOptions? = null): String =
        fix.apply(wikitext, options?.options ?: ClientParseOptions.DEFAULT)

    /** The page, already parsed, with this fix applied. */
    public fun applyParsed(code: Wikitext): Wikitext = Wikitext(fix.apply(code.markup))

    /** Whether this fix would change [wikitext], parsed as a wiki with [options] parses it, when given. */
    public fun wouldChange(wikitext: String, options: ParseOptions? = null): Boolean =
        fix.wouldChange(wikitext, options?.options ?: ClientParseOptions.DEFAULT)
}

/**
 * Makes a fix.
 *
 * @param name how the fix is named on a command line.
 * @param description what it does, shown when the fixes are listed.
 * @param replacements the substitutions it makes, applied in order.
 * @param scope which text they may touch: prose unless given, so a fix cannot rewrite a template parameter or
 *   a link's target by accident.
 */
@JsExport
public fun createFix(
    name: String,
    description: String,
    replacements: Array<Replacement>,
    scope: TextScopeOptions? = null,
): Fix =
    Fix(
        ClientFix(
            name,
            description,
            replacements.map { ClientReplacement(it.pattern.toRegex(), it.replacement) },
            scopeOf(scope),
        )
    )

/** Runs of spaces inside a line reduced to one. Prose only: runs of spaces lay out tables and lists. */
@JsExport public val FIX_EXTRA_SPACES: Fix = Fix(Fixes.EXTRA_SPACES)

/** A range of years written with a hyphen given an en dash. */
@JsExport public val FIX_DATE_RANGES: Fix = Fix(Fixes.DATE_RANGES)

/** Exactly three dots given the ellipsis character. */
@JsExport public val FIX_ELLIPSIS: Fix = Fix(Fixes.ELLIPSIS)

/** Every fix this library ships, plus any that were registered, by name. */
@JsExport public fun allFixes(): Record<String, Fix> = Fixes.all.toRecord(::Fix)

/** The registered fix called [name], or `null`. */
@JsExport public fun fixNamed(name: String): Fix? = Fixes[name]?.let(::Fix)

/** Adds [fix] to the registry, replacing one of the same name. */
@JsExport
public fun registerFix(fix: Fix) {
    Fixes.register(fix.fix)
}

/**
 * The registered fixes [names] calls for, in that order.
 *
 * @throws IllegalArgumentException for one there is not, naming those there are.
 */
@JsExport
public fun fixesNamed(names: Array<String>): Array<Fix> =
    Fixes.named(names.toList()).map(::Fix).toTypedArray()

/**
 * Edit summaries and other text in the language of the wiki being edited. Bundles are keyed by language and
 * support `$1` placeholders and `{{PLURAL:}}`, by the language's CLDR rule. A key missing in the language
 * falls back through its chain and then to English; one missing everywhere comes back as the key itself.
 */
@JsExport
public class Messages internal constructor(private val messages: ClientMessages) {

    /** The message for [key] in [language], with `$1`, `$2`… replaced by [values] in order. */
    public fun get(key: String, language: String, values: Array<MessageValue> = emptyArray()): String =
        messages.get(key, language, *values)

    /** Whether [key] exists in [language] or anything it falls back to. */
    public fun has(key: String, language: String): Boolean = messages.has(key, language)

    /** The languages this holds messages for. */
    public val languages: Array<String>
        get() = messages.languages.toTypedArray()

    /** The languages tried for [language], in order, ending in English. */
    public fun chain(language: String): Array<String> = messages.chain(language).toTypedArray()
}

/** The language every chain of `Messages` ends at. */
@JsExport public const val MESSAGES_DEFAULT_LANGUAGE: String = ClientMessages.DEFAULT

/** Which languages `Messages` tries when one has no message, unless told otherwise. */
@JsExport
public fun messageFallbacks(): Record<String, Array<String>> =
    ClientMessages.FALLBACKS.toRecord { it.toTypedArray() }

/**
 * Messages from bundles already parsed.
 *
 * @param bundles each language's messages, by key.
 * @param fallbacks which languages to try when one has no message; `messageFallbacks()` unless given.
 */
@JsExport
public fun createMessages(
    bundles: Record<String, Record<String, String>>,
    fallbacks: Record<String, Array<String>>? = null,
): Messages {
    val parsed = bundles.toMap().mapValues { (_, messages) -> messages.toMap() }
    return Messages(
        if (fallbacks == null) ClientMessages.of(parsed)
        else ClientMessages(parsed, fallbacks.toMap().mapValues { (_, chain) -> chain.toList() })
    )
}

/**
 * Messages from JSON bundles, one per language. The format is MediaWiki's own, a flat object of key to
 * message with `@metadata` ignored, so a bundle can be taken from translatewiki unchanged.
 */
@JsExport
public fun messagesFromJson(bundles: Record<String, String>): Messages =
    Messages(ClientMessages.fromJson(bundles.toMap()))

/**
 * Removes links to one page, keeping the words that were linked, as before a page is deleted. `[[volcano]]`
 * becomes `volcano` and `[[volcano|volcanoes]]` becomes `volcanoes`, at any depth.
 */
@JsExport
public class Unlinker(
    /**
     * Whether the wiki capitalises the first letter of a title, as nearly every wiki but Wiktionary does;
     * true unless given. It decides whether `[[Volcano]]` and `[[volcano]]` are the same link.
     */
    capitalLinks: Boolean = true
) {
    private val unlinker = ClientUnlinker(capitalLinks)

    /** [wikitext] with every link to [target] replaced by the words it was showing. */
    public fun unlink(wikitext: String, target: Title): String = unlinker.unlink(wikitext, localOf(target))

    /** The page, already parsed, with every link to [target] replaced by the words it was showing. */
    public fun unlinkParsed(code: Wikitext, target: Title): Wikitext =
        Wikitext(unlinker.unlink(code.markup, localOf(target)))

    /** Whether [wikitext] links to [target]. */
    public fun linksTo(wikitext: String, target: Title): Boolean = unlinker.linksTo(wikitext, localOf(target))

    /** Whether the page, already parsed, links to [target]. */
    public fun linksToParsed(code: Wikitext, target: Title): Boolean =
        unlinker.linksTo(code.markup, localOf(target))

    private fun localOf(target: Title): ParsedTitle.Local =
        requireNotNull(target.title as? ParsedTitle.Local) { "'$target' is not a page of this wiki" }
}
