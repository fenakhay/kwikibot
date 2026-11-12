import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.ContentModel
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Title as ParsedTitle
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.js.Date
import kotlin.js.RegExp
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class ModelTest {

    @Test
    fun `a namespace says what it is, and the core ones are named`() {
        listOf(
            NAMESPACE_MEDIA,
            NAMESPACE_SPECIAL,
            NAMESPACE_MAIN,
            NAMESPACE_TALK,
            NAMESPACE_USER,
            NAMESPACE_USER_TALK,
            NAMESPACE_PROJECT,
            NAMESPACE_PROJECT_TALK,
            NAMESPACE_FILE,
            NAMESPACE_FILE_TALK,
            NAMESPACE_MEDIAWIKI,
            NAMESPACE_MEDIAWIKI_TALK,
            NAMESPACE_TEMPLATE,
            NAMESPACE_TEMPLATE_TALK,
            NAMESPACE_HELP,
            NAMESPACE_HELP_TALK,
            NAMESPACE_CATEGORY,
            NAMESPACE_CATEGORY_TALK,
        ) shouldBe (-2..15).toList()

        val template = Namespace(NAMESPACE_TEMPLATE)
        listOf(template.id, template.isTalk, template.isVirtual) shouldBe listOf(10, false, false)
        listOf(template.talkSpace, template.subjectSpace) shouldBe listOf(11, 10)
        Namespace(11).isTalk shouldBe true
        Namespace(11).subjectSpace shouldBe 10
        Namespace(-1).isVirtual shouldBe true
        Namespace(-1).talkSpace shouldBe null
        template.toString() shouldBe "ns:10"
    }

    @Test
    fun `namespaces are built and titles read against them`() {
        val namespaces =
            createNamespaces(
                arrayOf(
                    optionsOf("id" to 0, "canonicalName" to "", "localName" to ""),
                    optionsOf(
                        "id" to 14,
                        "canonicalName" to "Category",
                        "localName" to "Catégorie",
                        "aliases" to arrayOf("Cat"),
                        "case" to "case-sensitive",
                        "subpages" to true,
                    ),
                )
            )
        val interwiki = createInterwiki(arrayOf("w", "fr"), arrayOf("fr"))
        listOf(interwiki.has("w"), interwiki.isSelf("fr")) shouldBe listOf(true, true)

        val category = namespaces.get(14) ?: error("no category")
        listOf(category.case, category.subpages) shouldBe listOf("case-sensitive", true)
        namespaces.byPrefix("cat")?.id shouldBe 14

        val read = parseTitleWith("cat:lave", optionsOf("namespaces" to namespaces, "interwiki" to interwiki))
        listOf(read.namespace, read.text) shouldBe listOf(14, "lave")
        namespaces.format(read) shouldBe "Catégorie:lave"
        read.toString() shouldBe "Catégorie:lave"

        val elsewhere = parseTitleWith("w:Lave", optionsOf("interwiki" to interwiki))
        listOf(elsewhere.kind, elsewhere.interwiki, elsewhere.rest) shouldBe listOf("interwiki", "w", "Lave")
        shouldThrow<IllegalArgumentException> { namespaces.format(elsewhere) }

        parseTitleWith("Lava", optionsOf("defaultNamespace" to NAMESPACE_TEMPLATE)).namespace shouldBe 10
        parseTitleWith("Lava").namespace shouldBe 0
        canonicalNamespaces().get(NAMESPACE_FILE)?.prefixes?.toList() shouldBe listOf("File", "Image")
        shouldThrow<IllegalArgumentException> {
            createNamespaces(arrayOf(optionsOf("id" to 1, "case" to "shouting")))
        }
        TITLE_MAX_BYTES shouldBe 255
    }

    @Test
    fun `a title renders as a link`() {
        val category = parseTitle("Category:Volcano (film)")
        category.render() shouldBe "[[Category:Volcano (film)]]"
        category.render("seen", true) shouldBe "[[:Category:Volcano (film)|seen]]"
        category.renderPiped() shouldBe "[[Category:Volcano (film)|Volcano]]"
        parseTitle("[bad]").render() shouldBe null
        parseTitle("[bad]").renderPiped() shouldBe null
        parseTitle("[bad]").raw shouldBe "[bad]"
    }

    @Test
    fun `timestamps read in both forms and write in either`() {
        val iso = "2026-08-31T21:43:26Z"
        parseTimestamp(iso).getTime() shouldBe Date(iso).getTime()
        parseTimestamp("20260831214326").getTime() shouldBe Date(iso).getTime()
        parseTimestampOrNull("soon") shouldBe null
        parseTimestampOrNull(iso)?.getTime() shouldBe Date(iso).getTime()
        shouldThrow<IllegalArgumentException> { parseTimestamp("soon") }
        isNeverTimestamp("infinity") shouldBe true
        isNeverTimestamp(iso) shouldBe false
        formatTimestamp(Date("2026-08-31T21:43:26.500Z")) shouldBe iso
        formatTimestampCompact(Date(iso)) shouldBe "20260831214326"
    }

    @Test
    fun `a link to another wiki renders forced or not`() {
        val link = SiteLink("fr", "Volcan", "ici")
        listOf(link.prefix, link.title, link.text) shouldBe listOf("fr", "Volcan", "ici")
        link.render() shouldBe "[[fr:Volcan|ici]]"
        link.render(forced = true) shouldBe "[[:fr:Volcan|ici]]"
        SiteLink("fr", "Volcan").toString() shouldBe "[[fr:Volcan]]"
    }

    @Test
    fun `day pages are titled and read in each language`() {
        dayTitle(1, 1, "fr") shouldBe "1er janvier"
        dayTitle(1, 31) shouldBe "January 31"
        dayTitle(1, 1, "xx") shouldBe null
        shouldThrow<IllegalArgumentException> { dayTitle(2, 30) }
        val read = parseDayTitle("1er janvier", "fr") ?: error("not a day")
        listOf(read.month, read.day) shouldBe listOf(1, 1)
        parseDayTitle("Lava") shouldBe null
        daysInMonth(2) shouldBe 29
        ("en" in dayTitleLanguages()) shouldBe true
        dayFormat("xx") shouldBe null
        val french = dayFormat("fr") ?: error("no French")
        listOf(french.pattern, french.firstOrdinal) shouldBe listOf("\$ordinal \$month", "er")
        french.months.first() shouldBe "janvier"

        registerDayFormat("zz", DayFormat(Array(12) { "m${it + 1}" }, "\$day-\$month"))
        dayTitle(3, 4, "zz") shouldBe "4-m3"
    }

    @Test
    fun `signatures are found by the formats shipped`() {
        val text = "first 21:43, 31 August 2026 (UTC) then 22:43, 31 August 2026 (UTC)"
        val english = signaturesFor("en")
        english.findAll(text).size shouldBe 2
        english.latest(text)?.text shouldBe "22:43, 31 August 2026 (UTC)"
        english.earliest(text)?.text shouldBe "21:43, 31 August 2026 (UTC)"
        english.latest("nothing") shouldBe null
        allSignatures().formats.size shouldBe english.formats.size + 3
        signaturesFor("fr").plus(english).formats.size shouldBe english.formats.size + 1
        (english.patterns().first() as Any is RegExp) shouldBe true
        english.formats.first().findAll(text).size shouldBe 2
        english.formats.first().pattern.toString() shouldContain "UTC"
    }

    @Test
    fun `a wiki's own signature format can be built`() {
        val format =
            signatureFormat(
                "xx",
                RegExp(
                    "(?<day>\\d+)/(?<month>\\p{L}+)/(?<year>\\d{4}) (?<hour>\\d\\d):(?<minute>\\d\\d) XT",
                    "u",
                ),
                recordOf("lune" to 3),
                "Europe/Paris",
            )
        val found = signaturesOf(arrayOf(format)).findAll("say 4/lune/2026 12:00 XT").single()

        found.language shouldBe "xx"
        found.date.toISOString() shouldBe "2026-03-04T11:00:00.000Z"
    }

    @Test
    fun `a wiki gives its own signatures, months, day format and template names`() = runTest {
        val mock = MockWiki(mapOf("Volcano" to "x"))
        mock.canned["redirects"] =
            """{"query":{"pages":[{"pageid":3,"ns":10,"title":"Template:L","redirects":[
              {"pageid":4,"ns":10,"title":"Template:Link"}]}]}}"""
        val wiki = mock.open()

        wiki.signatures().await().findAll("31 août 2026 à 21:43 (CEST)").single().date.toISOString() shouldBe
            "2026-08-31T19:43:00.000Z"
        wiki.monthNames().await().toMap()["août"] shouldBe 8
        wiki.dayTitleFormat().await()?.months?.first() shouldBe "janvier"
        wiki.templateNames("l").await().toList() shouldBe listOf("L", "Link")
        wiki.close()
    }

    @Test
    fun `a wiki answers what it has and refuses what it lacks`() = runTest {
        val mock = MockWiki()
        mock.extensions += "Echo"
        val wiki = mock.open(BotPassword("Bot", "task", "secret"))

        wiki.hasRight("edit").await() shouldBe true
        wiki.hasRight("delete").await() shouldBe false
        wiki.requireRight("edit").await()
        describeError(shouldThrow<Throwable> { wiki.requireRight("delete").await() })?.right shouldBe "delete"
        wiki.requireGroup("bot")
        describeError(shouldThrow<Throwable> { wiki.requireGroup("sysop") })?.kind shouldBe
            "permission-denied"
        wiki.hasExtension("Echo") shouldBe true
        wiki.requireExtension("Echo")
        describeError(shouldThrow<Throwable> { wiki.requireExtension("Nope") })?.extension shouldBe "Nope"
        wiki.hasVersion("1.47.0") shouldBe true
        wiki.hasVersion("1.48.0") shouldBe false
        wiki.requireVersion("1.40")
        val old = describeError(shouldThrow<Throwable> { wiki.requireVersion("1.48") })
        listOf(old?.kind, old?.requiredVersion, old?.actualVersion) shouldBe
            listOf("version-too-old", "1.48", "1.47.0")
        wiki.close()
    }

    private val lava = ParsedTitle.Local(com.fenakhay.kwikibot.model.title.Namespace.MAIN, "Lava")

    private fun described(error: Throwable): ErrorInfo = describeError(error) ?: error("not described")

    @Test
    fun `only this library's errors are described`() {
        describeError(IllegalStateException("x")) shouldBe null
        describeError(null) shouldBe null
    }

    @Test
    fun `a transport error says what became of the request`() {
        val timeout = described(WikiError.Transport.Timeout("u"))
        listOf(timeout.kind, timeout.url, timeout.isTransient) shouldBe listOf("timeout", "u", true)
        described(WikiError.Transport.Unreachable("u")).let {
            listOf(it.kind, it.url) shouldBe listOf("unreachable", "u")
        }
        val server = described(WikiError.Transport.ServerError(500, "u"))
        listOf(server.kind, server.status, server.url) shouldBe listOf("server-error", 500, "u")
        val refused = described(WikiError.Transport.Refused(403, "u", "d"))
        listOf(refused.kind, refused.status, refused.detail, refused.url, refused.isTransient) shouldBe
            listOf("refused", 403, "d", "u", false)
        val limited = described(WikiError.Transport.RateLimited(2.seconds))
        listOf(limited.kind, limited.retryAfter) shouldBe listOf("rate-limited", 2000.0)
        val lagged = described(WikiError.Transport.Maxlag(3.seconds, "db1"))
        listOf(lagged.kind, lagged.lag, lagged.host) shouldBe listOf("maxlag", 3000.0, "db1")
    }

    @Test
    fun `an API or account error says what the wiki said`() {
        val api = described(WikiError.Api("c", "i", "m"))
        listOf(api.kind, api.code, api.info, api.module) shouldBe listOf("api", "c", "i", "m")
        api.message shouldContain "API error"
        listOf(api.url, api.status, api.detail, api.title) shouldBe listOf(null, null, null, null)
        described(WikiError.Auth.NotLoggedIn("edit")).let {
            listOf(it.kind, it.action) shouldBe listOf("not-logged-in", "edit")
        }
        described(WikiError.Auth.LoginFailed("r")).let {
            listOf(it.kind, it.detail) shouldBe listOf("login-failed", "r")
        }
        described(WikiError.Auth.NoCredentials(WikiId("w"))).let {
            listOf(it.kind, it.wiki) shouldBe listOf("no-credentials", "w")
        }
        described(WikiError.Auth.MissingRight("r")).let {
            listOf(it.kind, it.right) shouldBe listOf("missing-right", "r")
        }
        described(WikiError.Auth.PermissionDenied("d")).let {
            listOf(it.kind, it.detail) shouldBe listOf("permission-denied", "d")
        }
        described(WikiError.Auth.AccountBlocked("d")).let {
            listOf(it.kind, it.detail) shouldBe listOf("account-blocked", "d")
        }
        described(WikiError.Auth.BadToken("csrf")).let {
            listOf(it.kind, it.tokenType) shouldBe listOf("bad-token", "csrf")
        }
        described(WikiError.ReadOnly("r")).let {
            listOf(it.kind, it.detail) shouldBe listOf("read-only", "r")
        }
    }

    @Test
    fun `a page error says which page`() {
        described(WikiError.Page.Missing(lava)).let {
            listOf(it.kind, it.title) shouldBe listOf("missing-page", "Lava")
        }
        described(WikiError.Page.NotRedirect(lava)).let {
            listOf(it.kind, it.title) shouldBe listOf("not-redirect", "Lava")
        }
        described(WikiError.Page.UnresolvableRedirect(lava, "d")).let {
            listOf(it.kind, it.title, it.detail) shouldBe listOf("unresolvable-redirect", "Lava", "d")
        }
        val invalid = ParsedTitle.Invalid("[x]", ParsedTitle.Invalid.Reason.ILLEGAL_CHARACTER)
        described(WikiError.Page.BadTitle(invalid)).let {
            listOf(it.kind, it.title, it.titleProblem) shouldBe
                listOf("bad-title", "[x]", "illegal-character")
        }
        described(WikiError.Page.OffWiki(ParsedTitle.Interwiki("w", "Lava"))).let {
            listOf(it.kind, it.title) shouldBe listOf("off-wiki", "w:Lava")
        }
        described(WikiError.Page.UnsupportedContentModel(lava, ContentModel.JSON)).let {
            listOf(it.kind, it.title, it.contentModel) shouldBe
                listOf("unsupported-content-model", "Lava", "json")
        }
    }

    @Test
    fun `a configuration error says what is lacking`() {
        described(WikiError.Configuration.UnknownSite(LangCode("xx"), "f")).let {
            listOf(it.kind, it.language, it.family) shouldBe listOf("unknown-site", "xx", "f")
        }
        described(WikiError.Configuration.MissingExtension("e")).let {
            listOf(it.kind, it.extension) shouldBe listOf("missing-extension", "e")
        }
        described(WikiError.Configuration.VersionTooOld("2", "1")).let {
            listOf(it.kind, it.requiredVersion, it.actualVersion) shouldBe listOf("version-too-old", "2", "1")
        }
        described(WikiError.Configuration.Unsupported("p", listOf("a"))).let {
            listOf(it.kind, it.parameter, it.alternatives?.toList()) shouldBe
                listOf("unsupported", "p", listOf("a"))
        }
    }

    @Test
    fun `a parse that would not write back says where`() {
        describeError(com.fenakhay.kwikibot.wikitext.WikitextRoundTripException(4, "m"))?.let {
            listOf(it.kind, it.offset) shouldBe listOf("round-trip", 4)
        } ?: error("not described")
    }
}
