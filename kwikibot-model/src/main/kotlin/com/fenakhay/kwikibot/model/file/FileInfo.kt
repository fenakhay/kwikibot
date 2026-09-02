package com.fenakhay.kwikibot.model.file

import com.fenakhay.kwikibot.model.title.Title
import kotlin.time.Instant

/**
 * One version of a file, as `prop=imageinfo` reports it.
 *
 * A file page has a history like any other page, and each entry here is one upload rather than one edit of
 * the description. The newest is first, which is the order the API returns.
 */
public data class FileInfo(
    /** Where the file itself can be fetched. */
    val url: String,
    /** The wiki page describing it. */
    val descriptionUrl: String? = null,
    /** The file size in bytes. */
    val size: Long = 0,
    /** Width in pixels, zero for a file with no dimensions such as audio. */
    val width: Int = 0,
    /** Height in pixels, zero for a file with no dimensions. */
    val height: Int = 0,
    /** The MIME type, `image/jpeg` and the like. */
    val mimeType: String? = null,
    /** `BITMAP`, `DRAWING`, `AUDIO`, `VIDEO`, `OFFICE`, as MediaWiki classifies it. */
    val mediaType: String? = null,
    /** The content hash, which is how a duplicate is recognised. */
    val sha1: String? = null,
    /** When this version was uploaded. */
    val timestamp: Instant? = null,
    /** Who uploaded it. */
    val user: String? = null,
    /** The upload summary. */
    val comment: String? = null,
) {
    /** Whether this version has pixel dimensions, which audio and documents do not. */
    val hasDimensions: Boolean
        get() = width > 0 && height > 0
}

/** Where a file on a shared repository is used, on a wiki that is not the one holding it. */
public data class GlobalUsage(
    /** The database name of the wiki using it. */
    val wiki: String,
    /** The title of the page using the file, on that wiki. */
    val title: String,
    /** A link to that page, when the wiki supplied one. */
    val url: String? = null,
)

/**
 * What became of an upload.
 *
 * Warnings are their own case rather than an error, because most of them are advisory — a duplicate name, a
 * file already on Commons, a badly formatted name — and the caller has to decide. The file is already on the
 * server when they arrive: publishing it is one more call with the [Warned.fileKey], not another upload.
 */
public sealed interface UploadOutcome {

    /** The file is on the wiki. */
    public data class Uploaded(
        /** The title the file now has, which is not always the one that was asked for. */
        val title: Title.Local,
        /** What the wiki knows about the stored file, absent when it did not say. */
        val info: FileInfo?,
    ) : UploadOutcome

    /**
     * The wiki accepted the bytes but wants the warnings acknowledged.
     *
     * @param warnings by kind: `duplicate`, `exists`, `badfilename`, `was-deleted`.
     * @param fileKey names the stashed upload, so publishing it costs no second transfer.
     */
    public data class Warned(
        val warnings: Map<String, String>,
        val fileKey: String?,
    ) : UploadOutcome {
        /** Whether the only complaint is that an identical file is already here. */
        val isDuplicateOnly: Boolean
            get() = warnings.keys.isNotEmpty() && warnings.keys.all { it in DUPLICATE_KINDS }

        private companion object {
            val DUPLICATE_KINDS = setOf("duplicate", "exists-normalized", "duplicate-archive")
        }
    }

    /** The wiki refused the upload, and said why. */
    public data class Refused(
        /** The wiki's code for the refusal, which is the part worth branching on. */
        val code: String,
        /** Its text for a person. */
        val detail: String,
    ) : UploadOutcome
}
