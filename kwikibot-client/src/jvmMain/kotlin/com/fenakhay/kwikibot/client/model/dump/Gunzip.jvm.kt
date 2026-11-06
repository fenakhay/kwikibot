package com.fenakhay.kwikibot.client.model.dump

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.asInputStream
import kotlinx.io.asSource
import kotlinx.io.buffered

private const val BUFFER = 1 shl 16

/** The JDK's zlib reads gzip about three times as fast as code on the JVM can, and every member of a file. */
internal actual fun gunzipping(source: RawSource): RawSource = JdkGunzip(source)

private class JdkGunzip(private val source: RawSource) : RawSource {
    private val stream: InputStream by lazy {
        try {
            GZIPInputStream(source.buffered().asInputStream(), BUFFER)
        } catch (e: ZipException) {
            corrupt(e.message ?: "this is not a gzip stream")
        } catch (_: EOFException) {
            truncated("a gzip header")
        }
    }
    private val delegate: RawSource by lazy { stream.asSource() }

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long =
        try {
            delegate.readAtMostTo(sink, byteCount)
        } catch (_: EOFException) {
            truncated("a gzip stream")
        } catch (e: ZipException) {
            corrupt(e.message ?: "a gzip stream is damaged")
        } catch (e: IOException) {
            if (e.message?.contains("Corrupt GZIP trailer") == true)
                badChecksum("a gzip stream's trailer is wrong")
            throw e
        }

    override fun close() {
        delegate.close()
    }
}
