import com.fenakhay.kwikibot.client.ApiDetector
import com.fenakhay.kwikibot.client.Family as ClientFamily
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.net.transport.ApiEndpoint

/** A group of wikis that share a domain pattern, such as the Wiktionaries. */
@JsExport
public class Family internal constructor(internal val family: ClientFamily) {

    /** `"wikimedia"`, one wiki per language under [domain], or `"single-site"`, one wiki at [server]. */
    public val kind: FamilyKind
        get() =
            when (family) {
                is ClientFamily.Wikimedia -> "wikimedia"
                is ClientFamily.SingleSite -> "single-site"
            }.unsafeCast<FamilyKind>()

    /** The family's name, such as `"wiktionary"`. */
    public val name: String
        get() = family.name

    /** The domain a `"wikimedia"` family's wikis sit under, such as `"wiktionary.org"`, or `null`. */
    public val domain: String?
        get() = (family as? ClientFamily.Wikimedia)?.domain

    /** The host of a `"single-site"` family, or `null`. */
    public val server: String?
        get() = (family as? ClientFamily.SingleSite)?.server

    /** Where a `"single-site"` family has MediaWiki under its host, or `null`. */
    public val scriptPath: String?
        get() = (family as? ClientFamily.SingleSite)?.scriptPath

    /** Where the API of this family's wiki in [language] is. */
    public fun endpoint(language: String): Endpoint = Endpoint(family.endpoint(LangCode(language)))
}

/** Encyclopedias. */
@JsExport public val FAMILY_WIKIPEDIA: Family = Family(ClientFamily.WIKIPEDIA)

/** Dictionaries, where a title is a word rather than a subject. */
@JsExport public val FAMILY_WIKTIONARY: Family = Family(ClientFamily.WIKTIONARY)

/** Transcribed source texts. */
@JsExport public val FAMILY_WIKISOURCE: Family = Family(ClientFamily.WIKISOURCE)

/** Collections of quotations. */
@JsExport public val FAMILY_WIKIQUOTE: Family = Family(ClientFamily.WIKIQUOTE)

/** Textbooks and manuals. */
@JsExport public val FAMILY_WIKIBOOKS: Family = Family(ClientFamily.WIKIBOOKS)

/** News reporting. */
@JsExport public val FAMILY_WIKINEWS: Family = Family(ClientFamily.WIKINEWS)

/** Learning materials. */
@JsExport public val FAMILY_WIKIVERSITY: Family = Family(ClientFamily.WIKIVERSITY)

/** Travel guides. */
@JsExport public val FAMILY_WIKIVOYAGE: Family = Family(ClientFamily.WIKIVOYAGE)

/** The shared media repository every other Wikimedia wiki reads files from. */
@JsExport public val FAMILY_COMMONS: Family = Family(ClientFamily.COMMONS)

/** The structured-data repository, whose pages are entities. */
@JsExport public val FAMILY_WIKIDATA: Family = Family(ClientFamily.WIKIDATA)

/** Coordination across the projects, including bot policy. */
@JsExport public val FAMILY_META: Family = Family(ClientFamily.META)

/** MediaWiki's own documentation, including the API reference. */
@JsExport public val FAMILY_MEDIAWIKI: Family = Family(ClientFamily.MEDIAWIKI)

/** The write-capable test wiki, where live write tests belong. */
@JsExport public val FAMILY_TEST: Family = Family(ClientFamily.TEST)

/** The family called [name], or `null` when it is not one of the known projects. */
@JsExport public fun familyNamed(name: String): Family? = ClientFamily.named(name)?.let(::Family)

/** A third-party wiki, named by its host and the path MediaWiki is installed under. */
@JsExport
public fun customFamily(name: String, server: String, scriptPath: String = "/w"): Family =
    Family(ClientFamily.custom(name, server, scriptPath))

/** The family of the wiki at [endpoint], as `detect` finds one, under [name] or else the server's. */
@JsExport
public fun familyAt(endpoint: Endpoint, name: String? = null): Family =
    Family(ClientFamily.at(endpoint.endpoint, name ?: endpoint.server))

/** The family of one wiki in the site matrix. */
@JsExport public fun familyOf(wiki: MatrixWiki): Family = Family(ClientFamily.of(wiki.wiki))

/** Where a wiki's API is, when its layout is already known. [secure] is off only for a local test wiki. */
@JsExport
public fun endpointOf(server: String, scriptPath: String = "/w", secure: Boolean = true): Endpoint =
    Endpoint(ApiEndpoint(server, scriptPath, secure))

/** Where the API of a Wikimedia project's wiki is, such as `"en"` and `"wiktionary.org"`. */
@JsExport
public fun wikimediaEndpoint(code: String, domain: String): Endpoint =
    Endpoint(ApiEndpoint.wikimedia(code, domain))

/**
 * Reads where the API is out of the HTML of a page at [pageUrl], which `detect` does after fetching it.
 * `null` when the page advertises none.
 */
@JsExport
public fun endpointFromHtml(html: String, pageUrl: String): Endpoint? =
    ApiDetector.endpointFrom(html, pageUrl)?.let(::Endpoint)
