import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.bindings.errors.ErrorDescription
import com.fenakhay.kwikibot.bindings.errors.describe

/**
 * What a failure from the wiki or a dump says, or `null` when [error] is something else, a mistaken argument
 * included: a JavaScript program cannot tell this library's errors apart any other way.
 */
@JsExport
public fun describeError(error: Any?): ErrorInfo? =
    (error as? Throwable)?.takeIf { describe(it) != null }?.let(::ErrorInfo)

/** One of this library's errors. [kind] says which, and so which of the other fields are set. */
@JsExport
public class ErrorInfo internal constructor(private val error: Throwable) {

    private val described: ErrorDescription
        get() = checkNotNull(describe(error))

    /**
     * `"timeout"`, `"unreachable"`, `"server-error"`, `"refused"`, `"rate-limited"` or `"maxlag"` when the
     * request produced no usable response; `"api"` for an error from the wiki with no more specific meaning;
     * `"not-logged-in"`, `"login-failed"`, `"no-credentials"`, `"missing-right"`, `"permission-denied"`,
     * `"account-blocked"` or `"bad-token"` when the account may not; `"missing-page"`, `"not-redirect"`,
     * `"unresolvable-redirect"`, `"bad-title"`, `"off-wiki"` or `"unsupported-content-model"` when the page
     * will not do; `"read-only"`; `"unknown-site"`, `"missing-extension"`, `"version-too-old"` or
     * `"unsupported"` when the wiki or the setup is not up to it; `"round-trip"` from `parseExact`; or
     * `"dump-format"` from reading a dump, with [dumpProblem] saying what is wrong with it.
     */
    public val kind: ErrorKind
        get() = described.kind.unsafeCast<ErrorKind>()

    /** What went wrong, for a person. */
    public val message: String
        get() = described.message

    /** Whether trying the identical request again could succeed. */
    public val isTransient: Boolean
        get() = described.isTransient

    /** The endpoint, on a `"timeout"`, `"unreachable"`, `"server-error"` or `"refused"`. */
    public val url: String?
        get() = described.url

    /** The HTTP status, on a `"server-error"` or `"refused"`. */
    public val status: Int?
        get() = described.status

    /**
     * What the wiki or server said, on a `"refused"`, `"permission-denied"`, `"account-blocked"`,
     * `"unresolvable-redirect"` or `"read-only"`, or as its reason on a `"login-failed"`.
     */
    public val detail: String?
        get() = described.detail

    /** How long to wait in milliseconds, on a `"rate-limited"`, when the server said. */
    public val retryAfter: Double?
        get() = described.retryAfter?.inWholeMilliseconds?.toDouble()

    /** How far behind the replica was in milliseconds, on a `"maxlag"`. */
    public val lag: Double?
        get() = described.lag?.inWholeMilliseconds?.toDouble()

    /** Which replica it was, on a `"maxlag"`, when the wiki named it. */
    public val host: String?
        get() = described.host

    /** The wiki's machine-readable code, on an `"api"`. */
    public val code: String?
        get() = described.code

    /** MediaWiki's own text for it, on an `"api"`. */
    public val info: String?
        get() = described.info

    /** The API module that raised it, on an `"api"`, when the wiki said. */
    public val module: String?
        get() = described.module

    /** The action that needed an account, on a `"not-logged-in"`. */
    public val action: String?
        get() = described.action

    /** The wiki nothing was configured for, on a `"no-credentials"`. */
    public val wiki: String?
        get() = described.wiki

    /** The right the account would need, on a `"missing-right"`. */
    public val right: String?
        get() = described.right

    /** Which token was rejected, on a `"bad-token"`: `"csrf"`, `"rollback"`, `"userrights"`. */
    public val tokenType: String?
        get() = described.tokenType

    /** The title it is about, on a kind about a page, as written in the error. */
    public val title: String?
        get() = described.title

    /** Why the title cannot name a page, on a `"bad-title"`, as `Title.invalidReason` says it. */
    public val titleProblem: TitleProblem?
        get() = described.titleProblem?.argumentName().unsafeCast<TitleProblem?>()

    /** What the page actually holds, on an `"unsupported-content-model"`. */
    public val contentModel: String?
        get() = described.contentModel

    /** The language code asked for, on an `"unknown-site"`. */
    public val language: String?
        get() = described.language

    /** The family asked for, on an `"unknown-site"`. */
    public val family: String?
        get() = described.family

    /** The extension the feature needs, on a `"missing-extension"`. */
    public val extension: String?
        get() = described.extension

    /** The lowest MediaWiki version that carries the feature, on a `"version-too-old"`. */
    public val requiredVersion: String?
        get() = described.requiredVersion

    /** What the wiki runs, on a `"version-too-old"`. */
    public val actualVersion: String?
        get() = described.actualVersion

    /** The module and parameter, such as `parse prop`, on an `"unsupported"`. */
    public val parameter: String?
        get() = described.parameter

    /** What the library would have sent, most preferred first, on an `"unsupported"`. */
    public val alternatives: Array<String>?
        get() = described.alternatives?.toTypedArray()

    /**
     * What is wrong with the dump on a `"dump-format"`: `"unexpected-eof"`, `"bad-checksum"` or `"bad-data"`.
     */
    public val dumpProblem: DumpProblem?
        get() = described.dumpProblem?.argumentName().unsafeCast<DumpProblem?>()

    /** The first offset where the text written back would differ, on a `"round-trip"`. */
    public val offset: Int?
        get() = described.offset
}
