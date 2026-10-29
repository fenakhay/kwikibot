package com.fenakhay.kwikibot.net

import io.ktor.http.HttpHeaders

internal actual fun runtimeDescription(): String = "Kotlin/Native ${KotlinVersion.CURRENT}"

internal actual val userAgentHeaders: List<String> = listOf(HttpHeaders.UserAgent)

internal actual fun pageOrigin(): String? = null
