package com.fenakhay.kwikibot.fleet

import com.fenakhay.kwikibot.bot.CommonOptions
import com.fenakhay.kwikibot.bot.run.PageOutcome
import com.fenakhay.kwikibot.bot.run.StopPolicy
import com.fenakhay.kwikibot.client.Family
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.LangCode
import kotlinx.io.files.Path

/**
 * What a run has of its host, and how it talks back.
 *
 * An interface, so a host other than [Fleet] can stand behind it, and a test with [RecordingContext]. A bot
 * says what it is doing and how much there is to do, asks a person when it has to, and checks whether it may
 * go on. Handing [outcomes] to a run's `onOutcome` gives most of that free.
 */
public interface RunContext {

    /** The wiki this bot's account edits, opened once and shared with every bot on the same account. */
    public val wiki: Wiki

    /** The fleet's stop page, to pass to `botRun`. */
    public val stopPolicy: StopPolicy

    /** The common flags this run was started with. */
    public val options: CommonOptions

    /** What the common flags did not take, in order, for the bot to read itself. */
    public val arguments: List<String>

    /** Where this run may write files, or `null` where there is no filesystem. */
    public val directory: Path?

    /** Another wiki, as the same account. */
    public suspend fun open(language: LangCode, family: Family): Wiki

    /** Says one line about what the run is doing. */
    public fun say(message: String, level: Level = Level.INFO)

    /** Says how much more work there is, so progress can be shown. Called per pass as totals become known. */
    public fun expect(items: Int)

    /** Says the run stopped before doing all it set out to. The first reason given is the one kept. */
    public fun stopped(reason: String)

    /** Runs [work] as a named phase of this run, whose context says things under that name. */
    public suspend fun <T> step(name: String, work: suspend (RunContext) -> T): T

    /**
     * Parks this lane and asks a person, while other bots keep going.
     *
     * @return whether they said yes. Where nobody can answer, the answer is no.
     */
    public suspend fun ask(question: Question): Boolean

    /**
     * Whether the run may go on, for a bot with a loop of its own.
     *
     * Fail-closed: a stop page that cannot be read answers `false`. A cancelled run throws instead of
     * answering.
     */
    public suspend fun mayContinue(): Boolean

    /** Hand this to a run's `onOutcome`, which counts each page and keeps the heartbeat going. */
    public val outcomes: (PageOutcome) -> Unit

    /** How much a line matters. */
    public enum class Level {
        /** What the run is doing. */
        INFO,

        /** A page changed. */
        SAVED,

        /** A page deliberately left alone. */
        SKIPPED,

        /** Something went wrong that did not end the run. */
        ERROR,
    }
}

/**
 * Something a run stopped to ask a person.
 *
 * @param title the question itself.
 * @param summary what saying yes does, and what saying no does.
 * @param diff what is about to change, as a unified diff, when there is one to show.
 */
public data class Question(val title: String, val summary: String = "", val diff: String? = null)

/**
 * Thrown by a bot whose question was answered no.
 *
 * Its own kind, because a refusal is a decision somebody made, and reads as one rather than as a failure.
 */
public class Declined(message: String) : Exception(message)
