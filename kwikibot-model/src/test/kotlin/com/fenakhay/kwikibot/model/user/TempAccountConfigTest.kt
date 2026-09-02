package com.fenakhay.kwikibot.model.user

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class TempAccountConfigTest {

    private val wikimedia = TempAccountConfig(enabled = true, matchPatterns = listOf("~2\$1"))

    @Test
    fun `a name fitting the wiki's pattern is a temporary account`() {
        wikimedia.matches("~2026-47315-11") shouldBe true
    }

    @Test
    fun `an ordinary account is not`() {
        wikimedia.matches("Fenakhay") shouldBe false
        wikimedia.matches("SemperBlottoBot") shouldBe false
    }

    @Test
    fun `the prefix alone is somebody's account name, not a temporary one`() {
        wikimedia.matches("~2") shouldBe false
    }

    @Test
    fun `an IP address is not a temporary account`() {
        wikimedia.matches("203.0.113.5") shouldBe false
    }

    @Test
    fun `a wiki with the feature off matches nothing`() {
        val off = TempAccountConfig(enabled = false, matchPatterns = listOf("~2\$1"))

        off.matches("~2026-47315-11") shouldBe false
        TempAccountConfig.DISABLED.matches("~2026-47315-11") shouldBe false
    }

    @Test
    fun `a pattern with a suffix is honoured at both ends`() {
        val bracketed = TempAccountConfig(enabled = true, matchPatterns = listOf("*\$1*"))

        bracketed.matches("*abc*") shouldBe true
        bracketed.matches("*abc") shouldBe false
    }

    @Test
    fun `a registered editor is neither anonymous nor temporary`() {
        UserInfo(name = "Fenakhay", id = 1).isRegistered shouldBe true
        UserInfo(name = "203.0.113.5", isAnonymous = true).isRegistered shouldBe false
        UserInfo(name = "~2026-1-1", id = 2, isTemporary = true).isRegistered shouldBe false
        UserInfo(name = "Nobody", isMissing = true).isRegistered shouldBe false
    }
}
