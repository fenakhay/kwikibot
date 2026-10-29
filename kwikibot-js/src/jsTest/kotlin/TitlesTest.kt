import io.kotest.matchers.shouldBe
import kotlin.test.Test

class TitlesTest {

    @Test
    fun `a title is read with the namespaces every wiki has`() {
        val title = parseTitle("category:volcanoes_of Italy#Etna")

        title.kind shouldBe "local"
        title.namespace shouldBe 14
        title.text shouldBe "Volcanoes of Italy"
        title.fragment shouldBe "Etna"
        title.interwiki shouldBe null
        title.invalidReason shouldBe null
        title.toString() shouldBe "Category:Volcanoes of Italy#Etna"
    }

    @Test
    fun `an invalid title says why and keeps what was given`() {
        val title = parseTitle("a[b]")

        title.kind shouldBe "invalid"
        title.namespace shouldBe null
        title.text shouldBe null
        title.invalidReason shouldBe "illegal-character"
        title.toString() shouldBe "a[b]"
    }

    @Test
    fun `signature times are found where they are and read as UTC`() {
        val text = "Thanks. [[User:Alice|Alice]] 21:43, 31 August 2026 (UTC)"

        val found = signatureTimes(text).single()

        found.date.toISOString() shouldBe "2026-08-31T21:43:00.000Z"
        found.text shouldBe "21:43, 31 August 2026 (UTC)"
        text.substring(found.start, found.end) shouldBe found.text
        found.language shouldBe "en"
    }
}
