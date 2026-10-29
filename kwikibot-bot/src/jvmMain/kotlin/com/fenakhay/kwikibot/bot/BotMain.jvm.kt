@file:JvmName("BotMain")

package com.fenakhay.kwikibot.bot

import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/** Runs the bot on this thread, then exits the process with its status. */
public actual fun botMain(args: Array<String>, anonymous: Boolean, block: suspend BotStart.() -> Int) {
    exitProcess(runBlocking { exitStatus(args.toList(), anonymous, BotEnvironment.SYSTEM, block) })
}
