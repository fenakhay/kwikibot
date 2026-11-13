package com.fenakhay.kwikibot.bindings.connect

import com.fenakhay.kwikibot.bindings.common.Setting
import com.fenakhay.kwikibot.bindings.common.resolve
import com.fenakhay.kwikibot.client.Family
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.net.Kwikibot
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.cache.ResponseCache
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.HttpSettings
import com.fenakhay.kwikibot.net.transport.TransportListener
import com.fenakhay.kwikibot.protocol.ApiWarningListener
import kotlin.time.Duration

private const val DEFAULT_SCRIPT_PATH = "/w"

/**
 * How a bot identifies itself: as [given], or unless given as this library, for the operator [contact]
 * reaches.
 */
public fun userAgent(contact: String, given: UserAgent? = null): UserAgent =
    given ?: UserAgent(Kwikibot.NAME, Kwikibot.version, contact)

/**
 * The configuration a binding opens wikis with: the library's own defaults for whatever is not given, and
 * this library's name with [contact] as the user agent unless [userAgent] is given. A [maxlag] turned off
 * leaves the parameter out, which only makes sense for a self-hosted wiki.
 */
@Suppress("LongParameterList")
public fun configOf(
    contact: String,
    userAgent: UserAgent? = null,
    throttle: Throttle? = null,
    retry: RetryPolicy? = null,
    maxlag: Setting<Int> = Setting.Default,
    cache: ResponseCache? = null,
    onWarning: ApiWarningListener? = null,
    relogins: Int? = null,
    http: HttpSettings? = null,
    listener: TransportListener? = null,
): WikiConfig {
    val defaults = WikiConfig(com.fenakhay.kwikibot.bindings.connect.userAgent(contact, userAgent))
    return defaults.copy(
        throttle = throttle ?: defaults.throttle,
        retry = retry ?: defaults.retry,
        maxlag = maxlag.resolve(defaults.maxlag),
        cache = cache ?: defaults.cache,
        onWarning = onWarning ?: defaults.onWarning,
        relogins = relogins ?: defaults.relogins,
        http = http ?: defaults.http,
        listener = listener ?: defaults.listener,
    )
}

/** How fast requests may go out: reads and writes at the library's default pace unless given. */
public fun throttleOf(read: Duration? = null, write: Duration? = null): Throttle =
    Throttle(read ?: Throttle.DEFAULT_READ, write ?: Throttle.DEFAULT_WRITE)

/** How failed requests are retried, the library's defaults holding for what is not given. */
public fun retryOf(
    maxRetries: Int? = null,
    initialDelay: Duration? = null,
    maxDelay: Duration? = null,
): RetryPolicy {
    val defaults = RetryPolicy()
    return RetryPolicy(
        maxRetries ?: defaults.maxRetries,
        initialDelay ?: defaults.initialDelay,
        maxDelay ?: defaults.maxDelay,
    )
}

/** How the client's connections are managed, the library's defaults holding for what is not given. */
public fun httpOf(
    maxConcurrentRequests: Int? = null,
    maxIdleConnections: Int? = null,
    timeout: Duration? = null,
): HttpSettings {
    val defaults = HttpSettings()
    return HttpSettings(
        maxConcurrentRequests ?: defaults.maxConcurrentRequests,
        maxIdleConnections ?: defaults.maxIdleConnections,
        timeout ?: defaults.timeout,
    )
}

/** Where the API of the wiki at [server] is: under `/w` and over HTTPS unless told. */
public fun endpointOf(server: String, scriptPath: String? = null, secure: Boolean? = null): ApiEndpoint =
    ApiEndpoint(server, scriptPath ?: DEFAULT_SCRIPT_PATH, secure ?: true)

/** Where the API of [family]'s wiki in [language] is: where the family says, unless told otherwise. */
public fun endpointOf(
    language: String,
    family: Family,
    scriptPath: String? = null,
    secure: Boolean? = null,
): ApiEndpoint {
    val endpoint = family.endpoint(LangCode(language))
    return ApiEndpoint(endpoint.server, scriptPath ?: endpoint.scriptPath, secure ?: endpoint.secure)
}
