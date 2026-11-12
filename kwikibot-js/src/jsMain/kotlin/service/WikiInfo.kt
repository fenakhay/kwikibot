import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.title.InterwikiMap
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceInfo as ClientNamespaceInfo
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title.Local as ParsedLocalTitle
import com.fenakhay.kwikibot.model.user.TempAccountConfig as ClientTempAccountConfig
import com.fenakhay.kwikibot.net.auth.Identity as ClientIdentity
import com.fenakhay.kwikibot.net.auth.TokenStore as ClientTokenStore
import com.fenakhay.kwikibot.protocol.ModuleDescription as ClientModuleDescription
import com.fenakhay.kwikibot.protocol.ParamDescription as ClientParamDescription
import com.fenakhay.kwikibot.protocol.ParamInfo as ClientParamInfo
import com.fenakhay.kwikibot.protocol.SiteInfo as ClientSiteInfo
import com.fenakhay.kwikibot.wikitext.MagicWords as ClientMagicWords
import com.fenakhay.kwikibot.wikitext.ParseOptions as ClientParseOptions
import com.fenakhay.kwikibot.wikitext.TitleRules as ClientTitleRules
import com.fenakhay.kwikibot.wikitext.Wikitext as Parser
import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise

/** How `wiki.call` sends a request. */
@JsExport
public external interface CallOptions {
    /**
     * Whether this changes the wiki, which sends it by POST and paces it as a write. `false` unless given.
     */
    public val write: Boolean?

    /** Whether a response cache may answer it, `true` unless given. */
    public val cacheable: Boolean?
}

/** [params] as `action=...` parameters, as `wiki.call` documents. */
internal fun apiParameters(params: Record<String, ApiValue>): Map<String, String> =
    params.toMap().mapNotNull { (name, value) -> apiValue(name, value)?.let { name to it } }.toMap()

private fun apiValue(name: String, value: Any?): String? =
    when (value) {
        null,
        false -> null
        true -> "1"
        is String -> value
        is Number -> value.toString()
        is Date -> value.toISOString()
        is Array<*> -> value.joinToString("|") { requireNotNull(apiValue(name, it)) { noValue(name) } }
        else -> throw IllegalArgumentException(noValue(name))
    }

private fun noValue(name: String): String =
    "parameter '$name' has to be a string, a number, a boolean, a date or an array of those"

/** What a wiki says about itself, read once when it is opened. */
@JsExport
public class SiteInfo internal constructor(private val info: ClientSiteInfo) {

    /** The wiki's database name, such as `"enwiktionary"`. */
    public val id: String
        get() = info.id.dbName

    /** The name the wiki calls itself, such as `"Wiktionary"`. */
    public val siteName: String
        get() = info.siteName

    /** Its content language, which is not always the language of its titles. */
    public val language: String
        get() = info.language.code

    /** Its host, without a scheme. */
    public val server: String
        get() = info.server

    /** The path pattern articles are served under, carrying a `$1` for the title. */
    public val articlePath: String
        get() = info.articlePath

    /** The title of its main page. */
    public val mainPage: String
        get() = info.mainPage

    /** The MediaWiki version string, such as `"MediaWiki 1.47.0-wmf.17"`. */
    public val generator: String
        get() = info.generator

    /** The MediaWiki version alone, such as `"1.47.0-wmf.17"`. */
    public val version: String
        get() = info.version

    /** Its namespaces. */
    public val namespaces: Namespaces
        get() = Namespaces(info.namespaces)

    /** Its interwiki prefixes. */
    public val interwiki: Interwiki
        get() = Interwiki(info.interwiki)

    /** The zone the wiki writes local times in, such as `"Europe/Berlin"`. */
    public val timezone: String
        get() = info.timezone

    /** Minutes that zone is ahead of UTC at the moment the site information was read. */
    public val timeOffsetMinutes: Int
        get() = info.timeOffsetMinutes

    /** Whether this wiki creates temporary accounts, and the shape of their names. */
    public val tempAccounts: TempAccountConfig
        get() = TempAccountConfig(info.tempAccounts)

    /** The extensions installed, by name. */
    public val extensions: Array<String>
        get() = info.extensions.toTypedArray()

    /** Whether [extension] is installed, compared ignoring case. */
    public fun hasExtension(extension: String): Boolean = info.hasExtension(extension)
}

/** Whether a wiki creates temporary accounts, and the shape of their names. */
@JsExport
public class TempAccountConfig internal constructor(private val config: ClientTempAccountConfig) {

    /** Whether the wiki creates them. */
    public val enabled: Boolean
        get() = config.enabled

    /** The name patterns, each with `$1` where the generated part goes. */
    public val matchPatterns: Array<String>
        get() = config.matchPatterns.toTypedArray()

    /** Whether [name] is a temporary account on this wiki. */
    public fun matches(name: String): Boolean = config.matches(name)
}

/** The account a wiki sees, or an anonymous session. */
@JsExport
public class Identity internal constructor(private val identity: ClientIdentity) {

    /** The account name as the wiki spells it, or the address of an anonymous session. */
    public val name: String
        get() = identity.name

    /** The account's id, `0` for an anonymous session. */
    public val id: Double
        get() = identity.id.toDouble()

    /** The groups the account is in, sorted. */
    public val groups: Array<String>
        get() = identity.groups.sorted().toTypedArray()

    /** What the account may do, sorted. */
    public val rights: Array<String>
        get() = identity.rights.sorted().toTypedArray()

    /** Whether the wiki has flagged the account as a bot. */
    public val isBot: Boolean
        get() = identity.isBot

    /** Whether the session is anonymous. */
    public val isAnonymous: Boolean
        get() = identity.isAnonymous

    /** How many titles, page ids, revision ids or usernames one query may name for this account. */
    public val batchLimit: Int
        get() = identity.batchLimit

    /** Whether the account holds [right]. */
    public fun has(right: String): Boolean = right in identity
}

/** One namespace as a wiki describes it. */
@JsExport
public class NamespaceInfo internal constructor(internal val info: ClientNamespaceInfo) {

    /** Its number, which is its identity across wikis. */
    public val id: Int
        get() = info.id.id

    /** The English canonical name, such as `"Category"`, or `null` or `""` for the main namespace. */
    public val canonicalName: String?
        get() = info.canonicalName

    /** The wiki's own name for it, such as `"Kategoria"`. */
    public val localName: String?
        get() = info.localName

    /** Other accepted prefixes, such as `"WP"` for `"Wikipedia"`. */
    public val aliases: Array<String>
        get() = info.aliases.toTypedArray()

    /** How the first letter of a title here is treated: `"first-letter"` or `"case-sensitive"`. */
    public val case: TitleCase
        get() = info.case.argumentName().unsafeCast<TitleCase>()

    /** Whether a slash in a title makes a subpage here. */
    public val subpages: Boolean
        get() = info.subpages

    /** Every prefix that resolves to this namespace. */
    public val prefixes: Array<String>
        get() = info.prefixes.toTypedArray()
}

/** The namespaces of one wiki. */
@JsExport
public class Namespaces internal constructor(internal val namespaces: NamespaceMap) {

    /** All of them, ordered by number. */
    public val all: Array<NamespaceInfo>
        get() = namespaces.all.map(::NamespaceInfo).toTypedArray()

    /** The namespace numbered [id], or `null` when the wiki has none. */
    public fun get(id: Int): NamespaceInfo? = namespaces[Namespace(id)]?.let(::NamespaceInfo)

    /** The namespace [prefix] refers to, compared as MediaWiki does, or `null` when it names none. */
    public fun byPrefix(prefix: String): NamespaceInfo? = namespaces.byPrefix(prefix)?.let(::NamespaceInfo)

    /**
     * The local [title] with its namespace's name on this wiki, as MediaWiki displays it.
     *
     * @throws IllegalArgumentException when [title] is not local.
     */
    public fun format(title: Title): String {
        val local =
            requireNotNull(title.title as? ParsedLocalTitle) {
                "only a title of this wiki has a namespace to format: ${title.title}"
            }
        return namespaces.format(local)
    }
}

/** The interwiki prefixes a wiki recognises, such as `w`, `wikt` or `de`. */
@JsExport
public class Interwiki internal constructor(internal val interwiki: InterwikiMap) {

    /** Whether [prefix] is an interwiki prefix on this wiki rather than page text. */
    public fun has(prefix: String): Boolean = prefix in interwiki

    /** Whether [prefix] names this same wiki, and so resolves to a local page. */
    public fun isSelf(prefix: String): Boolean = interwiki.isSelf(prefix)
}

/** One parameter of an API module, as the wiki describes it. */
@JsExport
public class ParamDescription internal constructor(private val parameter: ClientParamDescription) {

    /** Its name, without the module's prefix. */
    public val name: String
        get() = parameter.name

    /** Its type, such as `"string"`, or `null` when it takes a fixed set instead. */
    public val type: String?
        get() = parameter.type

    /** The values it accepts, when it takes a fixed set. */
    public val values: Array<String>
        get() = parameter.values.toTypedArray()

    /** For a parameter taking a count, the most results a request returns without `apihighlimits`. */
    public val limit: Int?
        get() = parameter.limit

    /** The most an account with `apihighlimits` may ask for. */
    public val highLimit: Int?
        get() = parameter.highLimit

    /** Whether the module refuses a request without it. */
    public val required: Boolean
        get() = parameter.required

    /** Whether it accepts several values joined by a pipe. */
    public val multiValued: Boolean
        get() = parameter.multiValued

    /** What the wiki uses when it is not sent. */
    public val default: String?
        get() = parameter.default

    /** Whether the wiki has announced it is going away. */
    public val deprecated: Boolean
        get() = parameter.deprecated

    /** Values still accepted but announced as going away. */
    public val deprecatedValues: Array<String>
        get() = parameter.deprecatedValues.toTypedArray()

    /** Whether the value is a credential, which must never reach a URL or a log. */
    public val sensitive: Boolean
        get() = parameter.sensitive

    /** For a multivalued parameter, the most values this account may join in one request. */
    public val valueLimit: Int?
        get() = parameter.valueLimit

    /** For a multivalued parameter, the most values an account with `apihighlimits` may join. */
    public val highValueLimit: Int?
        get() = parameter.highValueLimit

    /** Values accepted but marked internal or unstable, which may change without notice. */
    public val internalValues: Array<String>
        get() = parameter.internalValues.toTypedArray()
}

/** One API module, as the wiki describes it. */
@JsExport
public class ModuleDescription internal constructor(private val module: ClientModuleDescription) {

    /** Its own name, such as `"usercontribs"`. */
    public val name: String
        get() = module.name

    /** Its full path, such as `"query+usercontribs"`, which is how it is asked for. */
    public val path: String
        get() = module.path

    /** Its parameters, by name. */
    public val parameters: Record<String, ParamDescription>
        get() = module.parameters.toRecord(::ParamDescription)

    /** Whether it changes the wiki. */
    public val isWrite: Boolean
        get() = module.isWrite

    /** Whether it must be POSTed. */
    public val mustBePosted: Boolean
        get() = module.mustBePosted

    /** What provides it: `"MediaWiki"` for core, otherwise the extension's name. */
    public val source: String?
        get() = module.source

    /** The extension's display name, which differs from [source] for some extensions. */
    public val sourceName: String?
        get() = module.sourceName

    /** For a submodule of `query`, which of `list`, `prop` or `meta` it belongs to. */
    public val group: ModuleGroup?
        get() = module.group.unsafeCast<ModuleGroup?>()

    /** The prefix its own parameters carry, such as `"uc"`. */
    public val prefix: String
        get() = module.prefix

    /** Whether the wiki has announced it is going away. */
    public val deprecated: Boolean
        get() = module.deprecated

    /** Whether it is for MediaWiki's own use, with no compatibility promise. */
    public val internal: Boolean
        get() = module.internal

    /** Whether it takes a parameter called [parameter] on this wiki. */
    public fun has(parameter: String): Boolean = parameter in module

    /** The parameter called [parameter], or `null` when this wiki's version of it has none. */
    public fun get(parameter: String): ParamDescription? = module[parameter]?.let(::ParamDescription)
}

/**
 * What a wiki says its own API accepts, reached as `wiki.paramInfo`. Answers are kept for as long as the wiki
 * is open.
 */
@JsExport
public class ParamInfo
internal constructor(private val info: ClientParamInfo, private val scope: CoroutineScope) {

    /** How many modules one request may name. */
    public val maxModules: Int
        get() = ClientParamInfo.MAX_MODULES

    /**
     * The description of [module], named as `paraminfo` names it, such as `"query+categorymembers"`, or
     * `null` when this wiki has no such module.
     */
    public fun module(module: String): Promise<ModuleDescription?> = scope.promise {
        info.module(module)?.let(::ModuleDescription)
    }

    /**
     * The most results [module] returns in one request through [parameter], for an account with
     * `apihighlimits` when [highLimits] is `true`. `null` when it has no such limit, or the wiki did not say.
     */
    public fun limit(module: String, parameter: String, highLimits: Boolean): Promise<Int?> = scope.promise {
        info.limit(module, parameter, highLimits)
    }

    /** Whether [module] takes [parameter] on this wiki. */
    public fun supports(module: String, parameter: String): Promise<Boolean> = scope.promise {
        info.supports(module, parameter)
    }

    /**
     * The values [parameter] of [module] accepts on this wiki, or `null` when there is no such parameter, or
     * it takes free text.
     */
    public fun values(module: String, parameter: String): Promise<Array<String>?> = scope.promise {
        info.values(module, parameter)?.toTypedArray()
    }

    /** Whether this wiki has announced that [value] of [parameter] is going away. */
    public fun isDeprecated(module: String, parameter: String, value: String): Promise<Boolean> =
        scope.promise {
            info.isDeprecated(module, parameter, value)
        }

    /** Fetches each of [modules] not already known, in as few requests as it can. */
    public fun prefetch(modules: Array<String>): Promise<Unit> = scope.promiseVoid {
        info.prefetch(modules.toList())
    }

    /** Every module matching [patterns], which may use the `*` the API accepts, such as `"query+*"`. */
    public fun modules(patterns: Array<String>): Promise<Array<ModuleDescription>> = scope.promise {
        info.modules(*patterns).map(::ModuleDescription).toTypedArray()
    }
}

/**
 * The tokens writes need, reached as `wiki.tokens`: fetched on first use and kept. [type] is one of the
 * wiki's token types, `"csrf"`, what an ordinary write needs, unless given.
 */
@JsExport
public class TokenStore
internal constructor(private val tokens: ClientTokenStore, private val scope: CoroutineScope) {

    /** The token every ordinary write needs. */
    public val csrf: String
        get() = ClientTokenStore.CSRF

    /** The token needed to start a login. */
    public val login: String
        get() = ClientTokenStore.LOGIN

    /** The token needed to patrol a revision. */
    public val patrol: String
        get() = ClientTokenStore.PATROL

    /** The token needed to roll a page back. */
    public val rollback: String
        get() = ClientTokenStore.ROLLBACK

    /** The token for changing group membership. */
    public val userRights: String
        get() = ClientTokenStore.USER_RIGHTS

    /** The token needed to add or remove watchlist entries. */
    public val watch: String
        get() = ClientTokenStore.WATCH

    /** The token of [type]. Rejects in an anonymous session, which has no real one. */
    public fun token(type: String? = null): Promise<String> = scope.promise {
        tokens.token(type ?: ClientTokenStore.CSRF)
    }

    /** Drops the kept token of [type], so that the next use fetches a new one. */
    public fun invalidate(type: String? = null): Promise<Unit> = scope.promiseVoid {
        tokens.invalidate(type ?: ClientTokenStore.CSRF)
    }

    /** Drops every kept token. */
    public fun clear(): Promise<Unit> = scope.promiseVoid { tokens.clear() }

    /**
     * Calls [block] with a token and answers with what it returns, which may be a promise. When that is a
     * response saying the wiki rejected the token, as `wiki.call` gives one, [block] is called once more with
     * a fresh token; a second such answer rejects.
     */
    public fun <T> withFreshToken(block: (token: String) -> Awaitable<T>, type: String? = null): Promise<T> {
        val kind = type ?: ClientTokenStore.CSRF
        return scope.promise {
            tokens.withFreshToken(kind) { token ->
                val answer = settled(block(token))
                if (saysBadToken(answer)) throw WikiError.Auth.BadToken(kind)
                answer
            }
        }
    }

    private fun saysBadToken(answer: Any?): Boolean {
        val response: dynamic = answer
        if (js("typeof response") != "object" || response == null) return false
        val error: dynamic = response.error
        return js("typeof error") == "object" && error != null && error.code == BAD_TOKEN
    }

    private companion object {
        const val BAD_TOKEN = "badtoken"
    }
}

/** What a wiki's configuration changes about how its wikitext parses, from `wiki.parseOptions()`. */
@JsExport
public class ParseOptions internal constructor(internal val options: ClientParseOptions) {

    /** The tags whose bodies the preprocessor steps over, without their brackets. */
    public val extensionTags: Array<String>
        get() = options.extensionTags.toTypedArray()

    /** The extension tags whose body is itself wikitext, such as `ref`. */
    public val parsedTags: Array<String>
        get() = options.parsedTags.toTypedArray()

    /** The extension tags whose body has only its templates expanded, such as `inputbox`. */
    public val preprocessedTags: Array<String>
        get() = options.preprocessedTags.toTypedArray()

    /** The HTML elements the wiki allows. */
    public val htmlTags: Array<String>
        get() = options.htmlTags.toTypedArray()

    /** The URL schemes that make a link. */
    public val protocols: Array<String>
        get() = options.protocols.toTypedArray()

    /** Whether `-{ }-` is markup. */
    public val languageConversion: Boolean
        get() = options.languageConversion

    /** The names of the File namespace. */
    public val fileNamespaces: Array<String>
        get() = options.fileNamespaces.toTypedArray()

    /** Parses [text] the way the wiki these options came from reads it. */
    public fun parse(text: String): Wikitext = Wikitext(Parser.parse(text, options))
}

/** Names a wiki recognises in `{{...}}`, each compared exactly or ignoring case. */
@JsExport
public class MagicWords internal constructor(internal val words: ClientMagicWords) {

    /** The names compared exactly. */
    public val caseSensitive: Array<String>
        get() = words.caseSensitive.toTypedArray()

    /** The names compared ignoring case. */
    public val caseInsensitive: Array<String>
        get() = words.caseInsensitive.toTypedArray()

    /** Whether [name] is one of them. */
    public fun has(name: String): Boolean = name in words
}

/** How a wiki names pages, as far as telling which page a `{{...}}` transcludes needs. */
@JsExport
public class TitleRules internal constructor(internal val rules: ClientTitleRules) {

    /** Every namespace name and alias, with the namespace's number. */
    public val namespaces: Record<String, Int>
        get() = rules.namespaces.toRecord { it }

    /** The name each namespace, by number, is written with in a key. */
    public val canonicalNames: Record<String, String>
        get() = rules.canonicalNames.mapKeys { it.key.toString() }.toRecord { it }

    /** The namespaces whose titles keep their first letter as written. */
    public val caseSensitive: Array<Int>
        get() = rules.caseSensitive.toTypedArray()

    /** The parser functions written without a `#`, such as `lc`. */
    public val functions: MagicWords
        get() = MagicWords(rules.functions)

    /** The variables, such as `PAGENAME`. */
    public val variables: MagicWords
        get() = MagicWords(rules.variables)

    /** The prefixes that save a template's expansion instead of the template, such as `subst`. */
    public val substitutions: Array<String>
        get() = rules.substitutions.toTypedArray()

    /** The other prefixes that change how a template is transcluded, such as `msg`. */
    public val modifiers: Array<String>
        get() = rules.modifiers.toTypedArray()

    /**
     * Whether a template named [title] is a parser function or a variable rather than a page. [hasArguments]
     * says whether it is given any parameters: `{{PAGENAME|x}}` transcludes a page.
     */
    public fun isParserFunction(title: String, hasArguments: Boolean = false): Boolean =
        rules.isParserFunction(title, hasArguments)

    /**
     * The page a template named [title] transcludes, normalised, or `null` for a parser function or a
     * variable. [hasArguments] says whether it is given any parameters.
     */
    public fun key(title: String, hasArguments: Boolean = false): String? = rules.key(title, hasArguments)

    /** The page [template] transcludes on this wiki, normalised, or `null` for a parser function. */
    public fun keyOf(template: Template): String? = template.template.key(rules)

    /** The templates of [wikitext] that transclude any of [names], however each is written. */
    public fun templatesIn(wikitext: Wikitext, names: Array<String>): Array<Template> =
        wikitext.markup.templates(names.toList(), rules).map(::Template).toTypedArray()
}
