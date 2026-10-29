package com.fenakhay.kwikibot.bot.fix

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MessagesTest {

    private val messages =
        Messages.of(
            mapOf(
                "en" to
                    mapOf(
                        "added" to "added {{PLURAL:$1|one term|$1 terms}}",
                        "summary" to "linked $1 in $2",
                        "english-only" to "only here",
                    ),
                "fr" to mapOf("added" to "{{PLURAL:$1|$1 terme ajouté|$1 termes ajoutés}}"),
                "ru" to mapOf("added" to "{{PLURAL:$1|$1 термин|$1 термина|$1 терминов}}"),
                "pt" to mapOf("added" to "adicionados"),
            )
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
        val withDialect =
            Messages.of(
                mapOf(
                    "pt" to mapOf("k" to "português"),
                    "en" to mapOf("k" to "english"),
                )
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
        val nested = Messages.of(mapOf("en" to mapOf("k" to "{{PLURAL:$1|{{one}}|{{many|$1}}}} done")))

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
        val loaded =
            Messages.fromJson(mapOf("en" to """{"@metadata":{"authors":["Someone"]},"k":"hello $1"}"""))

        loaded["k", "en", "world"] shouldBe "hello world"
        loaded.has("@metadata", "en") shouldBe false
    }

    private val arabic =
        Messages.of(
            mapOf(
                "ar" to
                    mapOf(
                        "terms" to "{{PLURAL:$1|zero|one|two|few|many|other}}",
                        "short" to "{{PLURAL:$1|zero|one}}",
                    )
            )
        )

    @Test
    fun `arabic picks a different form for each of zero and one and two and few and many`() {
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
    fun `the few and many rules follow the last two digits not the number`() {
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
        val polish = Messages.of(mapOf("pl" to mapOf("n" to "{{PLURAL:$1|jeden|kilka|wiele}}")))

        polish["n", "pl", 1] shouldBe "jeden"
        polish["n", "pl", 2] shouldBe "kilka"
        polish["n", "pl", 5] shouldBe "wiele"
    }

    @Test
    fun `polish takes the many form for 21 where the slavic rule would take the one form`() {
        val polish = Messages.of(mapOf("pl" to mapOf("n" to "{{PLURAL:$1|plik|pliki|plików}}")))

        listOf(0, 1, 2, 4, 5, 12, 21, 22, 101, 112, 122).map { polish["n", "pl", it] } shouldBe
            listOf(
                "plików",
                "plik",
                "pliki",
                "pliki",
                "plików",
                "plików",
                "plików",
                "pliki",
                "plików",
                "plików",
                "pliki",
            )
    }

    private fun twoForms(language: String) =
        Messages.of(mapOf(language to mapOf("n" to "{{PLURAL:$1|one|other}}")))

    private fun threeForms(language: String) =
        Messages.of(mapOf(language to mapOf("n" to "{{PLURAL:$1|one|few|many}}")))

    @Test
    fun `french and armenian and lingala and brazilian portuguese take the singular for zero`() {
        listOf("fr", "hy", "ln", "pt-br").forEach { language ->
            val messages = twoForms(language)

            listOf(0, 1, 2).map { messages["n", language, it] } shouldBe listOf("one", "one", "other")
        }
    }

    @Test
    fun `every slavic language picks one few or many by the last two digits`() {
        listOf("ru", "uk", "be", "sr", "hr", "bs").forEach { language ->
            val messages = threeForms(language)

            listOf(0, 1, 2, 4, 5, 11, 12, 14, 21, 22, 25, 111, 112, 122).map {
                messages["n", language, it]
            } shouldBe
                listOf(
                    "many",
                    "one",
                    "few",
                    "few",
                    "many",
                    "many",
                    "many",
                    "many",
                    "one",
                    "few",
                    "many",
                    "many",
                    "many",
                    "few",
                )
        }
    }

    @Test
    fun `japanese and chinese and korean and thai take their one form for any count`() {
        listOf("ja", "zh", "ko", "th").forEach { language ->
            val messages = twoForms(language)

            listOf(1, 2, 7).map { messages["n", language, it] } shouldBe listOf("one", "one", "one")
        }
    }

    @Test
    fun `vietnamese takes its first form for none or one and its second for more as CLDR 49 has it`() {
        val messages = twoForms("vi")

        listOf(0, 1, 2, 7).map { messages["n", "vi", it] } shouldBe listOf("one", "one", "other", "other")
    }

    @Test
    fun `a language with no rule anywhere along its chain takes the english one`() {
        val messages = twoForms("tlh")

        listOf(0, 1, 2).map { messages["n", "tlh", it] } shouldBe listOf("other", "one", "other")
    }

    @Test
    fun `a language with no rule of its own takes the rule of the language it falls back to`() {
        val fallen =
            Messages.of(
                mapOf(
                    "ar" to mapOf("n" to "{{PLURAL:$1|zero|one|two|few|many|other}}"),
                    "be" to mapOf("n" to "{{PLURAL:$1|one|few|many}}"),
                    "sr" to mapOf("n" to "{{PLURAL:$1|one|few|many}}"),
                )
            )

        fallen["n", "arz", 0] shouldBe "zero"
        fallen["n", "ary", 3] shouldBe "few"
        fallen["n", "be-tarask", 5] shouldBe "many"
        fallen["n", "sr-ec", 5] shouldBe "many"
        fallen["n", "sr-el", 2] shouldBe "few"
    }

    private fun forms(language: String, vararg names: String) =
        Messages.of(mapOf(language to mapOf("n" to names.joinToString("|", "{{PLURAL:$1|", "}}"))))

    @Test
    fun `a category only a fraction takes still holds its place among the forms`() {
        val lithuanian = forms("lt", "one", "few", "many", "other")
        val czech = forms("cs", "one", "few", "many", "other")

        listOf(1, 2, 10, 21).map { lithuanian["n", "lt", it] } shouldBe listOf("one", "few", "other", "one")
        listOf(1, 3, 5).map { czech["n", "cs", it] } shouldBe listOf("one", "few", "other")
    }

    @Test
    fun `maltese has five forms numbered as mediawiki numbers them`() {
        val maltese = forms("mt", "one", "two", "few", "many", "other")

        listOf(0, 1, 2, 3, 11, 20, 102, 103, 111).map { maltese["n", "mt", it] } shouldBe
            listOf("few", "one", "two", "few", "many", "other", "other", "few", "many")
    }

    @Test
    fun `welsh has a form for each of zero and one and two and few and many and other`() {
        val welsh = forms("cy", "zero", "one", "two", "few", "many", "other")

        listOf(0, 1, 2, 3, 4, 6).map { welsh["n", "cy", it] } shouldBe
            listOf("zero", "one", "two", "few", "other", "many")
    }

    @Test
    fun `french keeps its many form for millions`() {
        val french = forms("fr", "one", "many", "other")

        listOf(1, 2, 1_000_000).map { french["n", "fr", it] } shouldBe listOf("one", "other", "many")
    }

    @Test
    fun `hebrew has a form for one for two and for every other count`() {
        val hebrew = forms("he", "one", "two", "other")

        listOf(0, 1, 2, 20).map { hebrew["n", "he", it] } shouldBe listOf("other", "one", "two", "other")
    }

    @Test
    fun `a variant takes its language's rule without a fallback naming it`() {
        val variants =
            Messages(
                mapOf(
                    "sr-ec" to mapOf("n" to "{{PLURAL:$1|one|few|other}}"),
                    "be-tarask" to mapOf("n" to "{{PLURAL:$1|one|few|many}}"),
                    "de-formal" to mapOf("n" to "{{PLURAL:$1|one|other}}"),
                ),
                fallbacks = emptyMap(),
            )

        variants["n", "sr-ec", 5] shouldBe "other"
        variants["n", "be-tarask", 5] shouldBe "many"
        variants["n", "de-formal", 0] shouldBe "other"
    }

    @Test
    fun `bhojpuri under its old code and interslavic take the rules mediawiki gives them`() {
        val bhojpuri = forms("bh", "one", "other")
        val interslavic = forms("isv-latn", "one", "few", "other")

        listOf(0, 1, 2).map { bhojpuri["n", "bh", it] } shouldBe listOf("one", "one", "other")
        listOf(1, 2, 5).map { interslavic["n", "isv-latn", it] } shouldBe listOf("one", "few", "other")
    }

    @Test
    fun `a message is there when its language or one it falls back to has it`() {
        messages.has("added", "fr") shouldBe true
        messages.has("english-only", "fr") shouldBe true
    }

    @Test
    fun `a count naming no argument takes the last form and a plural with no forms is empty`() {
        val odd =
            Messages.of(
                mapOf(
                    "en" to mapOf("named" to "{{PLURAL:${'$'}count|one|many}}", "bare" to "[{{PLURAL:$1}}]")
                )
            )

        odd["named", "en", 1] shouldBe "many"
        odd["bare", "en", 1] shouldBe "[]"
    }

    @Test
    fun `a plural form containing a piped link is not cut at the link's pipe`() {
        val linked =
            Messages.of(mapOf("en" to mapOf("k" to "{{PLURAL:$1|[[term|one term]]|[[term|$1 terms]]}}")))

        linked["k", "en", 1] shouldBe "[[term|one term]]"
        linked["k", "en", 4] shouldBe "[[term|4 terms]]"
    }

    @Test
    fun `the languages a bundle carries are the ones it was given`() {
        messages.languages shouldBe setOf("en", "fr", "ru", "pt")
    }

    @Test
    fun `a count with nothing to fill it takes the last form and an unclosed plural is left as written`() {
        val odd =
            Messages.of(
                mapOf(
                    "en" to
                        mapOf("unfilled" to "{{PLURAL:$2|one|many}}", "unclosed" to "{{PLURAL:$1|one|many")
                )
            )

        odd["unfilled", "en", 1] shouldBe "many"
        odd["unclosed", "en", 1] shouldBe "{{PLURAL:1|one|many"
    }

    @Test
    fun `the shipped fallbacks can be extended keeping the ones they had`() {
        val withSami =
            Messages(
                mapOf("nb" to mapOf("k" to "bokmål")),
                fallbacks = Messages.FALLBACKS + ("se" to listOf("nb")),
            )

        withSami["k", "se"] shouldBe "bokmål"
        withSami.chain("nn") shouldBe listOf("nn", "nb", "no", "en")
    }
}
