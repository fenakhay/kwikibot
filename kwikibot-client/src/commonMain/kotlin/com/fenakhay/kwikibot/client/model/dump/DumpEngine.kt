package com.fenakhay.kwikibot.client.model.dump

import com.fenakhay.kwikibot.client.internal.ioDispatcher
import com.fenakhay.kwikibot.client.model.Compression
import com.fenakhay.kwikibot.client.model.DumpPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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

/** Reads up to a whole array, taking as many reads as that needs, and says how many bytes came. */
private fun RawSource.readPiece(into: ByteArray, holding: Buffer): Int {
    var filled = 0
    while (filled < into.size) {
        if (holding.exhausted() && readAtMostTo(holding, (into.size - filled).toLong()) < 0) break
        filled += holding.readAtMostTo(into, filled, into.size)
    }
    return filled
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
        val holding = Buffer()
        while (true) {
            val count = input.readPiece(piece, holding)
            if (count == 0) break
            val pages = ArrayList<ByteArray>()
            splitter.feed(piece, count) { pages += it }
            for (page in pages) parsePage(page, namespaces)?.let { yield(it) }
        }
        splitter.finish()
    }
}

/**
 * Passes the pieces of decompressed XML in [path] to [piece], in order.
 *
 * bzip2 is decoded by [workers] threads, a block each, with the results put back in order. A range that fails
 * either ended early at a coincidental block marker or is one, so it is tried again with the range after it;
 * data that fails again is corrupt. gzip has no boundaries to split on, so it takes one thread however many
 * are offered.
 */
private suspend fun decompressedPieces(
    path: Path,
    compression: Compression,
    workers: Int,
    piece: suspend (ByteArray, Int) -> Unit,
) {
    if (compression == Compression.BZIP2 && workers > 1) {
        parallelBzip2(path, workers, piece)
        return
    }

    decompressing(SystemFileSystem.source(path), compression).use { input ->
        val bytes = ByteArray(PIECE)
        val holding = Buffer()
        while (true) {
            val count = input.readPiece(bytes, holding)
            if (count == 0) break
            piece(bytes, count)
            yield()
        }
    }
}

/** A block's bit range in the bytes that hold it. */
private class BlockRange(val bytes: ByteArray, val start: Long, val end: Long)

/** A decoded block, or the range to try again if it did not decode. */
private class DecodedBlock(val range: BlockRange, val output: ByteArray?)

private suspend fun parallelBzip2(path: Path, workers: Int, piece: suspend (ByteArray, Int) -> Unit) =
    coroutineScope {
        val decoders = Channel<Bzip2BlockDecoder>(workers)
        repeat(workers) { decoders.trySend(Bzip2BlockDecoder()) }
        val tasks = Channel<Deferred<DecodedBlock>>(workers * 2)
        val scanner = BlockScanner()

        launch(ioDispatcher) {
            try {
                SystemFileSystem.source(path).use { source ->
                    val bytes = ByteArray(PIECE)
                    val holding = Buffer()
                    while (true) {
                        val count = source.readPiece(bytes, holding)
                        val last = count < bytes.size
                        val ranges = scanner.feed(bytes, count, last)
                        for (index in ranges.starts.indices) {
                            val range = BlockRange(ranges.bytes, ranges.starts[index], ranges.ends[index])
                            tasks.send(decodeLater(range, decoders))
                        }
                        if (last) break
                        yield()
                    }
                }
            } finally {
                tasks.close()
            }
        }

        val retry = Bzip2BlockDecoder()
        var held: DecodedBlock? = null
        var any = false
        for (task in tasks) {
            val block = task.await()
            val earlier = held
            if (earlier != null) {
                held = null
                val joined = joinRanges(earlier.range, block.range)
                val output =
                    retry.decodeRange(joined.bytes, joined.start, joined.end)
                        ?: badChecksum("a bzip2 block cannot be decoded")
                piece(output, output.size)
                any = true
                continue
            }
            if (block.output == null) {
                held = block
            } else {
                piece(block.output, block.output.size)
                any = true
            }
        }
        if (held != null) truncated("a bzip2 block")
        if (!scanner.endedCleanly) {
            if (any) truncated("a bzip2 stream") else corrupt("this is not a bzip2 stream")
        }
    }

private fun CoroutineScope.decodeLater(
    range: BlockRange,
    decoders: Channel<Bzip2BlockDecoder>,
): Deferred<DecodedBlock> =
    async(Dispatchers.Default) {
        val decoder = decoders.receive()
        try {
            DecodedBlock(range, decoder.decodeRange(range.bytes, range.start, range.end))
        } finally {
            decoders.send(decoder)
        }
    }

/** One range covering two neighbours, which may sit in different arrays. */
private fun joinRanges(first: BlockRange, second: BlockRange): BlockRange {
    if (first.bytes === second.bytes) return BlockRange(first.bytes, first.start, second.end)

    val firstByte = (first.start ushr 3).toInt()
    val secondEnd = ((second.end + 7) ushr 3).toInt()
    val secondFirst = (second.start ushr 3).toInt()
    val joined =
        first.bytes.copyOfRange(firstByte, first.bytes.size).let { head ->
            val overlapInSecond = (first.end ushr 3).toInt() - firstByte
            head.copyOf(overlapInSecond) + second.bytes.copyOfRange(secondFirst, secondEnd)
        }
    return BlockRange(joined, first.start and 7, joined.size * 8L)
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

/** Every page of the dump at [path] handed to [block] by [workers] coroutines, in no particular order. */
internal suspend fun scanDump(
    path: Path,
    workers: Int,
    namespaces: Set<Int>,
    block: suspend (DumpPage) -> Unit,
) = coroutineScope {
    val compression = compressionOf(path)
    val slices = Channel<ByteArray>(workers * QUEUED_PER_WORKER)
    launch(ioDispatcher) {
        try {
            pagesOf(path, compression, workers) { slices.send(it) }
        } finally {
            slices.close()
        }
    }
    repeat(workers) {
        launch(Dispatchers.Default) {
            for (slice in slices) {
                parsePage(slice, namespaces)?.let { block(it) }
                yield()
            }
        }
    }
}

/** Every page of the dump at [path], decoded by [workers] coroutines and delivered in the dump's order. */
internal fun dumpFlow(path: Path, workers: Int, namespaces: Set<Int>): Flow<DumpPage> = flow {
    coroutineScope {
        val compression = compressionOf(path)
        val parsed = Channel<Deferred<DumpPage?>>(workers * QUEUED_PER_WORKER)
        launch(ioDispatcher) {
            try {
                pagesOf(path, compression, workers) { slice ->
                    parsed.send(async(Dispatchers.Default) { parsePage(slice, namespaces) })
                }
            } finally {
                parsed.close()
            }
        }
        for (page in parsed) page.await()?.let { emit(it) }
    }
}
