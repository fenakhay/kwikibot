package com.fenakhay.kwikibot.client.model.dump

import kotlinx.io.Buffer
import kotlinx.io.RawSource

private const val WINDOW_SIZE = 1 shl 15
private const val WINDOW_MASK = WINDOW_SIZE - 1
private const val OUTPUT_TARGET = 1 shl 16
private const val INPUT_CHUNK = 1 shl 16
private const val FAST_BITS = 10
private const val MAX_BITS = 15
private const val END_OF_BLOCK = 256
private const val FLAG_HEADER_CRC = 2
private const val FLAG_EXTRA = 4
private const val FLAG_NAME = 8
private const val FLAG_COMMENT = 16
private const val GZIP_ID1 = 0x1F
private const val GZIP_ID2 = 0x8B
private const val DEFLATE = 8

private val LENGTH_BASE =
    intArrayOf(
        3,
        4,
        5,
        6,
        7,
        8,
        9,
        10,
        11,
        13,
        15,
        17,
        19,
        23,
        27,
        31,
        35,
        43,
        51,
        59,
        67,
        83,
        99,
        115,
        131,
        163,
        195,
        227,
        258,
    )
private val LENGTH_EXTRA =
    intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
private val DISTANCE_BASE =
    intArrayOf(
        1,
        2,
        3,
        4,
        5,
        7,
        9,
        13,
        17,
        25,
        33,
        49,
        65,
        97,
        129,
        193,
        257,
        385,
        513,
        769,
        1025,
        1537,
        2049,
        3073,
        4097,
        6145,
        8193,
        12289,
        16385,
        24577,
    )
private val DISTANCE_EXTRA =
    intArrayOf(
        0,
        0,
        0,
        0,
        1,
        1,
        2,
        2,
        3,
        3,
        4,
        4,
        5,
        5,
        6,
        6,
        7,
        7,
        8,
        8,
        9,
        9,
        10,
        10,
        11,
        11,
        12,
        12,
        13,
        13,
    )
private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

private val CRC_TABLE =
    IntArray(256) { index ->
        var crc = index
        repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
        crc
    }

/** A canonical Huffman code: a table of short codes, and a walk over the counts for the long ones. */
private class HuffmanTable(size: Int) {
    val quick = ShortArray(1 shl FAST_BITS)
    val counts = ShortArray(MAX_BITS + 1)
    val symbols = ShortArray(size)

    fun build(lengths: ByteArray, from: Int, count: Int) {
        counts.fill(0)
        quick.fill(0)
        for (index in 0 until count) counts[lengths[from + index].toInt()]++
        counts[0] = 0

        val offsets = IntArray(MAX_BITS + 2)
        val next = IntArray(MAX_BITS + 2)
        var code = 0
        for (length in 1..MAX_BITS) {
            offsets[length + 1] = offsets[length] + counts[length]
            next[length] = code
            code = (code + counts[length]) shl 1
        }
        val placed = offsets.copyOf()
        for (index in 0 until count) {
            val length = lengths[from + index].toInt()
            if (length == 0) continue
            symbols[placed[length]++] = index.toShort()

            val assigned = next[length]++
            if (length <= FAST_BITS) {
                var reversed = 0
                var rest = assigned
                repeat(length) {
                    reversed = (reversed shl 1) or (rest and 1)
                    rest = rest ushr 1
                }
                val entry = ((length shl 11) or index).toShort()
                var fill = reversed
                while (fill < quick.size) {
                    quick[fill] = entry
                    fill += 1 shl length
                }
            }
        }
    }
}

/**
 * The gzip data of [input], with every member of it read one after another, as `GZIPInputStream` does.
 *
 * Bits arrive through a 64-bit accumulator, output goes to a fixed window, and neither is copied per chunk.
 */
internal class GzipSource(private val input: RawSource) : RawSource {
    private val incoming = ByteArray(INPUT_CHUNK)
    private var position = 0
    private var limit = 0
    private var sourceEnded = false
    private val chunk = Buffer()

    private var accumulator = 0L
    private var available = 0

    private val window = ByteArray(WINDOW_SIZE)
    private var windowEnd = 0
    private var total = 0L
    private var unflushed = 0
    private var crc = -1

    private val literals = HuffmanTable(288)
    private val distances = HuffmanTable(32)
    private val lengths = ByteArray(320)
    private val codeLengths = ByteArray(19)
    private val codeLengthTable = HuffmanTable(19)

    private var state = State.HEADER
    private var lastBlock = false
    private var storedLeft = 0
    private val pending = Buffer()
    private var membersRead = 0

    private enum class State {
        HEADER,
        BLOCK_START,
        STORED,
        HUFFMAN,
        TRAILER,
        FINISHED,
    }

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        while (pending.exhausted() && state != State.FINISHED) advance()
        if (pending.exhausted()) return -1
        return pending.readAtMostTo(sink, byteCount)
    }

    private fun advance() {
        when (state) {
            State.HEADER -> readHeader()
            State.BLOCK_START -> readBlockStart()
            State.STORED -> copyStored()
            State.HUFFMAN -> inflate()
            State.TRAILER -> readTrailer()
            State.FINISHED -> Unit
        }
    }

    private fun refillInput(): Boolean {
        if (chunk.exhausted()) {
            if (sourceEnded) return false
            if (input.readAtMostTo(chunk, INPUT_CHUNK.toLong()) < 0) {
                sourceEnded = true
                return false
            }
        }
        limit = chunk.readAtMostTo(incoming, 0, INPUT_CHUNK)
        position = 0
        return limit > 0
    }

    private fun nextByte(): Int {
        if (position == limit && !refillInput()) truncated("a gzip stream")
        return incoming[position++].toInt() and 0xFF
    }

    private fun need(count: Int) {
        while (available < count) {
            if (position == limit && !refillInput()) {
                if (available == 0) truncated("a gzip stream")
                return
            }
            accumulator = accumulator or ((incoming[position++].toLong() and 0xFF) shl available)
            available += 8
        }
    }

    private fun bits(count: Int): Int {
        if (count == 0) return 0
        need(count)
        if (available < count) truncated("a gzip stream")
        val value = (accumulator and ((1L shl count) - 1)).toInt()
        accumulator = accumulator ushr count
        available -= count
        return value
    }

    private fun alignToByte() {
        val extra = available and 7
        accumulator = accumulator ushr extra
        available -= extra
    }

    /** The next byte, taking what the accumulator read ahead first. */
    private fun alignedByte(): Int {
        if (available >= 8) {
            val value = (accumulator and 0xFF).toInt()
            accumulator = accumulator ushr 8
            available -= 8
            return value
        }
        return nextByte()
    }

    private fun readHeader() {
        if (available == 0 && position == limit && !refillInput()) {
            if (membersRead == 0) truncated("a gzip header")
            state = State.FINISHED
            return
        }
        val first = alignedByte()
        val second = alignedByte()
        if (first != GZIP_ID1 || second != GZIP_ID2) corrupt("this is not a gzip stream")
        if (alignedByte() != DEFLATE) corrupt("this gzip stream is not compressed with deflate")
        val flags = alignedByte()
        repeat(6) {
            val _ = alignedByte()
        }
        if (flags and FLAG_EXTRA != 0) {
            val size = alignedByte() or (alignedByte() shl 8)
            repeat(size) {
                val _ = alignedByte()
            }
        }
        if (flags and FLAG_NAME != 0) {
            while (alignedByte() != 0) {
                continue
            }
        }
        if (flags and FLAG_COMMENT != 0) {
            while (alignedByte() != 0) {
                continue
            }
        }
        if (flags and FLAG_HEADER_CRC != 0)
            repeat(2) {
                val _ = alignedByte()
            }

        crc = -1
        total = 0
        state = State.BLOCK_START
    }

    private fun readBlockStart() {
        lastBlock = bits(1) == 1
        when (bits(2)) {
            0 -> {
                alignToByte()
                val size = alignedByte() or (alignedByte() shl 8)
                val check = alignedByte() or (alignedByte() shl 8)
                if (size xor 0xFFFF != check) corrupt("a stored gzip block does not match its length")
                storedLeft = size
                state = State.STORED
            }
            1 -> {
                lengths.fill(8, 0, 144)
                lengths.fill(9, 144, 256)
                lengths.fill(7, 256, 280)
                lengths.fill(8, 280, 288)
                literals.build(lengths, 0, 288)
                lengths.fill(5, 0, 30)
                distances.build(lengths, 0, 30)
                state = State.HUFFMAN
            }
            2 -> {
                readDynamicTables()
                state = State.HUFFMAN
            }
            else -> corrupt("a gzip block is of a type that does not exist")
        }
    }

    private fun readDynamicTables() {
        val literalCount = bits(5) + 257
        val distanceCount = bits(5) + 1
        val codeCount = bits(4) + 4
        if (literalCount > 286 || distanceCount > 30) corrupt("a gzip block defines too many codes")

        codeLengths.fill(0)
        for (index in 0 until codeCount) codeLengths[CODE_LENGTH_ORDER[index]] = bits(3).toByte()
        codeLengthTable.build(codeLengths, 0, 19)

        var index = 0
        val count = literalCount + distanceCount
        while (index < count) {
            val symbol = decodeSymbol(codeLengthTable)
            when {
                symbol < 16 -> lengths[index++] = symbol.toByte()
                else -> {
                    val repeat: Int
                    var value: Byte = 0
                    when (symbol) {
                        16 -> {
                            if (index == 0) corrupt("a gzip block repeats a length before any was given")
                            value = lengths[index - 1]
                            repeat = 3 + bits(2)
                        }
                        17 -> repeat = 3 + bits(3)
                        else -> repeat = 11 + bits(7)
                    }
                    if (index + repeat > count) corrupt("a gzip block repeats past its code lengths")
                    lengths.fill(value, index, index + repeat)
                    index += repeat
                }
            }
        }
        if (lengths[END_OF_BLOCK].toInt() == 0) corrupt("a gzip block has no way to end")
        literals.build(lengths, 0, literalCount)
        distances.build(lengths, literalCount, distanceCount)
    }

    private fun decodeSymbol(table: HuffmanTable): Int {
        need(MAX_BITS)
        val entry = table.quick[(accumulator and ((1L shl FAST_BITS) - 1)).toInt()].toInt()
        if (entry != 0) {
            val length = entry ushr 11
            if (available < length) truncated("a gzip stream")
            accumulator = accumulator ushr length
            available -= length
            return entry and 0x7FF
        }

        var code = 0
        var first = 0
        var index = 0
        var bits = accumulator
        for (length in 1..MAX_BITS) {
            code = code or (bits and 1).toInt()
            bits = bits ushr 1
            val count = table.counts[length].toInt()
            if (code - count < first) {
                if (available < length) truncated("a gzip stream")
                accumulator = accumulator ushr length
                available -= length
                return table.symbols[index + (code - first)].toInt()
            }
            index += count
            first = (first + count) shl 1
            code = code shl 1
        }
        corrupt("a gzip code is not one the block defines")
    }

    private fun inflate() {
        val target = window
        var end = windowEnd
        var produced = 0

        while (produced < OUTPUT_TARGET) {
            val symbol = decodeSymbol(literals)
            when {
                symbol < END_OF_BLOCK -> {
                    target[end] = symbol.toByte()
                    end = (end + 1) and WINDOW_MASK
                    produced++
                    unflushed++
                    if (unflushed == WINDOW_SIZE) {
                        windowEnd = end
                        flush()
                    }
                }
                symbol == END_OF_BLOCK -> {
                    windowEnd = end
                    flush()
                    state = if (lastBlock) State.TRAILER else State.BLOCK_START
                    return
                }
                else -> {
                    val lengthCode = symbol - 257
                    if (lengthCode >= LENGTH_BASE.size) corrupt("a gzip length code is out of range")
                    val length = LENGTH_BASE[lengthCode] + bits(LENGTH_EXTRA[lengthCode])
                    val distanceCode = decodeSymbol(distances)
                    if (distanceCode >= DISTANCE_BASE.size) corrupt("a gzip distance code is out of range")
                    val distance = DISTANCE_BASE[distanceCode] + bits(DISTANCE_EXTRA[distanceCode])
                    if (distance > total + unflushed) corrupt("a gzip distance reaches before the data")

                    var from = (end - distance) and WINDOW_MASK
                    for (ignored in 0 until length) {
                        target[end] = target[from]
                        end = (end + 1) and WINDOW_MASK
                        from = (from + 1) and WINDOW_MASK
                        unflushed++
                        if (unflushed == WINDOW_SIZE) {
                            windowEnd = end
                            flush()
                        }
                    }
                    produced += length
                }
            }
        }
        windowEnd = end
        flush()
    }

    private fun copyStored() {
        val count = minOf(storedLeft, WINDOW_SIZE - unflushed)
        alignToByte()
        for (ignored in 0 until count) {
            window[windowEnd] = alignedByte().toByte()
            windowEnd = (windowEnd + 1) and WINDOW_MASK
        }
        unflushed += count
        storedLeft -= count
        flush()
        if (storedLeft == 0) state = if (lastBlock) State.TRAILER else State.BLOCK_START
    }

    /** Hands what the window holds on to the reader, and takes it into the checksum. */
    private fun flush() {
        if (unflushed == 0) return
        val start = (windowEnd - unflushed) and WINDOW_MASK
        val firstRun = minOf(unflushed, WINDOW_SIZE - start)
        update(start, firstRun)
        if (firstRun < unflushed) update(0, unflushed - firstRun)
        total += unflushed
        unflushed = 0
    }

    private fun update(from: Int, count: Int) {
        var running = crc
        for (index in from until from + count) {
            running = (running ushr 8) xor CRC_TABLE[(running xor window[index].toInt()) and 0xFF]
        }
        crc = running
        pending.write(window, from, from + count)
    }

    private fun readTrailer() {
        alignToByte()
        var stored = 0
        for (shift in 0 until 4) stored = stored or (alignedByte() shl (shift * 8))
        var size = 0
        for (shift in 0 until 4) size = size or (alignedByte() shl (shift * 8))
        if (stored != crc.inv()) badChecksum("a gzip stream does not match its checksum")
        if (size != total.toInt()) badChecksum("a gzip stream does not match its length")
        membersRead++
        state = State.HEADER
    }

    override fun close() {
        input.close()
    }
}
