package com.fenakhay.kwikibot.protocol.decode

import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.PageId
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.ContentModel
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.Revision
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class PageDecoderTest {

    private val wiki = WikiId("enwiktionary")

    private val decoder = PageDecoder(wiki, NamespaceMap.CANONICAL)

    private fun page(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `an existing page yields its reference and revision`() {
        val result =
            decoder
                .decode(
                    page(
                        """{"pageid":8123,"ns":0,"title":"volcano","contentmodel":"wikitext",
                   "revisions":[{"revid":9001,"parentid":8999,"timestamp":"2026-08-31T21:43:26Z",
                   "user":"Someone","comment":"tweak","size":4210,
                   "slots":{"main":{"content":"==English==\ntext"}}}]}"""
                    )
                )
                .shouldBeInstanceOf<PageResult.Existing>()

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
        val result =
            decoder
                .decode(page("""{"pageid":1,"ns":14,"title":"Category:English lemmas"}"""))
                .shouldBeInstanceOf<PageResult.Existing>()

        result.ref.title.namespace shouldBe Namespace.CATEGORY
        result.ref.title.text shouldBe "English lemmas"
    }

    @Test
    fun `a main-space title containing a colon survives intact`() {
        val result =
            decoder
                .decode(page("""{"pageid":2,"ns":0,"title":"Nineteen Eighty-Four: A Novel"}"""))
                .shouldBeInstanceOf<PageResult.Existing>()

        result.ref.title.text shouldBe "Nineteen Eighty-Four: A Novel"
    }

    @Test
    fun `a missing page is a distinct result, not an empty one`() {
        val result = decoder.decode(page("""{"ns":0,"title":"Nonexistent","missing":true}"""))

        result.shouldBeInstanceOf<PageResult.Missing>().ref.title.text shouldBe "Nonexistent"
    }

    @Test
    fun `an invalid title carries the reason MediaWiki gave`() {
        val result =
            decoder
                .decode(
                    page(
                        """{"title":"foo|bar","invalid":true,
                   "invalidreason":"The requested page title contains invalid characters."}"""
                    )
                )
                .shouldBeInstanceOf<PageResult.Invalid>()

        result.raw shouldBe "foo|bar"
        result.reason.contains("invalid characters") shouldBe true
    }

    @Test
    fun `presence flags are read as formatversion=2 sends them`() {
        decoder
            .decode(page("""{"ns":0,"title":"A","missing":true}"""))
            .shouldBeInstanceOf<PageResult.Missing>()

        decoder
            .decode(page("""{"pageid":1,"ns":0,"title":"A","redirect":false}"""))
            .shouldBeInstanceOf<PageResult.Existing>()
            .isRedirect shouldBe false
        decoder
            .decode(page("""{"pageid":1,"ns":0,"title":"A","redirect":true}"""))
            .shouldBeInstanceOf<PageResult.Existing>()
            .isRedirect shouldBe true
    }

    @Test
    fun `content is read from a revision slot or from the flat shape`() {
        val slotted =
            decoder
                .decode(
                    page(
                        """{"pageid":1,"ns":0,"title":"A","revisions":[{"revid":1,
                   "timestamp":"2026-01-01T00:00:00Z","slots":{"main":{"content":"slot text"}}}]}"""
                    )
                )
                .shouldBeInstanceOf<PageResult.Existing>()

        val flat =
            decoder
                .decode(
                    page(
                        """{"pageid":1,"ns":0,"title":"A","revisions":[{"revid":1,
                   "timestamp":"2026-01-01T00:00:00Z","content":"flat text"}]}"""
                    )
                )
                .shouldBeInstanceOf<PageResult.Existing>()

        slotted.content?.text shouldBe "slot text"
        flat.content?.text shouldBe "flat text"
    }

    @Test
    fun `a page queried without content has a revision but no text`() {
        val result =
            decoder
                .decode(
                    page(
                        """{"pageid":1,"ns":0,"title":"A","revisions":[{"revid":7,
                   "timestamp":"2026-01-01T00:00:00Z"}]}"""
                    )
                )
                .shouldBeInstanceOf<PageResult.Existing>()

        result.latestRevision?.id shouldBe RevisionId(7)
        result.content.shouldBeNull()
    }

    @Test
    fun `a redirect reports the target its own text names`() {
        val result =
            decoder
                .decode(
                    page(
                        """{"pageid":1,"ns":0,"title":"colour","redirect":true,
                   "revisions":[{"revid":1,"timestamp":"2026-01-01T00:00:00Z",
                   "slots":{"main":{"content":"#REDIRECT [[color]]"}}}]}"""
                    )
                )
                .shouldBeInstanceOf<PageResult.Existing>()

        result.isRedirect shouldBe true
        result.content?.redirectTarget shouldBe Title.Local(Namespace.MAIN, "Color")
    }

    @Test
    fun `a redirect is read whatever language the wiki writes the magic word in`() {
        val targets =
            listOf("#WEITERLEITUNG [[Farbe]]", "#تحويل [[لون]]", "  #redirect:[[colour|x]]").map { text ->
                decoder
                    .decode(
                        page(
                            """{"pageid":1,"ns":0,"title":"A","redirect":true,"revisions":[{"revid":1,
                               "timestamp":"2026-01-01T00:00:00Z","slots":{"main":{"content":"$text"}}}]}"""
                        )
                    )
                    .shouldBeInstanceOf<PageResult.Existing>()
                    .content
                    ?.redirectTarget
            }

        targets shouldBe
            listOf(
                Title.Local(Namespace.MAIN, "Farbe"),
                Title.Local(Namespace.MAIN, "لون"),
                Title.Local(Namespace.MAIN, "Colour"),
            )
    }

    @Test
    fun `a hidden author is reported as absent rather than as an empty name`() {
        val result =
            decoder
                .decode(
                    page(
                        """{"pageid":1,"ns":0,"title":"A","revisions":[{"revid":1,
                   "timestamp":"2026-01-01T00:00:00Z","userhidden":true}]}"""
                    )
                )
                .shouldBeInstanceOf<PageResult.Existing>()

        result.latestRevision?.user.shouldBeNull()
        result.latestRevision?.isUserHidden shouldBe true
    }

    @Test
    fun `a first revision has no parent`() {
        val result =
            decoder
                .decode(
                    page(
                        """{"pageid":1,"ns":0,"title":"A","revisions":[{"revid":1,"parentid":0,
                   "timestamp":"2026-01-01T00:00:00Z"}]}"""
                    )
                )
                .shouldBeInstanceOf<PageResult.Existing>()

        result.latestRevision?.parentId.shouldBeNull()
    }

    @Test
    fun `an entry carrying only a title decodes as an existing page with every default`() {
        decoder.decode(page("""{"title":"volcano"}""")) shouldBe
            PageResult.Existing(
                ref = PageRef(wiki, Title.Local(Namespace.MAIN, "volcano")),
                contentModel = ContentModel.WIKITEXT,
                isRedirect = false,
                latestRevision = null,
                content = null,
            )
    }

    @Test
    fun `a revision carrying no fields decodes with every default`() {
        decoder.decodeRevision(page("{}")) shouldBe
            Revision(id = RevisionId(0), timestamp = MwTimestamp.parse("1970-01-01T00:00:00Z"))
    }

    @Test
    fun `a revision read without its id or time still carries its text`() {
        val result =
            decoder
                .decode(page("""{"title":"A","revisions":[{"content":"text"}]}"""))
                .shouldBeInstanceOf<PageResult.Existing>()

        result.content shouldBe
            PageContent(
                ref = PageRef(wiki, Title.Local(Namespace.MAIN, "A")),
                revisionId = RevisionId(0),
                text = "text",
            )
    }

    @Test
    fun `an invalid title the wiki gave no reason for is still reported as invalid`() {
        decoder.decode(page("""{"title":"foo|bar","invalid":true}""")) shouldBe
            PageResult.Invalid("foo|bar", "MediaWiki rejected the title")
        decoder.decode(page("""{"invalid":true}""")) shouldBe
            PageResult.Invalid("", "MediaWiki rejected the title")
    }

    @Test
    fun `an entry whose title names no page is invalid rather than a page`() {
        decoder.decode(page("""{"ns":1,"title":"Talk:"}""")) shouldBe
            PageResult.Invalid("Talk:", "title could not be read")
        decoder.decode(page("""{"pageid":3,"ns":0}""")) shouldBe
            PageResult.Invalid("", "title could not be read")
    }

    @Test
    fun `a revision whose main text is hidden, or not asked for, has no content`() {
        val shapes =
            listOf(
                """{"revid":5,"slots":{"main":{"texthidden":true}}}""",
                """{"revid":5,"slots":{"mediainfo":{"content":"{}"}}}""",
            )

        shapes
            .map { revision ->
                decoder
                    .decode(page("""{"title":"A","revisions":[$revision]}"""))
                    .shouldBeInstanceOf<PageResult.Existing>()
                    .content
            }
            .shouldBe(listOf(null, null))
    }

    @Test
    fun `a redirect whose text names no target reports none`() {
        val result =
            decoder
                .decode(
                    page("""{"title":"A","redirect":true,"revisions":[{"revid":1,"content":"#REDIRECT"}]}""")
                )
                .shouldBeInstanceOf<PageResult.Existing>()

        result.isRedirect shouldBe true
        result.content?.redirectTarget.shouldBeNull()
    }

    @Test
    fun `each revision's text is read, and only wikitext is asked whether it redirects`() {
        val asked = mutableListOf<String>()
        val isRedirect = { text: String ->
            asked += text
            text.startsWith("#REDIRECT")
        }

        val wikitext =
            decoder.decodeContents(
                page(
                    """{"pageid":1,"ns":0,"title":"A","revisions":[
                       {"revid":2,"content":"#REDIRECT [[B]]"},
                       {"revid":1,"texthidden":true},
                       {"revid":0,"content":"text"}]}"""
                ),
                isRedirect,
            )
        val json =
            decoder.decodeContents(
                page(
                    """{"ns":0,"title":"Data","contentmodel":"json",
                       "revisions":[{"revid":3,"content":"#REDIRECT [[B]]"}]}"""
                ),
                isRedirect,
            )

        wikitext.map { it.revisionId to it.redirectTarget } shouldBe
            listOf(RevisionId(2) to Title.Local(Namespace.MAIN, "B"), RevisionId(0) to null)
        json.single().contentModel shouldBe ContentModel.JSON
        json.single().redirectTarget.shouldBeNull()
        asked shouldBe listOf("#REDIRECT [[B]]", "text")
    }

    @Test
    fun `an entry that is not an existing page has no revision texts`() {
        val entries =
            listOf(
                """{"ns":0,"title":"A","missing":true,"revisions":[{"content":"x"}]}""",
                """{"title":"foo|bar","invalid":true,"revisions":[{"content":"x"}]}""",
                """{"ns":0,"revisions":[{"content":"x"}]}""",
            )

        entries.map { decoder.decodeContents(page(it)) { true } } shouldBe entries.map { emptyList() }
    }

    @Test
    fun `minor and bot flags are read`() {
        val revision =
            decoder.decodeRevision(
                page("""{"revid":1,"timestamp":"2026-01-01T00:00:00Z","minor":true,"bot":true}""")
            )

        revision.isMinor shouldBe true
        revision.isBot shouldBe true
    }
}
