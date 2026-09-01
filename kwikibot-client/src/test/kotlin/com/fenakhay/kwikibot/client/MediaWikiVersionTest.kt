package com.fenakhay.kwikibot.client

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MediaWikiVersionTest {

    private fun version(raw: String) = MediaWikiVersion.parse(raw)

    @Test
    fun `numbers are compared as numbers, not as text`() {
        (version("1.10.0") > version("1.9.0")) shouldBe true
        (version("1.44.0") > version("1.9.9")) shouldBe true
    }

    @Test
    fun `a missing component counts as zero`() {
        version("1.44") shouldBe version("1.44.0")
        (version("1.44.1") > version("1.44")) shouldBe true
    }

    @Test
    fun `a prerelease is older than the release it leads to`() {
        (version("1.45.0-wmf.6") < version("1.45.0")) shouldBe true
        (version("1.43.0-rc.1") < version("1.43.0")) shouldBe true
    }

    @Test
    fun `the generator prefix MediaWiki reports is ignored`() {
        version("MediaWiki 1.47.0") shouldBe version("1.47.0")
    }

    @Test
    fun `a version this library cannot read is version zero, not an exception`() {
        (version("unknown") < version("1.0.0")) shouldBe true
    }

    @Test
    fun `equal versions compare equal both ways`() {
        (version("1.44.0") >= version("1.44.0")) shouldBe true
        (version("1.44.0") <= version("1.44.0")) shouldBe true
        version("1.44.0").hashCode() shouldBe version("1.44.0").hashCode()
    }
}
