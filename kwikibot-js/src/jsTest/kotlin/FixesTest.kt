import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.js.RegExp
import kotlin.test.Test

class FixesTest {

    private fun replacement(pattern: String, replacement: String): Replacement =
        optionsOf("pattern" to RegExp(pattern), "replacement" to replacement)

    @Test
    fun `a diff shows what changes, with the context asked for`() {
        unifiedDiff("a\nb\n", "a\nc\n", "Page") shouldBe
            "--- old/Page\n+++ new/Page\n@@ -1,3 +1,3 @@\n a\n-b\n+c\n \n".removeSuffix("\n")
        unifiedDiff("same", "same", "Page") shouldBe ""
        unifiedDiff("a\nb\nc", "a\nx\nc", "Page", 0) shouldContain "@@ -2,1 +2,1 @@"
        DIFF_DEFAULT_CONTEXT shouldBe 3
    }

    @Test
    fun `a fix replaces what it names in the scope it names`() {
        val fix = createFix("volcano", "Spells it", arrayOf(replacement("volcanoe", "volcano")))

        fix.name shouldBe "volcano"
        fix.description shouldBe "Spells it"
        fix.replacements.single().replacement shouldBe "volcano"
        fix.replacements.single().pattern.toString() shouldContain "volcanoe"
        fix.scope.templates shouldBe TEXT_SCOPE_PROSE.templates

        fix.apply("a volcanoe {{volcanoe}}") shouldBe "a volcano {{volcanoe}}"
        fix.apply("a volcanoe", defaultParseOptions()) shouldBe "a volcano"
        fix.applyParsed(parse("a volcanoe")).toString() shouldBe "a volcano"
        fix.wouldChange("a volcanoe") shouldBe true
        fix.wouldChange("a volcano", defaultParseOptions()) shouldBe false

        val everywhere =
            createFix("all", "All", arrayOf(replacement("volcanoe", "volcano")), TEXT_SCOPE_EVERYWHERE)
        everywhere.apply("{{volcanoe}}") shouldBe "{{volcano}}"
    }

    @Test
    fun `the fixes that ship are named, and more can be registered`() {
        FIX_EXTRA_SPACES.apply("a   b") shouldBe "a b"
        FIX_DATE_RANGES.apply("1990-1995") shouldBe "1990–1995"
        FIX_ELLIPSIS.apply("wait...") shouldBe "wait…"

        fixNamed("ellipsis")?.name shouldBe "ellipsis"
        fixNamed("nothing") shouldBe null
        fixesNamed(arrayOf("ellipsis", "date-ranges")).map { it.name } shouldBe
            listOf("ellipsis", "date-ranges")
        shouldThrow<IllegalArgumentException> { fixesNamed(arrayOf("nothing")) }
            .message
            .orEmpty() shouldContain "available"

        registerFix(createFix("mine", "Mine", arrayOf(replacement("a", "b"))))
        fixNamed("mine")?.description shouldBe "Mine"
        (allFixes().asDynamic().mine as Fix).name shouldBe "mine"
    }

    @Test
    fun `messages fill their placeholders and count by the language's rule`() {
        val bundles: Record<String, Record<String, String>> =
            optionsOf(
                "en" to optionsOf<Record<String, String>>("added" to "{{PLURAL:$1|$1 term|$1 terms}} added"),
                "fr" to
                    optionsOf<Record<String, String>>("added" to "{{PLURAL:$1|$1 terme|$1 termes}} ajoutés"),
                "ar" to optionsOf<Record<String, String>>("only" to "$1 $2"),
            )
        val messages = createMessages(bundles)

        messages.get("added", "fr", valuesOf(3)) shouldBe "3 termes ajoutés"
        messages.get("added", "en", valuesOf(1)) shouldBe "1 term added"
        messages.get("added", "de", valuesOf(2)) shouldBe "2 terms added"
        messages.get("only", "arz", valuesOf("a", "b")) shouldBe "a b"
        messages.get("unknown", "en") shouldBe "unknown"
        messages.has("added", "de") shouldBe true
        messages.has("unknown", "de") shouldBe false
        messages.languages.toSet() shouldBe setOf("en", "fr", "ar")
        messages.chain("arz").toList() shouldBe listOf("arz", "ar", "en")
        MESSAGES_DEFAULT_LANGUAGE shouldBe "en"
        (messageFallbacks().asDynamic().arz as Array<String>).toList() shouldBe listOf("ar")

        val told = createMessages(bundles, optionsOf<Record<String, Array<String>>>("de" to arrayOf("fr")))
        told.chain("de").toList() shouldBe listOf("de", "fr", "en")
        told.get("added", "de", valuesOf(2)) shouldBe "2 termes ajoutés"

        val fromJson = messagesFromJson(optionsOf("en" to """{"@metadata":{"x":"y"},"hi":"Hello $1"}"""))
        fromJson.get("hi", "en", valuesOf("you")) shouldBe "Hello you"
        fromJson.has("@metadata", "en") shouldBe false
    }

    @Test
    fun `links to a page come out, keeping the words shown`() {
        val unlinker = Unlinker()
        val target = parseTitle("Volcano")

        unlinker.unlink("[[volcano|volcanoes]] and [[Volcano]] and [[Etna]]", target) shouldBe
            "volcanoes and Volcano and [[Etna]]"
        unlinker.unlinkParsed(parse("[[Volcano]]"), target).toString() shouldBe "Volcano"
        unlinker.linksTo("see [[volcano#English]]", target) shouldBe true
        unlinker.linksToParsed(parse("see [[Etna]]"), target) shouldBe false

        val wiktionary = Unlinker(capitalLinks = false)
        wiktionary.linksTo("[[volcano]]", parseTitle("Volcano")) shouldBe false

        shouldThrow<IllegalArgumentException> { unlinker.unlink("x", parseTitle("[bad")) }
    }

    @Test
    fun `an exclusion on a page denies a bot with the reason`() {
        val policy = BotPolicy("FenaBot")

        policy.check("no template").isAllowed shouldBe true
        policy.check("no template").reason shouldBe null
        policy.check("{{nobots}}").isAllowed shouldBe false
        policy.check("{{nobots}}").reason shouldBe "{{nobots}}"
        policy.check("{{bots|allow=OtherBot}}").isAllowed shouldBe false
        policy.checkParsed(parse("{{bots|allow=FenaBot}}")).isAllowed shouldBe true

        BotPolicy("FenaBot", "nosummary").check("{{bots|optout=nosummary}}").isAllowed shouldBe false
        policy.check("{{bots|optout=nosummary}}").isAllowed shouldBe true
    }
}

private fun valuesOf(vararg values: Any): Array<MessageValue> = values.unsafeCast<Array<MessageValue>>()
