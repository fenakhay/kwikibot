package com.fenakhay.kwikibot.net

/** The runtime this code runs on, for [Kwikibot.runtime]. */
internal expect fun runtimeDescription(): String

/** The operating system or host, for [Kwikibot.platform]. */
internal expect fun platformDescription(): String

/** The headers [identifyAs] names the caller in on this platform. */
internal expect val userAgentHeaders: List<String>

/**
 * The origin of the web page this code runs in, such as `https://en.wiktionary.org`, or `null` outside a
 * browser.
 */
internal expect fun pageOrigin(): String?
