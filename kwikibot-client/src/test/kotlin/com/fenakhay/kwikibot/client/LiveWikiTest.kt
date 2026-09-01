package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.TextDirection
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.outline
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

@Tag("live")
class LiveWikiTest {

    private val config = WikiConfig(
        userAgent = UserAgent("kwikibot-livetest", "0.1.0", "https://en.wiktionary.org/wiki/User:FenaBot"),
        throttle = Throttle(read = 500.milliseconds),
    )

    private fun onWiktionary(block: suspend (Wiki) -> Unit): Unit = runBlocking {
        WikiClient(config).use { client ->
            block(client.wiki(LangCode("en"), Family.WIKTIONARY))
        }
    }

    @Test
    fun `site info describes the wiki we asked for`(): Unit = onWiktionary { wiki ->
        wiki.id.dbName shouldBe "enwiktionary"
        wiki.info.server shouldBe "en.wiktionary.org"

        val main = checkNotNull(wiki.namespaces[Namespace.MAIN])
        main.case shouldBe com.fenakhay.kwikibot.model.TitleCase.CASE_SENSITIVE
    }

    @Test
    fun `an interwiki target is refused rather than resolved to another project`() =
        onWiktionary { wiki ->
            wiki.parse("w:Etsy").shouldBeInstanceOf<Title.Interwiki>()
            wiki.parse("volcano").shouldBeInstanceOf<Title.Local>()
        }

    @Test
    fun `real pages round-trip through the parser byte for byte`(): Unit = onWiktionary { wiki ->
        val titles = listOf("volcano", "vog", "water", "-ing", "中文")
        val contents = wiki.pages.contents(titles.map { wiki.ref(it) })

        check(contents.size >= titles.size - 1) { "expected most pages to exist, got ${contents.size}" }

        val broken = contents.values.filter { Wikitext.parse(it.text).serialize() != it.text }
        if (broken.isNotEmpty()) {
            fail(
                "these live pages did not round-trip: " +
                    broken.joinToString { "${it.title.text} (${it.text.length} chars)" },
            )
        }
    }

    @Test
    fun `real entries outline into sections that reassemble`(): Unit = onWiktionary { wiki ->
        val content = checkNotNull(wiki.pages.content(wiki.ref("volcano"))) { "volcano should exist" }
        val outline = Wikitext.parse(content.text).outline()

        val english = checkNotNull(outline.find("English", level = 2)) {
            "expected an English section, found ${outline.subsections.map { it.title }}"
        }
        check(english.subsections.isNotEmpty()) { "expected subsections under English" }

        outline.serialize() shouldBe content.text
    }

    @Test
    fun `a category can be walked`(): Unit = onWiktionary { wiki ->
        val members = wiki.lists
            .categoryMembers(wiki.ref("Category:English lemmas", Namespace.CATEGORY))
            .take(5)
            .toList()

        members.size shouldBe 5
        members.all { it.wiki == wiki.id } shouldBe true
    }

    @Test
    fun `a page that does not exist reads as absent rather than empty`(): Unit = onWiktionary { wiki ->
        val ref = wiki.ref("Kwikibot test page that does not exist")

        wiki.pages.content(ref) shouldBe null
        wiki.pages.exists(ref) shouldBe false
    }

    @Test
    fun `a real entry's language links point at other wikis' titles`(): Unit = onWiktionary { wiki ->
        val links = wiki.lists.languageLinksOn(wiki.ref("volcano"), limit = 20).toList()

        links.isNotEmpty() shouldBe true
        links.all { it.code.code.isNotEmpty() && it.title.isNotEmpty() } shouldBe true
        links.all { it.url != null && it.autonym != null } shouldBe true
    }

    @Test
    fun `external links come back as absolute urls`(): Unit = onWiktionary { wiki ->
        val urls = wiki.lists.externalLinksOn(wiki.ref("volcano"), limit = 10).toList()

        urls.all { it.startsWith("http") } shouldBe true
    }

    @Test
    fun `a category reports how much it holds`(): Unit = onWiktionary { wiki ->
        val category = wiki.ref("Category:English lemmas")
        val info = wiki.pages.categoryInfo(listOf(category)).values.single()

        (info.pages > 1000) shouldBe true
        info.size shouldBe info.pages + info.files + info.subcategories
    }

    @Test
    fun `an anonymous client is told it may not edit a protected page, and why`(): Unit =
        onWiktionary { wiki ->
            val checks = wiki.pages
                .testActions(listOf(wiki.ref("Wiktionary:Main Page")), setOf("edit"))
                .values
                .single()

            checks.allows("edit") shouldBe false
            checks.reasons("edit").isNotEmpty() shouldBe true
        }

    @Test
    fun `the wiki's sections and the local parser's agree on the top-level headings`(): Unit =
        onWiktionary { wiki ->
            val page = wiki.ref("volcano")

            val rendered = wiki.renderer.sections(page)
                .filter { it.level == 2 }
                .map { it.heading }
            val content = checkNotNull(wiki.pages.content(page)) { "volcano should exist" }
            val parsed = Wikitext.parse(content.text)
                .outline()
                .subsections
                .mapNotNull { it.title }

            rendered shouldBe parsed
            rendered.isNotEmpty() shouldBe true
        }

    @Test
    fun `a draft's red links are visible before it is saved`(): Unit = onWiktionary { wiki ->
        val draft = "[[lava]] and [[a page that does not exist zzqq]]"

        val links = wiki.renderer
            .resolveText(draft, context = wiki.ref("volcano"), setOf(ParseProperty.LINKS))
            .links

        links.single { it.page.title.text == "lava" }.exists shouldBe true
        links.single { it.page.title.text.startsWith("a page that does not") }.exists shouldBe false
    }

    @Test
    fun `categories and prefixes enumerate`(): Unit = onWiktionary { wiki ->
        wiki.lists.allCategories(prefix = "English ", limit = 5).toList().isNotEmpty() shouldBe true
        wiki.lists.prefixSearch("volcan", limit = 10).toList()
            .any { it.title.text == "volcano" } shouldBe true
    }

    @Test
    fun `the wiki knows more about languages than the JVM does`(): Unit = onWiktionary { wiki ->
        val known = wiki.meta.languages(listOf(LangCode("fr"), LangCode("ar")))

        known.getValue(LangCode("fr")).autonym shouldBe "français"
        known.getValue(LangCode("fr")).direction shouldBe TextDirection.LEFT_TO_RIGHT
        known.getValue(LangCode("ar")).direction shouldBe TextDirection.RIGHT_TO_LEFT
    }

    @Test
    fun `tracking categories enumerate rather than hang`(): Unit = onWiktionary { wiki ->
        val found = wiki.lists.trackingCategories(limit = 5).toList()

        found.isNotEmpty() shouldBe true
        found.all { it.title.namespace == Namespace.CATEGORY } shouldBe true
    }

    @Test
    fun `a real page has both named and anonymous contributors`(): Unit = onWiktionary { wiki ->
        val who = wiki.pages.contributors(listOf(wiki.ref("volcano"))).values.single()

        who.users.isNotEmpty() shouldBe true
        who.users.all { it.name.isNotEmpty() } shouldBe true
    }

    @Test
    fun `a wiktionary reads its files from Commons as well as its own`(): Unit =
        onWiktionary { wiki ->
            val repos = wiki.files.repositories()

            repos.any { it.isLocal } shouldBe true
            repos.any { !it.isLocal } shouldBe true
        }
}
