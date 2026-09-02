package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.model.file.FileInfo
import com.fenakhay.kwikibot.model.file.FileRepository
import com.fenakhay.kwikibot.model.file.GlobalUsage
import com.fenakhay.kwikibot.model.file.UploadOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow

/**
 * Files: what is known about them, where they are used, and how to add one.
 *
 * Uploading is the one operation in this library that does not go through the transport. It needs a multipart
 * body carrying bytes, and the transport deliberately speaks only in string parameters — so uploads use the
 * HTTP client directly, and pay for that by handling their own token retry.
 */
public interface FileService {

    /**
     * The upload history of files, newest version first.
     *
     * @param refs the files to ask about.
     * @param versions how many versions of each to fetch. One is the current file.
     */
    public suspend fun info(
        refs: Collection<PageRef>,
        versions: Int = 1,
    ): Map<PageRef, List<FileInfo>>

    /** The current version of one file, or `null` if there is no such file. */
    public suspend fun latest(ref: PageRef): FileInfo?

    /** Pages that use a file. */
    public fun usage(file: PageRef, limit: Int? = null): Flow<PageRef>

    /**
     * Where a file on this wiki is used on other wikis.
     *
     * Needs the GlobalUsage extension, which is what makes deleting a file on Commons a cross-wiki decision
     * rather than a local one.
     */
    public fun globalUsage(file: PageRef, limit: Int? = null): Flow<GlobalUsage>

    /** Files with the same content hash, which is how a duplicate is found before uploading. */
    public suspend fun duplicatesOf(sha1: String, limit: Int = DUPLICATE_LIMIT): List<PageRef>

    /**
     * Files that have been deleted, in title order. Needs the `deletedhistory` right.
     *
     * The titles only. The API reports more about each - size, hash, who deleted it - but this account cannot
     * see any of it, and a decoder written against documentation rather than a response is a decoder nobody
     * has checked.
     */
    public fun deletedFiles(
        from: String? = null,
        to: String? = null,
        prefix: String? = null,
        limit: Int? = null,
    ): Flow<PageRef>

    /**
     * Where this wiki's files come from.
     *
     * Worth reading before trying to change a file: one held on a shared repository cannot be edited or
     * deleted through the wiki that displays it, and the failure that follows says nothing useful about why.
     */
    public suspend fun repositories(): List<FileRepository>

    /**
     * Downloads a file to [destination], returning where it went.
     *
     * Streamed rather than held in memory: a media file is not a page, and a video on Commons is larger than
     * a bot has any business buffering.
     *
     * @param ref the file to fetch.
     * @param destination a file to write, or a directory to write into under the file's own name.
     */
    public suspend fun download(ref: PageRef, destination: Path): Path

    /**
     * Uploads a file.
     *
     * Large files are sent in chunks, because a wiki will refuse a single request over its upload limit and a
     * connection that drops halfway through a hundred megabytes has wasted all of it. The chunking is not
     * optional to configure away — it is what makes the upload resumable in the first place.
     *
     * @param file the bytes to send.
     * @param to the title to upload under, whose namespace prefix is dropped.
     * @param comment the upload summary.
     * @param text the description page to create, or `null` to leave it to the wiki.
     * @param ignoreWarnings acknowledge every warning in advance. Off by default: most warnings say something
     *   the caller wanted to know, such as that this file is already here.
     * @param chunkSize how much goes in one request.
     */
    public suspend fun upload(
        file: Path,
        to: PageRef,
        comment: String = "",
        text: String? = null,
        ignoreWarnings: Boolean = false,
        chunkSize: Int = DEFAULT_CHUNK,
    ): UploadOutcome

    /**
     * Publishes a stashed upload, acknowledging the warnings it produced.
     *
     * The bytes are already on the server, so this costs no second transfer — which is the whole point of the
     * file key in [UploadOutcome.Warned].
     */
    public suspend fun publishStashed(
        fileKey: String,
        to: PageRef,
        comment: String = "",
        text: String? = null,
    ): UploadOutcome

    /** The sizes that decide how an upload is split. */
    public companion object {
        /**
         * How much of a file goes in one request.
         *
         * Wikimedia's limit is higher, but a smaller chunk means less to resend when a chunk fails, and the
         * number of requests is not the cost that matters here.
         */
        public const val DEFAULT_CHUNK: Int = 5 * 1024 * 1024

        /** How many duplicates to report. More than a handful is a sign of a different problem. */
        public const val DUPLICATE_LIMIT: Int = 10
    }
}
