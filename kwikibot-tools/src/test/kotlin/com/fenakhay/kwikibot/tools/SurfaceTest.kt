package com.fenakhay.kwikibot.tools

import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.protocol.ParamInfo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class SurfaceTest {

    // Two wikis' answers for the same modules, in the shape paraminfo gives them with helpformat=none.
    private val commons =
        """{"paraminfo":{"modules":[
          {"name":"abusefilters","path":"query+abusefilters","group":"list","prefix":"abf","source":"Abuse Filter",
           "parameters":[
             {"name":"prop","type":["actions","flags","id","status"],"multi":true,"lowlimit":50,"highlimit":500,
              "limit":50,"default":"id|description|actions|flags","deprecatedvalues":["status"]},
             {"name":"limit","type":"limit","default":10,"max":500,"highmax":5000,"min":1}]},
          {"name":"edit","path":"edit","group":"action","prefix":"","source":"MediaWiki","writerights":true,
           "mustbeposted":true,"parameters":[
             {"name":"tags","type":["mw-replace","Rescaled"],"multi":true,"limit":50,"highlimit":500},
             {"name":"watchlist","type":["nochange","preferences","unwatch","watch"],"default":"preferences"}]},
          {"name":"wbeditentity","path":"wbeditentity","prefix":"","source":"WikibaseRepository",
           "parameters":[{"name":"new","type":["mediainfo"]}]}]}}"""

    private val wikidata =
        """{"paraminfo":{"modules":[
          {"name":"edit","path":"edit","group":"action","prefix":"","source":"MediaWiki","writerights":true,
           "mustbeposted":true,"parameters":[
             {"name":"tags","type":["mw-replace","Spell4Wiki"],"multi":true,"limit":50,"highlimit":500},
             {"name":"watchlist","type":["nochange","preferences","unwatch","watch"],"default":"preferences"}]},
          {"name":"wbeditentity","path":"wbeditentity","prefix":"","source":"WikibaseRepository",
           "parameters":[{"name":"new","type":["item","lexeme","property"]}]}]}}"""

    @Test
    fun `a row records the parameter as sent, with its limits told apart`() = runTest {
        val rows = rows(commons).associateBy { it.key }

        val prop = rows.getValue("query+abusefilters" to "abfprop")
        prop.render() shouldBe
            "query+abusefilters\tabfprop\tlist\tAbuse Filter\tmulti\tenum=actions|flags|id|status " +
                "default=id|description|actions|flags limit=50 highlimit=500 deprecatedvalues=status"

        rows.getValue("query+abusefilters" to "abflimit").detail shouldBe
            mapOf("type" to "limit", "default" to "10", "max" to "500", "highmax" to "5000")
        rows.getValue("edit" to NO_PARAMETER).render() shouldBe "edit\t-\taction\tMediaWiki\t\twrite post"
    }

    @Test
    fun `site configuration is recorded as such rather than value by value`() = runTest {
        val tags = rows(commons).single { it.key == ("edit" to "tags") }

        tags.detail["enum"] shouldBe CONFIG
        isConfiguration("query+allusers", "augroup") shouldBe true
        isConfiguration("query+allusers", "aurights") shouldBe true
        isConfiguration("wbgetentities", "sitefilter") shouldBe true
        isConfiguration("userrights", "add") shouldBe true
        isConfiguration("query+globalusage", "gusite") shouldBe true
        // Ends like a group list, and is a fixed set of three.
        isConfiguration("translationentitysearch", "grouptypes") shouldBe false
        isConfiguration("edit", "watchlist") shouldBe false
    }

    @Test
    fun `merging unites what each wiki accepts and says where a row is missing`() = runTest {
        val merged =
            Surface.merge(mapOf("commonswiki" to rows(commons), "wikidatawiki" to rows(wikidata)))
                .associateBy {
                    it.key
                }

        merged.getValue("wbeditentity" to "new").values("enum") shouldBe
            setOf("item", "lexeme", "mediainfo", "property")
        merged.getValue("wbeditentity" to "new").wikis shouldBe emptySet()
        merged.getValue("query+abusefilters" to "abfprop").wikis shouldBe setOf("commonswiki")
        merged.getValue("edit" to "tags").detail["enum"] shouldBe CONFIG
    }

    @Test
    fun `a file reads back as the rows it was written from`() = runTest {
        val rows = Surface.merge(mapOf("commonswiki" to rows(commons), "wikidatawiki" to rows(wikidata)))
        val text = Surface.render(rows)

        text.lines().first() shouldBe COLUMNS
        text shouldNotContain "\t\n"
        Surface.parse(text) shouldBe rows
    }

    @Test
    fun `a detail value may contain a space without being split`() {
        val header = COLUMNS.split('\t')
        val row = Row.parse(header, "parse\tpage\taction\tMediaWiki\t\ttype=string default=Main Page max=5")

        row.detail shouldBe mapOf("type" to "string", "default" to "Main Page", "max" to "5")
    }

    @Test
    fun `the file written before wikis were recorded still reads`() {
        val rows =
            Surface.parse(
                "module\tparameter\tgroup\tsource\tflags\tdetail\nedit\t-\taction\tMediaWiki\t\twrite post\n"
            )

        rows.single().detail shouldBe mapOf("write" to "", "post" to "")
        rows.single().wikis shouldBe emptySet()
    }

    @Test
    fun `a report sorts changes by what has to be done about them`() = runTest {
        val before =
            Surface.parse(
                """
                module	parameter	group	source	flags	detail	wikis
                aggregategroups	group	action	Translate	deprecated	type=string
                query+abusefilters	abfdir	list	Abuse Filter		enum=newer|older default=newer
                query+abusefilters	abfprop	list	Abuse Filter	multi	enum=id|private|status default=id|status
                pagetriagelist	username	action	PageTriage		type=user
                """
                    .trimIndent()
            )
        val after =
            Surface.parse(
                """
                module	parameter	group	source	flags	detail	wikis
                edit	checkuserclienthints	action	CheckUser	sensitive	type=string
                query+abusefilters	abfdir	list	Abuse Filter		enum=ascending|descending|newer|older default=ascending
                query+abusefilters	abfprop	list	Abuse Filter	multi	enum=flags|id|private|status default=id|flags deprecatedvalues=private|status
                pagetriagelist	username	action	PageTriage	multi	type=user limit=10 highlimit=10
                """
                    .trimIndent()
            )
        val usage =
            Usage.parse(
                """
                module	parameter	value	symbol
                query+abusefilters	abfdir	newer	AbuseFilterOrder.LOWEST_ID_FIRST
                query+abusefilters	abfprop	flags	Registry.ABUSE_FILTER_FLAGS
                query+abusefilters	abfprop	status	AbuseFilterProperty.STATUS
                query+abusefilters	abfprop	status	Registry.ABUSE_FILTER_FLAGS
                """
                    .trimIndent()
            )

        val report = SurfaceReport.compare(before, after, usage)

        report.deprecated.single().render() shouldBe
            "- `query+abusefilters abfprop`: private, status — **used by kwikibot**: " +
                "AbuseFilterProperty.STATUS, Registry.ABUSE_FILTER_FLAGS"
        report.removed.single().render() shouldBe "- `aggregategroups group`"
        report.added.single().render() shouldBe
            "- `edit checkuserclienthints`: (CheckUser) sensitive type=string"
        report.changed.map { it.render() } shouldBe
            listOf(
                "- `pagetriagelist username`: flags +multi; limit (none) → 10; highlimit (none) → 10",
                "- `query+abusefilters abfdir`: enum +ascending +descending; default newer → ascending — " +
                    "**used by kwikibot**: AbuseFilterOrder",
                "- `query+abusefilters abfprop`: enum +flags; default id|status → id|flags — " +
                    "**used by kwikibot**: AbuseFilterProperty, Registry",
            )

        val markdown = report.render("API surface: production", "api-surface.tsv")
        markdown shouldContain "1 added, 1 removed, 3 changed, 1 newly deprecated"
        markdown.indexOf("### Newly deprecated") shouldBe markdown.indexOf("###")
    }

    @Test
    fun `a value kwikibot sends going away is named, not just its parameter`() {
        val header = COLUMNS.split('\t')
        val before =
            listOf(Row.parse(header, "parse\tprop\taction\tMediaWiki\tmulti\tenum=sections|text|tocdata"))
        val after = listOf(Row.parse(header, "parse\tprop\taction\tMediaWiki\tmulti\tenum=text|tocdata"))
        val usage =
            Usage.parse("module\tparameter\tvalue\tsymbol\nparse\tprop\tsections\tParseProperty.SECTIONS\n")

        SurfaceReport.compare(before, after, usage).changed.single().usedBy shouldBe
            listOf("ParseProperty.SECTIONS")
    }

    @Test
    fun `nothing to report says so`() {
        val rows = Surface.parse("$COLUMNS\nedit\t-\taction\tMediaWiki\n")

        val report = SurfaceReport.compare(rows, rows)

        report.isEmpty shouldBe true
        report.render("API surface: production", "api-surface.tsv") shouldContain
            "matches the reference wikis"
    }

    private suspend fun TestScope.rows(paramInfo: String): List<Row> {
        val transport =
            KtorTransport(
                client =
                    HttpClient(
                        MockEngine {
                            respond(
                                paramInfo,
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    ),
                endpoint = ApiEndpoint("commons.wikimedia.org"),
                userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
                retry = RetryPolicy.NONE,
            )
        return Surface.rows(ParamInfo(transport).modules("main", "*", "query+*"))
    }
}
