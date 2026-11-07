package com.fenakhay.kwikibot.fleet

/**
 * A bot a [Fleet] can run.
 *
 * One interface, for as many bots as a project has. Everything a fleet does for a bot, the lane, the
 * schedule, the shared session and stop page, it does knowing nothing about the bot beyond what is declared
 * here.
 */
public interface Bot {

    /** Stable and lowercase. It names the bot on the command line and in everything a fleet reports. */
    public val id: String

    /** One sentence on what it does, for `list`. */
    public val description: String

    /**
     * Which account it edits as: the credentials under `KWIKIBOT_<ACCOUNT>_` in the environment, or `null`
     * for the fleet's default account.
     */
    public val account: String?
        get() = null

    /** When `serve` runs it, or `null` to run only when asked. */
    public val schedule: Schedule?
        get() = null

    /**
     * Does the work.
     *
     * Whatever this throws ends the run as failed, against this bot's lane only. Cancellation is a stop, not
     * a failure, and [Declined] says somebody answered no.
     */
    public suspend fun run(context: RunContext)
}

/** The bots a fleet knows about. */
public class BotRegistry(bots: List<Bot>) {

    private val byId = bots.associateBy { it.id }

    init {
        val repeated = bots.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "two bots share an id: $repeated" }
    }

    /** Every bot, in the order they were given. */
    public val all: List<Bot> = bots

    /** One bot, or `null` if there is none by that id. */
    public fun find(id: String): Bot? = byId[id]

    /** One bot, or a failure naming the ones there are. */
    public fun get(id: String): Bot =
        find(id) ?: error("there is no bot '$id'; there are: ${byId.keys.sorted()}")
}
