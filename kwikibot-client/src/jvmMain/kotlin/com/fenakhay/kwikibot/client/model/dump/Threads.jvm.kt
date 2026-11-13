package com.fenakhay.kwikibot.client.model.dump

import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal actual fun decodeUtf8(bytes: ByteArray, start: Int, end: Int): String =
    bytes.decodeToString(start, end)

internal actual fun platformBlockDecoders(workers: Int): BlockDecoders? = null

internal actual fun openDumpFile(path: Path): RawSource = SystemFileSystem.source(path)
