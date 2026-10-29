package com.fenakhay.kwikibot.model

import com.fenakhay.kwikibot.model.page.ContentModel
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class WikiErrorTest {
    private val title = Title.Local(Namespace.MAIN, "volcano")

    @Test
    fun `waiting on a replica is transient so a run should come back to it`() {
        WikiError.Transport.Maxlag(5.seconds, "waiting for a replica").isTransient shouldBe true
    }

    @Test
    fun `a page the wiki will refuse the same way forever is not transient`() {
        WikiError.Page.UnsupportedContentModel(title, ContentModel.CSS).isTransient shouldBe false
        WikiError.Page.UnresolvableRedirect(title, "loops").isTransient shouldBe false
        WikiError.Auth.NotLoggedIn("editing").isTransient shouldBe false
        WikiError.Configuration.MissingExtension("ProofreadPage").isTransient shouldBe false
        WikiError.Configuration.Unsupported("parse prop", listOf("tocdata")).isTransient shouldBe false
    }

    @Test
    fun `a transport failure names the endpoint and keeps what caused it`() {
        val url = "https://en.wiktionary.org/w/api.php"
        val cause = IllegalStateException("socket closed")

        val timeout = WikiError.Transport.Timeout(url, cause)
        timeout.message shouldBe "request to $url timed out"
        timeout.url shouldBe url
        timeout.cause shouldBe cause
        timeout.isTransient shouldBe true
        WikiError.Transport.Timeout(url).cause shouldBe null

        WikiError.Transport.Unreachable(url, cause).message shouldBe "cannot reach $url"
        WikiError.Transport.ServerError(503, url).message shouldBe "server error 503 from $url"
    }

    @Test
    fun `a rate limit mentions the wait only when the server named one`() {
        WikiError.Transport.RateLimited(30.seconds).message shouldBe "rate limited, retry after 30s"
        WikiError.Transport.RateLimited(null).message shouldBe "rate limited"
        WikiError.Transport.RateLimited(null).isTransient shouldBe true
    }

    @Test
    fun `replication lag names the replica only when the wiki did`() {
        WikiError.Transport.Maxlag(5.seconds, "db1234").message shouldBe "database lag of 5s on db1234"
        WikiError.Transport.Maxlag(5.seconds, null).message shouldBe "database lag of 5s"
    }

    @Test
    fun `a rejected token is worth one more try since tokens expire`() {
        val error = WikiError.Auth.BadToken("csrf")

        error.isTransient shouldBe true
        error.message shouldBe "token 'csrf' rejected"
    }

    @Test
    fun `a wiki with no credentials configured is named and is not worth retrying`() {
        val error = WikiError.Auth.NoCredentials(WikiId("enwiktionary"))

        error.message shouldBe "no credentials configured for enwiktionary"
        error.wiki shouldBe WikiId("enwiktionary")
        error.isTransient shouldBe false
    }

    @Test
    fun `a page error names the title it is about`() {
        WikiError.Page.NotRedirect(title).message shouldBe "page is not a redirect: volcano"

        val invalid = Title.Invalid("foo[bar", Title.Invalid.Reason.ILLEGAL_CHARACTER)
        WikiError.Page.BadTitle(invalid).message shouldBe "invalid title: foo[bar (ILLEGAL_CHARACTER)"

        val elsewhere = Title.Interwiki("w", "Etsy")
        WikiError.Page.OffWiki(elsewhere).message shouldBe "title targets another wiki: w:Etsy"
        WikiError.Page.OffWiki(elsewhere).title shouldBe elsewhere
    }

    @Test
    fun `a site nobody knows is named by its code and family`() {
        val error = WikiError.Configuration.UnknownSite(LangCode("xx"), "wiktionary")

        error.message shouldBe "no wiki known for xx in family wiktionary"
        error.code shouldBe LangCode("xx")
        error.family shouldBe "wiktionary"
    }

    @Test
    fun `a wiki that takes none of the spellings says which were tried`() {
        val error = WikiError.Configuration.Unsupported("parse prop", listOf("tocdata", "sections"))

        error.message shouldBe "wiki accepts none of tocdata, sections for parse prop"
    }
}
