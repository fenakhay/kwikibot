package com.fenakhay.kwikibot.cli

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix._exit
import platform.posix.fflush

/**
 * Ends the process with [status] without running the C library's exit handlers.
 *
 * Ktor's Curl engine closes its client on a thread of its own and does not wait for it, so a process that
 * ended through `exit` ran OpenSSL's exit handler while that thread was still freeing its connections, and
 * now and then aborted with `corrupted double-linked list` after the command had done its work. Standard
 * output and error are flushed first; Kotlin writes them unbuffered, and C code may not.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun exitWith(status: Int) {
    fflush(null)
    _exit(status)
}
