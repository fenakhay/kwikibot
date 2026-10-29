package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.internal.ioDispatcher
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readTo
import kotlin.uuid.Uuid
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readLine

/**
 * Holds an answer streamed from a service until it is read back a line at a time, so that a large answer is
 * never held as one string. Closing it lets go of what it held.
 *
 * ```
 * spool("petscan").use { spooled ->
 *     client.prepareGet(url).execute { spooled.fill(it.bodyAsChannel()) }
 *     spooled.lines().forEach(::println)
 * }
 * ```
 */
public interface Spool : AutoCloseable {
    /** Copies everything [channel] has into the spool, once, before [lines] is called. */
    public suspend fun fill(channel: ByteReadChannel)

    /** What [fill] copied, a line at a time and without the line breaks. Single-pass. */
    public fun lines(): Sequence<String>
}

/**
 * A spool for this platform: a temporary file named after [prefix] where there is a filesystem to rely on,
 * and memory on JavaScript, where a browser has none.
 */
public expect fun spool(prefix: String): Spool

/** A spool in a file of its own under the system's temporary directory, deleted when the spool is closed. */
internal class FileSpool(prefix: String) : Spool {
    private val path = Path(SystemTemporaryDirectory, "$prefix-${Uuid.random()}")
    private var reading: Source? = null

    override suspend fun fill(channel: ByteReadChannel) {
        withContext(ioDispatcher) { SystemFileSystem.sink(path).buffered().use { channel.readTo(it) } }
    }

    override fun lines(): Sequence<String> {
        val source = SystemFileSystem.source(path).buffered().also { reading = it }
        return generateSequence { source.readLine() }
    }

    override fun close() {
        reading?.close()
        SystemFileSystem.delete(path, mustExist = false)
    }
}

/** A spool in memory. */
internal class MemorySpool : Spool {
    private val buffer = Buffer()

    override suspend fun fill(channel: ByteReadChannel) {
        channel.readTo(buffer)
    }

    override fun lines(): Sequence<String> = generateSequence { buffer.readLine() }

    override fun close() {
        buffer.clear()
    }
}
