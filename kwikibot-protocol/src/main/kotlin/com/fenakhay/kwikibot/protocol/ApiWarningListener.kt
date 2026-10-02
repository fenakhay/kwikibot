package com.fenakhay.kwikibot.protocol

import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.TransportListener
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject

/**
 * Hears every warning a wiki attaches to a response.
 *
 * Deprecations matter most and are the easiest to miss: the request keeps working until the release that
 * removes what it used. This library asks each wiki which values it takes and avoids deprecated ones where it
 * can, so a deprecation that reaches a listener means a value the request sent needs a replacement.
 *
 * Handed to a transport as its [TransportListener], and set for a whole client through
 * `WikiConfig.onWarning`. The announcement-list advice that follows every deprecation is left out.
 *
 * ```
 * WikiConfig(userAgent, onWarning = { request, warning ->
 *     if (warning.isDeprecation) metrics.count("deprecated", request.action)
 * })
 * ```
 */
public fun interface ApiWarningListener : TransportListener {

    /** Called once for each warning on the response to [request]. */
    public fun onWarning(request: ApiRequest, warning: ApiWarning)

    /** Reads the warnings out of [response] and hands each to [onWarning]. */
    override fun onResponse(request: ApiRequest, response: JsonObject) {
        response
            .warnings()
            .filterNot { it.code == ApiWarning.DEPRECATION_HELP }
            .forEach { onWarning(request, it) }
    }

    /** Ready-made listeners. */
    public companion object {
        /**
         * Logs each distinct warning once per process: deprecations at `WARN`, everything else at `DEBUG`.
         *
         * A bot reading many pages draws the same warning on each, and one line is enough. Warnings are told
         * apart by module, code and text.
         */
        public val LOG: ApiWarningListener = LoggingWarningListener()

        /** Ignores every warning. */
        public val NONE: ApiWarningListener = ApiWarningListener { _, _ -> }
    }
}

private val log = KotlinLogging.logger {}

private class LoggingWarningListener : ApiWarningListener {

    private val seen = ConcurrentHashMap.newKeySet<Triple<String, String?, String>>()

    override fun onWarning(request: ApiRequest, warning: ApiWarning) {
        // Bounded, since a warning can quote a value the request sent and so come in many versions. Past the
        // bound, every warning is logged.
        val first =
            if (seen.size < MAX_REMEMBERED) seen.add(Triple(warning.module, warning.code, warning.text))
            else true
        if (!first) return

        if (warning.isDeprecation) {
            log.warn { "action=${request.action} uses something MediaWiki has deprecated. $warning" }
        } else {
            log.debug { "action=${request.action} drew a warning (${warning.code ?: "no code"}). $warning" }
        }
    }

    private companion object {
        const val MAX_REMEMBERED = 1024
    }
}
