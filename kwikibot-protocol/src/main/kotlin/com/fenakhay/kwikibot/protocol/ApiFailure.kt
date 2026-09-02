package com.fenakhay.kwikibot.protocol

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * An error block as the API reported it.
 *
 * MediaWiki has two shapes for this. `errorformat=plaintext` and friends produce an `errors` array whose
 * entries carry `text`; the legacy format produces one `error` object carrying `info`. Both are read here so
 * callers never have to care which arrived.
 */
public data class ApiFailure(
    /** The machine-readable code, which is what a caller should branch on. */
    val code: String,
    /** The wiki's own message, whichever of the two shapes it arrived in. */
    val text: String,
    /** Which API module raised it, when the wiki said. */
    val module: String? = null,
) {
    /**
     * The typed error to raise for this failure.
     *
     * Codes MediaWiki uses for *expected* edit refusals are deliberately absent: those become [EditOutcome]
     * values through [toEditRefusal] rather than exceptions.
     */
    public fun toWikiError(): WikiError =
        when (code) {
            in NOT_LOGGED_IN -> WikiError.Auth.NotLoggedIn(module ?: "this action")
            in BLOCKED -> WikiError.Auth.AccountBlocked(text)
            in PERMISSION_DENIED -> WikiError.Auth.PermissionDenied(text)
            BAD_TOKEN -> WikiError.Auth.BadToken("csrf")
            READ_ONLY -> WikiError.ReadOnly(text)
            else -> WikiError.Api(code, text, module)
        }

    /**
     * The refusal to report for a failed edit, or `null` if this failure is not about the edit itself and
     * should be raised as an error instead.
     *
     * Maps MediaWiki's edit error codes by whether a bot can act on the outcome or must treat it as a fault.
     */
    public fun toEditRefusal(ref: PageRef): EditOutcome.Refused? =
        when (code) {
            // "undofailure" means the revisions no longer apply to the page as it stands. That is
            // an edit conflict by another name, and calls for the same response: re-read and decide.
            "editconflict",
            "undofailure" -> EditOutcome.Conflict(ref, text)
            "pagedeleted" -> EditOutcome.PageStateChanged(ref, text, wasDeleted = true)
            "articleexists" -> EditOutcome.PageStateChanged(ref, text, wasDeleted = false)

            "protectedpage",
            "protectedtitle",
            "protectednamespace",
            "protectednamespace-interface" -> EditOutcome.Protected(ref, text)

            "cascadeprotected" -> EditOutcome.Protected(ref, text, cascading = true)

            "abusefilter-disallowed",
            "abusefilter-warning",
            "spamblacklist",
            "titleblacklist-forbidden",
            "filtered",
            "spamdetected" -> EditOutcome.Filtered(ref, text, filter = filterName())

            "ratelimited" -> EditOutcome.RateLimited(ref, text)
            "captcha",
            "captchacreate",
            "captchaneeded" -> EditOutcome.CaptchaRequired(ref, text)

            in PERMISSION_DENIED,
            in BLOCKED,
            in ANONYMOUS_DENIED -> EditOutcome.PermissionDenied(ref, text)

            // Everything MediaWiki refused for a reason we do not model: reported with its code
            // rather than disguised as one of the cases above.
            "missingtitle",
            "contenttoobig",
            "nocreate-missing",
            "badmd5",
            "emptypage",
            "emptynewsection",
            "hookaborted",
            "unknownerror" -> EditOutcome.Rejected(ref, text, code)

            else -> null
        }

    /** The blacklist entry or filter name, when the message carries one. */
    private fun filterName(): String? = FILTER_NAME.find(text)?.groupValues?.get(1)

    /** Reading a failure out of a response, and the codes that map to a typed error. */
    public companion object {
        private const val BAD_TOKEN = "badtoken"
        private const val READ_ONLY = "readonly"

        private val NOT_LOGGED_IN =
            setOf(
                "notloggedin",
                "mustbeloggedin",
                "assertuserfailed",
                "assertbotfailed",
                "mustbeposted",
            )

        /**
         * Refusals that mean "anonymous users may not do this".
         *
         * Separate from [NOT_LOGGED_IN] because these describe the edit, whereas a failed assertion means the
         * session died underneath us — which is an error to recover from by logging in again, not a refusal
         * to record against the page.
         */
        private val ANONYMOUS_DENIED =
            setOf(
                "noedit-anon",
                "cantcreate-anon",
                "noimageredirect-anon",
            )

        private val BLOCKED = setOf("blocked", "autoblocked", "blockedfrommail")

        private val PERMISSION_DENIED =
            setOf(
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

        private val FILTER_NAME = Regex("""\(([^)]+)\)\s*$""")

        /**
         * Reads the error block from a response, in whichever shape it arrived.
         *
         * Returns `null` for a response that reported no error.
         */
        public fun from(response: JsonObject): ApiFailure? {
            val error =
                response["errors"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: response["error"]?.jsonObject
                    ?: return null

            val code = error["code"]?.jsonPrimitive?.content ?: return null
            val text = (error["text"] ?: error["info"])?.jsonPrimitive?.content.orEmpty()
            val module = error["module"]?.jsonPrimitive?.content

            return ApiFailure(code, text, module)
        }
    }
}

/**
 * Raises the API error this response carries, if it carries one.
 *
 * The transport hands error blocks back rather than throwing, because only the caller knows whether a given
 * code is a failure. This is the shorthand for "any error here is a failure".
 */
/**
 * A warning a wiki attached to a response it nonetheless answered.
 *
 * Warnings are how MediaWiki says "this worked, but": a parameter is deprecated, a result was truncated
 * because the limit was too high for this account, a value was ignored. A bot that never looks at them keeps
 * working until the day the deprecated parameter is removed.
 */
public data class ApiWarning(
    /** The module that warned. */
    val module: String,
    /** What it said, in whichever of the two shapes the wiki used. */
    val text: String,
) {
    override fun toString(): String = "$module: $text"
}

/**
 * Every warning on a response, in the order the wiki listed them.
 *
 * Both shapes are read, as with errors: `errorformat=plaintext` produces a `warnings` array with a module and
 * text, and the legacy format an object keyed by module.
 */
public fun JsonObject.warnings(): List<ApiWarning> {
    val array = this["warnings"] as? JsonArray
    if (array != null) {
        return array.map { entry ->
            val warning = entry.jsonObject
            ApiWarning(
                module = warning["module"]?.jsonPrimitive?.content.orEmpty(),
                text =
                    warning["text"]?.jsonPrimitive?.content ?: warning["*"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }

    val legacy = this["warnings"] as? JsonObject ?: return emptyList()
    return legacy.entries.map { (module, value) ->
        val text =
            (value as? JsonObject)?.let {
                it["warnings"]?.jsonPrimitive?.content ?: it["*"]?.jsonPrimitive?.content
            }
        ApiWarning(module, text.orEmpty())
    }
}

/**
 * Returns the response, or raises the typed error it carries.
 *
 * The transport hands error blocks back rather than throwing, so a caller that wants a failure to be an
 * exception says so here.
 */
public fun JsonObject.throwOnError(): JsonObject {
    ApiFailure.from(this)?.let { throw it.toWikiError() }
    return this
}
