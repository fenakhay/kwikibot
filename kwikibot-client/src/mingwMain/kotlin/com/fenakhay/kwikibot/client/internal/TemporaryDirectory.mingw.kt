package com.fenakhay.kwikibot.client.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.io.files.Path
import platform.windows.GetTempPathW
import platform.windows.MAX_PATH
import platform.windows.WCHARVar

/** Asked of Windows, which tries `TMP`, `TEMP` and the user's profile directory in turn. */
@OptIn(ExperimentalForeignApi::class)
internal actual val temporaryDirectory: Path = memScoped {
    val size = MAX_PATH + 1
    val buffer = allocArray<WCHARVar>(size)
    val length = GetTempPathW(size.convert(), buffer).toInt()
    check(length in 1..<size) { "Windows reports no temporary directory" }
    Path(buffer.toKStringFromUtf16())
}
