import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.bindings.common.enumArgument
import com.fenakhay.kwikibot.net.Kwikibot
import com.fenakhay.kwikibot.net.UserAgent as ClientUserAgent
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.environmentVariable
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level

/**
 * An OAuth 2.0 owner-only access token, to log in with. It is a kind of `BotPassword`, so it goes wherever
 * one does: `connect`, `connectWith`, a fleet's `login` and `accounts`.
 *
 * It is sent as a bearer header on every request, with no login round trip. Its [account] is its [username],
 * or `""` when none was given, and its [botName] is `""`.
 */
@JsExport
public class OAuth2(
    /** The bearer token, sent on every request. */
    accessToken: String,
    /** The account the token belongs to, which keeps the answers it is given apart in a cache. */
    public val username: String? = null,
) : BotPassword(username.orEmpty(), "", "") {
    init {
        val _ = Credentials.OAuth2(accessToken, username)
        keepSecret(this, accessToken)
    }

    override val credentials: Credentials
        get() = hideFields(Credentials.OAuth2(secretOf(this), username))
}

/**
 * An OAuth 1.0a owner-only consumer, to log in with: its key and secret, and the access token and secret the
 * wiki issued with it. It is a kind of `BotPassword`, so it goes wherever one does: `connect`, `connectWith`,
 * a fleet's `login` and `accounts`, and what `createAccounts` is given.
 *
 * Every request is signed with HMAC-SHA1, with no login round trip, and the signature goes in an
 * `Authorization: OAuth` header. All four values are kept where nothing that looks at this object finds them.
 * Its [account] is its [username], or `""` when none was given, and its [botName] is `""`.
 */
@JsExport
public class OAuth1(
    /** The consumer's key, sent with every request. */
    consumerKey: String,
    /** The consumer's secret, which only keys the signature and is never sent. */
    consumerSecret: String,
    /** The access token, sent with every request. */
    accessToken: String,
    /** The access token's secret, which only keys the signature and is never sent. */
    accessSecret: String,
    /** The account the consumer belongs to, for the logs. */
    public val username: String? = null,
) : BotPassword(username.orEmpty(), "", "") {
    init {
        keepSecret(
            this,
            hideFields(Credentials.OAuth1(consumerKey, consumerSecret, accessToken, accessSecret, username)),
        )
    }

    override val credentials: Credentials
        get() = secretOf(this)
}

/**
 * The login the environment holds under [prefix]: an `OAuth2` from `<prefix>_OAUTH_TOKEN`, or a `BotPassword`
 * from `<prefix>_ACCOUNT`, `<prefix>_BOT_NAME` and `<prefix>_PASSWORD`. `null` when neither is set, which a
 * bot that must log in can check for, and always in a browser, which has no environment.
 *
 * [environment] reads a variable, the process environment unless given. Throws when a bot password is set
 * only in part, which is a mistake rather than a choice to run anonymously.
 *
 * An `OAuth1` is read from `<prefix>_OAUTH_CONSUMER_KEY`, `<prefix>_OAUTH_CONSUMER_SECRET`,
 * `<prefix>_OAUTH_ACCESS_TOKEN` and `<prefix>_OAUTH_ACCESS_SECRET`, with `<prefix>_ACCOUNT` as its username,
 * and wins over a bot password as an `OAuth2` does. Those four set only in part throw, as does any of them
 * set beside `<prefix>_OAUTH_TOKEN`; the message names the variables, never what they hold.
 */
@JsExport
public fun credentialsFromEnvironment(
    prefix: String = "KWIKIBOT",
    environment: ((name: String) -> String?)? = null,
): BotPassword? =
    loginOf(
        Credentials.fromEnvironment(prefix) { name ->
            fromJs { (environment ?: ::environmentVariable)(name) }
        }
    )

/** [found] as what this package logs in with, or `null` for an anonymous session. */
internal fun loginOf(found: Credentials): BotPassword? =
    when (found) {
        is Credentials.Anonymous -> null
        is Credentials.BotPassword -> BotPassword(found.account, found.botName, found.password)
        is Credentials.OAuth2 -> OAuth2(found.accessToken, found.username)
        is Credentials.OAuth1 ->
            OAuth1(
                found.consumerKey,
                found.consumerSecret,
                found.accessToken,
                found.accessSecret,
                found.username,
            )
    }

/** The `User-Agent` a bot sends: Wikimedia's policy asks for a name and a way to reach its operator. */
@JsExport
public class UserAgent(
    /** The bot or tool name, such as `"FenaBot"`. */
    public val tool: String,
    /** The tool's version, such as `"1.0"`. */
    public val version: String,
    /** A URL or email address an operator can be reached at. */
    public val contact: String,
) {
    internal val agent: ClientUserAgent = ClientUserAgent(tool, version, contact)

    /** The header's value, in the form Wikimedia's policy asks for. */
    public val headerValue: String
        get() = agent.headerValue

    /** The header's value. */
    override fun toString(): String = agent.toString()
}

/** The name this library goes by in a user agent. */
@JsExport public const val LIBRARY_NAME: String = Kwikibot.NAME

/** Where to read about this library, for a user agent that has nothing better to point at. */
@JsExport public const val LIBRARY_URL: String = Kwikibot.URL

/** This library's version. */
@JsExport public fun libraryVersion(): String = Kwikibot.version

/** What this runs on, such as Node's version, as a bug report says it. */
@JsExport public fun libraryRuntime(): String = Kwikibot.runtime

/** The operating system this runs on, as a bug report says it. */
@JsExport public fun libraryPlatform(): String = Kwikibot.platform

/**
 * The runtime and operating system this runs on, and the library's version, one line each, for a bug report.
 */
@JsExport public fun versionReport(): String = Kwikibot.report()

/**
 * How much the library says on the console: `"trace"`, `"debug"`, `"info"`, `"warn"`, `"error"` or `"off"`.
 * `"info"` until set, and it holds for every wiki in the process.
 */
@JsExport
public fun setLogLevel(level: LogLevel) {
    KotlinLoggingConfiguration.direct.logLevel = enumArgument<Level>("level", level.unsafeCast<String>())
}

/** How much the library says on the console, as `setLogLevel` spells it. */
@JsExport
public fun logLevel(): LogLevel =
    KotlinLoggingConfiguration.direct.logLevel.argumentName().unsafeCast<LogLevel>()
