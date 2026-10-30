package com.fenakhay.kwikibot.wikitext.internal

/**
 * The constructs the [Tokenizer] is in the middle of, innermost last: a few numbers each, all in one array.
 *
 * A frame is addressed by where its numbers start, which stays the same while it is on the stack. Each holds
 * what kind of construct it is, how far through it the tokenizer has got, the part of it being read (how, and
 * up to where), and up to five numbers of the construct's own, [A] to [E].
 */
internal class Frames {
    private var values = IntArray(INITIAL_DEPTH * SIZE)

    /** How many frames are on the stack. */
    var depth = 0
        private set

    /** Where the innermost frame's numbers start. */
    val top: Int
        get() = (depth - 1) * SIZE

    /**
     * Puts a frame of [kind] on top, at its first step and reading nothing yet, and returns where it starts.
     */
    fun push(kind: Int, a: Int = 0, b: Int = 0, c: Int = 0, d: Int = 0): Int {
        if ((depth + 1) * SIZE > values.size) values = values.copyOf(values.size * 2)
        val frame = depth * SIZE
        values[frame + KIND] = kind
        values[frame + STEP] = 0
        values[frame + MODE] = NONE
        values[frame + LIMIT] = 0
        values[frame + A] = a
        values[frame + B] = b
        values[frame + C] = c
        values[frame + D] = d
        values[frame + E] = 0
        depth++
        return frame
    }

    fun pop() {
        depth--
    }

    fun clear() {
        depth = 0
    }

    operator fun get(frame: Int, field: Int): Int = values[frame + field]

    operator fun set(frame: Int, field: Int, value: Int) {
        values[frame + field] = value
    }

    companion object {
        const val KIND = 0
        const val STEP = 1

        /** How the part being read is read: [NONE], [SEGMENT], [CONTENT] or [PREPROCESSED]. */
        const val MODE = 2

        /** Where the part being read ends. */
        const val LIMIT = 3
        const val A = 4
        const val B = 5
        const val C = 6
        const val D = 7
        const val E = 8
        private const val SIZE = 9
        private const val INITIAL_DEPTH = 8

        /** The kinds of construct a frame reads. */
        const val PAGE = 0
        const val TEMPLATE_FRAME = 1
        const val ARGUMENT_FRAME = 2
        const val WIKILINK_FRAME = 3
        const val BRACKETED_FRAME = 4
        const val FREE_FRAME = 5
        const val EXTENSION_FRAME = 6
        const val TAG_FRAME = 7
        const val STYLE_FRAME = 8
        const val HEADING_FRAME = 9

        /** A frame's first step, before anything of its construct is emitted. */
        const val START = 0

        /** A part has been read, and the next one, if any, comes after a separator. */
        const val PART_READ = 1

        /** A template parameter's name has been read, and its `=` and value come next. */
        const val KEY_READ = 2

        /** The last part has been read, and the construct closes. */
        const val DONE = 3

        const val ATTRIBUTE_NEXT = 4
        const val ATTRIBUTE_NAME_READ = 5
        const val ATTRIBUTE_READ = 6
        const val TAG_CONTENTS = 7
        const val EXTENSION_DOCUMENT = 8
        const val EXTENSION_BODY = 9
        const val EXTENSION_LINES = 10
        const val EXTENSION_LINE_READ = 11

        /** Nothing is being read: the frame goes on with its next step. */
        const val NONE = 0

        /** A segment of its own is being read: content, with HTML tags paired inside it alone. */
        const val SEGMENT = 1

        /** Content is being read inside the segment around it, as a tag's contents are. */
        const val CONTENT = 2

        /** Only the preprocessor's constructs are being read, as in a URL or an attribute. */
        const val PREPROCESSED = 3
    }
}
