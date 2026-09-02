package com.fenakhay.kwikibot.model.file

/**
 * Where a wiki's files come from.
 *
 * A wiki usually has two: its own, and a shared one. A file bot has to know which, since a file held on
 * Commons cannot be edited or deleted through the wiki displaying it.
 */
public data class FileRepository(
    /** The internal name, `local` or `shared`. */
    val name: String,
    /** The name meant for a reader, `Wikimedia Commons`. */
    val displayName: String,
    /** Whether the files live on this wiki. */
    val isLocal: Boolean,
    /** Where a file's own page lives, when the wiki said. */
    val url: String? = null,
    /** Where the files themselves are served from. */
    val rootUrl: String? = null,
    /** Whether this session may upload here. */
    val canUpload: Boolean = false,
)
