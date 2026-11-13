package com.fenakhay.kwikibot.bot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.buffered

internal actual fun homeDirectory(): String? = System.getProperty("user.home")

internal actual fun standardError(): Sink = System.err.asSink().buffered()

/** Read on a thread kept for blocking, so that coroutines sharing the caller's thread go on meanwhile. */
internal actual suspend fun readInputLine(): String? = withContext(Dispatchers.IO) { readlnOrNull() }
