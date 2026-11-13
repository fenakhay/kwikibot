@file:Suppress("MagicNumber", "LoopWithTooManyJumpStatements")

package com.fenakhay.kwikibot.client.model.dump

import kotlinx.coroutines.Deferred

private const val MAGIC_BYTES = 6
private const val HEADER_BYTES = 4
private const val CRC_BYTES = 4
private const val PADDING_BYTES = 1

/**
 * A stretch of a bzip2 file that starts with what might be a marker.
 *
 * @param bytes the array holding it.
 * @param start where it starts in [bytes], in bits.
 * @param end where it ends in [bytes], in bits.
 * @param position where it starts in the file, in bits.
 * @param kind what its marker would be.
 * @param decoded its block, decoded ahead of the reader, if it was worth trying.
 * @param gaveUp whether the scanner stopped here, having found no marker for too long.
 */
internal class BlockStretch(
    val bytes: ByteArray,
    val start: Long,
    val end: Long,
    val position: Long,
    val kind: MarkerKind,
    val decoded: Deferred<DecodedRange?>?,
    val gaveUp: Boolean,
) {
    /** Where it ends in the file, in bits. */
    val endPosition: Long
        get() = position + (end - start)
}

/**
 * Follows a bzip2 file from its first bit to its last as [Bzip2Source] does, taking blocks others have
 * decoded ahead of it when they are the ones it needs.
 *
 * The scanner's markers are only guesses, and a guess can be wrong both ways. So a block counts only when the
 * block before it ended exactly where it starts; a marker inside a block is passed over and whatever was
 * decoded from it is dropped; and a place where a marker should be but none was found is refused. A block
 * that did not decode ahead is decoded here from all the input it could need, which also gives the same
 * failure [Bzip2Source] would. Each stream's header and CRC are checked. What comes out is what [Bzip2Source]
 * gives, or nothing.
 *
 * @param nextStretch the next stretch of the file, or `null` at its end.
 */
internal class BlockChain(private val nextStretch: suspend () -> BlockStretch?) {
    private val held = ArrayDeque<BlockStretch>()
    private val decoder by lazy { Bzip2BlockDecoder() }
    private var ended = false
    private var gaveUp = false
    private var position = 0L
    private var streamCrc = 0

    /**
     * Follows the whole file, handing each block's bytes to [piece].
     *
     * @throws DumpFormatException if the file is cut short or damaged, once the blocks before that are handed
     *   on.
     */
    suspend fun follow(piece: suspend (ByteArray, Int) -> Unit) {
        var inStream = startStream(first = true)
        while (inStream) {
            val marker = markerHere() ?: noMarker()
            if (marker.kind == MarkerKind.BLOCK) {
                readBlock(marker, piece)
            } else {
                endStream()
                inStream = startStream(first = false)
            }
        }
    }

    /** Whether a stream starts here, as it must in the first place, rather than the file ending. */
    private suspend fun startStream(first: Boolean): Boolean {
        val header = bytesHere(HEADER_BYTES)
        if (header.isEmpty()) {
            if (first) truncated("a bzip2 header")
            refuseIfScannerGaveUp()
            return false
        }
        if (header.size < HEADER_BYTES) {
            refuseIfScannerGaveUp()
            truncated("a bzip2 header")
        }
        val isHeader =
            header[0] == 'B'.code.toByte() &&
                header[1] == 'Z'.code.toByte() &&
                header[2] == 'h'.code.toByte() &&
                header[3].toInt() in '1'.code..'9'.code
        if (!isHeader) corrupt("this is not a bzip2 stream")
        position += HEADER_BYTES * 8L
        streamCrc = 0
        return true
    }

    /** The stretch starting exactly here, or `null` if none does. */
    private suspend fun markerHere(): BlockStretch? {
        while (true) {
            if (held.isEmpty() && !pull()) return null
            val first = held.first()
            when {
                first.position > position -> return null
                first.position == position -> return first
                first.endPosition > position -> return null
                else -> dropFirst()
            }
        }
    }

    private suspend fun noMarker(): Nothing {
        val here = bytesHere(MAGIC_BYTES + PADDING_BYTES)
        refuseIfScannerGaveUp()
        if (bitsHere(here) < MAGIC_BYTES * 8) truncated("a bzip2 stream")
        corrupt("what follows a bzip2 block is not another block")
    }

    private suspend fun readBlock(marker: BlockStretch, piece: suspend (ByteArray, Int) -> Unit) {
        val ahead = marker.decoded?.await()
        if (ahead != null) {
            accept(ahead.crc, marker.position + (ahead.endBit - marker.start))
            piece(ahead.output, ahead.output.size)
            return
        }

        val input = bytesHere(MAX_SEGMENT)
        val bits = MsbBits(input, position and 7, input.size)
        val _ = bits.long48()
        try {
            decoder.decode(bits)
        } catch (e: DumpFormatException) {
            if (bits.overran) {
                refuseIfScannerGaveUp()
                if (ended) truncated("a bzip2 block")
                corrupt("a bzip2 block is longer than any block bzip2 can write")
            }
            throw e
        }
        accept(decoder.blockCrc, (position and 7L.inv()) + bits.bitPosition)
        piece(decoder.output, decoder.outputLength)
    }

    private fun accept(blockCrc: Int, endPosition: Long) {
        streamCrc = ((streamCrc shl 1) or (streamCrc ushr 31)) xor blockCrc
        position = endPosition
    }

    private suspend fun endStream() {
        val input = bytesHere(MAGIC_BYTES + CRC_BYTES + PADDING_BYTES)
        if (bitsHere(input) < (MAGIC_BYTES + CRC_BYTES) * 8) {
            refuseIfScannerGaveUp()
            truncated("a bzip2 stream")
        }
        val bits = MsbBits(input, position and 7, input.size)
        val _ = bits.long48()
        val stored = bits.bits(32)
        if (stored != streamCrc) badChecksum("a bzip2 stream does not match its checksum")
        bits.align()
        position = (position and 7L.inv()) + bits.bitPosition
    }

    /** How many bits there are from here in [bytes], which start at the byte holding the next bit. */
    private fun bitsHere(bytes: ByteArray): Long = bytes.size * 8L - (position and 7)

    private fun refuseIfScannerGaveUp() {
        if (gaveUp) corrupt("a bzip2 file has no block marker where a block must have ended")
    }

    /**
     * Up to [wanted] bytes of the file from the byte holding the next bit, fewer only where the file or the
     * scanner stopped.
     */
    private suspend fun bytesHere(wanted: Int): ByteArray {
        val firstByte = position ushr 3
        while (held.firstOrNull()?.let { it.endPosition <= position } == true) dropFirst()

        var index = 0
        val result = ByteArray(wanted)
        var filled = 0
        while (filled < wanted) {
            if (index == held.size && !pull()) break
            val stretch = held[index++]
            val inFile = stretch.position ushr 3
            val inArray = stretch.start ushr 3
            val endInArray = (stretch.end + 7) ushr 3
            val from = maxOf(firstByte + filled, inFile)
            val count = minOf(endInArray - inArray - (from - inFile), (wanted - filled).toLong())
            if (count <= 0) continue
            val source = (inArray + (from - inFile)).toInt()
            stretch.bytes.copyInto(result, filled, source, source + count.toInt())
            filled += count.toInt()
        }
        return if (filled == wanted) result else result.copyOf(filled)
    }

    private suspend fun pull(): Boolean {
        if (ended) return false
        val stretch = nextStretch()
        if (stretch == null) {
            ended = true
            return false
        }
        gaveUp = gaveUp || stretch.gaveUp
        held.addLast(stretch)
        return true
    }

    private fun dropFirst() {
        held.removeFirst().decoded?.cancel()
    }
}
