import com.fenakhay.kwikibot.bot.BotPolicy as ClientBotPolicy
import com.fenakhay.kwikibot.bot.EditPermission as ClientEditPermission

/**
 * Whether a page has excluded a bot with `{{nobots}}` or `{{bots}}`, which wikis block bots for ignoring.
 * `allow` is a whitelist: everybody not on it is denied.
 */
@JsExport
public class BotPolicy(
    /** The account the bot edits as, without the bot-password suffix. */
    botName: String,
    /** The task, for `{{bots|optout=}}`, which some pages use to opt out of one kind of edit. */
    task: String? = null,
) {
    internal val policy: ClientBotPolicy = ClientBotPolicy(botName, task)

    /** Whether this bot may edit [wikitext]. */
    public fun check(wikitext: String): EditPermission = EditPermission(policy.check(wikitext))

    /** Whether this bot may edit a page already parsed. */
    public fun checkParsed(code: Wikitext): EditPermission = EditPermission(policy.check(code.markup))
}

/** Why a page may or may not be edited by a bot. */
@JsExport
public class EditPermission internal constructor(private val permission: ClientEditPermission) {

    /** Whether editing is allowed. */
    public val isAllowed: Boolean
        get() = permission.isAllowed

    /** Which exclusion applied, or `null` when nothing on the page objects. */
    public val reason: String?
        get() = (permission as? ClientEditPermission.Denied)?.reason
}
