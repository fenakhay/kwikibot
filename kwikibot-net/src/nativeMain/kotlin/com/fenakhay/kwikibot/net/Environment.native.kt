package com.fenakhay.kwikibot.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** Read with the C library's `getenv`. */
@OptIn(ExperimentalForeignApi::class)
public actual fun environmentVariable(name: String): String? = getenv(name)?.toKString()
