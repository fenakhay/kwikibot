package com.fenakhay.kwikibot.net

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

class KwikibotTest {

    @Test
    fun `the report names the library, the runtime and the platform`() {
        val report = Kwikibot.report()

        report shouldContain Kwikibot.NAME
        report shouldContain Kwikibot.version
        report shouldContain "runtime: "
        report shouldContain "platform: "
    }

    @Test
    fun `the report says nothing about the machine it ran on`() {
        val report = Kwikibot.report()

        for (property in listOf("user.name", "user.home", "user.dir", "java.io.tmpdir")) {
            val value = System.getProperty(property) ?: continue
            report shouldNotContain value
        }
    }

    @Test
    fun `every line is one fact, so the report pastes readably`() {
        val lines = Kwikibot.report().trim().lines()

        (lines.size >= 3) shouldBe true
        lines.forEach { it.isNotBlank() shouldBe true }
    }

    @Test
    fun `running without a jar reports the fallback rather than nothing`() {
        Kwikibot.version.isNotBlank() shouldBe true
        Kwikibot.version shouldNotContain "null"
    }

    @Test
    fun `the url points at the project, for a user agent with nothing better to offer`() {
        Kwikibot.URL shouldContain "github.com"
        Kwikibot.NAME shouldBe "kwikibot"
    }
}
