import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.model.edit.Protection as ClientProtection
import com.fenakhay.kwikibot.model.page.ContentModel
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.cache.DiskCache as ClientDiskCache
import com.fenakhay.kwikibot.net.transport.HttpSettings
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import com.fenakhay.kwikibot.protocol.ApiWarning as ClientApiWarning

/** How many requests a client has in flight at once unless told, the most Wikimedia asks for. */
@JsExport public const val DEFAULT_CONCURRENT_REQUESTS: Int = HttpSettings.DEFAULT_CONCURRENT_REQUESTS

/** How many idle connections a client keeps for reuse unless told. */
@JsExport public const val DEFAULT_CONNECTIONS: Int = HttpSettings.DEFAULT_CONNECTIONS

/** How long a request may take unless told, in milliseconds. */
@JsExport public val DEFAULT_TIMEOUT: Double = WikiHttpClient.DEFAULT_TIMEOUT.inWholeMilliseconds.toDouble()

/** How many times in ten minutes a dropped session is restored by logging in again unless told. */
@JsExport public const val DEFAULT_RELOGINS: Int = WikiConfig.DEFAULT_RELOGINS

/** The least time between two reads unless told, in milliseconds. */
@JsExport public val DEFAULT_READ_DELAY: Double = Throttle.DEFAULT_READ.inWholeMilliseconds.toDouble()

/** The least time between two writes unless told, in milliseconds. */
@JsExport public val DEFAULT_WRITE_DELAY: Double = Throttle.DEFAULT_WRITE.inWholeMilliseconds.toDouble()

/** How long a `DiskCache` keeps an entry unless told, in milliseconds. */
@JsExport
public val DISK_CACHE_DEFAULT_TTL: Double = ClientDiskCache.DEFAULT_TTL.inWholeMilliseconds.toDouble()

/** The longest URL a read is sent in before it goes as a POST instead. */
@JsExport public const val MAX_GET_LENGTH: Int = KtorTransport.MAX_GET_LENGTH

/** The module of the advice a wiki attaches to every deprecation, which warnings leave out. */
@JsExport public const val DEPRECATION_HELP: String = ClientApiWarning.DEPRECATION_HELP

/** The protection level only administrators may edit through. */
@JsExport public const val PROTECTION_SYSOP: String = ClientProtection.SYSOP

/** The protection level autoconfirmed accounts may edit through. */
@JsExport public const val PROTECTION_AUTOCONFIRMED: String = ClientProtection.AUTOCONFIRMED

/** Wikitext, as `Page.contentModel` spells it. */
@JsExport public val CONTENT_MODEL_WIKITEXT: String = ContentModel.WIKITEXT.id

/** JSON, as `Page.contentModel` spells it. */
@JsExport public val CONTENT_MODEL_JSON: String = ContentModel.JSON.id

/** CSS, as `Page.contentModel` spells it. */
@JsExport public val CONTENT_MODEL_CSS: String = ContentModel.CSS.id

/** JavaScript, as `Page.contentModel` spells it. */
@JsExport public val CONTENT_MODEL_JAVASCRIPT: String = ContentModel.JAVASCRIPT.id

/** A Lua module, as `Page.contentModel` spells it. */
@JsExport public val CONTENT_MODEL_SCRIBUNTO: String = ContentModel.SCRIBUNTO.id

/** TemplateStyles' CSS, as `Page.contentModel` spells it. */
@JsExport public val CONTENT_MODEL_SANITIZED_CSS: String = ContentModel.SANITIZED_CSS.id
