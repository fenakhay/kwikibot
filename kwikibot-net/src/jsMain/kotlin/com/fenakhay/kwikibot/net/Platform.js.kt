package com.fenakhay.kwikibot.net

import io.ktor.http.HttpHeaders

internal actual fun runtimeDescription(): String =
    js(
        "typeof process !== 'undefined' && process.versions && process.versions.node" +
            " ? 'Node.js ' + process.versions.node" +
            " : (typeof navigator !== 'undefined' ? navigator.userAgent : 'JavaScript')"
    )

internal actual fun platformDescription(): String =
    js("typeof process !== 'undefined' ? process.platform + ' ' + process.arch : 'browser'")

internal actual val userAgentHeaders: List<String> = listOf(HttpHeaders.UserAgent, "Api-User-Agent")

internal actual fun pageOrigin(): String? =
    js("typeof location === 'undefined' ? undefined : location.origin")
