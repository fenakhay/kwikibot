@file:Suppress("MagicNumber")

package com.fenakhay.kwikibot.client.model.dump

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Int8Array

private val utf8: dynamic = js("new TextDecoder('utf-8', { ignoreBOM: true })")

@Suppress("UNUSED_PARAMETER", "UnusedParameter")
private fun subarray(bytes: ByteArray, start: Int, end: Int): dynamic = js("bytes.subarray(start, end)")

internal actual fun decodeUtf8(bytes: ByteArray, start: Int, end: Int): String =
    utf8.decode(subarray(bytes, start, end)) as String

/** A built-in Node module, or `null` outside Node. */
private fun nodeModule(name: String): dynamic {
    val process: dynamic = js("typeof process === 'undefined' ? null : process")
    return if (process == null || process.getBuiltinModule == undefined) null
    else process.getBuiltinModule(name)
}

/** This module's own file, for a worker to load. */
private val ownFile: String? = js("typeof __filename === 'undefined' ? null : __filename") as String?

/**
 * On a worker thread, answers each block it is sent. On the main thread, nothing.
 *
 * Runs as this module loads, which is the one thing a worker does on its own when given this file.
 */
@Suppress("DEPRECATION", "unused")
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val workerLoop: Unit = run {
    val threads = nodeModule("worker_threads")
    if (threads != null && threads.isMainThread == false && threads.workerData?.kwikibotDump == true) {
        val decoder = Bzip2BlockDecoder()
        val port = threads.parentPort
        port.on("message") { message: dynamic ->
            val bytes = message.bytes.unsafeCast<ByteArray>()
            val output =
                decoder.decodeRange(
                    bytes,
                    (message.start as Double).toLong(),
                    (message.end as Double).toLong(),
                )
            val answer: dynamic = js("({})")
            answer.id = message.id
            answer.output = output
            if (output != null) {
                val buffer = output.unsafeCast<Int8Array>().buffer
                port.postMessage(answer, arrayOf(buffer))
            } else {
                port.postMessage(answer)
            }
        }
    }
}

/**
 * Decodes blocks on Node's worker threads, sending each its block's bytes and taking the decoded ones back
 * without copying either. Where there are no worker threads, as in a browser, there is nothing to offer.
 */
internal actual fun platformBlockDecoders(workers: Int): BlockDecoders? {
    val threads = nodeModule("worker_threads") ?: return null
    val file = ownFile ?: return null
    if (threads.isMainThread != true) return null
    return NodeBlockDecoders(threads, file, workers)
}

@Suppress("UNUSED_PARAMETER", "UnusedParameter")
private fun newWorker(threads: dynamic, file: String, options: dynamic): dynamic =
    js("new threads.Worker(file, options)")

private class NodeBlockDecoders(threads: dynamic, file: String, workers: Int) : BlockDecoders {
    private val idle = Channel<dynamic>(workers)
    private val waiting = HashMap<Int, CancellableContinuation<ByteArray?>>()
    private val all = ArrayList<dynamic>()
    private var nextId = 0

    init {
        val options: dynamic = js("({})")
        val data: dynamic = js("({})")
        data.kwikibotDump = true
        options.workerData = data
        repeat(workers) {
            val worker = newWorker(threads, file, options)
            worker.on("message") { message: dynamic ->
                val id = message.id as Int
                val output: ByteArray? = message.output?.unsafeCast<ByteArray>()
                waiting.remove(id)?.resumeWith(Result.success(output))
            }
            all.add(worker)
            val _ = idle.trySend(worker)
        }
    }

    override suspend fun decode(bytes: ByteArray, start: Long, end: Long): ByteArray? {
        val first = (start ushr 3).toInt()
        val last = minOf(((end + 7) ushr 3).toInt(), bytes.size)
        val slice = bytes.copyOfRange(first, last)
        val worker = idle.receive()
        try {
            return suspendCancellableCoroutine { continuation ->
                val id = nextId++
                waiting[id] = continuation
                continuation.invokeOnCancellation { waiting.remove(id) }
                val message: dynamic = js("({})")
                message.id = id
                message.bytes = slice
                message.start = (start - first * 8L).toDouble()
                message.end = (end - first * 8L).toDouble()
                worker.postMessage(message, arrayOf(slice.unsafeCast<Int8Array>().buffer))
            }
        } finally {
            idle.send(worker)
        }
    }

    override fun close() {
        all.forEach { worker -> worker.terminate() }
        idle.close()
    }
}
