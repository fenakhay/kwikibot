import com.fenakhay.kwikibot.bindings.fleet.sharedStopWithDefaults
import com.fenakhay.kwikibot.bot.run.StopPolicy as ClientStopPolicy
import com.fenakhay.kwikibot.fleet.SharedStop as ClientSharedStop
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

/**
 * The check that decides whether a bot may keep writing. A check that cannot be performed stops the run: a
 * bot that keeps editing because its emergency stop was unreachable is what this prevents.
 */
@JsExport
@OptIn(DelicateCoroutinesApi::class)
public class StopPolicy internal constructor(internal val policy: ClientStopPolicy) {

    /** Whether the bot may continue. Rejecting is treated as "stop". */
    public fun mayContinue(): Promise<Boolean> = GlobalScope.promise { policy.mayContinue() }

    /**
     * Rejects if the policy already says stop or could not be read, for work before a run that a stopped bot
     * should not do either.
     */
    public fun check(): Promise<Unit> = GlobalScope.promiseVoid { policy.check() }
}

/** No stop check. Only for dry runs and for wikis you own. */
@JsExport public val STOP_POLICY_NONE: StopPolicy = StopPolicy(ClientStopPolicy.NONE)

/**
 * A policy that asks [check], which may return a promise. Only `true` lets the bot go on; anything else, a
 * rejection or a throw stops it.
 */
@JsExport
public fun stopPolicyOf(check: () -> Awaitable<Boolean>): StopPolicy =
    StopPolicy(ClientStopPolicy { settledResult(check()) == true })

/**
 * A policy letting a bot go on only while every one of [policies] does. The first to say stop, or to fail,
 * decides.
 */
@JsExport
public fun allStopPolicies(policies: Array<StopPolicy>): StopPolicy =
    StopPolicy(ClientStopPolicy.allOf(*policies.map { it.policy }.toTypedArray()))

/**
 * A policy that stops when page [title] of [wiki] says anything other than `false`. Read fresh every time,
 * never from a response cache.
 */
@JsExport
public fun stopPolicyOnPage(wiki: Wiki, title: String): StopPolicy =
    StopPolicy(ClientStopPolicy.page(wiki.wiki.pages, wiki.wiki.ref(title)))

/** How a shared stop reads. */
@JsExport
public external interface SharedStopOptions {
    /** How often to look, in milliseconds; a minute unless given. */
    public val every: Double?

    /** How long an answer stays good once it can no longer be refreshed, in milliseconds; 5 minutes. */
    public val staleAfter: Double?

    /** What the time is, so a test can say rather than wait; the system's clock unless given. */
    public val now: (() -> Date)?
}

/**
 * One stop page for many bots: [read] gives the page's text, or `null` when there is no such page, and may
 * return a promise. It is called at most once per `every`, and every bot asks what it found. The text has to
 * say `false` for bots to run. A failed read keeps the last good answer until it is stale; with no good
 * answer yet, the bots stop.
 */
@JsExport
public fun sharedStop(read: () -> Awaitable<String?>, options: SharedStopOptions? = null): StopPolicy =
    StopPolicy(
        sharedStopWithDefaults(
            { settledResult(read()).unsafeCast<String?>() },
            options?.every?.milliseconds,
            options?.staleAfter?.milliseconds,
            options?.now?.let(::clockOf),
        )
    )

/** One stop page for many bots: page [title] of [wiki], read fresh at most once a minute. */
@JsExport
public fun sharedStopOnPage(wiki: Wiki, title: String): StopPolicy =
    StopPolicy(ClientSharedStop.onPage(wiki.wiki, title))

/** A clock reading [now]. */
internal fun clockOf(now: () -> Date): Clock =
    object : Clock {
        override fun now() = instantOf(now())
    }
