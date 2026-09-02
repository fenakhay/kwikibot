package com.fenakhay.kwikibot.bot

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class PromptTest {

    private val choices =
        listOf(
            Choice('s', "skip", "skip"),
            Choice('e', "edit", "edit"),
            Choice('q', "quit", "quit"),
        )

    @Test
    fun `a terminal prompt reads the answer typed`() = runTest {
        val prompt = TerminalPrompt(output = {}, input = { "y" })

        prompt.confirm("Save?") shouldBe true
    }

    @Test
    fun `an empty line takes the default`() = runTest {
        TerminalPrompt(output = {}, input = { "" }).confirm("Save?", default = true) shouldBe true
        TerminalPrompt(output = {}, input = { "" }).confirm("Save?", default = false) shouldBe false
    }

    @Test
    fun `input that has ended takes the default instead of looping`() = runTest {
        val prompt = TerminalPrompt(output = {}, input = { null })

        prompt.confirm("Save?", default = true) shouldBe true
        prompt.text("Summary?", default = "none") shouldBe "none"
        prompt.choose("What?", choices) shouldBe "skip"
    }

    @Test
    fun `a choice is selected by its key, whatever the case`() = runTest {
        TerminalPrompt(output = {}, input = { "E" }).choose("What?", choices) shouldBe "edit"
    }

    @Test
    fun `an empty choice list is a programming error, not a prompt`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            TerminalPrompt(output = {}, input = { "" }).choose("What?", emptyList())
        }
    }

    @Test
    fun `an unattended run answers rather than waits, and counts what it answered`() = runTest {
        val prompt = NonInteractive(answer = false)

        prompt.confirm("Save?") shouldBe false
        prompt.confirm("Really?") shouldBe false
        prompt.asked shouldBe 2
        prompt.isInteractive shouldBe false
    }

    @Test
    fun `an unattended choice takes the first option, which is the safe one`() = runTest {
        NonInteractive().choose("What?", choices) shouldBe "skip"
    }

    @Test
    fun `scripted answers are used in order and then fall back`() = runTest {
        val prompt = ScriptedPrompt(listOf("y", "n"), fallback = NonInteractive(answer = true))

        prompt.confirm("one") shouldBe true
        prompt.confirm("two") shouldBe false
        prompt.confirm("three") shouldBe true
    }

    @Test
    fun `a scripted prompt answers from its script, in order`() = runTest {
        val prompt = ScriptedPrompt(listOf("y", "e", "some text"))

        prompt.confirm("Save?") shouldBe true
        prompt.choose("What now?", choices) shouldBe "edit"
        prompt.text("Summary?") shouldBe "some text"
    }

    @Test
    fun `a scripted prompt is never interactive, whatever it was handed`() {
        ScriptedPrompt(listOf("y")).isInteractive shouldBe false
    }

    @Test
    fun `a script that runs out falls back rather than blocking`() = runTest {
        val prompt = ScriptedPrompt(emptyList(), fallback = NonInteractive(answer = false))

        prompt.confirm("Save?", default = true) shouldBe false
        prompt.choose("What now?", choices) shouldBe "skip"
        prompt.text("Summary?", default = "none") shouldBe "none"
    }

    @Test
    fun `an answer matching no choice takes the first, which is the safe one`() = runTest {
        ScriptedPrompt(listOf("z")).choose("What now?", choices) shouldBe "skip"
    }

    @Test
    fun `a choice is matched by its key, whatever case it was typed in`() = runTest {
        ScriptedPrompt(listOf("Q")).choose("What now?", choices) shouldBe "quit"
    }
}
