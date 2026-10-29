package com.fenakhay.kwikibot.bot.fix

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DiffsTest {

    private val letters = ('a'..'j').joinToString("\n")

    @Test
    fun `an edit that changes nothing has no diff`() {
        Diffs.unified(letters, letters, "Volcano") shouldBe ""
    }

    @Test
    fun `a change is shown with three lines either side`() {
        Diffs.unified(letters, letters.replace("e", "E"), "Volcano") shouldBe
            """
            --- old/Volcano
            +++ new/Volcano
            @@ -2,7 +2,7 @@
             b
             c
             d
            -e
            +E
             f
             g
             h
            """
                .trimIndent()
    }

    @Test
    fun `changes whose context would overlap share one hunk`() {
        val after = letters.replace("c", "C").replace("h", "H")

        Diffs.unified(letters, after, "Volcano") shouldBe
            """
            --- old/Volcano
            +++ new/Volcano
            @@ -1,10 +1,10 @@
             a
             b
            -c
            +C
             d
             e
             f
             g
            -h
            +H
             i
             j
            """
                .trimIndent()
    }

    @Test
    fun `changes far apart are separate hunks`() {
        val before = ('a'..'t').joinToString("\n")
        val after = before.replace("b", "B").replace("s", "S")

        Diffs.unified(before, after, "Volcano", context = 1) shouldBe
            """
            --- old/Volcano
            +++ new/Volcano
            @@ -1,3 +1,3 @@
             a
            -b
            +B
             c
            @@ -18,3 +18,3 @@
             r
            -s
            +S
             t
            """
                .trimIndent()
    }

    @Test
    fun `a newline added at the end is a line of its own`() {
        Diffs.unified("a\nb", "a\nb\n", "Volcano") shouldBe
            """
            --- old/Volcano
            +++ new/Volcano
            @@ -1,2 +1,3 @@
             a
             b
            +
            """
                .trimIndent()
    }

    @Test
    fun `changes whose context only touches still share a hunk`() {
        Diffs.unified("b\nc\nd", "a\nb\nc", "Volcano", context = 1) shouldBe
            """
            --- old/Volcano
            +++ new/Volcano
            @@ -1,3 +1,3 @@
            +a
             b
             c
            -d
            """
                .trimIndent()
    }

    @Test
    fun `everything replaced is one hunk of removals then additions`() {
        Diffs.unified("old", "new", "Volcano", context = 0) shouldBe
            """
            --- old/Volcano
            +++ new/Volcano
            @@ -1,1 +1,1 @@
            -old
            +new
            """
                .trimIndent()
    }
}
