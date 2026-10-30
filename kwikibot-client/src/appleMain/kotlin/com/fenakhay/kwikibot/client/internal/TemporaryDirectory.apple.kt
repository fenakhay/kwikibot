package com.fenakhay.kwikibot.client.internal

import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory

/** Foundation's `NSTemporaryDirectory()`. */
internal actual val temporaryDirectory: Path = SystemTemporaryDirectory
