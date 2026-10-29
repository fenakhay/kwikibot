package com.fenakhay.kwikibot.net

import io.ktor.http.HttpHeaders

internal actual fun runtimeDescription(): String =
    "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}"

internal actual fun platformDescription(): String =
    "${System.getProperty("os.name")} ${System.getProperty("os.version")}"

internal actual val userAgentHeaders: List<String> = listOf(HttpHeaders.UserAgent)

internal actual fun pageOrigin(): String? = null
