package com.fenakhay.kwikibot.cli

import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class LoggingTest {

    private val written = mutableListOf<String>()
    private val appender = StandardErrorAppender { written += it }

    private fun log(level: Level, logger: String, message: String) =
        appender.log(KLoggingEvent(level, null, logger, message))

    @Test
    fun `the login manager is kept to its warnings and errors`() {
        log(Level.INFO, LOGIN_MANAGER, "logged in as TestBot (bot)")
        log(Level.WARN, LOGIN_MANAGER, "the login was throttled")
        log(Level.ERROR, LOGIN_MANAGER, "the login failed")

        written shouldBe
            listOf(
                "WARN: [$LOGIN_MANAGER] the login was throttled",
                "ERROR: [$LOGIN_MANAGER] the login failed",
            )
    }

    @Test
    fun `every other logger is written at the level it logs`() {
        log(Level.INFO, "com.fenakhay.kwikibot.client.WikiClient", "logged back in to de.wikipedia.org")

        written shouldBe
            listOf("INFO: [com.fenakhay.kwikibot.client.WikiClient] logged back in to de.wikipedia.org")
    }

    @Test
    fun `the command's loggers are the direct ones writing to standard error`() {
        configureLogging()

        KotlinLoggingConfiguration.loggerFactory shouldBe DirectLoggerFactory
        KotlinLoggingConfiguration.direct.appender.shouldBeInstanceOf<StandardErrorAppender>()
    }
}
