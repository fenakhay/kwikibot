package com.fenakhay.kwikibot.cli

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.stderr

/**
 * Has kotlin-logging's direct loggers write to standard error. Its defaults write to the unified log on macOS
 * and to standard output elsewhere, where the logs would mix with what a command prints.
 */
internal actual fun configureLogging() {
    KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
    KotlinLoggingConfiguration.direct.appender = StandardErrorAppender()
}

/** Formats each event and passes it to [write], leaving out what [LOGIN_MANAGER] logs below WARN. */
internal class StandardErrorAppender(private val write: (String) -> Unit = ::writeToStandardError) :
    Appender {
    override fun log(loggingEvent: KLoggingEvent) {
        if (loggingEvent.loggerName == LOGIN_MANAGER && loggingEvent.level < Level.WARN) return
        write(KotlinLoggingConfiguration.direct.formatter.formatMessage(loggingEvent))
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeToStandardError(line: String) {
    fputs("$line\n", stderr)
    fflush(stderr)
}
