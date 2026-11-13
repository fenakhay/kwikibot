import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.bindings.testing.DEFAULT_FAKE_IDENTITY
import com.fenakhay.kwikibot.bindings.testing.fakeIdentityOf
import com.fenakhay.kwikibot.bindings.testing.fakeWikiIdOf
import com.fenakhay.kwikibot.bindings.testing.refusalNamed
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.client.service.EditBuilder
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.net.auth.Identity as ClientIdentity
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.ApiFailure
import com.fenakhay.kwikibot.testkit.FakePageService as ClientFakePageService
import com.fenakhay.kwikibot.testkit.FakeWiki as ClientFakeWiki
import com.fenakhay.kwikibot.testkit.MockTransport as ClientMockTransport
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The revision every page of a `FakePageService` is at before anything edits it. */
@JsExport public const val FAKE_INITIAL_REVISION: Double = 1.0

/** What a fake wiki says instead of applying an edit. */
@JsExport
public external interface FakeRefusal {
    /**
     * Why, as `EditResult.status` spells it: `"conflict"`, `"protected"`, `"permission-denied"`,
     * `"rate-limited"`, `"filtered"`, `"captcha"`, `"page-changed"` or `"rejected"`.
     */
    public val status: EditRefusal

    /** What the wiki says. */
    public val detail: String?

    /** On a `"conflict"`, the revision the page is on now. */
    public val currentRevision: Double?

    /** On a `"protected"`, the protection level that blocked the edit. */
    public val protectionLevel: String?

    /** On a `"protected"`, whether the protection comes from a page transcluding this one. */
    public val isCascadingProtection: Boolean?

    /** On a `"filtered"`, the filter name or blacklist entry. */
    public val filter: String?

    /** On a `"rate-limited"`, how long the wiki asks to wait, in milliseconds. */
    public val retryAfter: Double?

    /** On a `"captcha"`, the captcha the wiki wants solved. */
    public val captchaId: String?

    /** On a `"page-changed"`, whether the page was deleted. */
    public val wasDeleted: Boolean?

    /** On a `"rejected"`, the wiki's code for it. */
    public val code: String?
}

/** The error every edit of a fake wiki fails with, as a wiki would say it in its answer. */
@JsExport
public external interface FakeFailure {
    /**
     * The wiki's code, which decides the error, such as `"readonly"`, `"blocked"` or `"internal_api_error"`.
     */
    public val code: String

    /** What the wiki says. */
    public val info: String?
}

/** What a fake wiki's pages start as, and how they answer. */
@JsExport
public external interface FakePageServiceOptions {
    /** The pages it starts with, by title, prefix included. None unless given. */
    public val pages: Record<String, String>?

    /** The database name of the wiki its pages belong to; `"testwiki"` unless given. */
    public val wiki: String?

    /** A refusal to give instead of applying an edit to the page [title], or `null` to apply it. */
    public val refuse: ((title: String) -> FakeRefusal?)?

    /** The error to fail every edit with, for testing a bot's failure path. */
    public val failWith: (() -> FakeFailure)?

    /** What expanding wikitext makes of it, with the page it is expanded on. The text itself unless given. */
    public val expand: ((wikitext: String, title: String?) -> String)?
}

/**
 * A wiki's pages held in memory, for testing a bot with no wiki. Give it to `createFakeWiki`. Edits are
 * applied to the text it holds and recorded, so a test can check both what the bot decided and what the page
 * ended up saying. A stale `baseRevision` is a conflict, `createOnly` on a page that exists and `noCreate` on
 * one that does not are refused. It does not edit numbered sections, and an edit of one rejects saying so.
 */
@JsExport
public class FakePageService(options: FakePageServiceOptions? = null) {
    internal val service: ClientFakePageService =
        ClientFakePageService(
            options?.pages?.toMap().orEmpty(),
            fakeWikiIdOf(options?.wiki),
            { ref -> fromJs { options?.refuse?.invoke(nameOf(ref)) }?.let { refusalOf(ref, it) } },
            options?.failWith?.let { fail -> { wikiErrorOf(fromJs { fail() }) } },
            { text, ref -> fromJs { options?.expand?.invoke(text, ref?.let(::nameOf)) } ?: text },
        )

    /** The current text of the page [title], or `null` if it does not exist. */
    public fun text(title: String): String? = service.text(title)

    /** The revision the page [title] is at, or `null` if it does not exist. */
    public fun revision(title: String): Double? = service.revision(title)?.value?.toDouble()

    /** Whether the page [title] is on the fake watchlist. */
    public fun isWatched(title: String): Boolean = service.isWatched(title)

    /** Every edit that was applied, in order. Each read gives those so far, and does not change after. */
    public val edits: Array<FakeEdit>
        get() = service.edits.map { (ref, edit) -> FakeEdit(ref, edit) }.toTypedArray()
}

/** One edit a `FakePageService` applied, with everything the bot said of it. */
@JsExport
public class FakeEdit internal constructor(ref: PageRef, private val edit: EditBuilder) {

    /** The page edited, prefix included in English. */
    public val title: String = nameOf(ref)

    /** The new text, or `null` for an edit that only added some. */
    public val text: String?
        get() = edit.text

    /** The edit summary. */
    public val summary: String
        get() = edit.summary

    /** The text put before the page's. */
    public val prependText: String?
        get() = edit.prependText

    /** The text put after the page's. */
    public val appendText: String?
        get() = edit.appendText

    /** Whether it was marked minor. */
    public val minor: Boolean
        get() = edit.minor

    /** Whether it was marked as a bot's. */
    public val bot: Boolean
        get() = edit.bot

    /** The change tags on it. */
    public val tags: Array<String>
        get() = edit.tags.toTypedArray()

    /** The section it replaces, or `"new"`, or `null` for the whole page. */
    public val section: String?
        get() = edit.section

    /** The heading of a new section. */
    public val sectionTitle: String?
        get() = edit.sectionTitle

    /** The revision the text was computed from. */
    public val baseRevision: Double?
        get() = edit.baseRevision?.value?.toDouble()

    /** Whether the page had to not exist yet. */
    public val createOnly: Boolean
        get() = edit.createOnly

    /** Whether the page had to exist already. */
    public val noCreate: Boolean
        get() = edit.noCreate

    /** What it does to the watchlist, as `EditOptions` spells it. */
    public val watchlist: WatchMode
        get() = edit.watchlist.argumentName().unsafeCast<WatchMode>()

    /** How long the page is watched for. */
    public val watchlistExpiry: String?
        get() = edit.watchlistExpiry
}

/** A request a `MockTransport` was asked to send. */
@JsExport
public class MockRequest internal constructor(private val request: ApiRequest) {

    /** Its parameters. */
    public val params: Record<String, String>
        get() = request.params.toRecord { it }

    /** The `action` it names. */
    public val action: String
        get() = request.action

    /** Whether it is a write. */
    public val isWrite: Boolean
        get() = request.isWrite

    /** Whether its answer may be served from a cache. */
    public val cacheable: Boolean
        get() = request.cacheable
}

/**
 * A transport that answers from [handler] instead of a wiki, for what `wiki.call`, `wiki.callPaged`,
 * `wiki.tokens` and `wiki.paramInfo` send on a fake wiki. [handler] sees each request's parameters and
 * answers as a wiki would, in plain JSON or a promise of it. Throwing from it fails the call.
 */
@JsExport
public class MockTransport(
    handler: (params: Record<String, String>) -> Awaitable<Record<String, JsonValue>>,
    /** Where it claims to point; `test.example.org` unless given. */
    endpoint: Endpoint? = null,
) {
    internal val transport: ClientMockTransport =
        ClientMockTransport(endpoint?.endpoint ?: ApiEndpoint("test.example.org")) { request ->
            jsonObjectOf(settledCall { handler(request.params.toRecord { it }) })
        }

    /** Every request it was asked to send, in order. */
    public val requests: Array<MockRequest>
        get() = transport.requests.map(::MockRequest).toTypedArray()
}

/** Who a fake wiki says is logged in. */
@JsExport
public external interface FakeIdentity {
    /** The account name. */
    public val name: String

    /** The account's id; 1 unless given, and 0 means anonymous. */
    public val id: Double?

    /** The groups it is in; none unless given. */
    public val groups: Array<String>?

    /** The rights it has; none unless given. */
    public val rights: Array<String>?
}

/** What a fake wiki is made of. */
@JsExport
public external interface FakeWikiOptions {
    /** Its pages, to read back what was saved; an empty `FakePageService` unless given. */
    public val pages: FakePageService?

    /** Its database name; `"testwiki"` unless given. */
    public val id: String?

    /** The namespaces titles are parsed against; the canonical English ones unless given. */
    public val namespaces: Namespaces?

    /** Who it says is logged in; a bot called `TestBot` unless given. */
    public val identity: FakeIdentity?

    /**
     * What `wiki.call`, `wiki.callPaged`, `wiki.tokens` and `wiki.paramInfo` talk to. Unless given, one that
     * refuses, so a test cannot reach the network by accident.
     */
    public val transport: MockTransport?

    /**
     * A wiki to take `lists`, `revisions`, `users`, `logs`, `render` and `meta` from, such as one opened with
     * `globalThis.fetch` stubbed. Unless given, using one of them rejects, saying the fake does not implement
     * it.
     */
    public val services: Wiki?
}

/**
 * A wiki that exists only in memory, for testing a bot with no wiki: the facade `Wiki`, so `wiki.botRun`,
 * `wiki.page`, `wiki.pagesService` and the rest work on it. Pages are real. Anything not handed in is not
 * implemented, and rejects saying so, since a fake that answered with an empty list would make a test pass
 * while the bot did nothing: `files`, `extensions` and `proofread` always, and those of
 * [FakeWikiOptions.services] unless given. A listing rejects as it is walked; what answers at once, such as
 * `extensions.has`, throws.
 */
@JsExport
public fun createFakeWiki(options: FakeWikiOptions? = null): Wiki {
    withoutLoggingBanner()
    val from: ClientWiki? = options?.services?.wiki
    val identity = options?.identity
    val fake =
        ClientFakeWiki(
            options?.pages?.service ?: ClientFakePageService(),
            fakeWikiIdOf(options?.id),
            options?.namespaces?.namespaces ?: NamespaceMap.CANONICAL,
            if (identity == null) DEFAULT_FAKE_IDENTITY else identityOf(identity),
            from?.lists,
            from?.revisions,
            from?.users,
            from?.logs,
            from?.renderer,
            from?.meta,
            options?.transport?.transport as MediaWikiTransport?,
        )
    return Wiki(fake, CoroutineScope(SupervisorJob())) {}
}

private fun identityOf(identity: FakeIdentity): ClientIdentity =
    fakeIdentityOf(identity.name, identity.id?.toLong(), identity.groups?.asList(), identity.rights?.asList())

private fun nameOf(ref: PageRef): String = NamespaceMap.CANONICAL.format(ref.title)

private fun wikiErrorOf(failure: FakeFailure) =
    requireNotNull(
            ApiFailure.from(
                buildJsonObject {
                    put(
                        "error",
                        buildJsonObject {
                            put("code", failure.code)
                            put("info", failure.info.orEmpty())
                        },
                    )
                }
            )
        )
        .toWikiError()

private fun refusalOf(ref: PageRef, refusal: FakeRefusal): EditOutcome.Refused =
    refusalNamed(
        ref,
        refusal.status.unsafeCast<String>(),
        refusal.detail,
        refusal.currentRevision?.let { RevisionId(it.toLong()) },
        refusal.protectionLevel,
        refusal.isCascadingProtection,
        refusal.retryAfter?.milliseconds,
        refusal.filter,
        refusal.captchaId,
        refusal.wasDeleted,
        refusal.code,
    )
