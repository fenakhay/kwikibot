package com.fenakhay.kwikibot.tools

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class ApiSurfaceTest {

    private val directory = Files.createTempDirectory("kwikibot-surface")
    private val target = directory.resolve("api-surface.tsv")

    // What every wiki reports, in the shape paraminfo gives it with helpformat=none. `main` is in no group,
    // and the empty default of `title` is recorded as no default at all.
    private val modules =
        """
        {"name":"main","path":"main","prefix":"","source":"MediaWiki","parameters":[
           {"name":"action","type":["edit","help","query"],"default":"help"}]},
        {"name":"edit","path":"edit","group":"action","prefix":"","source":"MediaWiki","writerights":true,
         "mustbeposted":true,"parameters":[
           {"name":"title","type":"string","required":true,"default":""},
           {"name":"token","type":"string","required":true,"sensitive":true}]},
        {"name":"oldlist","path":"query+oldlist","group":"list","prefix":"ol","source":"MediaWiki",
         "deprecated":true,"internal":true,"parameters":[
           {"name":"mode","type":["a","b","c"],"deprecated":true,"deprecatedvalues":["a"],
            "internalvalues":["c"]}]}
        """

    // And what only Wikidata adds.
    private val wikibase =
        """
        {"name":"wbgetentities","path":"wbgetentities","group":"action","prefix":"",
         "source":"WikibaseRepository","parameters":[{"name":"sites","type":["enwiki","frwiki"],"multi":true}]}
        """

    private val surface =
        """
        module	parameter	group	source	flags	detail	wikis
        edit	-	action	MediaWiki		write post
        edit	title	action	MediaWiki	required	type=string
        edit	token	action	MediaWiki	required,sensitive	type=string
        main	-		MediaWiki
        main	action		MediaWiki		enum=edit|help|query default=help
        query+oldlist	-	list	MediaWiki	deprecated,internal	prefix=ol
        query+oldlist	olmode	list	MediaWiki	deprecated	enum=a|b|c deprecatedvalues=a internalvalues=c
        wbgetentities	-	action	WikibaseRepository			wikidatawiki
        wbgetentities	sites	action	WikibaseRepository	multi	enum=<config>	wikidatawiki

        """
            .trimIndent()

    // What was recorded before the wikis changed: `summary` since gone, `token` not there yet, and nothing of
    // `query+oldlist` deprecated.
    private val earlier =
        """
        module	parameter	group	source	flags	detail	wikis
        edit	-	action	MediaWiki		write post
        edit	summary	action	MediaWiki		type=string
        edit	title	action	MediaWiki	required	type=string
        main	-		MediaWiki
        main	action		MediaWiki		enum=edit|help|query default=help
        query+oldlist	-	list	MediaWiki	internal	prefix=ol
        query+oldlist	olmode	list	MediaWiki		enum=a|b|c internalvalues=c
        wbgetentities	-	action	WikibaseRepository			wikidatawiki
        wbgetentities	sites	action	WikibaseRepository	multi	enum=<config>	wikidatawiki
        """
            .trimIndent()

    @Test
    fun `a dump writes what every production wiki reports as one file`() = runTest {
        val wikis = wikis()

        val status = writeOrCheckSurface(arrayOf(target.toString()), wikis.environment)

        status shouldBe 0
        target.readText() shouldBe surface
        wikis.output.toString() shouldBe "wrote $target\n"
        wikis.sent.map { it.host } shouldBe
            listOf(
                "en.wikipedia.org",
                "en.wiktionary.org",
                "commons.wikimedia.org",
                "www.wikidata.org",
                "en.wikisource.org",
            )
        wikis.log.toString() shouldContain "www.wikidata.org: 4 modules\n"
    }

    @Test
    fun `the whole surface is asked for in one request, and lag does not hold it back`() = runTest {
        val wikis = wikis()

        writeOrCheckSurface(arrayOf(target.toString()), wikis.environment)

        val request = wikis.sent.first()
        request["action"] shouldBe "paraminfo"
        request["modules"] shouldBe "main|*|query+*"
        request["helpformat"] shouldBe "none"
        request["maxlag"] shouldBe null
    }

    @Test
    fun `a check that finds production drifting prints the report, keeps it, and fails`() = runTest {
        target.writeText(earlier)
        val registry = directory.resolve("wire-registry.tsv")
        registry.writeText("module\tparameter\tvalue\tsymbol\nedit\tsummary\t\tEditRequest.summary\n")
        val report = directory.resolve("reports/api-surface/production.md")
        val summary = directory.resolve("summary.md")
        summary.writeText("earlier step\n")
        val wikis = wikis()
        wikis.variables["GITHUB_STEP_SUMMARY"] = summary.toString()

        val status =
            writeOrCheckSurface(
                arrayOf(
                    target.toString(),
                    "--check",
                    "--registry",
                    registry.toString(),
                    "--report",
                    report.toString(),
                ),
                wikis.environment,
            )

        val markdown =
            """
            ## API surface: production

            `api-surface.tsv` differs from what the reference wikis report: 1 added, 1 removed, 0 changed, 2 newly deprecated.

            ### Newly deprecated

            - `query+oldlist` (module): the whole module
            - `query+oldlist olmode`: the whole parameter; a

            ### Removed

            - `edit summary` — **used by kwikibot**: EditRequest

            ### Added

            - `edit token`: (MediaWiki) required,sensitive type=string

            Review the lines above, then run `./gradlew wikiApiDump`.

            """
                .trimIndent()
        status shouldBe 1
        wikis.output.toString() shouldBe "$markdown\n"
        report.readText() shouldBe markdown
        summary.readText() shouldBe "earlier step\n$markdown\n"
        // A check reads the file; only a dump writes it.
        target.readText() shouldContain "edit\tsummary"
    }

    @Test
    fun `a check that matches says so and passes`() = runTest {
        target.writeText(surface)
        val wikis = wikis()

        val status = writeOrCheckSurface(arrayOf(target.toString(), "--check"), wikis.environment)

        status shouldBe 0
        wikis.output.toString() shouldBe
            "## API surface: production\n\n`api-surface.tsv` matches the reference wikis.\n\n"
    }

    @Test
    fun `without a file named, a check reads api-surface tsv in the working directory`() = runTest {
        val production = wikis()
        production.workingDirectory.resolve("api-surface.tsv").writeText(surface)
        writeOrCheckSurface(arrayOf("--check"), production.environment)

        production.output.toString() shouldContain "`api-surface.tsv` matches"
    }

    @Test
    fun `without a file named, a dump writes api-surface tsv in the working directory`() = runTest {
        val wikis = wikis()

        writeOrCheckSurface(arrayOf(), wikis.environment)

        val written = wikis.workingDirectory.resolve("api-surface.tsv")
        written.readText() shouldBe surface
        wikis.output.toString() shouldBe "wrote $written\n"
    }

    @Test
    fun `a check takes a relative registry and report from the working directory`() = runTest {
        val wikis = wikis()
        wikis.workingDirectory.resolve("api-surface.tsv").writeText(earlier)
        wikis.workingDirectory
            .resolve("wire-registry.tsv")
            .writeText("module\tparameter\tvalue\tsymbol\nedit\tsummary\t\tEditRequest.summary\n")

        writeOrCheckSurface(
            arrayOf("--check", "--registry", "wire-registry.tsv", "--report", "reports/production.md"),
            wikis.environment,
        )

        wikis.workingDirectory.resolve("reports/production.md").readText() shouldContain
            "- `edit summary` — **used by kwikibot**: EditRequest"
    }

    @Test
    fun `a wiki that cannot be read is one line, in the log and the report, and fails the run`() = runTest {
        target.writeText(surface)
        val report = directory.resolve("reports/production.md")
        val wikis =
            FakeWikis(this) { sent ->
                if (sent.host == "en.wiktionary.org") {
                    respond(
                        "<html><body>Forbidden by policy</body></html>",
                        HttpStatusCode.Forbidden,
                        headersOf(HttpHeaders.ContentType, "text/html"),
                    )
                } else {
                    respondJson(paramInfo(modules))
                }
            }

        val status =
            writeOrCheckSurface(
                arrayOf(target.toString(), "--check", "--report", report.toString()),
                wikis.environment,
            )

        val line =
            "API surface: production: could not read the surface: " +
                "HTTP 403 from https://en.wiktionary.org/w/api.php: Forbidden by policy"
        status shouldBe 1
        // The wikis before it were read; the one that refused is the last thing said.
        wikis.log.toString() shouldBe "en.wikipedia.org: 3 modules\n$line\n"
        report.readText() shouldBe "$line\n"
        wikis.output.toString() shouldBe ""
    }

    @Test
    fun `a dump that cannot read a wiki leaves the file alone`() = runTest {
        val wikis =
            FakeWikis(this) {
                respondJson("""{"errors":[{"code":"unknown_action","text":"Unrecognized value."}]}""")
            }

        val status = writeOrCheckSurface(arrayOf(target.toString()), wikis.environment)

        status shouldBe 1
        wikis.log.toString() shouldBe
            "API surface: production: could not read the surface: " +
                "API error [unknown_action]: Unrecognized value.\n"
        target.exists() shouldBe false
    }

    private fun TestScope.wikis() =
        FakeWikis(this) { sent ->
            respondJson(paramInfo(if ("wikidata" in sent.host) "$modules,$wikibase" else modules))
        }

    private fun paramInfo(modules: String) = """{"paraminfo":{"modules":[$modules]}}"""
}
