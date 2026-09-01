package com.fenakhay.kwikibot.protocol

import com.fenakhay.kwikibot.model.ContentModel
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageId
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.model.WikiId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

class PageDecoderTest {

    private val decoder = PageDecoder(WikiId("enwiktionary"), NamespaceMap.CANONICAL)

    private fun page(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `an existing page yields its reference and revision`() {
        val result = decoder.decode(
            page(
                """{"pageid":8123,"ns":0,"title":"volcano","contentmodel":"wikitext",
                   "revisions":[{"revid":9001,"parentid":8999,"timestamp":"2026-08-31T21:43:26Z",
                   "user":"Someone","comment":"tweak","size":4210,
                   "slots":{"main":{"content":"==English==\ntext"}}}]}""",
            ),
        ).shouldBeInstanceOf<PageResult.Existing>()

        result.ref.title shouldBe Title.Local(Namespace.MAIN, "volcano")
        result.ref.pageId shouldBe PageId(8123)
        result.contentModel shouldBe ContentModel.WIKITEXT
        result.latestRevision?.id shouldBe RevisionId(9001)
        result.latestRevision?.parentId shouldBe RevisionId(8999)
        result.latestRevision?.user shouldBe "Someone"
        result.latestRevision?.timestamp shouldBe MwTimestamp.parse("2026-08-31T21:43:26Z")
        result.content?.text shouldBe "==English==\ntext"
        result.content?.revisionId shouldBe RevisionId(9001)
    }

    @Test
    fun `a namespace prefix is stripped from the title but kept in the namespace`() {
        val result = decoder.decode(page("""{"pageid":1,"ns":14,"title":"Category:English lemmas"}"""))
            .shouldBeInstanceOf<PageResult.Existing>()

        result.ref.title.namespace shouldBe Namespace.CATEGORY
        result.ref.title.text shouldBe "English lemmas"
    }

    @Test
    fun `a main-space title containing a colon survives intact`() {
        val result = decoder.decode(page("""{"pageid":2,"ns":0,"title":"Nineteen Eighty-Four: A Novel"}"""))
            .shouldBeInstanceOf<PageResult.Existing>()

        result.ref.title.text shouldBe "Nineteen Eighty-Four: A Novel"
    }

    @Test
    fun `a missing page is a distinct result, not an empty one`() {
        val result = decoder.decode(page("""{"ns":0,"title":"Nonexistent","missing":true}"""))

        result.shouldBeInstanceOf<PageResult.Missing>()
            .ref.title.text shouldBe "Nonexistent"
    }

    @Test
    fun `an invalid title carries the reason MediaWiki gave`() {
        val result = decoder.decode(
            page(
                """{"title":"foo|bar","invalid":true,
                   "invalidreason":"The requested page title contains invalid characters."}""",
            ),
        ).shouldBeInstanceOf<PageResult.Invalid>()

        result.raw shouldBe "foo|bar"
        result.reason.contains("invalid characters") shouldBe true
    }

    @Test
    fun `presence flags are read in both formatversion shapes`() {
        decoder.decode(page("""{"ns":0,"title":"A","missing":true}"""))
            .shouldBeInstanceOf<PageResult.Missing>()
        decoder.decode(page("""{"ns":0,"title":"A","missing":""}"""))
            .shouldBeInstanceOf<PageResult.Missing>()

        decoder.decode(page("""{"pageid":1,"ns":0,"title":"A","redirect":false}"""))
            .shouldBeInstanceOf<PageResult.Existing>().isRedirect shouldBe false
        decoder.decode(page("""{"pageid":1,"ns":0,"title":"A","redirect":true}"""))
            .shouldBeInstanceOf<PageResult.Existing>().isRedirect shouldBe true
    }

    @Test
    fun `content is read from a revision slot or from the flat shape`() {
        val slotted = decoder.decode(
            page(
                """{"pageid":1,"ns":0,"title":"A","revisions":[{"revid":1,
                   "timestamp":"2026-01-01T00:00:00Z","slots":{"main":{"content":"slot text"}}}]}""",
            ),
        ).shouldBeInstanceOf<PageResult.Existing>()

        val flat = decoder.decode(
            page(
                """{"pageid":1,"ns":0,"title":"A","revisions":[{"revid":1,
                   "timestamp":"2026-01-01T00:00:00Z","content":"flat text"}]}""",
            ),
        ).shouldBeInstanceOf<PageResult.Existing>()

        slotted.content?.text shouldBe "slot text"
        flat.content?.text shouldBe "flat text"
    }

    @Test
    fun `a page queried without content has a revision but no text`() {
        val result = decoder.decode(
            page(
                """{"pageid":1,"ns":0,"title":"A","revisions":[{"revid":7,
                   "timestamp":"2026-01-01T00:00:00Z"}]}""",
            ),
        ).shouldBeInstanceOf<PageResult.Existing>()

        result.latestRevision?.id shouldBe RevisionId(7)
        result.content.shouldBeNull()
    }

    @Test
    fun `a redirect reports the target its own text names`() {
        val result = decoder.decode(
            page(
                """{"pageid":1,"ns":0,"title":"colour","redirect":true,
                   "revisions":[{"revid":1,"timestamp":"2026-01-01T00:00:00Z",
                   "slots":{"main":{"content":"#REDIRECT [[color]]"}}}]}""",
            ),
        ).shouldBeInstanceOf<PageResult.Existing>()

        result.isRedirect shouldBe true
        result.content?.redirectTarget shouldBe Title.Local(Namespace.MAIN, "Color")
    }

    @Test
    fun `a hidden author is reported as absent rather than as an empty name`() {
        val result = decoder.decode(
            page(
                """{"pageid":1,"ns":0,"title":"A","revisions":[{"revid":1,
                   "timestamp":"2026-01-01T00:00:00Z","userhidden":true}]}""",
            ),
        ).shouldBeInstanceOf<PageResult.Existing>()

        result.latestRevision?.user.shouldBeNull()
        result.latestRevision?.isUserHidden shouldBe true
    }

    @Test
    fun `a first revision has no parent`() {
        val result = decoder.decode(
            page(
                """{"pageid":1,"ns":0,"title":"A","revisions":[{"revid":1,"parentid":0,
                   "timestamp":"2026-01-01T00:00:00Z"}]}""",
            ),
        ).shouldBeInstanceOf<PageResult.Existing>()

        result.latestRevision?.parentId.shouldBeNull()
    }

    @Test
    fun `minor and bot flags are read`() {
        val revision = decoder.decodeRevision(
            page("""{"revid":1,"timestamp":"2026-01-01T00:00:00Z","minor":true,"bot":true}"""),
        )

        revision.isMinor shouldBe true
        revision.isBot shouldBe true
    }
}
