package com.fenakhay.kwikibot.bindings.errors

import com.fenakhay.kwikibot.client.model.dump.DumpFormatException
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.ContentModel
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.wikitext.WikitextRoundTripException
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** Every kind of error `describeError` names, as `kwikibot.d.ts` documents its fields. */
class ErrorsTest {

    private val page = Title.Local(Namespace.MAIN, "Volcano")
    private val url = "https://test.example.org/w/api.php"

    private fun described(error: Throwable, kind: String, transient: Boolean = false) =
        ErrorDescription(kind, error.message.orEmpty(), transient)

    private val table: List<Pair<Throwable, (ErrorDescription) -> ErrorDescription>> =
        listOf(
            WikiError.Transport.Timeout(url) to { it.copy(url = url) },
            WikiError.Transport.Unreachable(url) to { it.copy(url = url) },
            WikiError.Transport.ServerError(503, url) to { it.copy(url = url, status = 503) },
            WikiError.Transport.Refused(403, url, "go away") to
                {
                    it.copy(url = url, status = 403, detail = "go away")
                },
            WikiError.Transport.RateLimited(30.seconds) to { it.copy(retryAfter = 30.seconds) },
            WikiError.Transport.RateLimited(null) to { it },
            WikiError.Transport.Maxlag(6.seconds, "db1") to { it.copy(lag = 6.seconds, host = "db1") },
            WikiError.Api("badvalue", "Bad value.", "query") to
                {
                    it.copy(code = "badvalue", info = "Bad value.", module = "query")
                },
            WikiError.Auth.NotLoggedIn("edit") to { it.copy(action = "edit") },
            WikiError.Auth.LoginFailed("Incorrect password.") to { it.copy(detail = "Incorrect password.") },
            WikiError.Auth.NoCredentials(WikiId("frwiki")) to { it.copy(wiki = "frwiki") },
            WikiError.Auth.MissingRight("delete") to { it.copy(right = "delete") },
            WikiError.Auth.PermissionDenied("no") to { it.copy(detail = "no") },
            WikiError.Auth.AccountBlocked("blocked") to { it.copy(detail = "blocked") },
            WikiError.Auth.BadToken("csrf") to { it.copy(tokenType = "csrf") },
            WikiError.Page.Missing(page) to { it.copy(title = page.toString()) },
            WikiError.Page.NotRedirect(page) to { it.copy(title = page.toString()) },
            WikiError.Page.UnresolvableRedirect(page, "loop") to
                {
                    it.copy(title = page.toString(), detail = "loop")
                },
            WikiError.Page.BadTitle(Title.Invalid("[x]", Title.Invalid.Reason.EMPTY)) to
                {
                    it.copy(title = "[x]", titleProblem = Title.Invalid.Reason.EMPTY)
                },
            WikiError.Page.OffWiki(Title.Interwiki("w", "Foo")) to
                {
                    it.copy(title = Title.Interwiki("w", "Foo").toString())
                },
            WikiError.Page.UnsupportedContentModel(page, ContentModel("json")) to
                {
                    it.copy(title = page.toString(), contentModel = "json")
                },
            WikiError.ReadOnly("maintenance") to { it.copy(detail = "maintenance") },
            WikiError.Configuration.UnknownSite(LangCode("xx"), "wikipedia") to
                {
                    it.copy(language = "xx", family = "wikipedia")
                },
            WikiError.Configuration.MissingExtension("Echo") to { it.copy(extension = "Echo") },
            WikiError.Configuration.VersionTooOld("1.45", "1.39") to
                {
                    it.copy(requiredVersion = "1.45", actualVersion = "1.39")
                },
            WikiError.Configuration.Unsupported("parse prop", listOf("a", "b")) to
                {
                    it.copy(parameter = "parse prop", alternatives = listOf("a", "b"))
                },
            DumpFormatException(DumpFormatException.Code.BAD_CHECKSUM, "bad") to
                {
                    it.copy(dumpProblem = DumpFormatException.Code.BAD_CHECKSUM)
                },
            WikitextRoundTripException(12, "differs") to { it.copy(offset = 12) },
        )

    private val kinds =
        listOf(
            "timeout",
            "unreachable",
            "server-error",
            "refused",
            "rate-limited",
            "rate-limited",
            "maxlag",
            "api",
            "not-logged-in",
            "login-failed",
            "no-credentials",
            "missing-right",
            "permission-denied",
            "account-blocked",
            "bad-token",
            "missing-page",
            "not-redirect",
            "unresolvable-redirect",
            "bad-title",
            "off-wiki",
            "unsupported-content-model",
            "read-only",
            "unknown-site",
            "missing-extension",
            "version-too-old",
            "unsupported",
            "dump-format",
            "round-trip",
        )

    @Test
    fun `every error of this library is described with its kind and its own fields`() {
        table.size shouldBe kinds.size
        table.zip(kinds).forEach { (case, kind) ->
            val (error, fields) = case
            val transient = (error as? WikiError)?.isTransient == true
            describe(error) shouldBe fields(described(error, kind, transient))
        }
    }

    @Test
    fun `whether an error is transient is the wiki's say`() {
        describe(WikiError.Transport.Timeout(url))?.isTransient shouldBe true
        describe(WikiError.Auth.MissingRight("delete"))?.isTransient shouldBe false
    }

    @Test
    fun `anything else is not described`() {
        describe(IllegalArgumentException("mistaken")) shouldBe null
        describe(IllegalStateException("not this library's")) shouldBe null
    }
}
