@file:Suppress("MatchingDeclarationName")

package com.fenakhay.kwikibot.bindings.errors

import com.fenakhay.kwikibot.client.model.dump.DumpFormatException
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.wikitext.WikitextRoundTripException
import kotlin.time.Duration

/**
 * One of this library's errors, flattened for a binding: [kind] says which, and so which of the other fields
 * are set. Every field a kind does not have is `null`.
 *
 * @property kind `"timeout"`, `"unreachable"`, `"server-error"`, `"refused"`, `"rate-limited"` or `"maxlag"`
 *   when the request produced no usable response; `"api"`; `"not-logged-in"`, `"login-failed"`,
 *   `"no-credentials"`, `"missing-right"`, `"permission-denied"`, `"account-blocked"` or `"bad-token"`;
 *   `"missing-page"`, `"not-redirect"`, `"unresolvable-redirect"`, `"bad-title"`, `"off-wiki"` or
 *   `"unsupported-content-model"`; `"read-only"`; `"unknown-site"`, `"missing-extension"`,
 *   `"version-too-old"` or `"unsupported"`; `"round-trip"`; or `"dump-format"`.
 * @property message what went wrong, for a person.
 * @property isTransient whether trying the identical request again could succeed.
 * @property url the endpoint, on a `"timeout"`, `"unreachable"`, `"server-error"` or `"refused"`.
 * @property status the HTTP status, on a `"server-error"` or `"refused"`.
 * @property detail what the wiki or server said, on a `"refused"`, `"permission-denied"`,
 *   `"account-blocked"`, `"unresolvable-redirect"` or `"read-only"`, or the reason of a `"login-failed"`.
 * @property retryAfter how long to wait, on a `"rate-limited"`, when the server said.
 * @property lag how far behind the replica was, on a `"maxlag"`.
 * @property host which replica it was, on a `"maxlag"`, when the wiki named it.
 * @property code the wiki's machine-readable code, on an `"api"`.
 * @property info MediaWiki's own text for it, on an `"api"`.
 * @property module the API module that raised it, on an `"api"`, when the wiki said.
 * @property action the action that needed an account, on a `"not-logged-in"`.
 * @property wiki the wiki nothing was configured for, on a `"no-credentials"`.
 * @property right the right the account would need, on a `"missing-right"`.
 * @property tokenType which token was rejected, on a `"bad-token"`.
 * @property title the title it is about, on a kind about a page, as written in the error.
 * @property titleProblem why the title cannot name a page, on a `"bad-title"`.
 * @property contentModel what the page actually holds, on an `"unsupported-content-model"`.
 * @property language the language code asked for, on an `"unknown-site"`.
 * @property family the family asked for, on an `"unknown-site"`.
 * @property extension the extension the feature needs, on a `"missing-extension"`.
 * @property requiredVersion the lowest MediaWiki version that carries the feature, on a `"version-too-old"`.
 * @property actualVersion what the wiki runs, on a `"version-too-old"`.
 * @property parameter the module and parameter, on an `"unsupported"`.
 * @property alternatives what the library would have sent, most preferred first, on an `"unsupported"`.
 * @property dumpProblem what is wrong with the dump, on a `"dump-format"`.
 * @property offset the first offset where the text written back would differ, on a `"round-trip"`.
 */
@Suppress("LongParameterList")
public data class ErrorDescription(
    val kind: String,
    val message: String,
    val isTransient: Boolean,
    val url: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val retryAfter: Duration? = null,
    val lag: Duration? = null,
    val host: String? = null,
    val code: String? = null,
    val info: String? = null,
    val module: String? = null,
    val action: String? = null,
    val wiki: String? = null,
    val right: String? = null,
    val tokenType: String? = null,
    val title: String? = null,
    val titleProblem: Title.Invalid.Reason? = null,
    val contentModel: String? = null,
    val language: String? = null,
    val family: String? = null,
    val extension: String? = null,
    val requiredVersion: String? = null,
    val actualVersion: String? = null,
    val parameter: String? = null,
    val alternatives: List<String>? = null,
    val dumpProblem: DumpFormatException.Code? = null,
    val offset: Int? = null,
)

/**
 * What a failure from the wiki, a dump or `parseExact` says, or `null` when [error] is something else, a
 * mistaken argument included.
 */
public fun describe(error: Throwable): ErrorDescription? {
    val kind = kindOf(error) ?: return null
    val base = ErrorDescription(kind, error.message.orEmpty(), (error as? WikiError)?.isTransient == true)
    return when (error) {
        is WikiError.Transport -> base.transport(error)
        is WikiError.Api -> base.copy(code = error.code, info = error.info, module = error.module)
        is WikiError.Auth -> base.auth(error)
        is WikiError.Page -> base.page(error)
        is WikiError.ReadOnly -> base.copy(detail = error.reason)
        is WikiError.Configuration -> base.configuration(error)
        is DumpFormatException -> base.copy(dumpProblem = error.code)
        else -> base.copy(offset = (error as WikitextRoundTripException).offset)
    }
}

private fun ErrorDescription.transport(error: WikiError.Transport): ErrorDescription =
    when (error) {
        is WikiError.Transport.Timeout -> copy(url = error.url)
        is WikiError.Transport.Unreachable -> copy(url = error.url)
        is WikiError.Transport.ServerError -> copy(url = error.url, status = error.status)
        is WikiError.Transport.Refused -> copy(url = error.url, status = error.status, detail = error.detail)
        is WikiError.Transport.RateLimited -> copy(retryAfter = error.retryAfter)
        is WikiError.Transport.Maxlag -> copy(lag = error.lag, host = error.host)
    }

private fun ErrorDescription.auth(error: WikiError.Auth): ErrorDescription =
    when (error) {
        is WikiError.Auth.NotLoggedIn -> copy(action = error.action)
        is WikiError.Auth.LoginFailed -> copy(detail = error.reason)
        is WikiError.Auth.NoCredentials -> copy(wiki = error.wiki.dbName)
        is WikiError.Auth.MissingRight -> copy(right = error.right)
        is WikiError.Auth.PermissionDenied -> copy(detail = error.detail)
        is WikiError.Auth.AccountBlocked -> copy(detail = error.detail)
        is WikiError.Auth.BadToken -> copy(tokenType = error.tokenType)
    }

private fun ErrorDescription.page(error: WikiError.Page): ErrorDescription =
    when (error) {
        is WikiError.Page.Missing -> copy(title = error.title.toString())
        is WikiError.Page.NotRedirect -> copy(title = error.title.toString())
        is WikiError.Page.UnresolvableRedirect -> copy(title = error.title.toString(), detail = error.detail)
        is WikiError.Page.BadTitle -> copy(title = error.title.raw, titleProblem = error.title.reason)
        is WikiError.Page.OffWiki -> copy(title = error.title.toString())
        is WikiError.Page.UnsupportedContentModel ->
            copy(title = error.title.toString(), contentModel = error.contentModel.id)
    }

private fun ErrorDescription.configuration(error: WikiError.Configuration): ErrorDescription =
    when (error) {
        is WikiError.Configuration.UnknownSite -> copy(language = error.code.code, family = error.family)
        is WikiError.Configuration.MissingExtension -> copy(extension = error.extension)
        is WikiError.Configuration.VersionTooOld ->
            copy(requiredVersion = error.required, actualVersion = error.actual)
        is WikiError.Configuration.Unsupported ->
            copy(parameter = error.parameter, alternatives = error.alternatives.toList())
    }

/** The kind [error] is, as [ErrorDescription.kind] spells it, or `null` when it is none of this library's. */
@Suppress("CyclomaticComplexMethod")
private fun kindOf(error: Throwable): String? {
    return when (error) {
        is WikiError ->
            when (error) {
                is WikiError.Transport ->
                    when (error) {
                        is WikiError.Transport.Timeout -> "timeout"
                        is WikiError.Transport.Unreachable -> "unreachable"
                        is WikiError.Transport.ServerError -> "server-error"
                        is WikiError.Transport.Refused -> "refused"
                        is WikiError.Transport.RateLimited -> "rate-limited"
                        is WikiError.Transport.Maxlag -> "maxlag"
                    }
                is WikiError.Api -> "api"
                is WikiError.Auth ->
                    when (error) {
                        is WikiError.Auth.NotLoggedIn -> "not-logged-in"
                        is WikiError.Auth.LoginFailed -> "login-failed"
                        is WikiError.Auth.NoCredentials -> "no-credentials"
                        is WikiError.Auth.MissingRight -> "missing-right"
                        is WikiError.Auth.PermissionDenied -> "permission-denied"
                        is WikiError.Auth.AccountBlocked -> "account-blocked"
                        is WikiError.Auth.BadToken -> "bad-token"
                    }
                is WikiError.Page ->
                    when (error) {
                        is WikiError.Page.Missing -> "missing-page"
                        is WikiError.Page.NotRedirect -> "not-redirect"
                        is WikiError.Page.UnresolvableRedirect -> "unresolvable-redirect"
                        is WikiError.Page.BadTitle -> "bad-title"
                        is WikiError.Page.OffWiki -> "off-wiki"
                        is WikiError.Page.UnsupportedContentModel -> "unsupported-content-model"
                    }
                is WikiError.ReadOnly -> "read-only"
                is WikiError.Configuration ->
                    when (error) {
                        is WikiError.Configuration.UnknownSite -> "unknown-site"
                        is WikiError.Configuration.MissingExtension -> "missing-extension"
                        is WikiError.Configuration.VersionTooOld -> "version-too-old"
                        is WikiError.Configuration.Unsupported -> "unsupported"
                    }
            }
        is WikitextRoundTripException -> "round-trip"
        is DumpFormatException -> "dump-format"
        else -> null
    }
}
