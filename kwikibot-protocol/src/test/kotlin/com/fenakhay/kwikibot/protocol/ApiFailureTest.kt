package com.fenakhay.kwikibot.protocol

import com.fenakhay.kwikibot.model.EditOutcome
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.WikiId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ApiFailureTest {

    private val ref = PageRef(WikiId("enwiktionary"), Title.Local(Namespace.MAIN, "volcano"))

    private fun response(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `reads the errors array that errorformat=plaintext returns`() {
        val failure = ApiFailure.from(
            response(
                """{"errors":[{"code":"protectedpage","text":"This page is protected.","module":"edit"}]}""",
            ),
        )

        failure shouldBe ApiFailure("protectedpage", "This page is protected.", "edit")
    }

    @Test
    fun `reads the legacy error object too`() {
        val failure = ApiFailure.from(
            response("""{"error":{"code":"editconflict","info":"Edit conflict.","module":"edit"}}"""),
        )

        failure shouldBe ApiFailure("editconflict", "Edit conflict.", "edit")
    }

    @Test
    fun `a response with no error yields nothing`() {
        ApiFailure.from(response("""{"batchcomplete":true,"query":{"pages":[]}}""")).shouldBeNull()
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
        ApiFailure("assertuserfailed", "Assertion failed").toWikiError()
            .shouldBeInstanceOf<WikiError.Auth.NotLoggedIn>()
        ApiFailure("blocked", "You are blocked").toWikiError()
            .shouldBeInstanceOf<WikiError.Auth.AccountBlocked>()
        ApiFailure("permissiondenied", "Not allowed").toWikiError()
            .shouldBeInstanceOf<WikiError.Auth.PermissionDenied>()
        ApiFailure("badtoken", "Invalid CSRF token").toWikiError()
            .shouldBeInstanceOf<WikiError.Auth.BadToken>()
    }

    @Test
    fun `an unmapped code keeps its code and message`() {
        val error = ApiFailure("badvalue", "Unrecognised value for parameter", "query")
            .toWikiError().shouldBeInstanceOf<WikiError.Api>()

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
        val deleted = ApiFailure("pagedeleted", "The page has been deleted.").toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.PageStateChanged>()
        deleted.wasDeleted shouldBe true

        val created = ApiFailure("articleexists", "The page already exists.").toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.PageStateChanged>()
        created.wasDeleted shouldBe false
    }

    @Test
    fun `protection is reported with its cascading flag`() {
        ApiFailure("protectedpage", "Protected.").toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.Protected>().cascading shouldBe false

        ApiFailure("cascadeprotected", "Cascade protected.").toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.Protected>().cascading shouldBe true
    }

    @Test
    fun `filters and blacklists are one outcome, with the filter named when given`() {
        val filtered = ApiFailure(
            "abusefilter-disallowed",
            "This action was automatically identified as harmful (repeated spam)",
        ).toEditRefusal(ref).shouldBeInstanceOf<EditOutcome.Filtered>()

        filtered.filter shouldBe "repeated spam"

        ApiFailure("spamblacklist", "Blocked by the spam blacklist").toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.Filtered>()
        ApiFailure("titleblacklist-forbidden", "Title is blacklisted").toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.Filtered>()
    }

    @Test
    fun `permission refusals distinguish anonymous from denied`() {
        ApiFailure("noedit-anon", "Anonymous users may not edit").toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.PermissionDenied>()
        ApiFailure("blocked", "You are blocked").toEditRefusal(ref)
            .shouldBeInstanceOf<EditOutcome.PermissionDenied>()
    }

    @Test
    fun `a refusal we do not model keeps its code rather than being disguised`() {
        val rejected = ApiFailure("contenttoobig", "The content is too large")
            .toEditRefusal(ref).shouldBeInstanceOf<EditOutcome.Rejected>()

        rejected.code shouldBe "contenttoobig"
        rejected.isRetryable shouldBe false
    }

    @Test
    fun `a dead session is an error to recover from, not a refusal to record`() {
        ApiFailure("assertuserfailed", "Assertion failed").toEditRefusal(ref).shouldBeNull()
        ApiFailure("assertuserfailed", "Assertion failed").toWikiError()
            .shouldBeInstanceOf<WikiError.Auth.NotLoggedIn>()
    }

    @Test
    fun `a failure unrelated to the edit is not a refusal`() {
        ApiFailure("maxlag", "Waiting for a replica").toEditRefusal(ref).shouldBeNull()
        ApiFailure("badvalue", "Bad parameter").toEditRefusal(ref).shouldBeNull()
    }

    @Test
    fun `warnings are read from the array errorformat=plaintext returns`() {
        val warnings = response(
            """{"warnings":[
               {"module":"query","text":"Because \"rvslots\" was not specified..."},
               {"module":"main","text":"Subscribe to the mediawiki-api-announce list."}]}""",
        ).warnings()

        warnings.map { it.module } shouldBe listOf("query", "main")
        warnings.first().text shouldBe "Because \"rvslots\" was not specified..."
    }

    @Test
    fun `warnings are read from the legacy object keyed by module`() {
        val warnings = response(
            """{"warnings":{"query":{"*":"Truncated to the limit for your account."}}}""",
        ).warnings()

        warnings.single().module shouldBe "query"
        warnings.single().text shouldBe "Truncated to the limit for your account."
    }

    @Test
    fun `the legacy shape reads either key MediaWiki uses for the message`() {
        response("""{"warnings":{"main":{"warnings":"Deprecated parameter."}}}""")
            .warnings().single().text shouldBe "Deprecated parameter."
    }

    @Test
    fun `a warning with neither key is kept, with no text rather than dropped`() {
        response("""{"warnings":{"parse":{}}}""").warnings().single() shouldBe
            ApiWarning("parse", "")
    }

    @Test
    fun `a response with no warnings yields none`() {
        response("""{"query":{"pages":[]}}""").warnings().shouldBeEmpty()
    }

    @Test
    fun `a warning prints as module and message, which is what a log line wants`() {
        ApiWarning("query", "Truncated.").toString() shouldBe "query: Truncated."
    }
}
