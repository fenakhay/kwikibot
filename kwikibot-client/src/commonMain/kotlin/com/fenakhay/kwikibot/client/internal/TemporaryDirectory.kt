package com.fenakhay.kwikibot.client.internal

import kotlinx.io.files.Path

/**
 * The system's directory for temporary files, as an absolute path, which a file spool keeps its file in.
 *
 * kotlinx-io's `SystemTemporaryDirectory` is read from `TMPDIR` or `TMP` on Linux and Windows, and is the
 * empty path when neither is set, as on most Linux systems, so that files meant for it land in the working
 * directory.
 */
internal expect val temporaryDirectory: Path
