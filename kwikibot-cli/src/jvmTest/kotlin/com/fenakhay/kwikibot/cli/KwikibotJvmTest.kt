package com.fenakhay.kwikibot.cli

import com.fenakhay.kwikibot.net.Kwikibot
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test

class KwikibotJvmTest {

    @Test
    fun `main runs the command line it is given`() {
        val printed = ByteArrayOutputStream()
        val standardOutput = System.out
        System.setOut(PrintStream(printed, true))
        try {
            main(arrayOf("version"))
        } finally {
            System.setOut(standardOutput)
        }

        printed.toString().lines() shouldBe Kwikibot.report().lines()
    }
}
