package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Wikitext

/** Why a page may or may not be edited by this bot. */
public sealed interface EditPermission {

    /** Nothing on the page objects. */
    public data object Allowed : EditPermission

    /** The page carries an exclusion this bot is covered by. */
    public data class Denied(
        /** Which exclusion applied, which is what a run report records. */
        val reason: String,
    ) : EditPermission

    /** Whether editing is allowed. */
    public val isAllowed: Boolean get() = this is Allowed
}

/**
 * Whether a page has excluded this bot with `{{nobots}}` or `{{bots}}`.
 *
 * Wikis block bots that edit pages carrying an exclusion, so this is checked before an edit is
 * computed rather than before it is saved.
 *
 * The rules, in the order they are checked:
 *
 * - `{{nobots}}` with no parameters denies every bot.
 * - `{{nobots|deny=all}}` denies every bot; `{{nobots|deny=FenaBot,OtherBot}}` denies those.
 * - `{{nobots|allow=FenaBot}}` denies every bot *except* those, which is the case most
 *   implementations get wrong by reading `allow` as a permission rather than as a whitelist.
 * - `{{bots|deny=…}}` and `{{bots|allow=…}}` mean the same as their `nobots` equivalents.
 * - `{{bots|optout=all}}` denies bots that respect opt-outs, which is what this is.
 *
 * ```
 * if (!BotPolicy("FenaBot").check(page.text).isAllowed) skip("excluded by {{nobots}}")
 * ```
 */
public class BotPolicy(
    /** The account the bot edits as, without the bot-password suffix. */
    private val botName: String,
    /**
     * The task, for `{{bots|optout=}}`.
     *
     * Some pages opt out of one kind of edit rather than all of them: `{{bots|optout=nosummary}}`
     * asks bots not to leave summary-only edits.
     */
    private val task: String? = null,
) {

    /** Whether this bot may edit a page. */
    public fun check(code: Markup): EditPermission {
        val templates = code.templates().filter { it.name.text.trim().lowercase() in NAMES }
        if (templates.isEmpty()) return EditPermission.Allowed

        return templates.firstNotNullOfOrNull { template ->
            val name = template.name.text.trim().lowercase()
            val deny = template.value("deny")
            val allow = template.value("allow")
            val optout = template.value("optout")

            when {
                // A bare {{nobots}} is the plainest form of "go away".
                name == NOBOTS && deny == null && allow == null -> denied("{{nobots}}")

                deny != null && covers(deny) -> denied("{{$name|deny=$deny}}")

                // "allow" is a whitelist, not a permission: everybody not on it is denied.
                allow != null && !covers(allow) -> denied("{{$name|allow=$allow}}")

                optout != null && optedOut(optout) -> denied("{{$name|optout=$optout}}")

                else -> null
            }
        } ?: EditPermission.Allowed
    }

    /** Whether this bot may edit wikitext. */
    public fun check(wikitext: String): EditPermission = check(Wikitext.parse(wikitext))

    /** Whether a list names this bot, or names everybody. */
    private fun covers(value: String): Boolean {
        val names = value.split(',').map { it.trim() }
        return names.any { it.equals(ALL, ignoreCase = true) || it.equals(botName, ignoreCase = true) }
    }

    /**
     * Whether an opt-out covers this bot's task.
     *
     * `optout=all` covers everything. A named opt-out only applies when the bot said what it was
     * doing; a bot that did not name its task is not covered by a task-specific opt-out, since
     * there is nothing to compare.
     */
    private fun optedOut(value: String): Boolean {
        val kinds = value.split(',').map { it.trim() }
        if (kinds.any { it.equals(ALL, ignoreCase = true) }) return true

        val task = task ?: return false
        return kinds.any { it.equals(task, ignoreCase = true) }
    }

    private fun denied(reason: String) = EditPermission.Denied(reason)

    private companion object {
        const val NOBOTS = "nobots"
        const val ALL = "all"
        val NAMES = setOf("bots", NOBOTS)
    }
}
