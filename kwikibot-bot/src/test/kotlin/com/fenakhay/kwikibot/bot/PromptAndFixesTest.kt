package com.fenakhay.kwikibot.bot

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PromptTest {

    private val choices = listOf(
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

class FixesTest {

    @Test
    fun `runs of spaces inside a line are collapsed`() {
        Fixes.EXTRA_SPACES.apply("a  b   c") shouldBe "a b c"
    }

    @Test
    fun `indentation is not a run of spaces between words`() {
        val before = "  indented\na  b"

        Fixes.EXTRA_SPACES.apply(before) shouldBe "  indented\na b"
    }

    @Test
    fun `a year range gets an en dash and an ordinary hyphen does not`() {
        Fixes.DATE_RANGES.apply("1990-1995 and well-known") shouldBe "1990–1995 and well-known"
    }

    @Test
    fun `three dots become an ellipsis and four do not`() {
        Fixes.ELLIPSIS.apply("wait... and then.... hmm") shouldBe "wait… and then.... hmm"
    }

    @Test
    fun `a fix does not reach inside a template or a link target`() {
        val before = "{{col|en|a  b}} and [[a  b]] but a  b"

        Fixes.EXTRA_SPACES.apply(before) shouldBe "{{col|en|a  b}} and [[a  b]] but a b"
    }

    @Test
    fun `fixes are looked up by name and an unknown one lists the others`() {
        Fixes["ellipsis"] shouldBe Fixes.ELLIPSIS

        val failure = assertFailsWith<IllegalArgumentException> {
            Fixes.named(listOf("elipsis"))
        }
        failure.message.orEmpty().contains("ellipsis") shouldBe true
    }

    @Test
    fun `a bot can register its own fix`() {
        Fixes.register(
            Fix(
                name = "test-only",
                description = "for the test",
                replacements = listOf(Replacement(Regex("teh"), "the")),
            ),
        )

        Fixes["test-only"]?.apply("teh word") shouldBe "the word"
    }

    @Test
    fun `a fix reports whether it would change anything`() {
        Fixes.ELLIPSIS.wouldChange("nothing here") shouldBe false
        Fixes.ELLIPSIS.wouldChange("wait...") shouldBe true
    }
}
