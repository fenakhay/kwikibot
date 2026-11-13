package com.fenakhay.kwikibot.client.model.dump

import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.asInputStream
import kotlinx.io.asSource
import kotlinx.io.buffered

private const val BUFFER = 1 shl 16

/** The JDK's zlib reads gzip about three times as fast as code on the JVM can, and every member of a file. */
internal actual fun gunzipping(source: RawSource): RawSource = JdkGunzip(source)

/**
 * A source as a stream that says truthfully whether more is to come.
 *
 * `GZIPInputStream` looks for another member only if `available()` is above zero, and a [Source] reports only
 * what it happens to hold, which between two of its segments is nothing, so the members after that would be
 * lost.
 */
private class TruthfulStream(private val source: Source) : FilterInputStream(source.asInputStream()) {
    override fun available(): Int = if (source.request(1)) 1 else 0
}

private class JdkGunzip(source: RawSource) : RawSource {
    private val buffered = source.buffered()
    private var delegate: RawSource? = null
    private var stream: InputStream? = null

    private fun open(): RawSource =
        delegate
            ?: try {
                GZIPInputStream(TruthfulStream(buffered), BUFFER).let { opened ->
                    stream = opened
                    opened.asSource().also { delegate = it }
                }
            } catch (e: ZipException) {
                corrupt(e.message ?: "this is not a gzip stream")
            } catch (_: EOFException) {
                truncated("a gzip header")
            }

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long =
        try {
            open().readAtMostTo(sink, byteCount)
        } catch (_: EOFException) {
            truncated("a gzip stream")
        } catch (e: IOException) {
            when {
                e.message?.contains("Corrupt GZIP trailer") == true ->
                    badChecksum("a gzip stream's trailer is wrong")
                e is ZipException -> corrupt(e.message ?: "a gzip stream is damaged")
                else -> throw e
            }
        }

    /** Closes the file whether or not its header ever read as gzip. */
    override fun close() {
        try {
            stream?.close()
        } finally {
            buffered.close()
        }
    }
}
