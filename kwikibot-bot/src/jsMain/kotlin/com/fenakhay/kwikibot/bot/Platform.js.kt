package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.net.environmentVariable
import kotlinx.coroutines.channels.Channel
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.readByteArray

internal actual fun homeDirectory(): String? =
    environmentVariable("HOME") ?: environmentVariable("USERPROFILE")

internal actual fun standardError(): Sink = StandardError.buffered()

/** The next line of Node's standard input; outside Node there is none. */
internal actual suspend fun readInputLine(): String? = standardInput?.next()

private val standardInput: LineReader? by lazy {
    val process: dynamic = js("typeof process === 'undefined' ? null : process")
    if (process == null) null else LineReader(process.stdin)
}

/**
 * The lines of [input], a Node stream, one for each [next], through one `readline` interface, which keeps the
 * lines it has read ahead for the calls after. The stream is paused between calls, so it keeps no process
 * alive while nothing is being asked.
 */
internal class LineReader(input: dynamic) {
    private val lines = Channel<String>(Channel.UNLIMITED)
    private var ended = false
    private val reader: dynamic

    init {
        val options: dynamic = js("({})")
        options.input = input
        options.terminal = false
        reader = nodeModule("readline").createInterface(options)
        reader.on("line") { line: String -> lines.trySend(line) }
        reader.on("close") {
            ended = true
            lines.close()
        }
        reader.pause()
    }

    /** The next line, without its line break, or `null` once the stream has ended. */
    suspend fun next(): String? {
        if (!ended) reader.resume()
        return try {
            lines.receiveCatching().getOrNull()
        } finally {
            if (!ended) reader.pause()
        }
    }
}

/** A built-in Node module, or `null` outside Node. */
internal fun nodeModule(name: String): dynamic {
    val process: dynamic = js("typeof process === 'undefined' ? null : process")
    return if (process == null || process.getBuiltinModule == undefined) null
    else process.getBuiltinModule(name)
}

/** Node's `process.stderr`, or the browser console where there is no process. */
private object StandardError : RawSink {
    override fun write(source: Buffer, byteCount: Long) {
        val bytes = source.readByteArray(byteCount.toInt())
        if (bytes.isEmpty()) return
        val process: dynamic = js("typeof process === 'undefined' ? null : process")
        if (process != null) process.stderr.write(bytes) else console.error(bytes.decodeToString())
    }

    override fun flush() = Unit

    override fun close() = Unit
}
