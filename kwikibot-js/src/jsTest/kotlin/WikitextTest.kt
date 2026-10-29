import io.kotest.matchers.shouldBe
import kotlin.js.RegExp
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WikitextTest {

    private val entry =
        """
        ==English==
        {{en-noun|es}}
        A [[mountain|hill]] that erupts.

        ===Derived terms===
        {{col|en|supervolcano}}

        [[Category:Landforms]]
        """
            .trimIndent()

    @Test
    fun `parsed text writes back byte for byte`() {
        parse(entry).toString() shouldBe entry
    }

    @Test
    fun `templates are found by name and read by parameter`() {
        val noun = parse(entry).templates("en-noun").single()

        noun.name shouldBe "en-noun"
        noun.key shouldBe "En-noun"
        noun.get("1") shouldBe "es"
        noun.getRaw("1") shouldBe "es"
        noun.has("2") shouldBe false
        noun.parameters.map { it.name to it.named } shouldBe listOf("1" to false)
        noun.toString() shouldBe "{{en-noun|es}}"
        parse(entry).templates().map { it.name } shouldBe listOf("en-noun", "col")
    }

    @Test
    fun `a parameter set by mapTemplates changes that template and nothing else`() {
        val code = parse(entry)

        val updated = code.mapTemplates("col") { it.withParameter("3", "hypervolcano") }

        updated.toString() shouldBe
            entry.replace("{{col|en|supervolcano}}", "{{col|en|supervolcano|hypervolcano}}")
        code.toString() shouldBe entry
    }

    @Test
    fun `a template replaced by another one without a parameter is written where it was`() {
        val code = parse(entry)
        val noun = code.templates("en-noun").single()

        code.replaceTemplate(noun, noun.withoutParameter("1")).toString() shouldBe
            entry.replace("{{en-noun|es}}", "{{en-noun}}")
    }

    @Test
    fun `sections nest under the lead and are found by title and level`() {
        val outline = parse(entry).outline()

        outline.title shouldBe null
        outline.level shouldBe 0
        val english = outline.find("English", 2) ?: error("no English section")
        english.subsections.map { it.title to it.level } shouldBe listOf("Derived terms" to 3)
        english.content.templates().map { it.name } shouldBe listOf("en-noun")
        outline.find("English", 3) shouldBe null
        english.toString() shouldBe entry
    }

    @Test
    fun `a section given new content is written back where it was`() {
        val code = parse(entry)
        val derived = code.outline().find("Derived terms") ?: error("no derived terms")

        val content = parse(derived.content.toString().replace("supervolcano", "hypervolcano"))

        val updated = code.replaceSection(derived, derived.withContent(content))

        updated.toString() shouldBe entry.replace("supervolcano", "hypervolcano")
    }

    @Test
    fun `categories are listed and added and removed`() {
        val code = parse(entry)

        code.categories().toList() shouldBe listOf("Landforms")
        code.addCategory("Volcanoes").categories().toList() shouldBe listOf("Landforms", "Volcanoes")
        code.addCategory("Volcanoes", "Etna").toString() shouldBe "$entry\n[[Category:Volcanoes|Etna]]"
        code.removeCategory("Landforms").categories().toList() shouldBe emptyList()
    }

    @Test
    fun `replaceText changes prose and leaves templates and link targets alone unless told otherwise`() {
        val code = parse("[[volcano]] volcano {{volcano}}")

        code.replaceText(RegExp("volcano"), "mountain").toString() shouldBe "[[volcano]] mountain {{volcano}}"
        code.replaceText(RegExp("volcano"), "mountain", "everywhere").toString() shouldBe
            "[[mountain]] mountain {{mountain}}"
    }

    @Test
    fun `a pattern's i flag applies and the replacement is taken as it is`() {
        parse("Volcano volcano").replaceText(RegExp("volcano", "i"), "vent").toString() shouldBe "vent vent"
        parse("ash").replaceText(RegExp("(a)sh"), "$1").toString() shouldBe "$1"
    }

    @Test
    fun `a flag with no equivalent here and an unknown scope are refused`() {
        assertFailsWith<IllegalArgumentException> { parse("a").replaceText(RegExp("a", "s"), "b") }
        assertFailsWith<IllegalArgumentException> { parse("a").replaceText(RegExp("a"), "b", "templates") }
    }

    @Test
    fun `links are read with their targets and the visible text drops the markup`() {
        val code = parse("A [[mountain|hill]] and [[ash]].")

        code.links().map { it.target to it.text } shouldBe listOf("mountain" to "hill", "ash" to null)
        code.links().first().toString() shouldBe "[[mountain|hill]]"
        code.text shouldBe "A hill and ash."
    }
}
