package com.fenakhay.kwikibot.client.model.dump

/**
 * A dump that cannot be read to its end.
 *
 * Failing is the safe answer here: a bot that treats a partial dump as the whole wiki concludes that every
 * page missing from it has been deleted.
 *
 * @param code what is wrong with it.
 * @param message what was found, and where.
 */
public class DumpFormatException(public val code: Code, message: String) : Exception(message) {

    /** What is wrong with a dump. */
    public enum class Code {
        /** It ended before it was finished, which is what an interrupted download looks like. */
        UNEXPECTED_EOF,

        /** Its compressed data does not match the checksum stored with it. */
        BAD_CHECKSUM,

        /** It is not what it claims to be, or uses something this reader refuses, such as a DTD. */
        BAD_DATA,
    }
}

internal fun truncated(what: String): Nothing =
    throw DumpFormatException(DumpFormatException.Code.UNEXPECTED_EOF, "the dump ends inside $what")

internal fun corrupt(what: String): Nothing =
    throw DumpFormatException(DumpFormatException.Code.BAD_DATA, what)

internal fun badChecksum(what: String): Nothing =
    throw DumpFormatException(DumpFormatException.Code.BAD_CHECKSUM, what)
