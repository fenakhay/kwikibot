package com.fenakhay.kwikibot.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.uname
import platform.posix.utsname

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformDescription(): String = memScoped {
    val system = alloc<utsname>()
    if (uname(system.ptr) == 0) "${system.sysname.toKString()} ${system.release.toKString()}" else "Linux"
}
