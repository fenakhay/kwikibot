package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.net.environmentVariable
import kotlinx.io.files.Path

/** `TMPDIR` when it is set, and `/tmp` otherwise, as POSIX has it. */
internal actual val temporaryDirectory: Path = Path(environmentVariable("TMPDIR")?.ifEmpty { null } ?: "/tmp")
