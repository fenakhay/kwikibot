import com.fenakhay.kwikibot.wikitext.MagicWords as ClientMagicWords
import com.fenakhay.kwikibot.wikitext.ParseOptions as ClientParseOptions
import com.fenakhay.kwikibot.wikitext.TitleRules as ClientTitleRules

/** What a wiki's configuration changes about how its wikitext parses. What is left out is Wikimedia's. */
@JsExport
public external interface ParseOptionsInit {
    /** The tags whose bodies the preprocessor steps over, without their brackets. */
    public val extensionTags: Array<String>?

    /** The extension tags whose body is itself wikitext, such as `ref`. */
    public val parsedTags: Array<String>?

    /** The extension tags whose body has only its templates expanded, such as `inputbox`. */
    public val preprocessedTags: Array<String>?

    /** The HTML elements the wiki allows. */
    public val htmlTags: Array<String>?

    /** The URL schemes that make a link. */
    public val protocols: Array<String>?

    /** Whether `-{ }-` is markup. */
    public val languageConversion: Boolean?

    /** The names of the File namespace. */
    public val fileNamespaces: Array<String>?
}

/** What en.wikipedia and en.wiktionary parse with. Its lists are Wikimedia's own. */
@JsExport public fun defaultParseOptions(): ParseOptions = ParseOptions(ClientParseOptions.DEFAULT)

/** Parse options for a wiki that is not open, with what [options] gives in place of Wikimedia's. */
@JsExport
public fun createParseOptions(options: ParseOptionsInit): ParseOptions {
    val defaults = ClientParseOptions.DEFAULT
    return ParseOptions(
        ClientParseOptions(
            options.extensionTags?.toSet() ?: defaults.extensionTags,
            options.parsedTags?.toSet() ?: defaults.parsedTags,
            options.preprocessedTags?.toSet() ?: defaults.preprocessedTags,
            options.htmlTags?.toSet() ?: defaults.htmlTags,
            options.protocols?.toList() ?: defaults.protocols,
            options.languageConversion ?: defaults.languageConversion,
            options.fileNamespaces?.toSet() ?: defaults.fileNamespaces,
        )
    )
}

/** Names recognised in `{{...}}`, those in [caseSensitive] compared exactly, the others ignoring case. */
@JsExport
public fun createMagicWords(caseSensitive: Array<String>, caseInsensitive: Array<String>): MagicWords =
    MagicWords(ClientMagicWords(caseSensitive.toSet(), caseInsensitive.toSet()))

/** How a wiki names pages. What is left out is what every MediaWiki has. */
@JsExport
public external interface TitleRulesInit {
    /** Every namespace name and alias, with the namespace's number. */
    public val namespaces: Record<String, Int>?

    /** The name each namespace, by number, is written with in a key. */
    public val canonicalNames: Record<String, String>?

    /** The namespaces whose titles keep their first letter as written. */
    public val caseSensitive: Array<Int>?

    /** The parser functions written without a `#`. */
    public val functions: MagicWords?

    /** The variables, such as `PAGENAME`. */
    public val variables: MagicWords?

    /** The prefixes that save a template's expansion instead of the template, such as `subst`. */
    public val substitutions: Array<String>?

    /** The other prefixes that change how a template is transcluded, such as `msg`. */
    public val modifiers: Array<String>?
}

/** The rules every MediaWiki starts from. Its tables are the core ones. */
@JsExport public fun defaultTitleRules(): TitleRules = TitleRules(ClientTitleRules.DEFAULT)

/** Title rules for a wiki that is not open, with what [options] gives in place of the core ones. */
@JsExport
public fun createTitleRules(options: TitleRulesInit): TitleRules {
    val defaults = ClientTitleRules.DEFAULT
    return TitleRules(
        ClientTitleRules(
            options.namespaces?.toMap() ?: defaults.namespaces,
            options.canonicalNames?.toMap()?.mapKeys { it.key.toInt() } ?: defaults.canonicalNames,
            options.caseSensitive?.toSet() ?: defaults.caseSensitive,
            options.functions?.words ?: defaults.functions,
            options.variables?.words ?: defaults.variables,
            options.substitutions?.toSet() ?: defaults.substitutions,
            options.modifiers?.toSet() ?: defaults.modifiers,
        )
    )
}

/** MediaWiki's own ids for the core parser functions, whatever a wiki calls them. */
@JsExport public fun coreFunctionIds(): Array<String> = ClientTitleRules.CORE_FUNCTION_IDS.toTypedArray()
