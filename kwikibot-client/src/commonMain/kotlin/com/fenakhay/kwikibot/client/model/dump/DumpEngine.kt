@file:Suppress("MagicNumber", "TooManyFunctions", "CyclomaticComplexMethod")

package com.fenakhay.kwikibot.client.model.dump

import com.fenakhay.kwikibot.client.internal.ioDispatcher
import com.fenakhay.kwikibot.client.model.Compression
import com.fenakhay.kwikibot.client.model.DumpPage
import com.fenakhay.kwikibot.client.model.availableWorkers
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.files.Path

private const val PIECE = 1 shl 20
private const val QUEUED_PER_WORKER = 4

internal fun compressionOf(path: Path): Compression {
    val name = path.name.lowercase()
    return when {
        name.endsWith(".bz2") -> Compression.BZIP2
        name.endsWith(".gz") -> Compression.GZIP
        else -> Compression.NONE
    }
}

private fun decompressing(source: RawSource, compression: Compression): RawSource =
    when (compression) {
        Compression.NONE -> source
        Compression.GZIP -> gunzipping(source)
        Compression.BZIP2 -> Bzip2Source(source)
    }

/** gzip through whatever is fastest here: the JDK's own zlib on the JVM, and [GzipSource] elsewhere. */
internal expect fun gunzipping(source: RawSource): RawSource

/**
 * Reads from a source up to a whole array at a time, taking as many reads as that needs.
 *
 * A failure part way through an array waits for the next read, so that what came before it is still handed
 * on.
 */
private class PieceReader(private val source: RawSource) {
    private val holding = Buffer()
    private var failure: DumpFormatException? = null

    /** Says how many bytes came into [into], which is none only at the end of the source. */
    fun read(into: ByteArray): Int {
        failure?.let { throw it }
        var filled = 0
        try {
            while (filled < into.size) {
                if (holding.exhausted() && source.readAtMostTo(holding, (into.size - filled).toLong()) < 0)
                    break
                filled += holding.readAtMostTo(into, filled, into.size)
            }
        } catch (e: DumpFormatException) {
            if (filled == 0) throw e
            failure = e
        }
        return filled
    }
}

/** The pages of [source], on the calling thread and in the order of the dump. Closes it when they end. */
internal fun sequentialPages(
    source: RawSource,
    compression: Compression,
    namespaces: Set<Int>,
): Sequence<DumpPage> = sequence {
    decompressing(source, compression).use { input ->
        val splitter = PageSplitter()
        val piece = ByteArray(PIECE)
        val reader = PieceReader(input)
        while (true) {
            val count = reader.read(piece)
            if (count == 0) break
            val pages = ArrayList<ByteArray>()
            splitter.feed(piece, count) { pages += it }
            for (page in pages) parsePage(page, namespaces)?.let { yield(it) }
        }
        splitter.finish()
    }
}

/** The file at [path], read without holding it in memory. */
internal expect fun openDumpFile(path: Path): RawSource

/** How many threads are worth decoding on for [workers] workers: no more than there are processors. */
internal fun decodersFor(workers: Int): Int = minOf(workers, availableWorkers())

/**
 * Passes the pieces of decompressed XML in [path] to [piece], in order.
 *
 * bzip2 is decoded by [decodersFor] threads, a block each, with [BlockChain] putting the results back in
 * order. gzip has no boundaries to split on, so it takes one thread however many are offered.
 */
private suspend fun decompressedPieces(
    path: Path,
    compression: Compression,
    workers: Int,
    piece: suspend (ByteArray, Int) -> Unit,
) {
    val decoders = decodersFor(workers)
    if (compression == Compression.BZIP2 && decoders > 1) {
        parallelBzip2(path, decoders, piece)
        return
    }

    decompressing(openDumpFile(path), compression).use { input ->
        val bytes = ByteArray(PIECE)
        val reader = PieceReader(input)
        while (true) {
            val count = reader.read(bytes)
            if (count == 0) break
            piece(bytes, count)
            yield()
        }
    }
}

/** UTF-8 through whatever is fastest here, which on JavaScript is the runtime's own decoder. */
internal expect fun decodeUtf8(bytes: ByteArray, start: Int, end: Int): String

/**
 * Decodes bzip2 blocks somewhere other than the calling thread, a block at a time and some number at once.
 */
internal interface BlockDecoders {
    /** The block the range starts with, or `null` if it does not hold a whole valid one. */
    suspend fun decode(bytes: ByteArray, start: Long, end: Long): DecodedRange?

    fun close()
}

/** The platform's way of decoding blocks on other threads, or `null` to use coroutines on this one's pool. */
internal expect fun platformBlockDecoders(workers: Int): BlockDecoders?

private class PooledBlockDecoders(workers: Int) : BlockDecoders {
    private val decoders = Channel<Bzip2BlockDecoder>(workers)

    init {
        repeat(workers) {
            val _ = decoders.trySend(Bzip2BlockDecoder())
        }
    }

    override suspend fun decode(bytes: ByteArray, start: Long, end: Long): DecodedRange? {
        val decoder = decoders.receive()
        return try {
            withContext(Dispatchers.Default) { decoder.decodeRange(bytes, start, end, MAX_AHEAD_OUTPUT) }
        } finally {
            val _ = decoders.trySend(decoder)
        }
    }

    override fun close() = Unit
}

private suspend fun parallelBzip2(path: Path, decoderCount: Int, piece: suspend (ByteArray, Int) -> Unit) =
    coroutineScope {
        val decoders = platformBlockDecoders(decoderCount) ?: PooledBlockDecoders(decoderCount)
        val stretches = Channel<BlockStretch>(decoderCount * 2)

        launch(ioDispatcher) {
            try {
                openDumpFile(path).use { source ->
                    val scanner = BlockScanner()
                    val bytes = ByteArray(PIECE)
                    val reader = PieceReader(source)
                    while (true) {
                        val count = reader.read(bytes)
                        val last = count < bytes.size
                        val ranges = scanner.feed(bytes, count, last)
                        for (index in ranges.starts.indices) {
                            stretches.send(stretchOf(ranges, index, decoders))
                        }
                        if (last || ranges.gaveUp) break
                        yield()
                    }
                }
            } finally {
                stretches.close()
            }
        }

        try {
            BlockChain { stretches.receiveCatching().getOrNull() }.follow(piece)
        } finally {
            decoders.close()
        }
    }

private fun CoroutineScope.stretchOf(ranges: BlockRanges, index: Int, decoders: BlockDecoders): BlockStretch {
    val start = ranges.starts[index]
    val end = ranges.ends[index]
    val kind = ranges.kinds[index]
    val gaveUp = ranges.gaveUp && index == ranges.starts.lastIndex
    val decoded =
        if (kind == MarkerKind.BLOCK && !gaveUp) async { decoders.decode(ranges.bytes, start, end) } else null
    return BlockStretch(ranges.bytes, start, end, ranges.base + start, kind, decoded, gaveUp)
}

/** Decompresses and cuts the file on this coroutine's helpers, handing each page's bytes to [page]. */
private suspend fun pagesOf(
    path: Path,
    compression: Compression,
    workers: Int,
    page: suspend (ByteArray) -> Unit,
) {
    val splitter = PageSplitter()
    decompressedPieces(path, compression, workers) { bytes, count ->
        val pages = ArrayList<ByteArray>()
        splitter.feed(bytes, count) { pages += it }
        for (slice in pages) page(slice)
    }
    splitter.finish()
}

/**
 * Every page of the dump at [path] handed to [block] by [workers] coroutines, in no particular order.
 *
 * A damaged dump ends the reading rather than the workers, so each page before the damage still reaches
 * [block] before the failure is thrown. The first exception from [block] ends everything at once.
 */
@OptIn(ExperimentalAtomicApi::class)
internal suspend fun scanDump(
    path: Path,
    workers: Int,
    namespaces: Set<Int>,
    block: suspend (DumpPage) -> Unit,
) {
    val failure = AtomicReference<DumpFormatException?>(null)
    coroutineScope {
        val compression = compressionOf(path)
        val slices = Channel<ByteArray>(workers * QUEUED_PER_WORKER)
        launch(ioDispatcher) {
            try {
                pagesOf(path, compression, workers) { slices.send(it) }
            } catch (e: DumpFormatException) {
                val _ = failure.compareAndSet(null, e)
            } finally {
                slices.close()
            }
        }
        repeat(workers) {
            launch(Dispatchers.Default) {
                for (slice in slices) {
                    val page =
                        try {
                            parsePage(slice, namespaces)
                        } catch (e: DumpFormatException) {
                            val _ = failure.compareAndSet(null, e)
                            slices.cancel()
                            break
                        }
                    page?.let { block(it) }
                    yield()
                }
            }
        }
    }
    failure.load()?.let { throw it }
}

/**
 * Every page of the dump at [path], decoded by [workers] coroutines and delivered in the dump's order.
 *
 * A failure takes its place in that order, behind each page before the damage.
 */
internal fun dumpFlow(path: Path, workers: Int, namespaces: Set<Int>): Flow<DumpPage> = flow {
    coroutineScope {
        val compression = compressionOf(path)
        val parsed = Channel<Deferred<Result<DumpPage?>>>(workers * QUEUED_PER_WORKER)
        var failure: DumpFormatException? = null
        launch(ioDispatcher) {
            try {
                pagesOf(path, compression, workers) { slice ->
                    parsed.send(async(Dispatchers.Default) { parsedOrFailure(slice, namespaces) })
                }
            } catch (e: DumpFormatException) {
                failure = e
            } finally {
                parsed.close()
            }
        }
        for (page in parsed) page.await().getOrThrow()?.let { emit(it) }
        failure?.let { throw it }
    }
}

private fun parsedOrFailure(slice: ByteArray, namespaces: Set<Int>): Result<DumpPage?> =
    try {
        Result.success(parsePage(slice, namespaces))
    } catch (e: DumpFormatException) {
        Result.failure(e)
    }
