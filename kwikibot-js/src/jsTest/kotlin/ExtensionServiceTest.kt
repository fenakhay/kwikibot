import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.js.Date
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class ExtensionServiceTest {

    private val mock =
        MockWiki().apply {
            extensions +=
                listOf(
                    "GeoData",
                    "PageImages",
                    "TextExtracts",
                    "WikibaseClient",
                    "Linter",
                    "Echo",
                    "Thanks",
                    "UrlShortener",
                    "FlaggedRevs",
                    "Abuse Filter",
                    "PageTriage",
                )
        }

    private fun runWiki(block: suspend TestScope.(ExtensionService) -> Unit) = runTest {
        val wiki = mock.open()
        block(wiki.extensions)
        wiki.close()
    }

    private fun sentProp(prop: String): Map<String, String> = mock.requests.last { it["prop"] == prop }

    private fun sent(action: String): Map<String, String> = mock.requests.last { it["action"] == action }

    private fun page(fields: String): String =
        """{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano",$fields}]}}"""

    @Test
    fun `has and the names say what is installed`() = runWiki { extensions ->
        listOf(
                extensions.geoData,
                extensions.pageImagesExtension,
                extensions.textExtracts,
                extensions.wikibaseClient,
                extensions.linter,
                extensions.echo,
                extensions.thanks,
                extensions.urlShortener,
                extensions.flaggedRevs,
                extensions.abuseFilter,
                extensions.pageTriage,
            )
            .forEach { extensions.has(it) shouldBe true }
        extensions.has("Nothing") shouldBe false
        extensions.maxCreators shouldBe 10
    }

    @Test
    fun `a missing extension rejects`() = runTest {
        val bare = MockWiki()
        val wiki = bare.open()

        shouldThrow<Throwable> { wiki.extensions.coordinates(arrayOf("Volcano")).await() }
            .message shouldContain "GeoData"
        shouldThrow<Throwable> { wiki.extensions.lintErrors().take() }.message shouldContain "Linter"
        wiki.close()
    }

    @Test
    fun `coordinates and nearby read GeoData`() = runWiki { extensions ->
        mock.canned["coordinates"] =
            page(
                """coordinates":[{"lat":1.5,"lon":-2.5,"globe":"mars","primary":"","type":"mountain"}]"""
                    .prefixed()
            )
        mock.canned["geosearch"] = """{"query":{"geosearch":[{"ns":0,"title":"Volcano"}]}}"""

        val found = extensions.coordinates(arrayOf("Volcano")).await().single()
        found.title shouldBe "Volcano"
        val point = found.coordinates.single()
        listOf(point.latitude, point.longitude, point.globe, point.isPrimary, point.type) shouldBe
            listOf(1.5, -2.5, "mars", true, "mountain")

        extensions.nearby(1.5, 2.5, radius = 300, limit = 4).await().toList() shouldBe listOf("Volcano")
        val sent = mock.requests.last { it["list"] == "geosearch" }
        listOf(sent["gscoord"], sent["gsradius"], sent["gslimit"]) shouldBe listOf("1.5|2.5", "300", "4")
    }

    @Test
    fun `pageImages, extracts and wikibaseItems read one value a page`() = runWiki { extensions ->
        mock.canned["pageimages"] = page(""""pageimage":"Fire.jpg"""")
        mock.canned["extracts"] = page(""""extract":"Hot."""")
        mock.canned["pageprops"] = page(""""pageprops":{"wikibase_item":"Q8072"}""")

        extensions.pageImages(arrayOf("Volcano")).await().single().value shouldBe "Fire.jpg"
        val extract = extensions.extracts(arrayOf("Volcano"), sentences = 1).await().single()
        listOf(extract.title, extract.value) shouldBe listOf("Volcano", "Hot.")
        sentProp("extracts")["exsentences"] shouldBe "1"
        extensions.wikibaseItems(arrayOf("Volcano")).await().single().value shouldBe "Q8072"
    }

    @Test
    fun `lintErrors reads the location and details`() = runWiki { extensions ->
        mock.canned["linterrors"] =
            """{"query":{"linterrors":[{"lintId":5,"category":"obsolete-tag","ns":0,"title":"Volcano",
               "location":[3,9],"params":{"name":"font"}}]}}"""

        val error = extensions.lintErrors("obsolete-tag", 1).take().single()

        listOf(error.id, error.category, error.page, error.rangeStart, error.rangeEnd) shouldBe
            listOf(5.0, "obsolete-tag", "Volcano", 3, 9)
        (error.details.asDynamic().name as String) shouldBe "font"
        mock.requests.last { it["list"] == "linterrors" }["lntcategories"] shouldBe "obsolete-tag"
    }

    @Test
    fun `notifications reads Echo`() = runWiki { extensions ->
        mock.canned["notifications"] =
            """{"query":{"notifications":{"list":[{"id":4,"type":"mention","title":{"full":"Volcano"},
               "agent":{"name":"Alice"},"timestamp":{"utciso8601":"2026-08-31T21:43:26Z"},"read":"x"}]}}}"""

        val found = extensions.notifications(unreadOnly = true, limit = 2).await().single()

        listOf(found.id, found.type, found.title, found.agent, found.isRead) shouldBe
            listOf(4.0, "mention", "Volcano", "Alice", true)
        found.timestamp?.getTime() shouldBe Date("2026-08-31T21:43:26Z").getTime()
        val sent = mock.requests.last { it["meta"] == "notifications" }
        listOf(sent["notfilter"], sent["notlimit"]) shouldBe listOf("!read", "2")
    }

    @Test
    fun `thank, shortenUrl and review write`() = runWiki { extensions ->
        mock.canned["thank"] = """{"result":{"success":1}}"""
        mock.canned["shortenurl"] = """{"shortenurl":{"shorturl":"https://w.example/a"}}"""
        mock.canned["review"] = """{"review":{"revid":9}}"""

        extensions.thank(9.0).await()
        listOf(sent("thank")["rev"], sent("thank")["source"]) shouldBe listOf("9", "kwikibot")

        extensions.shortenUrl("https://example.org/long").await() shouldBe "https://w.example/a"

        extensions.review(9.0, optionsOf("accuracy" to 2), "fine").await()
        listOf(sent("review")["revid"], sent("review")["flag_accuracy"], sent("review")["comment"]) shouldBe
            listOf("9", "2", "fine")
    }

    @Test
    fun `flagged reads the reviewed state`() = runWiki { extensions ->
        mock.canned["flagged"] =
            page(
                """"flagged":{"stable_revid":8,"level":1,"level_text":"stable",
                   "pending_since":"2026-08-31T21:43:26Z"}"""
            )

        val found = extensions.flagged(arrayOf("Volcano")).await().single()

        listOf(
            found.title,
            found.stableRevisionId,
            found.level,
            found.levelText,
            found.hasPendingChanges,
        ) shouldBe listOf("Volcano", 8.0, 1, "stable", true)
        found.pendingSince?.getTime() shouldBe Date("2026-08-31T21:43:26Z").getTime()
    }

    @Test
    fun `abuseFilters sends its options and reads each filter`() = runWiki { extensions ->
        mock.canned["paraminfo"] = """{"paraminfo":{"modules":[]}}"""
        mock.canned["abusefilters"] =
            """{"query":{"abusefilters":[{"id":12,"description":"d","actions":["warn"],"pattern":"p",
               "comments":"c","hits":40,"lasteditor":"Alice","lastedittime":"2026-08-31T21:43:26Z",
               "enabled":true,"deleted":false,"private":true,"protected":false,"suppressed":false,
               "hitsredacted":true}]}}"""

        val filter =
            extensions
                .abuseFilters(
                    optionsOf(
                        "show" to arrayOf("enabled", "!deleted"),
                        "properties" to arrayOf("pattern", "hits", "last-editor", "flags"),
                        "order" to "highest-id-first",
                        "limit" to 3,
                    )
                )
                .take()
                .single()

        listOf(
            filter.id,
            filter.description,
            filter.pattern,
            filter.comments,
            filter.hits,
            filter.lastEditor,
        ) shouldBe listOf(12.0, "d", "p", "c", 40.0, "Alice")
        filter.actions.toList() shouldBe listOf("warn")
        filter.lastEdited?.getTime() shouldBe Date("2026-08-31T21:43:26Z").getTime()
        filter.redacted.toList() shouldBe listOf("hits")
        val flags = filter.flags!!
        listOf(
            flags.isEnabled,
            flags.isDeleted,
            flags.isPrivate,
            flags.isProtected,
            flags.isSuppressed,
        ) shouldBe listOf(true, false, true, false, false)
        val sent = mock.requests.last { it["list"] == "abusefilters" }
        listOf(sent["abfdir"], sent["abfshow"]) shouldBe listOf("older", "enabled|!deleted")
        sent["abfprop"]?.contains("lasteditor") shouldBe true
    }

    @Test
    fun `newPages sends its filters and reads each page`() = runWiki { extensions ->
        mock.canned["pagetriagelist"] =
            """{"pagetriagelist":{"pages":[{"pageid":"1","title":"Volcano","user_name":"Alice",
               "creation_date_utc":"20260831214326","creator_is_temp_account":"0","patrol_status":"2",
               "reviewer":"Bob","ptrp_reviewed_updated":"20260901000000","is_redirect":"0","linkcount":"0"}]}}"""

        val found =
            extensions
                .newPages(
                    optionsOf(
                        "namespace" to 2,
                        "reviewed" to "all",
                        "redirects" to true,
                        "creators" to arrayOf("Alice"),
                        "hideOwnPages" to true,
                        "order" to "oldest-first",
                        "limit" to 1,
                    )
                )
                .take()
                .single()

        listOf(
            found.creator,
            found.status,
            found.reviewer,
            found.creatorIsTemporary,
            found.isRedirect,
            found.isOrphan,
        ) shouldBe listOf("Alice", "patrolled", "Bob", false, false, true)
        found.title shouldBe "User:Volcano"
        found.created?.getTime() shouldBe Date("2026-08-31T21:43:26Z").getTime()
        found.reviewUpdated?.getTime() shouldBe Date("2026-09-01T00:00:00Z").getTime()
        val sent = sent("pagetriagelist")
        listOf(
            sent["namespace"],
            sent["showreviewed"],
            sent["showunreviewed"],
            sent["showothers"],
            sent["username"],
            sent["hideownpages"],
            sent["dir"],
        ) shouldBe listOf("2", "1", "1", null, "Alice", "1", "oldestfirst")

        extensions.newPages().take()
        sent("pagetriagelist")["showreviewed"] shouldBe null
        shouldThrow<IllegalArgumentException> { extensions.newPages(optionsOf("reviewed" to "maybe")) }
            .message shouldContain "'all'"
    }

    @Test
    fun `newPageStats reads the backlog`() = runWiki { extensions ->
        mock.canned["pagetriagestats"] =
            """{"pagetriagestats":{"stats":{"unreviewedarticle":{"count":3,"oldest":"20260831214326"},
               "unreviewedredirect":{"count":0,"oldest":"20260831214326"},"unrevieweddraft":{"count":1,"oldest":"20260831214326"},
               "reviewedarticle":{"reviewed_count":5},"reviewedredirect":{"reviewed_count":6},"filteredarticle":7}}}"""

        val stats =
            extensions
                .newPageStats(
                    optionsOf("namespace" to 0, "creators" to arrayOf("Alice"), "hideOwnPages" to true)
                )
                .await()

        stats.unreviewedArticles.count shouldBe 3.0
        stats.unreviewedArticles.oldest?.getTime() shouldBe Date("2026-08-31T21:43:26Z").getTime()
        stats.unreviewedRedirects.oldest shouldBe null
        stats.unreviewedDrafts?.count shouldBe 1.0
        listOf(stats.reviewedArticles, stats.reviewedRedirects, stats.matching) shouldBe listOf(5.0, 6.0, 7.0)
        sent("pagetriagestats")["username"] shouldBe "Alice"
    }

    private fun String.prefixed(): String = "\"$this"
}
