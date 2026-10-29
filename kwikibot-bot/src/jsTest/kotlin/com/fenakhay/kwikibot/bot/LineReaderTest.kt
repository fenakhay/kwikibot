package com.fenakhay.kwikibot.bot

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class LineReaderTest {

    @Test
    fun `each read takes the next line and keeps those read ahead and the end of input reads as null`() =
        runTest {
            val stream: dynamic = nodeModule("stream")
            val reader = LineReader(stream.Readable.from(arrayOf("yes\nno\r\n", "last")))

            listOf(reader.next(), reader.next(), reader.next(), reader.next()) shouldBe
                listOf("yes", "no", "last", null)
        }
}
