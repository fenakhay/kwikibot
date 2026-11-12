import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class ListServiceTest {

    private val mock = MockWiki()

    private fun list(module: String, vararg titles: String) {
        mock.canned[module] =
            """{"query":{"$module":[${titles.joinToString(",") { """{"ns":0,"title":"$it"}""" }}]}}"""
    }

    private fun prop(module: String, entries: String) {
        mock.canned[module] =
            """{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano","$module":[$entries]}]}}"""
    }

    private fun sent(module: String): Map<String, String> =
        mock.requests.last { it["list"] == module || it["prop"] == module }

    private fun runWiki(block: suspend TestScope.(ListService) -> Unit) = runTest {
        val wiki = mock.open()
        block(wiki.lists)
        wiki.close()
    }

    @Test
    fun `categoryMembers sends every option`() = runWiki { lists ->
        list("categorymembers", "Etna")

        val found =
            lists
                .categoryMembers(
                    "Volcanoes",
                    optionsOf(
                        "type" to "subcategory",
                        "namespaces" to arrayOf(14),
                        "sort" to "timestamp",
                        "startSortKey" to "E",
                        "limit" to 3,
                    ),
                )
                .take()

        found shouldBe listOf("Etna")
        val sent = sent("categorymembers")
        sent["cmtitle"] shouldBe "Category:Volcanoes"
        sent["cmtype"] shouldBe "subcat"
        sent["cmnamespace"] shouldBe "14"
        sent["cmsort"] shouldBe "timestamp"
        sent["cmstartsortkeyprefix"] shouldBe "E"
        sent["cmlimit"] shouldBe "3"
    }

    @Test
    fun `categoryMembers without options asks for pages by sort key`() = runWiki { lists ->
        list("categorymembers", "Etna")

        lists.categoryMembers("Category:Volcanoes").take() shouldBe listOf("Etna")

        val sent = sent("categorymembers")
        listOf(sent["cmtype"], sent["cmsort"], sent["cmnamespace"]) shouldBe listOf("page", "sortkey", null)
    }

    @Test
    fun `an option that names no value is refused, saying which there are`() = runWiki { lists ->
        val refused =
            shouldThrow<IllegalArgumentException> {
                lists.categoryMembers("Volcanoes", optionsOf("type" to "pages"))
            }

        refused.message.orEmpty() shouldContain "type cannot be 'pages'"
        refused.message.orEmpty() shouldContain "'page', 'subcategory', 'file'"
    }

    @Test
    fun `a title in a namespace comes back with its prefix`() = runWiki { lists ->
        mock.canned["categorymembers"] =
            """{"query":{"categorymembers":[{"ns":14,"title":"Category:Active"}]}}"""

        lists.categoryMembers("Volcanoes").take() shouldBe listOf("Category:Active")
    }

    @Test
    fun `backlinks reads through redirects unless told not to`() = runWiki { lists ->
        list("backlinks", "Ash")

        lists.backlinks("Volcano").take() shouldBe listOf("Ash")
        sent("backlinks")["blredirect"] shouldBe "1"

        lists
            .backlinks(
                "Volcano",
                optionsOf("includeRedirects" to false, "namespaces" to arrayOf(0, 2), "limit" to 1),
            )
            .take()
        val sent = sent("backlinks")
        listOf(sent["bltitle"], sent["blredirect"], sent["blnamespace"], sent["bllimit"]) shouldBe
            listOf("Volcano", null, "0|2", "1")
    }

    @Test
    fun `transclusions reads a template and linksFrom a page`() = runWiki { lists ->
        list("embeddedin", "Etna")
        prop("links", """{"ns":0,"title":"Ash"}""")

        val options = optionsOf<ListOptions>("namespaces" to arrayOf(0), "limit" to 2)
        lists.transclusions("Infobox", options).take() shouldBe listOf("Etna")
        lists.linksFrom("Volcano", options).take() shouldBe listOf("Ash")

        val embedded = sent("embeddedin")
        listOf(embedded["eititle"], embedded["einamespace"], embedded["eilimit"]) shouldBe
            listOf("Template:Infobox", "0", "2")
        val links = sent("links")
        listOf(links["titles"], links["plnamespace"], links["pllimit"]) shouldBe listOf("Volcano", "0", "2")
    }

    @Test
    fun `what is on a page is read`() = runWiki { lists ->
        prop("templates", """{"ns":10,"title":"Template:Infobox"}""")
        prop("categories", """{"ns":14,"title":"Category:Volcanoes"}""")
        prop("images", """{"ns":0,"title":"Etna.jpg"}""")
        prop("extlinks", """{"url":"https://example.org/"}""")

        lists.templatesOn("Volcano", 1).take() shouldBe listOf("Template:Infobox")
        sent("templates")["tllimit"] shouldBe "1"
        lists.categoriesOf("Volcano", 2).take() shouldBe listOf("Category:Volcanoes")
        sent("categories")["cllimit"] shouldBe "2"
        lists.filesOn("Volcano", 3).take() shouldBe listOf("Etna.jpg")
        sent("images")["imlimit"] shouldBe "3"
        lists.externalLinksOn("Volcano", 4).take() shouldBe listOf("https://example.org/")
        sent("extlinks")["ellimit"] shouldBe "4"
    }

    @Test
    fun `language and interwiki links are read`() = runWiki { lists ->
        prop(
            "langlinks",
            """{"lang":"fr","title":"Volcan","url":"https://fr.example.org/Volcan","autonym":"français",
                "langname":"French"}""",
        )
        prop("iwlinks", """{"prefix":"w","title":"Etsy","url":"https://en.wikipedia.org/wiki/Etsy"}""")

        val language = lists.languageLinksOn("Volcano", 5).take().single()
        listOf(language.code, language.title, language.url, language.autonym, language.name) shouldBe
            listOf("fr", "Volcan", "https://fr.example.org/Volcan", "français", "French")
        sent("langlinks")["lllimit"] shouldBe "5"

        val interwiki = lists.interwikiLinksOn("Volcano", 6).take().single()
        listOf(interwiki.prefix, interwiki.title, interwiki.url) shouldBe
            listOf("w", "Etsy", "https://en.wikipedia.org/wiki/Etsy")
        sent("iwlinks")["iwlimit"] shouldBe "6"
    }

    @Test
    fun `allPages sends every option`() = runWiki { lists ->
        list("allpages", "Etna")

        lists.allPages().take() shouldBe listOf("Etna")
        sent("allpages")["apnamespace"] shouldBe "0"
        sent("allpages")["apfilterredir"] shouldBe null

        lists
            .allPages(
                optionsOf(
                    "namespace" to 2,
                    "from" to "A",
                    "to" to "B",
                    "prefix" to "Ab",
                    "descending" to true,
                    "redirects" to "no-redirects",
                    "limit" to 7,
                )
            )
            .take()
        val sent = sent("allpages")
        listOf(
            sent["apnamespace"],
            sent["apfrom"],
            sent["apto"],
            sent["apprefix"],
            sent["apdir"],
            sent["apfilterredir"],
            sent["aplimit"],
        ) shouldBe listOf("2", "A", "B", "Ab", "descending", "nonredirects", "7")
    }

    @Test
    fun `allCategories and trackingCategories name categories`() = runWiki { lists ->
        mock.canned["allcategories"] = """{"query":{"allcategories":[{"category":"Active volcanoes"}]}}"""
        mock.canned["trackingcategories"] =
            """{"query":{"trackingcategories":[{"category":"Broken files"}]}}"""

        lists
            .allCategories(
                optionsOf(
                    "from" to "A",
                    "to" to "B",
                    "prefix" to "Ac",
                    "min" to 1,
                    "max" to 9,
                    "limit" to 8,
                )
            )
            .take() shouldBe listOf("Category:Active volcanoes")
        val sent = sent("allcategories")
        listOf(
            sent["acfrom"],
            sent["acto"],
            sent["acprefix"],
            sent["acmin"],
            sent["acmax"],
            sent["aclimit"],
        ) shouldBe listOf("A", "B", "Ac", "1", "9", "8")

        lists.trackingCategories(2).take() shouldBe listOf("Category:Broken files")
        sent("trackingcategories")["tclimit"] shouldBe "2"
    }

    @Test
    fun `the link target walks share their options`() = runWiki { lists ->
        val walks =
            listOf(
                Triple("alllinks", "al", lists::allLinkTargets),
                Triple("allredirects", "ar", lists::allRedirectTargets),
                Triple("alltransclusions", "at", lists::allTransclusionTargets),
            )
        for ((module, prefix, walk) in walks) {
            list(module, "Etna")

            walk(
                    optionsOf(
                        "namespace" to 2,
                        "from" to "A",
                        "to" to "B",
                        "prefix" to "Ab",
                        "unique" to false,
                        "limit" to 4,
                    )
                )
                .take() shouldBe listOf("Etna")

            val sent = sent(module)
            listOf(
                sent["${prefix}namespace"],
                sent["${prefix}from"],
                sent["${prefix}to"],
                sent["${prefix}prefix"],
                sent["${prefix}unique"],
                sent["${prefix}limit"],
            ) shouldBe listOf("2", "A", "B", "Ab", null, "4")
        }
    }

    @Test
    fun `the link target walks default to a namespace and to unique targets`() = runWiki { lists ->
        list("alllinks", "Etna")
        list("alltransclusions", "Etna")
        list("allredirects", "Etna")

        lists.allLinkTargets().take()
        lists.allRedirectTargets().take()
        lists.allTransclusionTargets().take()

        listOf(sent("alllinks")["alnamespace"], sent("alllinks")["alunique"]) shouldBe listOf("0", "1")
        listOf(sent("allredirects")["arnamespace"], sent("allredirects")["arunique"]) shouldBe
            listOf("0", "1")
        listOf(sent("alltransclusions")["atnamespace"], sent("alltransclusions")["atunique"]) shouldBe
            listOf("10", "1")
    }

    @Test
    fun `allFileUsages sends its options`() = runWiki { lists ->
        list("allfileusages", "Etna.jpg")

        lists
            .allFileUsages(
                optionsOf("from" to "A", "to" to "B", "prefix" to "Ab", "unique" to false, "limit" to 4)
            )
            .take() shouldBe listOf("Etna.jpg")

        val sent = sent("allfileusages")
        listOf(sent["affrom"], sent["afto"], sent["afprefix"], sent["afunique"], sent["aflimit"]) shouldBe
            listOf("A", "B", "Ab", null, "4")
    }

    @Test
    fun `searches default to the main namespace`() = runWiki { lists ->
        list("prefixsearch", "Etna")
        list("search", "Etna")
        list("random", "Etna")

        lists.prefixSearch("Et").take() shouldBe listOf("Etna")
        lists.search("lava").take() shouldBe listOf("Etna")
        lists.randomPages().take() shouldBe listOf("Etna")

        listOf(sent("prefixsearch")["pssearch"], sent("prefixsearch")["psnamespace"]) shouldBe
            listOf("Et", "0")
        val search = sent("search")
        listOf(search["srsearch"], search["srnamespace"], search["srwhat"], search["srsort"]) shouldBe
            listOf("lava", "0", "text", "relevance")
        sent("random")["rnnamespace"] shouldBe "0"
    }

    @Test
    fun `searches send their options`() = runWiki { lists ->
        list("prefixsearch", "Etna")
        list("search", "Etna")
        list("random", "Etna")
        val options = optionsOf<ListOptions>("namespaces" to arrayOf(2), "limit" to 3)

        lists.prefixSearch("Et", options).take()
        lists
            .search(
                "lava",
                optionsOf(
                    "namespaces" to arrayOf(2, 10),
                    "scope" to "near-match",
                    "sort" to "last-edit-descending",
                    "limit" to 3,
                ),
            )
            .take()
        lists.randomPages(options).take()

        listOf(sent("prefixsearch")["psnamespace"], sent("prefixsearch")["pslimit"]) shouldBe listOf("2", "3")
        val search = sent("search")
        listOf(search["srnamespace"], search["srwhat"], search["srsort"], search["srlimit"]) shouldBe
            listOf("2|10", "nearmatch", "last_edit_desc", "3")
        listOf(sent("random")["rnnamespace"], sent("random")["rnlimit"]) shouldBe listOf("2", "3")
    }

    @Test
    fun `specialPage reads what a special page lists`() = runWiki { lists ->
        mock.canned["querypage"] =
            """{"query":{"querypage":{"name":"Lonelypages","results":[{"ns":0,"title":"Etna"}]}}}"""

        lists.specialPage("Lonelypages", 2).take() shouldBe listOf("Etna")

        listOf(sent("querypage")["qppage"], sent("querypage")["qplimit"]) shouldBe listOf("Lonelypages", "2")
    }

    @Test
    fun `externalLinkUsage sends its options`() = runWiki { lists ->
        list("exturlusage", "Etna")

        lists
            .externalLinkUsage(
                "example.org",
                optionsOf("protocol" to "https", "namespaces" to arrayOf(0), "limit" to 2),
            )
            .take() shouldBe listOf("Etna")

        val sent = sent("exturlusage")
        listOf(sent["euquery"], sent["euprotocol"], sent["eunamespace"], sent["eulimit"]) shouldBe
            listOf("example.org", "https", "0", "2")
    }

    @Test
    fun `watchlist, pagesWithProperty and protectedTitles are read`() = runWiki { lists ->
        list("watchlistraw", "Etna")
        list("pageswithprop", "Etna")
        list("protectedtitles", "Etna")
        val options = optionsOf<ListOptions>("namespaces" to arrayOf(0), "limit" to 2)

        lists.watchlist(options).take() shouldBe listOf("Etna")
        lists.pagesWithProperty("disambiguation", 2).take() shouldBe listOf("Etna")
        lists.protectedTitles(options).take() shouldBe listOf("Etna")

        listOf(sent("watchlistraw")["wrnamespace"], sent("watchlistraw")["wrlimit"]) shouldBe listOf("0", "2")
        listOf(sent("pageswithprop")["pwppropname"], sent("pageswithprop")["pwplimit"]) shouldBe
            listOf("disambiguation", "2")
        listOf(sent("protectedtitles")["ptnamespace"], sent("protectedtitles")["ptlimit"]) shouldBe
            listOf("0", "2")
    }

    @Test
    fun `a limit stops the walk`() = runWiki { lists ->
        list("allpages", "Etna", "Fuji", "Hekla")

        lists.allPages(optionsOf("limit" to 2)).take() shouldBe listOf("Etna", "Fuji")
    }

    @Test
    fun `a page says its content model and id`() = runTest {
        val wiki = MockWiki(pages = mapOf("Volcano" to "lava")).open()

        val page = wiki.page("Volcano").await() ?: error("no page")

        listOf(page.contentModel, page.redirectTarget, page.pageId) shouldBe listOf("wikitext", null, 1.0)
        wiki.close()
    }
}
