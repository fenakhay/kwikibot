package com.fenakhay.kwikibot.bot

import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/** The whole of the file at this path, read as UTF-8. */
internal fun Path.readText(): String = SystemFileSystem.source(this).buffered().use { it.readString() }

/** The file at this path, opened to write after whatever it holds, its directories made first. */
internal fun Path.appending(): Sink {
    parent?.let { SystemFileSystem.createDirectories(it) }
    return SystemFileSystem.sink(this, append = true).buffered()
}
