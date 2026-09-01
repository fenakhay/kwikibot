package com.fenakhay.kwikibot.bot

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MessagesTest {

    private val messages = Messages.of(
        mapOf(
            "en" to mapOf(
                "added" to "added {{PLURAL:$1|one term|$1 terms}}",
                "summary" to "linked $1 in $2",
                "english-only" to "only here",
            ),
            "fr" to mapOf(
                "added" to "{{PLURAL:$1|$1 terme ajouté|$1 termes ajoutés}}",
            ),
            "ru" to mapOf(
                "added" to "{{PLURAL:$1|$1 термин|$1 термина|$1 терминов}}",
            ),
            "pt" to mapOf("added" to "adicionados"),
        ),
    )

    @Test
    fun `placeholders are filled in order`() {
        messages["summary", "en", "volcano", "English"] shouldBe "linked volcano in English"
    }

    @Test
    fun `english takes the singular only for one`() {
        messages["added", "en", 1] shouldBe "added one term"
        messages["added", "en", 3] shouldBe "added 3 terms"
        messages["added", "en", 0] shouldBe "added 0 terms"
    }

    @Test
    fun `french takes the singular for zero as well as one`() {
        messages["added", "fr", 0] shouldBe "0 terme ajouté"
        messages["added", "fr", 1] shouldBe "1 terme ajouté"
        messages["added", "fr", 2] shouldBe "2 termes ajoutés"
    }

    @Test
    fun `russian has three forms and picks between them by the last digits`() {
        messages["added", "ru", 1] shouldBe "1 термин"
        messages["added", "ru", 3] shouldBe "3 термина"
        messages["added", "ru", 5] shouldBe "5 терминов"
        messages["added", "ru", 11] shouldBe "11 терминов"
        messages["added", "ru", 21] shouldBe "21 термин"
    }

    @Test
    fun `a language with fewer forms than the rule needs gets the last one`() {
        messages["added", "pt", 5] shouldBe "adicionados"
    }

    @Test
    fun `a missing message falls back to english`() {
        messages["english-only", "fr"] shouldBe "only here"
    }

    @Test
    fun `a language falls back through its own chain first`() {
        val withDialect = Messages.of(
            mapOf(
                "pt" to mapOf("k" to "português"),
                "en" to mapOf("k" to "english"),
            ),
        )

        withDialect["k", "pt-br"] shouldBe "português"
        withDialect.chain("pt-br") shouldBe listOf("pt-br", "pt", "en")
    }

    @Test
    fun `a message nobody has returns the key rather than throwing`() {
        messages["no-such-key", "fr"] shouldBe "no-such-key"
        messages.has("no-such-key", "fr") shouldBe false
    }

    @Test
    fun `a plural form containing a template still ends in the right place`() {
        val nested = Messages.of(
            mapOf("en" to mapOf("k" to "{{PLURAL:$1|{{one}}|{{many|$1}}}} done")),
        )

        nested["k", "en", 1] shouldBe "{{one}} done"
        nested["k", "en", 5] shouldBe "{{many|5}} done"
    }

    @Test
    fun `a literal count works as well as a placeholder`() {
        val literal = Messages.of(mapOf("en" to mapOf("k" to "{{PLURAL:2|a|b}}")))

        literal["k", "en"] shouldBe "b"
    }

    @Test
    fun `bundles are read in mediawiki's own json format`() {
        val loaded = Messages.fromJson(
            mapOf(
                "en" to """{"@metadata":{"authors":["Someone"]},"k":"hello $1"}""",
            ),
        )

        loaded["k", "en", "world"] shouldBe "hello world"
        loaded.has("@metadata", "en") shouldBe false
    }

    private val arabic = Messages.of(
        mapOf(
            "ar" to mapOf(
                "terms" to "{{PLURAL:$1|zero|one|two|few|many|other}}",
                "short" to "{{PLURAL:$1|zero|one}}",
            ),
        ),
    )

    @Test
    fun `arabic picks a different form for zero, one, two, few and many`() {
        arabic["terms", "ar", 0] shouldBe "zero"
        arabic["terms", "ar", 1] shouldBe "one"
        arabic["terms", "ar", 2] shouldBe "two"
        arabic["terms", "ar", 3] shouldBe "few"
        arabic["terms", "ar", 10] shouldBe "few"
        arabic["terms", "ar", 11] shouldBe "many"
        arabic["terms", "ar", 99] shouldBe "many"
        arabic["terms", "ar", 100] shouldBe "other"
    }

    @Test
    fun `the few and many rules follow the last two digits, not the number`() {
        arabic["terms", "ar", 103] shouldBe "few"
        arabic["terms", "ar", 111] shouldBe "many"
        arabic["terms", "ar", 200] shouldBe "other"
    }

    @Test
    fun `a bundle offering fewer forms than the language needs falls back to its last`() {
        arabic["short", "ar", 100] shouldBe "one"
    }

    @Test
    fun `languages with a single form ignore the count`() {
        val single = Messages.of(mapOf("ja" to mapOf("n" to "{{PLURAL:$1|件}}")))

        single["n", "ja", 1] shouldBe "件"
        single["n", "ja", 7] shouldBe "件"
    }

    @Test
    fun `polish agrees with the slavic rule except about one`() {
        val polish = Messages.of(
            mapOf("pl" to mapOf("n" to "{{PLURAL:$1|jeden|kilka|wiele}}")),
        )

        polish["n", "pl", 1] shouldBe "jeden"
        polish["n", "pl", 2] shouldBe "kilka"
        polish["n", "pl", 5] shouldBe "wiele"
    }

    @Test
    fun `the languages a bundle carries are the ones it was given`() {
        messages.languages shouldBe setOf("en", "fr", "ru", "pt")
    }
}
