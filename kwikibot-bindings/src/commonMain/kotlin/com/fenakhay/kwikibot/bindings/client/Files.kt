package com.fenakhay.kwikibot.bindings.client

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.service.FileService
import com.fenakhay.kwikibot.model.file.FileInfo
import com.fenakhay.kwikibot.model.file.UploadOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.Namespace
import kotlinx.io.files.Path

/** [title] as a file of this wiki, which needs no `File:` prefix. */
public fun Wiki.fileRef(title: String): PageRef = ref(title, Namespace.FILE)

/** The upload history of each of [files], [versions] of each: only the current one unless given. */
public suspend fun Wiki.fileInfoWithDefaults(
    files: Collection<String>,
    versions: Int? = null,
): Map<PageRef, List<FileInfo>> = this.files.info(files.map(::fileRef), versions ?: 1)

/** The files with the content hash [sha1], at most [limit], [FileService.DUPLICATE_LIMIT] unless given. */
public suspend fun Wiki.duplicatesOfWithDefaults(sha1: String, limit: Int? = null): List<PageRef> =
    files.duplicatesOf(sha1, limit ?: FileService.DUPLICATE_LIMIT)

/**
 * Uploads the file at [path] as [to], with no comment, heeding warnings and in chunks of
 * [FileService.DEFAULT_CHUNK] unless told.
 */
@Suppress("LongParameterList")
public suspend fun Wiki.uploadWithDefaults(
    path: Path,
    to: String,
    comment: String? = null,
    text: String? = null,
    ignoreWarnings: Boolean? = null,
    chunkSize: Int? = null,
): UploadOutcome =
    files.upload(
        path,
        fileRef(to),
        comment.orEmpty(),
        text,
        ignoreWarnings ?: false,
        chunkSize ?: FileService.DEFAULT_CHUNK,
    )
