package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.model.WikiId
import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.KtorTransport
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.protocol.PageDecoder
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration

class ListServiceTest {

    private val wiki = WikiId("enwiktionary")

    private fun ref(text: String, namespace: Namespace = Namespace.MAIN) =
        PageRef(wiki, Title.Local(namespace, text))

    @Test
    fun `category members are listed across continuation batches`() = runTest {
        var batch = 0
        val lists = lists {
            batch++
            if (batch == 1) {
                respondJson(
                    """{"continue":{"cmcontinue":"page|2","continue":"-||"},
                       "query":{"categorymembers":[{"ns":0,"title":"aardvark"},
                       {"ns":0,"title":"badger"}]}}""",
                )
            } else {
                respondJson("""{"query":{"categorymembers":[{"ns":0,"title":"capybara"}]}}""")
            }
        }

        val members = lists.categoryMembers(ref("English lemmas", Namespace.CATEGORY)).toList()

        members.map { it.title.text } shouldBe listOf("aardvark", "badger", "capybara")
    }

    @Test
    fun `the category title is sent with its namespace prefix`() = runTest {
        var params = emptyMap<String, String>()
        val lists = lists { request ->
            params = request.url.parameters.entries().associate { it.key to it.value.first() }
            respondJson("""{"query":{"categorymembers":[]}}""")
        }

        lists.categoryMembers(
            ref("English lemmas", Namespace.CATEGORY),
            type = CategoryMemberType.SUBCATEGORY,
            namespaces = setOf(Namespace.MAIN, Namespace.CATEGORY),
        ).toList()

        params["cmtitle"] shouldBe "Category:English lemmas"
        params["cmtype"] shouldBe "subcat"
        params["cmnamespace"] shouldBe "0|14"
        params["list"] shouldBe "categorymembers"
    }

    @Test
    fun `a limit stops the paging early rather than filtering afterwards`() = runTest {
        var requests = 0
        val lists = lists {
            requests++
            respondJson(
                """{"continue":{"cmcontinue":"x","continue":"-||"},
                   "query":{"categorymembers":[{"ns":0,"title":"a"},{"ns":0,"title":"b"}]}}""",
            )
        }

        val members = lists.categoryMembers(ref("Huge", Namespace.CATEGORY), limit = 2).toList()

        members.size shouldBe 2
        requests shouldBe 1
    }

    @Test
    fun `a small limit is passed to the wiki instead of fetching the maximum`() = runTest {
        var limit: String? = null
        val lists = lists { request ->
            limit = request.url.parameters["cmlimit"]
            respondJson("""{"query":{"categorymembers":[{"ns":0,"title":"a"}]}}""")
        }

        lists.categoryMembers(ref("C", Namespace.CATEGORY), limit = 3).toList()

        limit shouldBe "3"
    }

    @Test
    fun `backlinks include pages linked through a redirect`() = runTest {
        var params = emptyMap<String, String>()
        val lists = lists { request ->
            params = request.url.parameters.entries().associate { it.key to it.value.first() }
            respondJson("""{"query":{"backlinks":[{"ns":0,"title":"linker"}]}}""")
        }

        val links = lists.backlinks(ref("volcano")).toList()

        params["bltitle"] shouldBe "volcano"
        params["blredirect"] shouldBe "1"
        links.single().title.text shouldBe "linker"
    }

    @Test
    fun `transclusions of a template are listed`() = runTest {
        var params = emptyMap<String, String>()
        val lists = lists { request ->
            params = request.url.parameters.entries().associate { it.key to it.value.first() }
            respondJson("""{"query":{"embeddedin":[{"ns":0,"title":"user of the template"}]}}""")
        }

        val users = lists.transclusions(ref("col", Namespace.TEMPLATE)).toList()

        params["eititle"] shouldBe "Template:col"
        users.single().title.text shouldBe "user of the template"
    }

    @Test
    fun `results nested under a page are pulled out`() = runTest {
        val lists = lists {
            respondJson(
                """{"query":{"pages":[{"pageid":1,"ns":0,"title":"volcano",
                   "templates":[{"ns":10,"title":"Template:col"},{"ns":10,"title":"Template:l"}]}]}}""",
            )
        }

        val templates = lists.templatesOn(ref("volcano")).toList()

        templates.map { it.title.text } shouldBe listOf("col", "l")
        templates.all { it.title.namespace == Namespace.TEMPLATE } shouldBe true
    }

    @Test
    fun `search asks for the namespaces it was given`() = runTest {
        var params = emptyMap<String, String>()
        val lists = lists { request ->
            params = request.url.parameters.entries().associate { it.key to it.value.first() }
            respondJson("""{"query":{"search":[{"ns":0,"title":"volcano"}]}}""")
        }

        lists.search("insource:vog", namespaces = setOf(Namespace.MAIN)).toList()

        params["srsearch"] shouldBe "insource:vog"
        params["srnamespace"] shouldBe "0"
        params["srwhat"] shouldBe "text"
    }

    @Test
    fun `a special page is queried through querypage`() = runTest {
        var params = emptyMap<String, String>()
        val lists = lists { request ->
            params = request.url.parameters.entries().associate { it.key to it.value.first() }
            respondJson("""{"query":{"querypage":[{"ns":14,"title":"Category:Wanted"}]}}""")
        }

        val wanted = lists.specialPage("Wantedcategories").toList()

        params["qppage"] shouldBe "Wantedcategories"
        wanted.single().title.namespace shouldBe Namespace.CATEGORY
    }

    @Test
    fun `nothing is requested until the flow is collected`() = runTest {
        var requests = 0
        val lists = lists {
            requests++
            respondJson("""{"query":{"allpages":[{"ns":0,"title":"a"}]}}""")
        }

        val flow = lists.allPages()
        requests shouldBe 0

        flow.first()
        requests shouldBe 1
    }

    @Test
    fun `an alphabetical range is bounded by the wiki, not after collection`() = runTest {
        var url = ""
        val lists = lists { request ->
            url = request.url.toString()
            respondJson("""{"query":{"allpages":[{"ns":0,"title":"volcano"}]}}""")
        }

        lists.allPages(from = "va", to = "vz").toList()

        url.contains("apfrom=va") shouldBe true
        url.contains("apto=vz") shouldBe true
        url.contains("apdir") shouldBe false
    }

    @Test
    fun `an enumeration can run backwards`() = runTest {
        var url = ""
        val lists = lists { request ->
            url = request.url.toString()
            respondJson("""{"query":{"allpages":[]}}""")
        }

        lists.allPages(descending = true).toList()

        url.contains("apdir=descending") shouldBe true
    }

    @Test
    fun `a language link carries the other wiki's title, which may not be this one's`() = runTest {
        val lists = lists {
            respondJson(
                """{"query":{"pages":[{"ns":0,"title":"volcano","langlinks":[
                   {"lang":"fr","title":"volcan","url":"https://fr.wiktionary.org/wiki/volcan",
                    "autonym":"français","langname":"French"}]}]}}""",
            )
        }

        val link = lists.languageLinksOn(ref("volcano")).toList().single()

        link.title shouldBe "volcan"
        link.code shouldBe LangCode("fr")
        link.autonym shouldBe "français"
    }

    @Test
    fun `an interwiki link is not a language link`() = runTest {
        val lists = lists {
            respondJson(
                """{"query":{"pages":[{"ns":0,"title":"volcano","iwlinks":[
                   {"prefix":"w","title":"Volcano","url":"https://en.wikipedia.org/wiki/Volcano"}]}]}}""",
            )
        }

        lists.interwikiLinksOn(ref("volcano")).toList().single().prefix shouldBe "w"
    }

    @Test
    fun `external links come back as the urls they are`() = runTest {
        val lists = lists {
            respondJson(
                """{"query":{"pages":[{"ns":0,"title":"volcano","extlinks":[
                   {"url":"https://archive.org/details/x"},{"url":"https://example.org/"}]}]}}""",
            )
        }

        lists.externalLinksOn(ref("volcano")).toList() shouldBe
            listOf("https://archive.org/details/x", "https://example.org/")
    }

    @Test
    fun `files on a page are the ones it uses, not the ones that use it`() = runTest {
        val lists = lists {
            respondJson(
                """{"query":{"pages":[{"ns":0,"title":"volcano","images":[
                   {"ns":6,"title":"File:Stromboli.jpg"}]}]}}""",
            )
        }

        lists.filesOn(ref("volcano")).toList().single().title.text shouldBe "Stromboli.jpg"
    }

    @Test
    fun `a page with none of a property yields nothing rather than failing`() = runTest {
        val lists = lists {
            respondJson("""{"query":{"pages":[{"ns":0,"title":"volcano"}]}}""")
        }

        lists.languageLinksOn(ref("volcano")).toList() shouldBe emptyList()
        lists.externalLinksOn(ref("volcano")).toList() shouldBe emptyList()
    }

    @Test
    fun `link targets are asked for once each, not once per link`() = runTest {
        var url = ""
        val lists = lists { request ->
            url = request.url.toString()
            respondJson("""{"query":{"alllinks":[{"ns":0,"title":"lava"}]}}""")
        }

        lists.allLinkTargets().toList().single().title.text shouldBe "lava"

        url.contains("alunique=1") shouldBe true
    }

    @Test
    fun `asking for duplicates is possible, but has to be asked for`() = runTest {
        var url = ""
        val lists = lists { request ->
            url = request.url.toString()
            respondJson("""{"query":{"alllinks":[]}}""")
        }

        lists.allLinkTargets(unique = false).toList()

        url.contains("alunique") shouldBe false
    }

    @Test
    fun `file usages have no namespace, since their targets are always files`() = runTest {
        var url = ""
        val lists = lists { request ->
            url = request.url.toString()
            respondJson("""{"query":{"allfileusages":[]}}""")
        }

        lists.allFileUsages(prefix = "Volcano").toList()

        url.contains("afnamespace") shouldBe false
        url.contains("afprefix=Volcano") shouldBe true
    }

    @Test
    fun `a category can be walked by when pages joined it`() = runTest {
        var url = ""
        val lists = lists { request ->
            url = request.url.toString()
            respondJson("""{"query":{"categorymembers":[]}}""")
        }

        lists.categoryMembers(ref("Category:X"), sort = CategorySort.TIMESTAMP).toList()

        url.contains("cmsort=timestamp") shouldBe true
    }

    @Test
    fun `a redirect filter is sent only when it narrows anything`() = runTest {
        val urls = mutableListOf<String>()
        val lists = lists { request ->
            urls += request.url.toString()
            respondJson("""{"query":{"allpages":[]}}""")
        }

        lists.allPages().toList()
        lists.allPages(redirects = RedirectFilter.NO_REDIRECTS).toList()

        urls[0].contains("apfilterredir") shouldBe false
        urls[1].contains("apfilterredir=nonredirects") shouldBe true
    }

    @Test
    fun `a category enumeration names categories, which are not shaped like pages`() = runTest {
        val lists = lists {
            respondJson(
                """{"query":{"allcategories":[
                   {"category":"English_lemmas"},{"category":"English -s"}]}}""",
            )
        }

        val found = lists.allCategories(prefix = "English").toList()

        found.map { it.title.text } shouldBe listOf("English lemmas", "English -s")
        found.all { it.title.namespace == Namespace.CATEGORY } shouldBe true
    }

    @Test
    fun `tracking categories are named the way categories are, not the way pages are`() = runTest {
        val lists = lists {
            respondJson(
                """{"query":{"trackingcategories":[
                   {"category":"Pages with broken file links",
                    "catid":"broken-file-category"}]}}""",
            )
        }

        val found = lists.trackingCategories().toList().single()

        found.title.text shouldBe "Pages with broken file links"
        found.title.namespace shouldBe Namespace.CATEGORY
    }

    @Test
    fun `the enumerations each ask their own list module`() = runTest {
        val asked = mutableListOf<String>()
        fun listing() = lists { request ->
            asked += request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            respondJson("""{"query":{"pages":[{"ns":0,"title":"volcano"}]}}""")
        }

        listing().linksFrom(ref("volcano")).toList()
        listing().categoriesOf(ref("volcano")).toList()

        val lists = mutableListOf<String>()
        fun enumerating() = lists { request ->
            lists += request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            respondJson("""{"query":{"exturlusage":[{"ns":0,"title":"volcano"}]}}""")
        }

        enumerating().externalLinkUsage("https://example.org").toList()
        enumerating().prefixSearch("volc").toList()
        enumerating().randomPages().toList()
        enumerating().watchlist().toList()
        enumerating().pagesWithProperty("displaytitle").toList()
        enumerating().protectedTitles().toList()

        asked.count { "prop=links" in it } shouldBe 1
        asked.count { "prop=categories" in it } shouldBe 1

        listOf(
            "list=exturlusage", "list=prefixsearch", "list=random",
            "list=watchlistraw", "list=pageswithprop", "list=protectedtitles",
        ).forEach { module -> lists.count { module in it } shouldBe 1 }
    }

    @Test
    fun `external link usage passes the protocol separately, as the API wants it`() = runTest {
        var asked = ""
        val lists = lists { request ->
            asked = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            respondJson("""{"query":{"exturlusage":[]}}""")
        }

        lists.externalLinkUsage("example.org/x", protocol = "https").toList()

        asked.contains("euprotocol=https") shouldBe true
    }

    private fun TestScope.lists(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ListService {
        val transport = KtorTransport(
            client = HttpClient(MockEngine(handler)),
            endpoint = ApiEndpoint("en.wiktionary.org"),
            userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
            throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
            retry = RetryPolicy.NONE,
        )
        return ApiListService(
            transport = transport,
            decoder = PageDecoder(wiki, NamespaceMap.CANONICAL),
            namespaces = NamespaceMap.CANONICAL,
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
