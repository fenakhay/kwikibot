package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.internal.wire.Registry
import com.fenakhay.kwikibot.client.raiseBadToken
import com.fenakhay.kwikibot.client.service.FileService
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.file.FileInfo
import com.fenakhay.kwikibot.model.file.FileRepository
import com.fenakhay.kwikibot.model.file.GlobalUsage
import com.fenakhay.kwikibot.model.file.UploadOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.identifyAs
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.net.transport.TransportListener
import com.fenakhay.kwikibot.net.transport.transportFailure
import com.fenakhay.kwikibot.net.transport.withRequestPermit
import com.fenakhay.kwikibot.protocol.ApiFailure
import com.fenakhay.kwikibot.protocol.decode.Continuation
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.protocol.decode.array
import com.fenakhay.kwikibot.protocol.decode.flag
import com.fenakhay.kwikibot.protocol.decode.int
import com.fenakhay.kwikibot.protocol.decode.long
import com.fenakhay.kwikibot.protocol.decode.obj
import com.fenakhay.kwikibot.protocol.decode.objects
import com.fenakhay.kwikibot.protocol.decode.string
import com.fenakhay.kwikibot.protocol.decode.time
import com.fenakhay.kwikibot.protocol.throwOnError
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readTo
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val log = KotlinLogging.logger("com.fenakhay.kwikibot.client.internal.ApiFileService")

internal class ApiFileService(
    private val transport: MediaWikiTransport,
    private val tokens: TokenStore,
    private val decoder: PageDecoder,
    private val namespaces: NamespaceMap,
    private val http: HttpClient,
    private val endpoint: ApiEndpoint,
    private val userAgent: UserAgent,
    private val batchSize: Int = DEFAULT_BATCH,
    private val onWarning: TransportListener = TransportListener.NONE,
    // Uploads skip the session transport, so they assert the account themselves.
    private val assertion: String? = null,
    private val throttle: Throttle = Throttle.unpaced(),
    private val retry: RetryPolicy = RetryPolicy.NONE,
    private val maxlag: Int? = null,
) : FileService {

    private val continuation = Continuation(transport)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun info(
        refs: Collection<PageRef>,
        versions: Int,
    ): Map<PageRef, List<FileInfo>> {
        if (refs.isEmpty()) return emptyMap()

        val found = mutableMapOf<Title.Local, List<FileInfo>>()
        for (batch in refs.map { it.title }.distinct().chunked(batchSize)) {
            continuation
                .pages(
                    ApiRequest.of(
                        "query",
                        "prop" to "imageinfo",
                        "iiprop" to Registry.IMAGE_INFO.joined,
                        "iilimit" to versions.toString(),
                        "titles" to batch.joinToString("|") { namespaces.format(it) },
                    )
                )
                .toList()
                .forEach { page ->
                    val ref = decoder.refOf(page) ?: return@forEach
                    val versionsOf = page.array("imageinfo").objects().map { decodeInfo(it) }
                    if (versionsOf.isNotEmpty()) found[ref.title] = versionsOf
                }
        }

        return refs.mapNotNull { ref -> found[ref.title]?.let { ref to it } }.toMap()
    }

    override suspend fun latest(ref: PageRef): FileInfo? = info(listOf(ref))[ref]?.firstOrNull()

    override fun usage(file: PageRef, limit: Int?): Flow<PageRef> =
        continuation
            .list(
                ApiRequest.of(
                    "query",
                    "list" to "imageusage",
                    "iutitle" to namespaces.format(file.title),
                    "iulimit" to limitParam(limit),
                ),
                "imageusage",
            )
            .mapNotNull { decoder.refOf(it) }
            .limitedTo(limit)

    override fun globalUsage(file: PageRef, limit: Int?): Flow<GlobalUsage> {
        val batches =
            continuation.pages(
                ApiRequest.of(
                    "query",
                    "prop" to "globalusage",
                    "titles" to namespaces.format(file.title),
                    "gulimit" to limitParam(limit),
                )
            )

        val uses = flow {
            batches.collect { page ->
                page.array("globalusage").objects().forEach { entry ->
                    emit(
                        GlobalUsage(
                            wiki = entry.string("wiki").orEmpty(),
                            title = entry.string("title").orEmpty(),
                            url = entry.string("url"),
                        )
                    )
                }
            }
        }

        return uses.limitedTo(limit)
    }

    override fun deletedFiles(
        from: String?,
        to: String?,
        prefix: String?,
        limit: Int?,
    ): Flow<PageRef> =
        continuation
            .list(
                ApiRequest.of(
                    "query",
                    "list" to "filearchive",
                    "fafrom" to from,
                    "fato" to to,
                    "faprefix" to prefix,
                    "falimit" to limitParam(limit),
                ),
                "filearchive",
            )
            .mapNotNull { decoder.refOf(it) }
            .limitedTo(limit)

    override suspend fun repositories(): List<FileRepository> {
        val response =
            transport
                .call(
                    ApiRequest.of(
                        "query",
                        "meta" to "filerepoinfo",
                        "friprop" to "name|displayname|local|rootUrl|url|canUpload",
                    )
                )
                .throwOnError()

        // Answered under "repos", not under the module's own name as every list module is.
        return response.obj("query")?.array("repos").objects().map { entry ->
            FileRepository(
                name = entry.string("name").orEmpty(),
                displayName = entry.string("displayname").orEmpty(),
                isLocal = entry.flag("local"),
                url = entry.string("url")?.takeIf { it.isNotEmpty() },
                rootUrl = entry.string("rootUrl")?.takeIf { it.isNotEmpty() },
                canUpload = entry.flag("canUpload"),
            )
        }
    }

    override suspend fun duplicatesOf(sha1: String, limit: Int): List<PageRef> =
        continuation
            .list(
                ApiRequest.of(
                    "query",
                    "list" to "allimages",
                    "aisha1" to sha1,
                    "ailimit" to limit.toString(),
                ),
                "allimages",
            )
            .map { entry ->
                // allimages reports a bare file name, without the namespace the rest of the API uses.
                val name = entry.string("title") ?: entry.string("name").orEmpty()
                decoder.refOf(name, Namespace.FILE.id)
            }
            .toList()
            .filterNotNull()
            .take(limit)

    override suspend fun download(ref: PageRef, destination: Path): Path {
        val info = latest(ref) ?: throw WikiError.Page.Missing(ref.title)
        require(info.url.isNotEmpty()) { "the wiki reported no URL for ${ref.title}" }

        val target =
            if (SystemFileSystem.metadataOrNull(destination)?.isDirectory == true) {
                Path(destination, ref.title.text)
            } else {
                destination
            }
        target.parent?.let { SystemFileSystem.createDirectories(it) }

        // Straight to disk through a channel: buffering a video to write it out again is a way
        // to exhaust the heap on a large upload.
        http.withRequestPermit {
            http
                .prepareGet(info.url) { identifyAs(userAgent) }
                .execute { response ->
                    SystemFileSystem.sink(target).buffered().use { sink ->
                        response.bodyAsChannel().readTo(sink)
                    }
                }
        }

        return target
    }

    override suspend fun upload(
        file: Path,
        to: PageRef,
        comment: String,
        text: String?,
        ignoreWarnings: Boolean,
        chunkSize: Int,
    ): UploadOutcome {
        require(chunkSize > 0) { "chunk size must be positive" }

        val fileName = to.title.text
        val size =
            SystemFileSystem.metadataOrNull(file)?.size ?: throw FileNotFoundException("no file at $file")

        val response =
            if (size <= chunkSize) {
                singleRequestUpload(file, fileName, comment, text, ignoreWarnings)
            } else {
                val key = uploadInChunks(file, fileName, size, chunkSize)
                publish(key, fileName, comment, text, ignoreWarnings)
            }

        return response.toOutcome(to)
    }

    override suspend fun publishStashed(
        fileKey: String,
        to: PageRef,
        comment: String,
        text: String?,
    ): UploadOutcome = publish(fileKey, to.title.text, comment, text, ignoreWarnings = true).toOutcome(to)

    // ------------------------------------------------------------------------- uploading

    private suspend fun singleRequestUpload(
        file: Path,
        fileName: String,
        comment: String,
        text: String?,
        ignoreWarnings: Boolean,
    ): JsonObject =
        multipart(
            fields =
                buildMap {
                    put("action", "upload")
                    put("filename", fileName)
                    put("comment", comment)
                    text?.let { put("text", it) }
                    if (ignoreWarnings) put("ignorewarnings", "1")
                },
            part = Part("file", file.name, withContext(ioDispatcher) { file.readBytes() }),
            step = UploadStep.PUBLISH,
        )

    /**
     * Sends a file in chunks, returning the key that names it on the server.
     *
     * Each chunk after the first carries the key the wiki gave for the one before, which is what ties them
     * together and what makes an interrupted upload resumable rather than wasted. The key of the last chunk
     * names the whole file.
     *
     * A chunk that comes up short, because the file was cut short after its size was read, is the last one
     * sent: a chunk after it would claim an offset past the bytes the wiki has, and the wiki refuses the
     * incomplete file when it is published.
     *
     * A chunk may be sent twice, when the wiki stored it and its answer was lost on the way back. The wiki
     * then refuses the copy, saying which offset it expected, and an offset past this chunk means this chunk
     * is already there, under the same key. A second stash from a first chunk sent twice is simply never
     * used. The last chunk is different: the wiki moves the file to a new key and forgets the old one, and
     * with the answer lost nothing names the new one. So that upload starts again, once.
     */
    private suspend fun uploadInChunks(
        file: Path,
        fileName: String,
        size: Long,
        chunkSize: Int,
        mayRestart: Boolean = true,
    ): String {
        // One chunk in memory at a time.
        var offset = 0L
        var fileKey: String? = null

        SystemFileSystem.source(file).use { source ->
            while (true) {
                val end = minOf(offset + chunkSize, size)
                val chunk = withContext(ioDispatcher) { source.readAtMost((end - offset).toInt()) }

                val response = runCatching {
                    multipart(
                        fields =
                            buildMap {
                                put("action", "upload")
                                put("filename", fileName)
                                put("filesize", size.toString())
                                put("offset", offset.toString())
                                put("stash", "1")
                                put("ignorewarnings", "1")
                                fileKey?.let { put("filekey", it) }
                            },
                        part = Part("chunk", file.name, chunk),
                        step = UploadStep.STASH,
                    )
                }
                    .getOrElse {
                        if (it !is CancellationException) logStopped(fileName, offset, fileKey)
                        throw it
                    }

                val isLast = end == size || chunk.size < end - offset
                val known = fileKey
                val keyWasLost = known != null && response.saysKeyIsGone()
                if (isLast && mayRestart && keyWasLost) {
                    log.warn { "The last chunk of $fileName arrived but its answer did not; starting again" }
                    return uploadInChunks(file, fileName, size, chunkSize, mayRestart = false)
                }
                val key =
                    if (known != null && !isLast && response.saysChunkArrived(end)) known
                    else response.fileKeyOrFail()
                if (isLast) return key
                fileKey = key
                offset = end
            }
        }
    }

    private fun JsonObject.firstError(): JsonObject? = array("errors").objects().firstOrNull() ?: obj("error")

    /** Whether the wiki refused a chunk because the key it was sent under no longer names anything. */
    private fun JsonObject.saysKeyIsGone(): Boolean = firstError()?.string("code") == STASH_KEY_GONE

    /** Whether the wiki refused a chunk because it already holds the file up to [end]. */
    private fun JsonObject.saysChunkArrived(end: Long): Boolean {
        val error = firstError() ?: return false
        if (error.string("code") != STASH_FAILED) return false
        return (error.obj("data")?.long("offset") ?: error.long("offset")) == end
    }

    private fun logStopped(fileName: String, offset: Long, fileKey: String?) {
        log.warn {
            "Upload of $fileName stopped at offset $offset, with stash key ${fileKey ?: "(none yet)"}"
        }
    }

    /**
     * The next [length] bytes of this source, or as many as are left.
     *
     * Read from the file itself rather than through a buffer, which would read ahead of the chunk, and in a
     * loop until the chunk is full or the file ends, since a read may return fewer bytes than were asked for.
     */
    private fun RawSource.readAtMost(length: Int): ByteArray {
        val chunk = Buffer()
        var remaining = length.toLong()
        while (remaining > 0) {
            val read = readAtMostTo(chunk, remaining)
            if (read < 0) break
            remaining -= read
        }
        return chunk.readByteArray()
    }

    private fun Path.readBytes(): ByteArray =
        SystemFileSystem.source(this).buffered().use { it.readByteArray() }

    /**
     * The key the wiki gave for a stashed chunk.
     *
     * A chunk with no key back is the end of the upload: there is nothing to tie the next chunk to, so
     * continuing would silently start a second file.
     */
    private fun JsonObject.fileKeyOrFail(): String {
        ApiFailure.from(this)?.let { throw it.toWikiError() }

        return obj("upload")?.string("filekey")
            ?: throw WikiError.Api("nofilekey", "the wiki returned no file key", "upload")
    }

    private suspend fun publish(
        fileKey: String,
        fileName: String,
        comment: String,
        text: String?,
        ignoreWarnings: Boolean,
    ): JsonObject =
        multipart(
            fields =
                buildMap {
                    put("action", "upload")
                    put("filename", fileName)
                    put("filekey", fileKey)
                    put("comment", comment)
                    text?.let { put("text", it) }
                    if (ignoreWarnings) put("ignorewarnings", "1")
                },
            part = null,
            step = UploadStep.PUBLISH,
        )

    /**
     * One multipart request, with a fresh token attached, until it is answered or the retries are spent.
     *
     * Paced and retried here rather than in the transport because uploads do not go through it: a long
     * chunked upload is exactly the case where a session expires or a connection drops mid-way. The body is
     * made again for every attempt, since a multipart body can be read only once.
     */
    private suspend fun multipart(fields: Map<String, String>, part: Part?, step: UploadStep): JsonObject =
        tokens.withFreshToken { token ->
            deliver(fields, part, step, token)
        }

    private suspend fun deliver(
        fields: Map<String, String>,
        part: Part?,
        step: UploadStep,
        token: String,
    ): JsonObject {
        var attempt = 0
        while (true) {
            throttle.acquire(step.kind)
            val answer = attempt(fields, part, token)
            if (answer is UploadAnswer.Answered && !answer.retryable) return answered(fields, answer.body)

            val mayRepeat = step.repeatsAfterLoss || answer is UploadAnswer.Answered
            if (attempt >= retry.maxRetries || !mayRepeat) return gaveUp(fields, answer)

            attempt++
            val wait = retry.delayFor(attempt, answer.retryAfter)
            log.info { "upload: ${answer.reason}; retry $attempt of ${retry.maxRetries} in $wait" }
            answer.retryAfter?.let { throttle.penalize(it) }
            delay(wait)
        }
    }

    /** The answer to hand back, shown to the listener as the transport would, since uploads skip it. */
    private suspend fun answered(fields: Map<String, String>, response: JsonObject): JsonObject {
        onWarning.onResponse(ApiRequest(fields, RequestKind.WRITE), response)
        response.raiseBadToken()
        return response
    }

    /**
     * What the last attempt comes to once there are to be no more: the failure it met, or the wiki's answer.
     */
    private suspend fun gaveUp(fields: Map<String, String>, answer: UploadAnswer): JsonObject =
        when (answer) {
            is UploadAnswer.Answered -> answered(fields, answer.body)
            is UploadAnswer.NotJson ->
                throw WikiError.Api("badresponse", "upload returned a non-JSON body", "upload")
            is UploadAnswer.Lost -> throw answer.failure
        }

    private suspend fun attempt(fields: Map<String, String>, part: Part?, token: String): UploadAnswer {
        val body = multipartBody(fields, part, token)
        val (response, text) =
            runCatching {
                http.withRequestPermit {
                    val response =
                        http.post(endpoint.apiUrl) {
                            identifyAs(userAgent)
                            setBody(body)
                        }
                    response to response.bodyAsText()
                }
            }
                .getOrElse {
                    if (transportFailure(endpoint.apiUrl, it) == null) throw it
                    return UploadAnswer.Lost(it)
                }

        val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.seconds
        val parsed = runCatching {
            json.parseToJsonElement(text).jsonObject
        }
            .getOrElse {
                return UploadAnswer.NotJson(response.status.value, retryAfter)
            }
        val code = parsed.firstError()?.string("code")
        return UploadAnswer.Answered(parsed, code, code in RETRYABLE_CODES, retryAfter)
    }

    private fun multipartBody(fields: Map<String, String>, part: Part?, token: String) =
        MultiPartFormDataContent(
            formData {
                fields.forEach { (key, value) -> append(key, value) }
                KtorTransport.DEFAULT_PARAMS.forEach { (key, value) -> append(key, value) }
                if (maxlag != null) append("maxlag", maxlag.toString())
                if (assertion != null) append("assert", assertion)
                if (part != null) {
                    append(
                        part.name,
                        part.bytes,
                        Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"${part.fileName}\"")
                        },
                    )
                }
                // Last, as MediaWiki requires of anything following a token.
                append("token", token)
            }
        )

    /**
     * Which part of an upload a request is.
     *
     * @param kind how it is paced. A stashed chunk changes nothing anyone can see, so it goes at the pace of
     *   a read; at the pace of an edit, a long video would take hours.
     * @param repeatsAfterLoss whether it may be sent again when no answer came back. A chunk can: the wiki
     *   recognises a copy. A publish cannot, since the wiki may have applied it already.
     */
    private enum class UploadStep(val kind: RequestKind, val repeatsAfterLoss: Boolean) {
        STASH(RequestKind.READ, true),
        PUBLISH(RequestKind.WRITE, false),
    }

    /** What came of one attempt at a request. */
    private sealed interface UploadAnswer {
        val retryAfter: Duration?
        val reason: String

        /** The wiki answered in JSON. [retryable] if it says the moment is wrong and it applied nothing. */
        data class Answered(
            val body: JsonObject,
            val code: String?,
            val retryable: Boolean,
            override val retryAfter: Duration?,
        ) : UploadAnswer {
            override val reason: String
                get() = code.orEmpty()
        }

        /** Something answered, but not MediaWiki, and the request may or may not have arrived. */
        data class NotJson(val status: Int, override val retryAfter: Duration?) : UploadAnswer {
            override val reason: String
                get() = "a non-JSON answer with status $status"
        }

        /** No answer came, and the request may or may not have arrived. */
        data class Lost(val failure: Throwable) : UploadAnswer {
            override val retryAfter: Duration?
                get() = null

            override val reason: String
                get() = failure.message ?: "the connection failed"
        }
    }

    /**
     * The file in a multipart upload.
     *
     * @param name the form field it goes in: `file` for a whole file, `chunk` for part of one.
     * @param fileName the name it is sent under.
     * @param bytes what is sent.
     */
    private class Part(val name: String, val fileName: String, val bytes: ByteArray)

    private fun JsonObject.toOutcome(to: PageRef): UploadOutcome {
        ApiFailure.from(this)?.let {
            return UploadOutcome.Refused(it.code, it.text)
        }

        val upload =
            obj("upload") ?: return UploadOutcome.Refused("noupload", "no upload block in the response")

        return when (val result = upload.string("result")) {
            "Success" ->
                UploadOutcome.Uploaded(
                    title = to.title,
                    info = upload.obj("imageinfo")?.let { decodeInfo(it) },
                )

            "Warning" ->
                UploadOutcome.Warned(
                    warnings =
                        upload
                            .obj("warnings")
                            ?.mapValues { (_, value) -> value.toString().trim('"') }
                            .orEmpty(),
                    fileKey = upload.string("filekey"),
                )

            else ->
                UploadOutcome.Refused(
                    result.orEmpty().ifEmpty { "unknown" },
                    "the wiki did not report a successful upload",
                )
        }
    }

    // ---------------------------------------------------------------------------- decoding

    private fun decodeInfo(entry: JsonObject) =
        FileInfo(
            url = entry.string("url").orEmpty(),
            descriptionUrl = entry.string("descriptionurl"),
            size = entry.long("size") ?: 0L,
            width = entry.int("width") ?: 0,
            height = entry.int("height") ?: 0,
            mimeType = entry.string("mime"),
            mediaType = entry.string("mediatype"),
            sha1 = entry.string("sha1"),
            timestamp = entry.time("timestamp"),
            user = entry.string("user"),
            comment = entry.string("comment"),
        )

    private companion object {
        const val DEFAULT_BATCH = 50
        const val STASH_FAILED = "stashfailed"
        const val STASH_KEY_GONE = "uploadstash-file-not-found"

        /** Answers saying the moment is wrong, for which MediaWiki has applied nothing. */
        val RETRYABLE_CODES = setOf("maxlag", "ratelimited", "readonly")
    }
}
