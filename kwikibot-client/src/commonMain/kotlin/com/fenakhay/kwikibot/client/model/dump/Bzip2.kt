package com.fenakhay.kwikibot.client.model.dump

import kotlinx.io.Buffer
import kotlinx.io.RawSource

private const val BLOCK_MAGIC = 0x314159265359L
private const val END_MAGIC = 0x177245385090L
private const val MAGIC_MASK = 0xFFFFFFFFFFFFL
private const val MAGIC_BITS = 48
private const val MAX_BLOCK = 900_000
private const val MAX_CODE_LENGTH = 20
private const val MAX_ALPHABET = 258
private const val MAX_GROUPS = 6
private const val MIN_GROUPS = 2
private const val GROUP_RUN = 50
private const val MAX_SELECTORS = 18_002
private const val FAST_BITS = 10
private const val RUN_A = 0
private const val RUN_B = 1
private const val INPUT_CHUNK = 1 shl 20
private const val MAX_BLOCK_INPUT = 5 * INPUT_CHUNK / 2

/** The CRC bzip2 stores: most significant bit first, unlike the one gzip stores. */
private val CRC_TABLE =
    IntArray(256) { index ->
        var crc = index shl 24
        repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        crc
    }

/**
 * Reads bits from a byte range, most significant first, through a 64-bit accumulator.
 *
 * Past the end it reads zeros and notes it, so the decoding loops need no check per symbol. [overran] then
 * says whether the zeros were used.
 */
internal class MsbBits(private val bytes: ByteArray, startBit: Long, private val endByte: Int) {
    private var position = (startBit ushr 3).toInt()
    private var accumulator = 0L
    private var available = 0
    private var padded = 0

    init {
        val skip = (startBit and 7).toInt()
        if (skip > 0) bits(skip)
    }

    /** Whether more bits were consumed than the range holds. */
    val overran: Boolean
        get() = padded > available

    /** The offset of the next unread bit in the array. */
    val bitPosition: Long
        get() = position * 8L - available + padded

    private fun refill() {
        while (available <= 56) {
            val next =
                if (position < endByte) {
                    bytes[position++].toLong() and 0xFF
                } else {
                    padded += 8
                    0L
                }
            accumulator = accumulator or (next shl (56 - available))
            available += 8
        }
    }

    /** The next [count] bits, without consuming them. At most 32. */
    fun peek(count: Int): Int {
        if (available < count) refill()
        return (accumulator ushr (64 - count)).toInt()
    }

    fun skip(count: Int) {
        accumulator = accumulator shl count
        available -= count
    }

    /** The next [count] bits. At most 32. */
    fun bits(count: Int): Int {
        val value = peek(count)
        skip(count)
        return value
    }

    fun bit(): Int = bits(1)

    fun long48(): Long = (bits(24).toLong() shl 24) or (bits(24).toLong() and 0xFFFFFF)

    /** Moves to the next whole byte. */
    fun align() {
        val extra = available and 7
        if (extra > 0) skip(extra)
    }
}

/**
 * Decodes bzip2 blocks, reusing its arrays from one to the next.
 *
 * Each block stands alone, which is what lets [BlockRanges] hand them to separate threads. Keep one per
 * thread: the arrays are large, and it is not safe to share.
 */
internal class Bzip2BlockDecoder {
    private val tt = IntArray(MAX_BLOCK)
    private val selectors = ByteArray(MAX_SELECTORS)
    private val symbols = ByteArray(256)
    private val mtf = ByteArray(256)
    private val starts = IntArray(257)
    private val lengths = Array(MAX_GROUPS) { ByteArray(MAX_ALPHABET) }
    private val limits = Array(MAX_GROUPS) { IntArray(MAX_CODE_LENGTH + 2) }
    private val bases = Array(MAX_GROUPS) { IntArray(MAX_CODE_LENGTH + 2) }
    private val permutes = Array(MAX_GROUPS) { ShortArray(MAX_ALPHABET) }
    private val fast = Array(MAX_GROUPS) { ShortArray(1 shl FAST_BITS) }
    private val minimums = IntArray(MAX_GROUPS)
    private val maximums = IntArray(MAX_GROUPS)
    private var selectorCount = 0

    /** The decoded bytes of the last block; only the first [outputLength] mean anything. */
    var output = ByteArray(MAX_BLOCK + MAX_BLOCK / 8)
        private set

    var outputLength = 0
        private set

    /** The CRC the last block stored, which [decode] has already checked. */
    var blockCrc = 0
        private set

    /**
     * Decodes the block [bits] stands at, just past its magic number, into [output].
     *
     * @throws DumpFormatException if the block is cut short, malformed, or fails its CRC.
     */
    fun decode(bits: MsbBits) {
        try {
            decodeBlock(bits)
        } catch (_: IndexOutOfBoundsException) {
            corrupt("a bzip2 block refers to something it does not hold")
        }
    }

    private fun decodeBlock(bits: MsbBits) {
        blockCrc = bits.bits(32)
        if (bits.bit() != 0) corrupt("this bzip2 file uses randomised blocks, which no current tool writes")
        val origin = bits.bits(24)

        val alphabet = readSymbols(bits)
        readSelectors(bits, alphabet).let { groups -> readTables(bits, groups, alphabet) }
        val count = readBlock(bits, alphabet)
        if (bits.overran) truncated("a bzip2 block")
        if (origin >= count && count > 0) corrupt("a bzip2 block's origin is past its end")

        invert(count)
        writeOutput(count, origin)
    }

    private fun readSymbols(bits: MsbBits): Int {
        val used = bits.bits(16)
        var count = 0
        for (group in 0 until 16) {
            if (used and (0x8000 ushr group) == 0) continue
            val members = bits.bits(16)
            for (member in 0 until 16) {
                if (members and (0x8000 ushr member) != 0) symbols[count++] = (group * 16 + member).toByte()
            }
        }
        if (count == 0) corrupt("a bzip2 block uses no symbols")
        return count + 2
    }

    private fun readSelectors(bits: MsbBits, alphabet: Int): Int {
        val groups = bits.bits(3)
        if (groups < MIN_GROUPS || groups > MAX_GROUPS || alphabet > MAX_ALPHABET) {
            corrupt("a bzip2 block has $groups Huffman tables")
        }
        val count = bits.bits(15)
        if (count < 1) corrupt("a bzip2 block has no selectors")

        var order = 0x543210
        for (index in 0 until count) {
            var position = 0
            while (bits.bit() == 1) {
                position++
                if (position >= groups) corrupt("a bzip2 selector is out of range")
            }
            val shift = position * 4
            val value = (order ushr shift) and 15
            val below = order and ((1 shl shift) - 1)
            val above = (order ushr (shift + 4)) shl (shift + 4)
            order = above or (below shl 4) or value
            if (index < MAX_SELECTORS) selectors[index] = value.toByte()
        }
        selectorCount = minOf(count, MAX_SELECTORS)
        return groups
    }

    private fun readTables(bits: MsbBits, groups: Int, alphabet: Int) {
        for (group in 0 until groups) {
            var length = bits.bits(5)
            val table = lengths[group]
            for (symbol in 0 until alphabet) {
                while (true) {
                    if (length < 1 || length > MAX_CODE_LENGTH) corrupt("a bzip2 code length is out of range")
                    if (bits.bit() == 0) break
                    length += if (bits.bit() == 0) 1 else -1
                }
                table[symbol] = length.toByte()
            }
            buildTable(group, alphabet)
        }
    }

    private fun buildTable(group: Int, alphabet: Int) {
        val table = lengths[group]
        val limit = limits[group]
        val base = bases[group]
        val permute = permutes[group]
        val quick = fast[group]

        var shortest = MAX_CODE_LENGTH + 1
        var longest = 0
        for (symbol in 0 until alphabet) {
            val length = table[symbol].toInt()
            if (length < shortest) shortest = length
            if (length > longest) longest = length
        }
        minimums[group] = shortest
        maximums[group] = longest

        var index = 0
        for (length in shortest..longest) {
            for (symbol in 0 until alphabet) {
                if (table[symbol].toInt() == length) permute[index++] = symbol.toShort()
            }
        }

        val counts = IntArray(MAX_CODE_LENGTH + 2)
        for (symbol in 0 until alphabet) counts[table[symbol].toInt()]++

        var code = 0
        var seen = 0
        for (length in shortest..longest) {
            base[length] = seen - code
            code += counts[length]
            seen += counts[length]
            limit[length] = code shl (MAX_CODE_LENGTH - length)
            code = code shl 1
        }
        limit[longest + 1] = Int.MAX_VALUE

        quick.fill(0)
        code = 0
        seen = 0
        for (length in shortest..minOf(longest, FAST_BITS)) {
            for (offset in 0 until counts[length]) {
                val prefix = (code + offset) shl (FAST_BITS - length)
                val entry = ((length shl 10) or permute[seen + offset].toInt()).toShort()
                for (fill in 0 until (1 shl (FAST_BITS - length))) {
                    if (prefix + fill < quick.size) quick[prefix + fill] = entry
                }
            }
            code = (code + counts[length]) shl 1
            seen += counts[length]
        }
    }

    private fun readBlock(bits: MsbBits, alphabet: Int): Int {
        val endOfBlock = alphabet - 1
        for (index in 0 until alphabet - 2) mtf[index] = symbols[index]
        starts.fill(0)

        var count = 0
        var run = 0
        var runBit = 1
        var selector = 0
        var left = 0
        var group = 0

        while (true) {
            if (left == 0) {
                if (selector >= selectorCount) corrupt("a bzip2 block runs out of selectors")
                group = selectors[selector++].toInt()
                left = GROUP_RUN
            }
            left--
            val symbol = nextSymbol(bits, group)

            if (symbol <= RUN_B) {
                run += runBit shl symbol
                runBit = runBit shl 1
                if (run > MAX_BLOCK) corrupt("a bzip2 run overflows its block")
                continue
            }

            if (run > 0) {
                if (count + run > MAX_BLOCK) corrupt("a bzip2 block overflows")
                val byte = mtf[0].toInt() and 0xFF
                starts[byte] += run
                tt.fill(byte, count, count + run)
                count += run
                run = 0
                runBit = 1
            }
            if (symbol == endOfBlock) return count
            if (count >= MAX_BLOCK) corrupt("a bzip2 block overflows")

            val position = symbol - 1
            val value = mtf[position]
            mtf.copyInto(mtf, 1, 0, position)
            mtf[0] = value
            val byte = value.toInt() and 0xFF
            starts[byte]++
            tt[count++] = byte

            if (bits.overran) truncated("a bzip2 block")
        }
    }

    private fun nextSymbol(bits: MsbBits, group: Int): Int {
        val peeked = bits.peek(MAX_CODE_LENGTH)
        val entry = fast[group][peeked ushr (MAX_CODE_LENGTH - FAST_BITS)].toInt()
        if (entry != 0) {
            bits.skip(entry ushr 10)
            return entry and 0x3FF
        }

        val limit = limits[group]
        var length = maxOf(minimums[group], FAST_BITS + 1)
        while (length <= MAX_CODE_LENGTH && peeked >= limit[length]) length++
        if (length > maximums[group]) corrupt("a bzip2 code is not one the block defines")
        val index = (peeked ushr (MAX_CODE_LENGTH - length)) + bases[group][length]
        bits.skip(length)
        return permutes[group][index].toInt()
    }

    private fun invert(count: Int) {
        var total = 0
        for (byte in 0 until 256) {
            val here = starts[byte]
            starts[byte] = total
            total += here
        }
        for (index in 0 until count) {
            val byte = tt[index] and 0xFF
            val target = starts[byte]++
            tt[target] = tt[target] or (index shl 8)
        }
    }

    /** Follows the inverted transform, undoes the run-length coding, and checks the CRC on the way. */
    private fun writeOutput(count: Int, origin: Int) {
        var out = output
        var written = 0
        var crc = -1
        if (count > 0) {
            var position = tt[origin] ushr 8
            var runLength = 0
            var previous = -1
            for (ignored in 0 until count) {
                val entry = tt[position]
                val byte = entry and 0xFF
                position = entry ushr 8

                if (runLength == 4) {
                    if (written + byte > out.size) out = out.copyOf(maxOf(out.size * 2, written + byte))
                    if (byte > 0) {
                        out.fill(previous.toByte(), written, written + byte)
                        repeat(byte) {
                            crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor previous) and 0xFF]
                        }
                        written += byte
                    }
                    runLength = 0
                    previous = -1
                    continue
                }

                runLength = if (byte == previous) runLength + 1 else 1
                previous = byte
                if (written == out.size) out = out.copyOf(out.size * 2)
                out[written++] = byte.toByte()
                crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor byte) and 0xFF]
            }
        }
        output = out
        outputLength = written
        if (crc.inv() != blockCrc) badChecksum("a bzip2 block does not match its checksum")
    }
}

/** The bzip2 data of [input], decoded on the calling thread: every stream of it, one after another. */
internal class Bzip2Source(private val input: RawSource) : RawSource {
    private val decoder = Bzip2BlockDecoder()
    private var buffer = ByteArray(INPUT_CHUNK * 3)
    private var start = 0
    private var end = 0
    private var sourceEnded = false
    private var bitPosition = 0L
    private var inStream = false
    private var streamCrc = 0
    private var pending = Buffer()
    private var finished = false
    private var seenStream = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        while (pending.exhausted() && !finished) step()
        if (pending.exhausted()) return -1
        return pending.readAtMostTo(sink, byteCount)
    }

    /** Makes sure the whole of the next block is in the buffer, whatever is left of the file. */
    private fun fill() {
        val first = (bitPosition ushr 3).toInt()
        if (end - first >= MAX_BLOCK_INPUT || sourceEnded) return
        buffer.copyInto(buffer, 0, first, end)
        bitPosition -= first * 8L
        end -= first
        start = 0
        val chunk = Buffer()
        while (end < buffer.size && !sourceEnded) {
            val read = input.readAtMostTo(chunk, (buffer.size - end).toLong())
            if (read < 0) {
                sourceEnded = true
            } else {
                while (!chunk.exhausted()) end += chunk.readAtMostTo(buffer, end, buffer.size)
            }
        }
    }

    private fun step() {
        fill()
        if (!inStream) {
            startStream()
            return
        }

        val bits = MsbBits(buffer, bitPosition, end)
        val magic = bits.long48()
        if (bits.overran) truncated("a bzip2 stream")
        when (magic) {
            BLOCK_MAGIC -> {
                try {
                    decoder.decode(bits)
                } catch (e: DumpFormatException) {
                    if (sourceEnded && bits.overran) truncated("a bzip2 block")
                    throw e
                }
                streamCrc = ((streamCrc shl 1) or (streamCrc ushr 31)) xor decoder.blockCrc
                pending.write(decoder.output, 0, decoder.outputLength)
                bitPosition = bits.bitPosition
            }
            END_MAGIC -> {
                val stored = bits.bits(32)
                if (bits.overran) truncated("a bzip2 stream")
                if (stored != streamCrc) badChecksum("a bzip2 stream does not match its checksum")
                bits.align()
                bitPosition = bits.bitPosition
                inStream = false
            }
            else -> corrupt("what follows a bzip2 block is not another block")
        }
    }

    private fun startStream() {
        val first = (bitPosition ushr 3).toInt()
        if (end - first == 0) {
            if (!seenStream) truncated("a bzip2 header")
            finished = true
            return
        }
        if (end - first < 4) truncated("a bzip2 header")
        val level = buffer[first + 3].toInt()
        val isHeader =
            buffer[first] == 'B'.code.toByte() &&
                buffer[first + 1] == 'Z'.code.toByte() &&
                buffer[first + 2] == 'h'.code.toByte() &&
                level in '1'.code..'9'.code
        if (!isHeader) corrupt("this is not a bzip2 stream")
        bitPosition += 32
        inStream = true
        streamCrc = 0
        seenStream = true
    }

    override fun close() {
        input.close()
    }
}

/** Where blocks may start, in bits from the start of [bytes]: every place the block magic appears. */
internal class BlockRanges(
    val bytes: ByteArray,
    val starts: LongArray,
    val ends: LongArray,
    val endOfStream: Boolean,
)

/**
 * Finds the blocks in a run of bzip2 data, wherever their bits happen to fall.
 *
 * bzip2 aligns nothing to bytes, so a marker can start at any bit. Feed it the file a piece at a time. A
 * match can be a coincidence in the middle of a block, about once per 2^48 bits, in which case decoding finds
 * out.
 */
internal class BlockScanner {
    private var carry = ByteArray(0)
    private var carryBitsSeen = 0L
    private var open = -1L
    private var sawEnd = false

    /** Whether the last marker this has seen was the end of a stream, so nothing is left unfinished. */
    val endedCleanly: Boolean
        get() = sawEnd && open < 0

    /**
     * The next blocks whole within what has arrived. [piece] is taken over by the result.
     *
     * @param last whether this is the last piece of the file.
     */
    fun feed(piece: ByteArray, length: Int, last: Boolean): BlockRanges {
        val bytes = ByteArray(carry.size + length)
        carry.copyInto(bytes)
        piece.copyInto(bytes, carry.size, 0, length)

        val starts = ArrayList<Long>()
        val ends = ArrayList<Long>()
        val total = bytes.size * 8L

        var index = maxOf(1, (carryBitsSeen ushr 3).toInt() + 1)
        val lastIndex = bytes.size - MAGIC_BYTES_NEEDED
        while (index <= lastIndex) {
            val shifts = SHIFTS[bytes[index].toInt() and 0xFF].toInt()
            if (shifts != 0) {
                for (shift in 0 until 8) {
                    if (shifts and (1 shl shift) == 0) continue
                    val at = (index - 1) * 8L + shift
                    if (at < carryBitsSeen) continue
                    val magic = magicAt(bytes, index - 1, shift)
                    if (magic == BLOCK_MAGIC || magic == END_MAGIC) {
                        if (open >= 0) {
                            starts += open
                            ends += at
                        }
                        open = if (magic == BLOCK_MAGIC) at else -1
                        sawEnd = magic == END_MAGIC
                    }
                }
            }
            index++
        }

        if (last && open >= 0) {
            starts += open
            ends += total
            open = -1
            sawEnd = false
        }

        val keepFrom = if (open >= 0) (open ushr 3).toInt() else maxOf(0, bytes.size - MAGIC_BYTES_NEEDED)
        val shift = keepFrom * 8L
        carry = bytes.copyOfRange(keepFrom, bytes.size)
        carryBitsSeen = maxOf(0L, (lastIndex + 1 - 1) * 8L - shift)
        if (open >= 0) open -= shift
        return BlockRanges(bytes, starts.toLongArray(), ends.toLongArray(), sawEnd)
    }

    private fun magicAt(bytes: ByteArray, index: Int, shift: Int): Long {
        var window = 0L
        for (offset in 0 until MAGIC_BYTES_NEEDED) {
            window = (window shl 8) or (bytes[index + offset].toLong() and 0xFF)
        }
        return (window ushr (MAGIC_WINDOW_BITS - MAGIC_BITS - shift)) and MAGIC_MASK
    }

    private companion object {
        const val MAGIC_BYTES_NEEDED = 7
        const val MAGIC_WINDOW_BITS = MAGIC_BYTES_NEEDED * 8

        /**
         * For each byte value, the bit offsets at which a magic number would put that value in the byte after
         * its first. Most bytes are in no such place, so most of the file is passed over a byte at a time.
         */
        val SHIFTS =
            ByteArray(256).also { table ->
                for (magic in listOf(BLOCK_MAGIC, END_MAGIC)) {
                    for (shift in 0 until 8) {
                        val value = ((magic ushr (32 + shift)) and 0xFF).toInt()
                        table[value] = (table[value].toInt() or (1 shl shift)).toByte()
                    }
                }
            }
    }
}

/**
 * Decodes one block's bit range.
 *
 * @return the decoded bytes, or `null` if the range does not hold a whole valid block, which either means the
 *   range ended early at a false match or is one, or the data is corrupt.
 */
internal fun Bzip2BlockDecoder.decodeRange(bytes: ByteArray, start: Long, end: Long): ByteArray? =
    try {
        val bits = MsbBits(bytes, start, ((end + 7) ushr 3).toInt())
        val _ = bits.long48()
        decode(bits)
        output.copyOf(outputLength)
    } catch (_: DumpFormatException) {
        null
    }
