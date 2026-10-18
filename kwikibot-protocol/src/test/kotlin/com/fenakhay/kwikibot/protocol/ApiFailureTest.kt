package com.fenakhay.kwikibot.protocol

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.net.transport.ApiRequest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ApiFailureTest {

    private val ref = PageRef(WikiId("enwiktionary"), Title.Local(Namespace.MAIN, "volcano"))

    private fun response(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `reads the errors array that errorformat=plaintext returns`() {
        val json =
            """{"errors":[{"code":"protectedpage","text":"This page is protected.","module":"edit"}]}"""
        val failure = ApiFailure.from(response(json))

        failure shouldBe ApiFailure("protectedpage", "This page is protected.", "edit")
    }

    @Test
    fun `reads the error object an errorformat=bc answer carries too`() {
        val failure =
            ApiFailure.from(
                response("""{"error":{"code":"editconflict","info":"Edit conflict.","module":"edit"}}""")
            )

        failure shouldBe ApiFailure("editconflict", "Edit conflict.", "edit")
    }

    @Test
    fun `a response with no error yields nothing`() {
        ApiFailure.from(response("""{"batchcomplete":true,"query":{"pages":[]}}""")).shouldBeNull()
    }

    @Test
    fun `an error block in a shape MediaWiki does not send reads as no error`() {
        val shapes =
            listOf(
                """{"errors":{}}""",
                """{"errors":[]}""",
                """{"errors":["readonly"]}""",
                """{"error":"readonly"}""",
                """{"errors":[{"code":null,"text":"no code"}]}""",
            )

        shapes.map { ApiFailure.from(response(it)) } shouldBe shapes.map { null }
    }

    @Test
    fun `an error without a message keeps its code and an empty text`() {
        ApiFailure.from(response("""{"errors":[{"code":"readonly"}]}""")) shouldBe ApiFailure("readonly", "")
    }

    @Test
    fun `throwOnError passes a clean response through`() {
        val clean = response("""{"batchcomplete":true}""")

        clean.throwOnError() shouldBe clean
    }

    @Test
    fun `throwOnError raises the mapped error`() {
        val failed = response("""{"errors":[{"code":"readonly","text":"Database maintenance."}]}""")

        val error = assertFailsWith<WikiError.ReadOnly> { failed.throwOnError() }

        error.isTransient shouldBe true
    }

    @Test
    fun `session failures map onto the auth hierarchy`() {
        ApiFailure("assertuserfailed", "Assertion failed")
            .toWikiError()
            .shouldBeInstanceOf<WikiError.Auth.NotLoggedIn>()
        ApiFailure("blocked", "You are blocked")
            .toWikiError()
            .shouldBeInstanceOf<WikiError.Auth.AccountBlocked>()
        ApiFailure("permissiondenied", "Not allowed")
            .toWikiError()
            .shouldBeInstanceOf<WikiError.Auth.PermissionDenied>()
        ApiFailure("badtoken", "Invalid CSRF token")
            .toWikiError()
            .shouldBeInstanceOf<WikiError.Auth.BadToken>()
    }

    @Test
    fun `an unmapped code keeps its code and message`() {
        val error =
            ApiFailure("badvalue", "Unrecognised value for parameter", "query")
                .toWikiError()
                .shouldBeInstanceOf<WikiError.Api>()

        error.code shouldBe "badvalue"
        error.module shouldBe "query"
        error.isTransient shouldBe false
    }

    @Test
    fun `an edit conflict is a refusal, not an error`() {
        val refusal = ApiFailure("editconflict", "Edit conflict.").toEditRefusal(ref)

        refusal.shouldBeInstanceOf<EditOutcome.Conflict>()
        refusal.isRetryable shouldBe true
    }

    @Test
    fun `deletion and creation under the edit are distinguished`() {
        val deleted =
            ApiFailure("pagedeleted", "The page has been deleted.")
                .toEditRefusal(ref)
                .shouldBeInstanceOf<EditOutcome.PageStateChanged>()
        deleted.wasDeleted shouldBe true

        val created =
            ApiFailure("articleexists", "The page already exists.")
                .toEditRefusal(ref)
                .shouldBeInstanceOf<EditOutcome.PageStateChanged>()
        created.wasDeleted shouldBe false
    }

    @Test
    fun `protection is reported with its cascading flag`() {
        ApiFailure("protectedpage", "Protected.")
            .toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.Protected>()
            .cascading shouldBe false

        ApiFailure("cascadeprotected", "Cascade protected.")
            .toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.Protected>()
            .cascading shouldBe true
    }

    @Test
    fun `filters and blacklists are one outcome, with the filter named when given`() {
        val filtered =
            ApiFailure(
                    "abusefilter-disallowed",
                    "This action was automatically identified as harmful (repeated spam)",
                )
                .toEditRefusal(ref)
                .shouldBeInstanceOf<EditOutcome.Filtered>()

        filtered.filter shouldBe "repeated spam"

        ApiFailure("spamblacklist", "Blocked by the spam blacklist")
            .toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.Filtered>()
        ApiFailure("titleblacklist-forbidden", "Title is blacklisted")
            .toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.Filtered>()
    }

    @Test
    fun `permission refusals distinguish anonymous from denied`() {
        ApiFailure("noedit-anon", "Anonymous users may not edit")
            .toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.PermissionDenied>()
        ApiFailure("blocked", "You are blocked")
            .toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.PermissionDenied>()
    }

    @Test
    fun `a refusal we do not model keeps its code rather than being disguised`() {
        val rejected =
            ApiFailure("contenttoobig", "The content is too large")
                .toEditRefusal(ref)
                .shouldBeInstanceOf<EditOutcome.Rejected>()

        rejected.code shouldBe "contenttoobig"
        rejected.isRetryable shouldBe false
    }

    @Test
    fun `a dead session is an error to recover from, not a refusal to record`() {
        ApiFailure("assertuserfailed", "Assertion failed").toEditRefusal(ref).shouldBeNull()
        ApiFailure("assertuserfailed", "Assertion failed")
            .toWikiError()
            .shouldBeInstanceOf<WikiError.Auth.NotLoggedIn>()
    }

    @Test
    fun `a failure unrelated to the edit is not a refusal`() {
        ApiFailure("maxlag", "Waiting for a replica").toEditRefusal(ref).shouldBeNull()
        ApiFailure("badvalue", "Bad parameter").toEditRefusal(ref).shouldBeNull()
    }

    @Test
    fun `every edit refusal code maps to the outcome a bot acts on`() {
        val text = "Refused."
        val expected =
            listOf("editconflict", "undofailure").associateWith { EditOutcome.Conflict(ref, text) } +
                mapOf(
                    "pagedeleted" to EditOutcome.PageStateChanged(ref, text, wasDeleted = true),
                    "articleexists" to EditOutcome.PageStateChanged(ref, text, wasDeleted = false),
                    "cascadeprotected" to EditOutcome.Protected(ref, text, cascading = true),
                    "ratelimited" to EditOutcome.RateLimited(ref, text),
                ) +
                listOf(
                        "protectedpage",
                        "protectedtitle",
                        "protectednamespace",
                        "protectednamespace-interface",
                    )
                    .associateWith { EditOutcome.Protected(ref, text) } +
                listOf(
                        "abusefilter-disallowed",
                        "abusefilter-warning",
                        "spamblacklist",
                        "titleblacklist-forbidden",
                        "filtered",
                        "spamdetected",
                    )
                    .associateWith { EditOutcome.Filtered(ref, text) } +
                listOf("captcha", "captchacreate", "captchaneeded").associateWith {
                    EditOutcome.CaptchaRequired(ref, text)
                } +
                listOf(
                        "permissiondenied",
                        "permissionerror",
                        "writeapidenied",
                        "noapiwrite",
                        "noedit",
                        "cantcreate",
                        "noimageredirect",
                        "protectedpage-ns",
                        "readapidenied",
                        "blocked",
                        "autoblocked",
                        "blockedfrommail",
                        "noedit-anon",
                        "cantcreate-anon",
                        "noimageredirect-anon",
                    )
                    .associateWith { EditOutcome.PermissionDenied(ref, text) } +
                listOf(
                        "missingtitle",
                        "contenttoobig",
                        "nocreate-missing",
                        "badmd5",
                        "emptypage",
                        "emptynewsection",
                        "hookaborted",
                        "unknownerror",
                    )
                    .associateWith { EditOutcome.Rejected(ref, text, it) }

        expected.mapValues { (code, _) -> ApiFailure(code, text).toEditRefusal(ref) } shouldBe expected
    }

    @Test
    fun `a filter refusal whose message names no filter has none`() {
        ApiFailure("spamblacklist", "Blocked by the spam blacklist.")
            .toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.Filtered>()
            .filter
            .shouldBeNull()
    }

    @Test
    fun `every code that means the session is gone names the module that needed one`() {
        val codes =
            listOf("notloggedin", "mustbeloggedin", "assertuserfailed", "assertbotfailed", "mustbeposted")

        codes.map {
            ApiFailure(it, "Log in first.", "edit")
                .toWikiError()
                .shouldBeInstanceOf<WikiError.Auth.NotLoggedIn>()
                .action
        } shouldBe codes.map { "edit" }
    }

    @Test
    fun `a session failure that names no module says what was refused in general terms`() {
        val error =
            ApiFailure("assertuserfailed", "Assertion failed")
                .toWikiError()
                .shouldBeInstanceOf<WikiError.Auth.NotLoggedIn>()

        error.action shouldBe "this action"
        error.message shouldBe "not logged in, which this action requires"
    }

    @Test
    fun `every block and permission code raises its typed error with the wiki's own message`() {
        val blocked = listOf("blocked", "autoblocked", "blockedfrommail")
        val denied =
            listOf(
                "permissiondenied",
                "permissionerror",
                "writeapidenied",
                "noapiwrite",
                "noedit",
                "cantcreate",
                "noimageredirect",
                "protectedpage-ns",
                "readapidenied",
            )

        blocked.map {
            ApiFailure(it, "Blocked until Friday.")
                .toWikiError()
                .shouldBeInstanceOf<WikiError.Auth.AccountBlocked>()
                .detail
        } shouldBe blocked.map { "Blocked until Friday." }
        denied.map {
            ApiFailure(it, "Not for you.")
                .toWikiError()
                .shouldBeInstanceOf<WikiError.Auth.PermissionDenied>()
                .detail
        } shouldBe denied.map { "Not for you." }
    }

    @Test
    fun `a read-only wiki and a rejected token raise their own errors`() {
        ApiFailure("readonly", "Database maintenance.")
            .toWikiError()
            .shouldBeInstanceOf<WikiError.ReadOnly>()
            .reason shouldBe "Database maintenance."
        ApiFailure("badtoken", "Invalid CSRF token.")
            .toWikiError()
            .shouldBeInstanceOf<WikiError.Auth.BadToken>()
            .tokenType shouldBe "csrf"
    }

    @Test
    fun `an unmapped failure that names no module is raised without one`() {
        val error =
            ApiFailure("badvalue", "Unrecognised value.").toWikiError().shouldBeInstanceOf<WikiError.Api>()

        error.module.shouldBeNull()
        error.message shouldBe "API error [badvalue]: Unrecognised value."
    }

    @Test
    fun `a failure read from a response keeps the absence of a module`() {
        ApiFailure.from(response("""{"errors":[{"code":"badvalue","text":"Bad."}]}""")) shouldBe
            ApiFailure("badvalue", "Bad.", module = null)
    }

    @Test
    fun `warnings are read from the array errorformat=plaintext returns`() {
        val warnings =
            response(
                    """{"warnings":[
               {"module":"query","text":"Because \"rvslots\" was not specified..."},
               {"module":"main","text":"Subscribe to the mediawiki-api-announce list."}]}"""
                )
                .warnings()

        warnings.map { it.module } shouldBe listOf("query", "main")
        warnings.first().text shouldBe "Because \"rvslots\" was not specified..."
    }

    @Test
    fun `a warning with no module or text is kept, with empty ones rather than dropped`() {
        response("""{"warnings":[{"module":"parse"},{}]}""").warnings() shouldBe
            listOf(ApiWarning("parse", ""), ApiWarning("", ""))
    }

    @Test
    fun `warnings in a shape MediaWiki does not send are left out`() {
        response("""{"warnings":{"query":{"*":"Truncated."}}}""").warnings().shouldBeEmpty()
        response("""{"warnings":[null,"Truncated.",{"module":"query","text":"Truncated."}]}""")
            .warnings() shouldBe listOf(ApiWarning("query", "Truncated."))
    }

    @Test
    fun `a response with no warnings yields none`() {
        response("""{"query":{"pages":[]}}""").warnings().shouldBeEmpty()
    }

    @Test
    fun `a warning prints as module and message, which is what a log line wants`() {
        ApiWarning("query", "Truncated.").toString() shouldBe "query: Truncated."
    }

    @Test
    fun `a warning carries its code, which is what tells a deprecation from the rest`() {
        val warnings =
            response(
                    """{"warnings":[
               {"code":"deprecation","module":"parse","text":"prop=sections is deprecated."},
               {"code":"deprecation-help","module":"main","text":"Subscribe to the list."},
               {"code":"truncatedresult","module":"query","text":"Truncated."}]}"""
                )
                .warnings()

        warnings.map { it.code } shouldBe listOf("deprecation", "deprecation-help", "truncatedresult")
        warnings.map { it.isDeprecation } shouldBe listOf(true, false, false)
    }

    @Test
    fun `a warning without a code is not a deprecation, whatever its text says`() {
        val warning =
            response("""{"warnings":[{"module":"main","text":"Deprecated parameter."}]}""")
                .warnings()
                .single()

        warning.code shouldBe null
        warning.isDeprecation shouldBe false
    }

    @Test
    fun `a warning made the way 1_1 made one has no code`() {
        ApiWarning("parse", "text") shouldBe ApiWarning("parse", "text", null)
    }

    @Test
    fun `a listener hears each warning except the advice that follows a deprecation`() {
        val heard = mutableListOf<String?>()
        val listener = ApiWarningListener { _, warning -> heard += warning.code }

        listener.onResponse(
            ApiRequest.of("parse"),
            response(
                """{"warnings":[
                   {"code":"deprecation","module":"parse","text":"prop=sections is deprecated."},
                   {"code":"deprecation-help","module":"main","text":"Subscribe to the list."}]}"""
            ),
        )

        heard shouldBe listOf("deprecation")
    }

    @Test
    fun `the logging listener takes the same warning many times without complaint`() {
        val request = ApiRequest.of("parse")

        repeat(3) {
            ApiWarningListener.LOG.onWarning(request, ApiWarning("parse", "prop=sections", "deprecation"))
            ApiWarningListener.LOG.onWarning(request, ApiWarning("query", "Truncated.", "truncatedresult"))
            ApiWarningListener.LOG.onWarning(request, ApiWarning("query", "Legacy."))
        }
        ApiWarningListener.NONE.onWarning(request, ApiWarning("query", "Ignored."))
    }
}
