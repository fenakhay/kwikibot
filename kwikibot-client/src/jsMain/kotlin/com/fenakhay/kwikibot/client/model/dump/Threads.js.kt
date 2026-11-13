@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.fenakhay.kwikibot.client.model.dump

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
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

/**
 * A file read a chunk at a time on Node. kotlinx-io's own source reads the whole file into memory at its
 * first read, and refuses one over 2 GiB, where a dump's size begins.
 */
internal actual fun openDumpFile(path: Path): RawSource {
    val fs = nodeModule("fs") ?: return SystemFileSystem.source(path)
    return NodeFileSource(fs, path.toString())
}

private const val FILE_CHUNK = 1 shl 20

private class NodeFileSource(private val fs: dynamic, private val path: String) : RawSource {
    private val chunk = ByteArray(FILE_CHUNK)
    private var closed = false
    private val descriptor: dynamic =
        try {
            fs.openSync(path, "r")
        } catch (e: Throwable) {
            if (e.asDynamic().code == "ENOENT") throw FileNotFoundException("no such file: $path")
            throw IOException("failed to open $path", e)
        }

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "the source is closed" }
        if (byteCount == 0L) return 0
        val wanted = minOf(byteCount, FILE_CHUNK.toLong()).toInt()
        val read =
            try {
                fs.readSync(descriptor, chunk, 0, wanted, null) as Int
            } catch (e: Throwable) {
                throw IOException("failed to read $path", e)
            }
        if (read == 0) return -1
        sink.write(chunk, 0, read)
        return read.toLong()
    }

    override fun close() {
        if (closed) return
        closed = true
        fs.closeSync(descriptor)
    }
}

/**
 * This module's own file, for a worker to load, or `null` when a worker cannot be given it: a worker runs all
 * of the file it is given, and once this code is bundled that file is the whole of the bot.
 */
private val ownFile: String? =
    ownModuleFile(
        js("typeof __filename === 'undefined' ? null : __filename"),
        js("typeof module === 'undefined' ? null : module"),
    )

private const val OWN_FILE_NAME = "kwikibot-root-kwikibot-client.js"

/**
 * [file] when it is this module's file and nothing more, going by Node's own [module] object, which only a
 * file Node loaded by itself has, and by the name the package gives it. A bundler replaces `module` with its
 * own, and one that concatenates files does not keep the name. Otherwise `null`.
 */
internal fun ownModuleFile(file: dynamic, module: dynamic): String? {
    if (jsTypeOf(file) != "string" || module == null || module.filename != file) return null
    val path = file.unsafeCast<String>()
    return path.takeIf { it.substringAfterLast('/').substringAfterLast('\\') == OWN_FILE_NAME }
}

/**
 * Gives kotlinx-io, which finds Node's modules by evaluating `require`, something to find them with where
 * this has been bundled as an ES module and there is none. It answers only for Node's built-in modules.
 */
private fun requireForBundledModule() {
    val builtins: dynamic = js("typeof process === 'undefined' ? null : process")
    val missing = js("eval('typeof require')") == "undefined"
    if (!missing || builtins == null || builtins.getBuiltinModule == undefined) return
    val require = { name: String ->
        builtins.getBuiltinModule(name) ?: throw IllegalArgumentException("cannot find module '$name'")
    }
    val descriptor: dynamic = js("({ enumerable: false, configurable: true, writable: true })")
    descriptor.value = require
    js("Object").defineProperty(js("globalThis"), "require", descriptor)
}

/**
 * On a worker thread, answers each block it is sent. On the main thread, sees that modules can be found.
 *
 * Runs as this module loads, which is the one thing a worker does on its own when given this file.
 */
@Suppress("DEPRECATION", "unused")
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val workerLoop: Unit = run {
    requireForBundledModule()
    val threads = nodeModule("worker_threads")
    if (threads != null && threads.isMainThread == false && threads.workerData?.kwikibotDump == true) {
        val decoder = Bzip2BlockDecoder()
        val port = threads.parentPort
        port.on("message") { message: dynamic ->
            val bytes = message.bytes.unsafeCast<ByteArray>()
            val decoded =
                decoder.decodeRange(
                    bytes,
                    (message.start as Double).toLong(),
                    (message.end as Double).toLong(),
                    MAX_AHEAD_OUTPUT,
                )
            val answer: dynamic = js("({})")
            answer.id = message.id
            if (decoded != null) {
                answer.output = decoded.output
                answer.endBit = decoded.endBit.toDouble()
                answer.crc = decoded.crc
                val buffer = decoded.output.unsafeCast<Int8Array>().buffer
                port.postMessage(answer, arrayOf(buffer))
            } else {
                port.postMessage(answer)
            }
        }
    }
}

/**
 * Decodes blocks on Node's worker threads, sending each its block's bytes and taking the decoded ones back
 * without copying either. Where there are no worker threads, as in a browser, or this is not in a file of its
 * own to give them, as in a bundle, there is nothing to offer, and blocks are decoded on the main thread.
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

/**
 * A worker holds Node open only while it has a block: one with nothing to do is no reason to stay, so a read
 * nobody finishes does not keep the process alive, while a read somebody is waiting for always has a block
 * out or work queued on the main thread.
 */
private class NodeBlockDecoders(threads: dynamic, file: String, workers: Int) : BlockDecoders {
    private val outstanding = HashMap<dynamic, Int>()
    private val idle = Channel<dynamic>(workers)
    private val waiting = HashMap<Int, CancellableContinuation<DecodedRange?>>()
    private val dead = HashSet<dynamic>()
    private val all = ArrayList<dynamic>()
    private val owners = HashMap<Int, dynamic>()
    private var nextId = 0

    init {
        val options: dynamic = js("({})")
        val data: dynamic = js("({})")
        data.kwikibotDump = true
        options.workerData = data
        repeat(workers) {
            val worker = newWorker(threads, file, options)
            worker.unref()
            worker.on("message") { message: dynamic ->
                settled(worker)
                val id = message.id as Int
                owners.remove(id)
                val output: ByteArray? = message.output?.unsafeCast<ByteArray>()
                val decoded = output?.let {
                    DecodedRange(it, (message.endBit as Double).toLong(), message.crc as Int)
                }
                waiting.remove(id)?.resumeWith(Result.success(decoded))
            }
            worker.on("error") { _: dynamic -> abandon(worker) }
            all.add(worker)
            val _ = idle.trySend(worker)
        }
    }

    /**
     * A worker that fails answers nothing more. What it held is decoded on the main thread instead, which
     * gives the same answer or the true failure.
     */
    private fun abandon(worker: dynamic) {
        dead.add(worker)
        outstanding.remove(worker)
        worker.unref()
        val lost = owners.entries.filter { it.value === worker }.map { it.key }
        lost.forEach { id ->
            owners.remove(id)
            waiting.remove(id)?.resumeWith(Result.success(null))
        }
    }

    private fun settled(worker: dynamic) {
        val left = (outstanding[worker] ?: 1) - 1
        outstanding[worker] = left
        if (left == 0) worker.unref()
    }

    override suspend fun decode(bytes: ByteArray, start: Long, end: Long): DecodedRange? {
        val first = (start ushr 3).toInt()
        val last = minOf(((end + 7) ushr 3).toInt(), bytes.size)
        val slice = bytes.copyOfRange(first, last)
        val worker = idle.receive()
        try {
            if (worker in dead) return null
            val answer: DecodedRange? = suspendCancellableCoroutine { continuation ->
                val id = nextId++
                waiting[id] = continuation
                owners[id] = worker
                continuation.invokeOnCancellation {
                    waiting.remove(id)
                    owners.remove(id)
                }
                val message: dynamic = js("({})")
                message.id = id
                message.bytes = slice
                message.start = (start - first * 8L).toDouble()
                message.end = (end - first * 8L).toDouble()
                outstanding[worker] = (outstanding[worker] ?: 0) + 1
                worker.ref()
                worker.postMessage(message, arrayOf(slice.unsafeCast<Int8Array>().buffer))
            }
            return answer?.let { DecodedRange(it.output, it.endBit + first * 8L, it.crc) }
        } finally {
            val _ = idle.trySend(worker)
        }
    }

    override fun close() {
        all.forEach { worker -> worker.terminate() }
    }
}
