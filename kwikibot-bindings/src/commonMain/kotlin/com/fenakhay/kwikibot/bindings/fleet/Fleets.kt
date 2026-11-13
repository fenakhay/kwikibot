package com.fenakhay.kwikibot.bindings.fleet

import com.fenakhay.kwikibot.bot.CommonOptions
import com.fenakhay.kwikibot.bot.run.StopPolicy
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.fleet.Accounts
import com.fenakhay.kwikibot.fleet.Bot
import com.fenakhay.kwikibot.fleet.Fleet
import com.fenakhay.kwikibot.fleet.Question
import com.fenakhay.kwikibot.fleet.RecordingContext
import com.fenakhay.kwikibot.fleet.SharedStop
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

private const val DEFAULT_FLEET_SCRIPT_PATH = "/w"

private val DEFAULT_HEARTBEAT = 30.seconds

private val DEFAULT_EVERY = 1.minutes

private val DEFAULT_STALE_AFTER = 5.minutes

/** Where a fleet's wiki is: at [server], under `/w` unless [scriptPath] is given. */
public fun fleetEndpointOf(server: String, scriptPath: String? = null): ApiEndpoint =
    ApiEndpoint(server, scriptPath ?: DEFAULT_FLEET_SCRIPT_PATH)

/** The common flags a run is started with: [options], or every flag's default unless given. */
public fun runOptionsOf(options: CommonOptions? = null): CommonOptions = options ?: CommonOptions()

/**
 * A fleet of [bots] on the wiki at [endpoint]: stopped by nothing, keeping no directory, on the system's
 * clock, and writing a heartbeat every thirty seconds unless told.
 */
@Suppress("LongParameterList")
public fun fleetWithDefaults(
    bots: List<Bot>,
    accounts: Accounts,
    endpoint: ApiEndpoint,
    scope: CoroutineScope,
    questions: suspend (String, Question) -> Boolean,
    out: (String) -> Unit,
    stop: StopPolicy? = null,
    directory: Path? = null,
    clock: Clock? = null,
    heartbeat: Duration? = null,
): Fleet =
    Fleet(
        bots,
        accounts,
        endpoint,
        stop ?: StopPolicy.NONE,
        scope,
        directory,
        questions = questions,
        clock = clock ?: Clock.System,
        out = out,
        heartbeat = heartbeat ?: DEFAULT_HEARTBEAT,
    )

/**
 * A run context on [wiki] with no fleet behind it, answering questions with [answer]: stopped by nothing,
 * with the default flags and no other arguments unless told.
 */
public fun recordingContextWithDefaults(
    wiki: Wiki,
    answer: (Question) -> Boolean,
    stopPolicy: StopPolicy? = null,
    options: CommonOptions? = null,
    arguments: List<String>? = null,
    directory: Path? = null,
): RecordingContext =
    RecordingContext(
        wiki,
        stopPolicy ?: StopPolicy.NONE,
        runOptionsOf(options),
        arguments.orEmpty(),
        directory,
        answer,
    )

/**
 * One stop for many bots, from what [read] answers: read at most once a minute, a good answer lasting five
 * minutes after a failed read, on the monotonic clock, unless told.
 */
public fun sharedStopWithDefaults(
    read: suspend () -> String?,
    every: Duration? = null,
    staleAfter: Duration? = null,
    timeSource: TimeSource? = null,
): SharedStop =
    SharedStop(
        read,
        every ?: DEFAULT_EVERY,
        staleAfter ?: DEFAULT_STALE_AFTER,
        timeSource ?: TimeSource.Monotonic,
    )
