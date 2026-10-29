package com.fenakhay.kwikibot.bot.run

/** A [SkipPage] that leaves its stack trace out, as the JVM lets a throwable do by not filling it in. */
internal actual fun skipPage(reason: String): SkipPage = TracelessSkipPage(reason)

private class TracelessSkipPage(reason: String) : SkipPage(reason) {
    override fun fillInStackTrace(): Throwable = this
}
