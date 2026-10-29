package com.fenakhay.kwikibot.bot

import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.buffered

internal actual fun homeDirectory(): String? = System.getProperty("user.home")

internal actual fun standardError(): Sink = System.err.asSink().buffered()

internal actual suspend fun readInputLine(): String? = readlnOrNull()
