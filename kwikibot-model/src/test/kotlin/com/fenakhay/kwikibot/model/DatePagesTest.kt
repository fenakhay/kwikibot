package com.fenakhay.kwikibot.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DatePagesTest {

    @Test
    fun `each language writes the day its own way`() {
        DatePages.dayTitle(1, 15, "en") shouldBe "January 15"
        DatePages.dayTitle(1, 15, "fr") shouldBe "15 janvier"
        DatePages.dayTitle(1, 15, "de") shouldBe "15. Januar"
        DatePages.dayTitle(1, 15, "es") shouldBe "15 de enero"
        DatePages.dayTitle(1, 15, "pt") shouldBe "15 de janeiro"
    }

    @Test
    fun `french writes an ordinal on the first of the month and nowhere else`() {
        DatePages.dayTitle(1, 1, "fr") shouldBe "1er janvier"
        DatePages.dayTitle(1, 2, "fr") shouldBe "2 janvier"
    }

    @Test
    fun `a language with no format here is null, not a guess in english`() {
        DatePages.dayTitle(1, 1, "ja").shouldBeNull()
    }

    @Test
    fun `a date that does not exist is refused`() {
        assertFailsWith<IllegalArgumentException> { DatePages.dayTitle(2, 31, "en") }
        assertFailsWith<IllegalArgumentException> { DatePages.dayTitle(13, 1, "en") }
        assertFailsWith<IllegalArgumentException> { DatePages.dayTitle(4, 31, "en") }
    }

    @Test
    fun `the twenty-ninth of february has a page, because a day page is not a date in a year`() {
        DatePages.dayTitle(2, 29, "en") shouldBe "February 29"
        DatePages.daysIn(2) shouldBe 29
    }

    @Test
    fun `a title is read back into its month and day`() {
        DatePages.parseDayTitle("January 15", "en") shouldBe (1 to 15)
        DatePages.parseDayTitle("15. Januar", "de") shouldBe (1 to 15)
        DatePages.parseDayTitle("1er janvier", "fr") shouldBe (1 to 1)
    }

    @Test
    fun `a month written in the other case is still that month`() {
        DatePages.parseDayTitle("15 Janvier", "fr") shouldBe (1 to 15)
    }

    @Test
    fun `a title that is not a date page is not read as one`() {
        DatePages.parseDayTitle("Volcano", "en").shouldBeNull()
        DatePages.parseDayTitle("January", "en").shouldBeNull()
    }

    @Test
    fun `a title naming an impossible date is not read as a date`() {
        DatePages.parseDayTitle("February 31", "en").shouldBeNull()
    }

    @Test
    fun `every day of every month round-trips in every language shipped`() {
        DatePages.languages.forEach { language ->
            (1..12).forEach { month ->
                (1..DatePages.daysIn(month)).forEach { day ->
                    val title = checkNotNull(DatePages.dayTitle(month, day, language))
                    DatePages.parseDayTitle(title, language) shouldBe (month to day)
                }
            }
        }
    }

    @Test
    fun `a bot can add a language this library does not ship`() {
        DatePages.register(
            "test",
            DatePages.DayFormat(
                months = (1..12).map { "month$it" },
                pattern = "\$month/\$day",
            ),
        )

        DatePages.dayTitle(3, 4, "test") shouldBe "month3/4"
        DatePages.parseDayTitle("month3/4", "test") shouldBe (3 to 4)
    }
}
