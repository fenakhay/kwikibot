package com.fenakhay.kwikibot.cli

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/** The status `kwikibot` ends with, which it gives back rather than letting Clikt end the process. */
class ExitStatusTest {

    private val environment = CliEnvironment(searchPath = { emptyList() })

    @Test
    fun `a command that succeeds gives 0 and one that fails gives its error's status`() = runTest {
        statusOf(kwikibot(environment), arrayOf("version")) shouldBe 0
        statusOf(kwikibot(environment), arrayOf("no-such-command")) shouldBe 1
        statusOf(kwikibot(environment), arrayOf("whoami")) shouldBe 1
    }
}
