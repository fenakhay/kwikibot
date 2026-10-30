package com.fenakhay.kwikibot.client.internal

import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory

/** The JVM's `java.io.tmpdir`. */
internal actual val temporaryDirectory: Path = SystemTemporaryDirectory
