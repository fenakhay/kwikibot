package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.raiseBadToken
import com.fenakhay.kwikibot.client.service.FileService
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.file.FileInfo
import com.fenakhay.kwikibot.model.file.FileRepository
import com.fenakhay.kwikibot.model.file.GlobalUsage
import com.fenakhay.kwikibot.model.file.UploadOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.ApiFailure
import com.fenakhay.kwikibot.protocol.decode.Continuation
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.protocol.throwOnError
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal class ApiFileService(
    private val transport: MediaWikiTransport,
    private val tokens: TokenStore,
    private val decoder: PageDecoder,
    private val namespaces: NamespaceMap,
    private val http: HttpClient,
    private val endpoint: ApiEndpoint,
    private val userAgent: UserAgent,
    private val batchSize: Int = DEFAULT_BATCH,
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
                        "iiprop" to IMAGE_PROPS,
                        "iilimit" to versions.toString(),
                        "titles" to batch.joinToString("|") { namespaces.format(it) },
                    )
                )
                .toList()
                .forEach { page ->
                    val ref = decoder.refOf(page) ?: return@forEach
                    val versionsOf = page["imageinfo"]?.jsonArray?.map { decodeInfo(it.jsonObject) }.orEmpty()
                    if (versionsOf.isNotEmpty()) found[ref.title] = versionsOf
                }
        }

        return refs.mapNotNull { ref -> found[ref.title]?.let { ref to it } }.toMap()
    }

    override suspend fun latest(ref: PageRef): FileInfo? = info(listOf(ref))[ref]?.firstOrNull()

    override fun usage(file: PageRef, limit: Int?): Flow<PageRef> {
        val pages =
            continuation
                .list(
                    ApiRequest.of(
                        "query",
                        "list" to "imageusage",
                        "iutitle" to namespaces.format(file.title),
                        "iulimit" to apiLimit(limit),
                    ),
                    "imageusage",
                )
                .mapNotNull { decoder.refOf(it) }

        return if (limit == null) pages else pages.take(limit)
    }

    override fun globalUsage(file: PageRef, limit: Int?): Flow<GlobalUsage> {
        val batches =
            continuation.pages(
                ApiRequest.of(
                    "query",
                    "prop" to "globalusage",
                    "titles" to namespaces.format(file.title),
                    "gulimit" to apiLimit(limit),
                )
            )

        val uses = flow {
            batches.collect { page ->
                page["globalusage"]?.jsonArray?.forEach { entry ->
                    val fields = entry.jsonObject
                    emit(
                        GlobalUsage(
                            wiki = fields["wiki"]?.jsonPrimitive?.content.orEmpty(),
                            title = fields["title"]?.jsonPrimitive?.content.orEmpty(),
                            url = fields["url"]?.jsonPrimitive?.content,
                        )
                    )
                }
            }
        }

        return if (limit == null) uses else uses.take(limit)
    }

    override fun deletedFiles(
        from: String?,
        to: String?,
        prefix: String?,
        limit: Int?,
    ): Flow<PageRef> {
        val found =
            continuation
                .list(
                    ApiRequest.of(
                        "query",
                        "list" to "filearchive",
                        "fafrom" to from,
                        "fato" to to,
                        "faprefix" to prefix,
                        "falimit" to if (limit != null && limit < MAX_ARCHIVE) limit.toString() else "max",
                    ),
                    "filearchive",
                )
                .mapNotNull { decoder.refOf(it) }

        return if (limit == null) found else found.take(limit)
    }

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
        val repos = response["query"]?.jsonObject?.get("repos") as? JsonArray ?: return emptyList()

        return repos
            .map { it.jsonObject }
            .map { entry ->
                FileRepository(
                    name = entry.repoText("name"),
                    displayName = entry.repoText("displayname"),
                    isLocal = entry["local"]?.jsonPrimitive?.booleanOrNull == true,
                    url = entry.repoText("url").takeIf { it.isNotEmpty() },
                    rootUrl = entry.repoText("rootUrl").takeIf { it.isNotEmpty() },
                    canUpload = entry["canUpload"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
    }

    private fun JsonObject.repoText(key: String): String =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

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
                val name =
                    entry["title"]?.jsonPrimitive?.content ?: entry["name"]?.jsonPrimitive?.content.orEmpty()
                decoder.refOf(name, Namespace.FILE.id)
            }
            .toList()
            .filterNotNull()
            .take(limit)

    override suspend fun download(ref: PageRef, destination: Path): Path {
        val info = latest(ref) ?: throw WikiError.Page.Missing(ref.title)
        require(info.url.isNotEmpty()) { "the wiki reported no URL for ${ref.title}" }

        val target =
            if (destination.isDirectory()) {
                destination.resolve(ref.title.text)
            } else {
                destination
            }
        target.createParentDirectories()

        // Straight to disk through a channel: buffering a video to write it out again is a way
        // to exhaust the heap on a large upload.
        http
            .prepareGet(info.url) {
                header(HttpHeaders.UserAgent, userAgent.headerValue)
            }
            .execute { response ->
                response.bodyAsChannel().copyAndClose(target.toFile().writeChannel())
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
        val size = file.fileSize()

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
            partName = "file",
            partFileName = file.name,
            bytes = file.readBytes(),
        )

    /**
     * Sends a file in chunks, returning the key that names it on the server.
     *
     * Each chunk after the first carries the key the wiki gave for the one before, which is what ties them
     * together and what makes an interrupted upload resumable rather than wasted.
     */
    private suspend fun uploadInChunks(
        file: Path,
        fileName: String,
        size: Long,
        chunkSize: Int,
    ): String {
        val bytes = file.readBytes()
        var offset = 0L
        var fileKey: String? = null

        while (offset < size) {
            val end = minOf(offset + chunkSize, size)
            val chunk = bytes.copyOfRange(offset.toInt(), end.toInt())

            val response =
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
                    partName = "chunk",
                    partFileName = file.name,
                    bytes = chunk,
                )

            fileKey = response.fileKeyOrFail()
            offset = end
        }

        return checkNotNull(fileKey) { "an empty file has no chunks" }
    }

    /**
     * The key the wiki gave for a stashed chunk.
     *
     * A chunk with no key back is the end of the upload: there is nothing to tie the next chunk to, so
     * continuing would silently start a second file.
     */
    private fun JsonObject.fileKeyOrFail(): String {
        ApiFailure.from(this)?.let { throw it.toWikiError() }

        return this["upload"]?.jsonObject?.get("filekey")?.jsonPrimitive?.content
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
            partName = null,
            partFileName = null,
            bytes = null,
        )

    /**
     * One multipart POST, with a fresh token attached.
     *
     * The token retry is here rather than in the transport because uploads do not go through it: a long
     * chunked upload is exactly the case where a session expires mid-way.
     */
    private suspend fun multipart(
        fields: Map<String, String>,
        partName: String?,
        partFileName: String?,
        bytes: ByteArray?,
    ): JsonObject = tokens.withFreshToken { token ->
        val body =
            MultiPartFormDataContent(
                formData {
                    fields.forEach { (key, value) -> append(key, value) }
                    append("format", "json")
                    append("formatversion", "2")
                    append("errorformat", "plaintext")
                    if (partName != null && bytes != null) {
                        append(
                            partName,
                            bytes,
                            Headers.build {
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "filename=\"${partFileName.orEmpty()}\"",
                                )
                            },
                        )
                    }
                    // Last, as MediaWiki requires of anything following a token.
                    append("token", token)
                }
            )

        val text =
            http
                .post(endpoint.apiUrl) {
                    header(HttpHeaders.UserAgent, userAgent.headerValue)
                    setBody(body)
                }
                .bodyAsText()

        val response = runCatching {
            json.parseToJsonElement(text).jsonObject
        }
            .getOrElse {
                throw WikiError.Api("badresponse", "upload returned a non-JSON body", "upload")
            }

        response.raiseBadToken()
        response
    }

    private fun JsonObject.toOutcome(to: PageRef): UploadOutcome {
        ApiFailure.from(this)?.let {
            return UploadOutcome.Refused(it.code, it.text)
        }

        val upload =
            this["upload"]?.jsonObject
                ?: return UploadOutcome.Refused("noupload", "no upload block in the response")

        return when (upload["result"]?.jsonPrimitive?.content) {
            "Success" ->
                UploadOutcome.Uploaded(
                    title = to.title,
                    info = upload["imageinfo"]?.jsonObject?.let { decodeInfo(it) },
                )

            "Warning" ->
                UploadOutcome.Warned(
                    warnings =
                        upload["warnings"]
                            ?.jsonObject
                            ?.mapValues { (_, value) -> value.toString().trim('"') }
                            .orEmpty(),
                    fileKey = upload["filekey"]?.jsonPrimitive?.content,
                )

            else ->
                UploadOutcome.Refused(
                    upload["result"]?.jsonPrimitive?.content.orEmpty().ifEmpty { "unknown" },
                    "the wiki did not report a successful upload",
                )
        }
    }

    // ---------------------------------------------------------------------------- decoding

    private fun decodeInfo(entry: JsonObject) =
        FileInfo(
            url = entry["url"]?.jsonPrimitive?.content.orEmpty(),
            descriptionUrl = entry["descriptionurl"]?.jsonPrimitive?.content,
            size = entry["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            width = entry["width"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            height = entry["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            mimeType = entry["mime"]?.jsonPrimitive?.content,
            mediaType = entry["mediatype"]?.jsonPrimitive?.content,
            sha1 = entry["sha1"]?.jsonPrimitive?.content,
            timestamp = entry["timestamp"]?.jsonPrimitive?.content?.let { MwTimestamp.parseOrNull(it) },
            user = entry["user"]?.jsonPrimitive?.content,
            comment = entry["comment"]?.jsonPrimitive?.content,
        )

    private fun apiLimit(limit: Int?): String =
        if (limit != null && limit < MAX_BATCH) limit.toString() else "max"

    private companion object {
        const val MAX_ARCHIVE = 500
        const val DEFAULT_BATCH = 50
        const val MAX_BATCH = 500
        const val IMAGE_PROPS = "timestamp|user|comment|url|size|dimensions|sha1|mime|mediatype"
    }
}
