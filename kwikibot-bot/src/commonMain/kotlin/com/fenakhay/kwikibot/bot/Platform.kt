package com.fenakhay.kwikibot.bot

import kotlinx.io.Sink

/** The user's home directory, or `null` where the platform does not say. */
internal expect fun homeDirectory(): String?

/** The process's standard error, written as text arrives and flushed on request. */
internal expect fun standardError(): Sink

/** The next line typed on standard input, or `null` once input has ended or there is none to read. */
internal expect suspend fun readInputLine(): String?
