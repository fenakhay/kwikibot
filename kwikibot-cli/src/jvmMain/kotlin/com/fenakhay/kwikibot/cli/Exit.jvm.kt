package com.fenakhay.kwikibot.cli

import kotlin.system.exitProcess

/** A failure ends the JVM with its status; a success lets it end on its own, once its threads are done. */
internal actual fun exitWith(status: Int) {
    if (status != 0) exitProcess(status)
}
