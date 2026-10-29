package com.fenakhay.kwikibot.bot

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Starts the bot and returns; Node exits with its status once it is done. */
public actual fun botMain(args: Array<String>, anonymous: Boolean, block: suspend BotStart.() -> Int) {
    MainScope().launch {
        val status = exitStatus(args.toList(), anonymous, BotEnvironment.SYSTEM, block)
        val process: dynamic = js("typeof process === 'undefined' ? null : process")
        if (process != null) process.exit(status)
    }
}
