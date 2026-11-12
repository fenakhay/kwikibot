import com.fenakhay.kwikibot.bindings.client.allUsersWithDefaults
import com.fenakhay.kwikibot.bindings.client.blockWithDefaults
import com.fenakhay.kwikibot.bindings.client.blocksWithDefaults
import com.fenakhay.kwikibot.bindings.client.changeRightsWithDefaults
import com.fenakhay.kwikibot.bindings.client.contributionsWithDefaults
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.user.BlockInfo as ClientBlockInfo
import com.fenakhay.kwikibot.model.user.BlockRecord as ClientBlockRecord
import com.fenakhay.kwikibot.model.user.Contribution as ClientContribution
import com.fenakhay.kwikibot.model.user.UserInfo as ClientUserInfo
import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise

/** The options of `contributions`. */
@JsExport
public external interface ContributionsOptions {
    /** Only edits in these namespaces, by number. Every namespace unless given. */
    public val namespaces: Array<Int>?

    /** Where in time to begin. */
    public val start: Date?

    /** Where in time to stop. */
    public val end: Date?

    /**
     * Filters the wiki applies, from `"minor"`, `"new"`, `"top"`, `"patrolled"` and `"autopatrolled"`: each
     * is required, or left out with a leading `!`, such as `["new", "!minor"]`.
     */
    public val show: Array<ContributionFilter>?

    /** Only edits carrying this change tag. */
    public val tag: String?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `block`. */
@JsExport
public external interface BlockOptions {
    /** When the block ends. It does not unless given. */
    public val expiry: Date?

    /**
     * Whether to block only logged-out edits from this address, which leaves it usable by account holders.
     */
    public val anonymousOnly: Boolean?

    /** Whether to stop new accounts being made from it, `true` unless given. */
    public val preventAccountCreation: Boolean?

    /** Whether to extend the block to addresses the account then uses, `true` unless given. */
    public val autoBlock: Boolean?
}

/** The options of `blocks`. */
@JsExport
public external interface BlocksOptions {
    /** Only these targets, which is cheaper than listing and filtering. */
    public val users: Array<String>?

    /**
     * Which kinds to include, from `"account"`, `"ip"`, `"range"` and `"temp"`: each is required, or left out
     * with a leading `!`, such as `["!temp"]`.
     */
    public val show: Array<BlockFilter>?

    /** Where in time to begin. */
    public val start: Date?

    /** Where in time to stop. */
    public val end: Date?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `allUsers`. */
@JsExport
public external interface AllUsersOptions {
    /** The account name to start at. */
    public val from: String?

    /** The account name to stop at. */
    public val to: String?

    /** Only accounts beginning with this. */
    public val prefix: String?

    /** Only accounts in these groups. */
    public val group: Array<String>?

    /** Whether to skip accounts that have never edited, which on a large wiki is most of them. */
    public val withEditsOnly: Boolean?

    /** Whether to skip the accounts a wiki auto-creates for logged-out editors. */
    public val excludeTemporary: Boolean?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `changeRights`, which needs [add] or [remove] to have something in it. */
@JsExport
public external interface ChangeRightsOptions {
    /** The groups to add. */
    public val add: Array<String>?

    /** The groups to take away. */
    public val remove: Array<String>?

    /** The log summary. */
    public val reason: String?

    /** How long the added memberships last, as MediaWiki spells durations. Permanent unless given. */
    public val expiry: String?
}

/** The people editing a wiki, reached as `wiki.users`. */
@JsExport
public class UserService
internal constructor(private val wiki: ClientWiki, private val scope: CoroutineScope) {

    /**
     * What the wiki knows about the accounts [names], by the name asked for. A name with no account behind it
     * comes back with `isMissing` set.
     */
    public fun info(names: Array<String>): Promise<Record<String, UserInfo>> = scope.promise {
        wiki.users.info(names.toList()).toRecord(::UserInfo)
    }

    /** The account this session is logged in as, with the rights the session has. */
    public fun current(): Promise<UserInfo> = scope.promise { UserInfo(wiki.users.current()) }

    /** The edits [user], an account or address, has made, newest first. */
    public fun contributions(
        user: String,
        options: ContributionsOptions? = null,
    ): AsyncIterable<Contribution> = listing {
        wiki.contributionsWithDefaults(
            user,
            options?.namespaces?.asList(),
            options?.start?.let(::instantOf),
            options?.end?.let(::instantOf),
            options?.show?.map { it.unsafeCast<String>() },
            options?.tag,
            options?.limit,
        )
    }
        .toAsyncIterable(scope) { Contribution(it, wiki.namespaces) }

    /**
     * Blocks [user], an account or address, with [reason], which they will see. Needs the `block` right.
     * Rejects with what the wiki said when it refuses.
     */
    public fun block(user: String, reason: String, options: BlockOptions? = null): Promise<Unit> =
        scope.promiseVoid {
            wiki.blockWithDefaults(
                user,
                reason,
                options?.expiry?.let(::instantOf),
                options?.anonymousOnly,
                options?.preventAccountCreation,
                options?.autoBlock,
            )
        }

    /** Lifts the block on [user]. Needs the `block` right. */
    public fun unblock(user: String, reason: String = ""): Promise<Unit> = scope.promiseVoid {
        wiki.users.unblock(user, reason)
    }

    /** The blocks in force, newest first. */
    public fun blocks(options: BlocksOptions? = null): AsyncIterable<BlockRecord> = listing {
        wiki.blocksWithDefaults(
            options?.users?.asList(),
            options?.show?.map { it.unsafeCast<String>() },
            options?.start?.let(::instantOf),
            options?.end?.let(::instantOf),
            options?.limit,
        )
    }
        .toAsyncIterable(scope, ::BlockRecord)

    /** Every account on the wiki, in name order. */
    public fun allUsers(options: AllUsersOptions? = null): AsyncIterable<UserInfo> = listing {
        wiki.allUsersWithDefaults(
            options?.from,
            options?.to,
            options?.prefix,
            options?.group?.asList(),
            options?.withEditsOnly,
            options?.excludeTemporary,
            options?.limit,
        )
    }
        .toAsyncIterable(scope, ::UserInfo)

    /**
     * Adds [user] to groups, takes them from others, or both. Needs the `userrights` right.
     *
     * Rejects when nothing is added or taken away, or when one group is both.
     */
    public fun changeRights(user: String, options: ChangeRightsOptions? = null): Promise<Unit> =
        scope.promiseVoid {
            wiki.changeRightsWithDefaults(
                user,
                options?.add?.asList(),
                options?.remove?.asList(),
                options?.reason,
                options?.expiry,
            )
        }
}

/** What a wiki knows about one account, at the moment it was asked. */
@JsExport
public class UserInfo internal constructor(private val user: ClientUserInfo) {

    /** The account name, spelled as the wiki spells it. */
    public val name: String
        get() = user.name

    /** The account id, or `null` for an anonymous editor. */
    public val id: Double?
        get() = user.id?.toDouble()

    /** The groups the account is in. */
    public val groups: Array<String>
        get() = user.groups.toTypedArray()

    /** What the account may do. */
    public val rights: Array<String>
        get() = user.rights.toTypedArray()

    /** How many edits the account has made. */
    public val editCount: Double
        get() = user.editCount.toDouble()

    /** When the account was created, or `null` for one older than the wiki's records. */
    public val registration: Date?
        get() = user.registration?.let(::dateOf)

    /** The block in force, or `null` when there is none. */
    public val block: BlockInfo?
        get() = user.block?.let(::BlockInfo)

    /** Whether the name is an IP address rather than an account. */
    public val isAnonymous: Boolean
        get() = user.isAnonymous

    /**
     * Whether this is an account the wiki auto-created for a logged-out editor, who will most likely never
     * see a message left on it.
     */
    public val isTemporary: Boolean
        get() = user.isTemporary

    /** Whether there is no such account. */
    public val isMissing: Boolean
        get() = user.isMissing

    /** The grammatical gender the account chose, or `null`. */
    public val gender: String?
        get() = user.gender

    /** Whether this user can be sent email through the wiki. */
    public val emailable: Boolean
        get() = user.emailable

    /** Whether the account is blocked. */
    public val isBlocked: Boolean
        get() = user.isBlocked

    /** Whether the editor is a person's own account: not an address, a temporary account or a missing one. */
    public val isRegistered: Boolean
        get() = user.isRegistered

    /** Whether the user holds [right]. */
    public fun hasRight(right: String): Boolean = user.hasRight(right)

    /** Whether the user is in [group]. Prefer [hasRight]: groups are how rights are granted. */
    public fun inGroup(group: String): Boolean = user.inGroup(group)
}

/** A block in force. */
@JsExport
public class BlockInfo internal constructor(private val block: ClientBlockInfo) {

    /** The block's own id, or `null` when the wiki did not say. */
    public val id: Double?
        get() = block.id?.toDouble()

    /** Who imposed it, or `null` when the wiki did not say. */
    public val by: String?
        get() = block.by

    /** The reason given, or `null` when the wiki did not say. */
    public val reason: String?
        get() = block.reason

    /** When it started, or `null` when the wiki did not say. */
    public val since: Date?
        get() = block.since?.let(::dateOf)

    /** When it ends, or `null` if it does not. */
    public val expiry: Date?
        get() = block.expiry?.let(::dateOf)

    /** Whether it covers only some pages or actions rather than the whole wiki. */
    public val isPartial: Boolean
        get() = block.isPartial

    /** Whether it never expires. */
    public val isInfinite: Boolean
        get() = block.isInfinite
}

/** One entry in a wiki's block list, which names what is blocked. */
@JsExport
public class BlockRecord internal constructor(private val record: ClientBlockRecord) {

    /** The block itself. */
    public val info: BlockInfo
        get() = BlockInfo(record.info)

    /** The account, address or range blocked, or `null` when that is hidden. */
    public val target: String?
        get() = record.target

    /** Whether the wiki applied this itself, following an address used by a blocked account. */
    public val isAutomatic: Boolean
        get() = record.isAutomatic

    /** Whether the target is an address range. */
    public val isRange: Boolean
        get() = record.isRange
}

/** One edit by a user. */
@JsExport
public class Contribution
internal constructor(private val contribution: ClientContribution, private val namespaces: NamespaceMap) {

    /** The title of the page edited. */
    public val page: String
        get() = namespaces.format(contribution.page.title)

    /** The wiki's own id for that page, or `null` when the wiki did not say. */
    public val pageId: Double?
        get() = contribution.page.pageId?.value?.toDouble()

    /** The revision the edit produced. */
    public val revision: Revision
        get() = Revision(contribution.revision)

    /** Whether this edit created the page. */
    public val isNew: Boolean
        get() = contribution.isNew

    /** Whether it is still the page's newest revision. */
    public val isTop: Boolean
        get() = contribution.isTop

    /** How many bytes the page gained, negative for a removal. */
    public val sizeChange: Int
        get() = contribution.sizeChange
}
