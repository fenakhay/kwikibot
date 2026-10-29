package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.net.environmentVariable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import platform.posix.fflush
import platform.posix.fwrite
import platform.posix.stderr

internal actual fun homeDirectory(): String? =
    environmentVariable("HOME") ?: environmentVariable("USERPROFILE")

internal actual fun standardError(): Sink = StandardError.buffered()

internal actual suspend fun readInputLine(): String? = readlnOrNull()

/** The C library's standard error stream, which the process owns and which is never closed here. */
@OptIn(ExperimentalForeignApi::class)
private object StandardError : RawSink {
    override fun write(source: Buffer, byteCount: Long) {
        val bytes = source.readByteArray(byteCount.toInt())
        if (bytes.isEmpty()) return
        bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), stderr) }
    }

    override fun flush() {
        fflush(stderr)
    }

    override fun close() {
        flush()
    }
}
