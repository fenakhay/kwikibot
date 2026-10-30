package com.fenakhay.kwikibot.client.internal

import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory

/** Node's `os.tmpdir()`. */
internal actual val temporaryDirectory: Path = SystemTemporaryDirectory
