package com.fenakhay.kwikibot.bot.run

/** A [SkipPage] with its stack trace, which this platform takes whenever a throwable is made. */
internal actual fun skipPage(reason: String): SkipPage = SkipPage(reason)
