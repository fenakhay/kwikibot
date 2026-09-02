package com.fenakhay.kwikibot.model

import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.ContentModel
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Title
import kotlin.time.Duration

/**
 * Everything that can go wrong talking to a wiki.
 *
 * Exceptions are for the exceptional. Outcomes a bot is expected to branch on — an edit conflict, a page the
 * abuse filter refused — are values, not throws; see [EditOutcome].
 *
 * The hierarchy is sealed, so a `when` over it is exhaustive and a new failure mode surfaces as a compile
 * error at the places that classify errors.
 */
public sealed class WikiError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * Whether retrying the identical request could plausibly succeed.
     *
     * Transport hiccups, replication lag and rate limits are transient; a missing page or a revoked
     * permission is not.
     */
    public open val isTransient: Boolean
        get() = false

    /** The request never produced a usable response. */
    public sealed class Transport(message: String, cause: Throwable? = null) : WikiError(message, cause) {

        override val isTransient: Boolean
            get() = true

        /** The request timed out. */
        public class Timeout(
            /** The endpoint that did not answer in time. */
            public val url: String,
            cause: Throwable? = null,
        ) : Transport("request to $url timed out", cause)

        /** The host could not be reached, or the connection failed mid-request. */
        public class Unreachable(
            /** The endpoint that could not be reached. */
            public val url: String,
            cause: Throwable? = null,
        ) : Transport("cannot reach $url", cause)

        /** The server answered 5xx. */
        public class ServerError(
            /** The HTTP status the server answered with. */
            public val status: Int,
            /** The endpoint that answered it. */
            public val url: String,
        ) : Transport("server error $status from $url")

        /**
         * The server asked us to slow down, via HTTP 429 or a `ratelimited` API error.
         *
         * @param retryAfter the delay the server asked for, when it named one.
         */
        public class RateLimited(
            /** How long the server asked us to wait, when it said. */
            public val retryAfter: Duration?
        ) : Transport("rate limited" + (retryAfter?.let { ", retry after $it" } ?: ""))

        /**
         * A replica lagged further behind than the request allowed.
         *
         * Raised only once the retry budget is spent; the transport waits and retries first.
         */
        public class Maxlag(
            /** How far behind the replica was. */
            public val lag: Duration,
            /** Which replica, when the wiki named it. */
            public val host: String?,
        ) : Transport("database lag of $lag" + (host?.let { " on $it" } ?: ""))
    }

    /**
     * The API answered with an `error` block that has no more specific mapping.
     *
     * @param code the machine-readable code, such as `badvalue`.
     * @param info the human-readable text MediaWiki supplied.
     * @param module the API module that raised it, when known.
     */
    public class Api(
        /** The machine-readable code, which is the part worth branching on. */
        public val code: String,
        /** MediaWiki's own text for it, meant for a person. */
        public val info: String,
        /** The API module that raised it, when the wiki said which. */
        public val module: String? = null,
    ) :
        WikiError(
            buildString {
                append("API error [").append(code).append(']')
                if (module != null) append(" in ").append(module)
                append(": ").append(info)
            }
        )

    /** The request was understood but the account may not make it. */
    public sealed class Auth(message: String) : WikiError(message) {

        /** The action needs a logged-in user and the session is anonymous. */
        public class NotLoggedIn(
            /** The action that needed an account. */
            public val action: String
        ) : Auth("not logged in, which $action requires")

        /** Login was attempted and refused. */
        public class LoginFailed(
            /** What the wiki gave as the reason, often a code rather than prose. */
            public val reason: String
        ) : Auth("login failed: $reason")

        /** No credentials are configured for this wiki. */
        public class NoCredentials(
            /** The wiki nothing was configured for. */
            public val wiki: WikiId
        ) : Auth("no credentials configured for $wiki")

        /** The account lacks a user right the action requires. */
        public class MissingRight(
            /** The right the account would need, named as MediaWiki names it. */
            public val right: String
        ) : Auth("account lacks the '$right' right")

        /**
         * The wiki refused the action on permission grounds without naming a right.
         *
         * Distinct from [MissingRight] because the wiki said only that we may not do this, which is all a
         * caller can report.
         */
        public class PermissionDenied(
            /** Whatever the wiki said, which is all there is to report. */
            public val detail: String
        ) : Auth("permission denied: $detail")

        /** The account or its IP is blocked. */
        public class AccountBlocked(
            /** The block reason the wiki gave. */
            public val detail: String
        ) : Auth("account is blocked: $detail")

        /**
         * The CSRF or action token was rejected.
         *
         * Transient: tokens expire, and the transport refetches and retries once before this ever reaches a
         * caller.
         */
        public class BadToken(
            /** Which token was rejected: `csrf`, `rollback`, `userrights`. */
            public val tokenType: String
        ) : Auth("token '$tokenType' rejected") {
            override val isTransient: Boolean
                get() = true
        }
    }

    /** The request named a page that cannot be used the way the caller intended. */
    public sealed class Page(message: String) : WikiError(message) {

        /** The page does not exist. */
        public class Missing(
            /** The title that names no page. */
            public val title: Title
        ) : Page("page does not exist: $title")

        /** A redirect was expected and the page is not one. */
        public class NotRedirect(
            /** The page that turned out not to be a redirect. */
            public val title: Title
        ) : Page("page is not a redirect: $title")

        /** A redirect target was needed and the chain does not terminate on this wiki. */
        public class UnresolvableRedirect(
            /** Where the chain started. */
            public val title: Title,
            /** Why it could not be followed: a loop, a length limit, another wiki. */
            public val detail: String,
        ) : Page("cannot resolve redirect from $title: $detail")

        /** The raw title cannot name a page at all. */
        public class BadTitle(
            /** The rejected title, carrying both the raw text and the reason. */
            public val title: Title.Invalid
        ) : Page("invalid title: ${title.raw} (${title.reason})")

        /**
         * The title belongs to another wiki.
         *
         * Kept explicit so a bot cannot edit the wrong project by accident.
         */
        public class OffWiki(
            /** The title, with the interwiki prefix that sends it elsewhere. */
            public val title: Title.Interwiki
        ) : Page("title targets another wiki: $title")

        /** The page holds content this operation cannot handle, such as JSON where wikitext was assumed. */
        public class UnsupportedContentModel(
            /** The page in question. */
            public val title: Title,
            /** What it actually holds. */
            public val contentModel: ContentModel,
        ) : Page("page $title holds $contentModel content")
    }

    /**
     * The wiki is in read-only mode.
     *
     * Transient by nature: read-only windows are how MediaWiki survives maintenance, and the same request
     * usually succeeds once the window closes.
     */
    public class ReadOnly(
        /** The notice the wiki is serving, usually naming the maintenance under way. */
        public val reason: String
    ) : WikiError("wiki is read-only: $reason") {
        override val isTransient: Boolean
            get() = true
    }

    /** The library is misconfigured, or was asked for a wiki it cannot resolve. */
    public sealed class Configuration(message: String) : WikiError(message) {

        /** No wiki matches the code and family given. */
        public class UnknownSite(
            /** The language code asked for. */
            public val code: LangCode,
            /** The project family asked for. */
            public val family: String,
        ) : Configuration("no wiki known for $code in family $family")

        /** The wiki lacks an extension the requested feature needs. */
        public class MissingExtension(
            /** The extension the feature needs, named as the wiki names it. */
            public val extension: String
        ) : Configuration("wiki does not have the $extension extension")

        /** The wiki is older than the feature requires. */
        public class VersionTooOld(
            /** The lowest version that carries the feature. */
            public val required: String,
            /** What the wiki actually runs. */
            public val actual: String,
        ) : Configuration("feature needs MediaWiki $required, wiki runs $actual")
    }
}
