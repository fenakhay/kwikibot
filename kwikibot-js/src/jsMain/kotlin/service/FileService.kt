import com.fenakhay.kwikibot.bindings.client.duplicatesOfWithDefaults
import com.fenakhay.kwikibot.bindings.client.fileInfoWithDefaults
import com.fenakhay.kwikibot.bindings.client.fileRef
import com.fenakhay.kwikibot.bindings.client.titleOf
import com.fenakhay.kwikibot.bindings.client.uploadWithDefaults
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.client.service.FileService as ClientFileService
import com.fenakhay.kwikibot.model.file.FileInfo as ClientFileInfo
import com.fenakhay.kwikibot.model.file.FileRepository as ClientFileRepository
import com.fenakhay.kwikibot.model.file.GlobalUsage as ClientGlobalUsage
import com.fenakhay.kwikibot.model.file.UploadOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise
import kotlinx.io.files.Path

/** The options of `deletedFiles`. */
@JsExport
public external interface DeletedFilesOptions {
    /** The name to start at, inclusive. */
    public val from: String?

    /** The name to stop at, inclusive. */
    public val to: String?

    /** Only names beginning with this. */
    public val prefix: String?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `upload`. */
@JsExport
public external interface UploadOptions {
    /** The upload summary. */
    public val comment: String?

    /** The description page to create. Left to the wiki unless given. */
    public val text: String?

    /** Whether to acknowledge every warning in advance, `false` unless given. */
    public val ignoreWarnings: Boolean?

    /** How many bytes go in one request, `defaultChunk` unless given. */
    public val chunkSize: Int?
}

/**
 * Files, reached as `wiki.files`. A file's title needs no `File:` prefix.
 *
 * `download` and `upload` read and write the filesystem, so they work in Node only, and in a browser reject
 * saying so.
 */
@JsExport
public class FileService
internal constructor(private val wiki: ClientWiki, private val scope: CoroutineScope) {

    /** How many bytes of a file go in one request unless told otherwise. */
    public val defaultChunk: Int
        get() = ClientFileService.DEFAULT_CHUNK

    /** How many duplicates `duplicatesOf` reports unless told otherwise. */
    public val duplicateLimit: Int
        get() = ClientFileService.DUPLICATE_LIMIT

    /**
     * The upload history of each of [files] the wiki has, newest version first, [versions] of each: one, the
     * current file, unless given.
     */
    public fun info(files: Array<String>, versions: Int? = null): Promise<Array<FileVersions>> =
        scope.promise {
            wiki
                .fileInfoWithDefaults(files.asList(), versions)
                .map { (ref, found) -> FileVersions(wiki.titleOf(ref), found) }
                .toTypedArray()
        }

    /** The current version of [file], or `null` when there is no such file. */
    public fun latest(file: String): Promise<FileInfo?> = scope.promise {
        wiki.files.latest(file(file))?.let(::FileInfo)
    }

    /** The titles of the pages that use [file], the first [limit] when given. */
    public fun usage(file: String, limit: Int? = null): AsyncIterable<String> = listing {
        wiki.files.usage(file(file), limit)
    }
        .toAsyncIterable(scope, wiki::titleOf)

    /** Where [file] is used on other wikis, the first [limit] when given. Needs GlobalUsage. */
    public fun globalUsage(file: String, limit: Int? = null): AsyncIterable<GlobalUsage> = listing {
        wiki.files.globalUsage(file(file), limit)
    }
        .toAsyncIterable(scope, ::GlobalUsage)

    /**
     * The titles of the files with the content hash [sha1], at most [limit], `duplicateLimit` unless given.
     */
    public fun duplicatesOf(sha1: String, limit: Int? = null): Promise<Array<String>> = scope.promise {
        wiki.duplicatesOfWithDefaults(sha1, limit).map(wiki::titleOf).toTypedArray()
    }

    /** The titles of the files that have been deleted, in title order. Needs the `deletedhistory` right. */
    public fun deletedFiles(options: DeletedFilesOptions? = null): AsyncIterable<String> = listing {
        wiki.files.deletedFiles(options?.from, options?.to, options?.prefix, options?.limit)
    }
        .toAsyncIterable(scope, wiki::titleOf)

    /** Where this wiki's files come from. */
    public fun repositories(): Promise<Array<FileRepository>> = scope.promise {
        wiki.files.repositories().map(::FileRepository).toTypedArray()
    }

    /**
     * Downloads [file] to [destination], a file to write or a directory to write into under the file's own
     * name, answering with where it went. Streamed, not held in memory. Node only.
     */
    public fun download(file: String, destination: String): Promise<String> = scope.promise {
        requireFileSystem("download")
        wiki.files.download(file(file), Path(destination)).toString()
    }

    /**
     * Uploads the file at [path] under the title [to], in chunks when it is large, and says how the wiki
     * answered: warnings and refusals are results. Node only.
     */
    public fun upload(path: String, to: String, options: UploadOptions? = null): Promise<UploadResult> =
        scope.promise {
            requireFileSystem("upload")
            val outcome =
                wiki.uploadWithDefaults(
                    Path(path),
                    to,
                    options?.comment,
                    options?.text,
                    options?.ignoreWarnings,
                    options?.chunkSize,
                )
            UploadResult(outcome, wiki)
        }

    /**
     * Publishes a stashed upload under the title [to], acknowledging the warnings it produced, from the
     * `fileKey` of a `"warned"` result. Costs no second transfer.
     */
    public fun publishStashed(
        fileKey: String,
        to: String,
        comment: String = "",
        text: String? = null,
    ): Promise<UploadResult> = scope.promise {
        UploadResult(wiki.files.publishStashed(fileKey, file(to), comment, text), wiki)
    }

    private fun file(title: String): PageRef = wiki.fileRef(title)
}

/** The upload history of one file. */
@JsExport
public class FileVersions
internal constructor(
    /** The file, prefix included. */
    public val title: String,
    private val found: List<ClientFileInfo>,
) {
    /** Each version, newest first. */
    public val versions: Array<FileInfo>
        get() = found.map(::FileInfo).toTypedArray()
}

/** One version of a file. */
@JsExport
public class FileInfo internal constructor(private val info: ClientFileInfo) {

    /** Where the file itself can be fetched, `""` on a version hidden from this account. */
    public val url: String
        get() = info.url

    /** The wiki page describing it, or `null`. */
    public val descriptionUrl: String?
        get() = info.descriptionUrl

    /** Its size in bytes. */
    public val size: Double
        get() = info.size.toDouble()

    /** Its width in pixels, `0` for one with no dimensions, such as audio. */
    public val width: Int
        get() = info.width

    /** Its height in pixels, `0` for one with no dimensions. */
    public val height: Int
        get() = info.height

    /** Whether it has pixel dimensions. */
    public val hasDimensions: Boolean
        get() = info.hasDimensions

    /** The MIME type, such as `"image/jpeg"`, or `null`. */
    public val mimeType: String?
        get() = info.mimeType

    /** How MediaWiki classifies it, such as `"BITMAP"` or `"AUDIO"`, or `null`. */
    public val mediaType: String?
        get() = info.mediaType

    /** The content hash, or `null`. */
    public val sha1: String?
        get() = info.sha1

    /** When this version was uploaded, or `null`. */
    public val timestamp: Date?
        get() = info.timestamp?.let(::dateOf)

    /** Who uploaded it, or `null`. */
    public val user: String?
        get() = info.user

    /** The upload summary, or `null`. */
    public val comment: String?
        get() = info.comment
}

/** Where a file on a shared repository is used, on another wiki. */
@JsExport
public class GlobalUsage internal constructor(private val usage: ClientGlobalUsage) {

    /** The database name of the wiki using it. */
    public val wiki: String
        get() = usage.wiki

    /** The title of the page using it, on that wiki. */
    public val title: String
        get() = usage.title

    /** A link to that page, or `null` when the wiki gave none. */
    public val url: String?
        get() = usage.url
}

/** Where a wiki's files come from. */
@JsExport
public class FileRepository internal constructor(private val repository: ClientFileRepository) {

    /** The internal name, such as `"local"` or `"shared"`. */
    public val name: String
        get() = repository.name

    /** The name meant for a reader, such as `"Wikimedia Commons"`. */
    public val displayName: String
        get() = repository.displayName

    /** Whether the files live on this wiki. */
    public val isLocal: Boolean
        get() = repository.isLocal

    /** Where a file's own page lives, or `null`. */
    public val url: String?
        get() = repository.url

    /** Where the files themselves are served from, or `null`. */
    public val rootUrl: String?
        get() = repository.rootUrl

    /** Whether this session may upload here. */
    public val canUpload: Boolean
        get() = repository.canUpload
}

/** What became of an upload. */
@JsExport
public class UploadResult
internal constructor(private val outcome: UploadOutcome, private val wiki: ClientWiki) {

    /**
     * `"uploaded"`, `"warned"`, when the wiki holds the bytes but wants its warnings acknowledged, or
     * `"refused"`.
     */
    public val kind: UploadKind
        get() =
            when (outcome) {
                is UploadOutcome.Uploaded -> "uploaded"
                is UploadOutcome.Warned -> "warned"
                is UploadOutcome.Refused -> "refused"
            }.unsafeCast<UploadKind>()

    /** On `"uploaded"`, the title the file now has. */
    public val title: String?
        get() = (outcome as? UploadOutcome.Uploaded)?.let { wiki.namespaces.format(it.title) }

    /** On `"uploaded"`, what the wiki knows about the stored file, when it said. */
    public val info: FileInfo?
        get() = (outcome as? UploadOutcome.Uploaded)?.info?.let(::FileInfo)

    /** On `"warned"`, the warnings by kind, such as `"duplicate"` or `"exists"`. */
    public val warnings: Record<String, String>?
        get() = (outcome as? UploadOutcome.Warned)?.warnings?.toRecord { it }

    /** On `"warned"`, what `publishStashed` takes, when the wiki gave one. */
    public val fileKey: String?
        get() = (outcome as? UploadOutcome.Warned)?.fileKey

    /** On `"warned"`, whether the only complaint is that an identical file is already here. */
    public val isDuplicateOnly: Boolean?
        get() = (outcome as? UploadOutcome.Warned)?.isDuplicateOnly

    /** On `"refused"`, the wiki's code for the refusal. */
    public val code: String?
        get() = (outcome as? UploadOutcome.Refused)?.code

    /** On `"refused"`, its text for a person. */
    public val detail: String?
        get() = (outcome as? UploadOutcome.Refused)?.detail
}
