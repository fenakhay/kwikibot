import com.fenakhay.kwikibot.bindings.client.applyTagsWithDefaults
import com.fenakhay.kwikibot.bindings.client.languagesWithDefaults
import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.bindings.common.enumArgument
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.client.service.TagOperation as ClientTagOperation
import com.fenakhay.kwikibot.model.LanguageInfo as ClientLanguageInfo
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise

/** The options of `applyTags`, which needs a tag to add or remove and something to put it on. */
@JsExport
public external interface ApplyTagsOptions {
    /** The tags to apply. */
    public val add: Array<String>?

    /** The tags to take off. */
    public val remove: Array<String>?

    /** Revision ids to tag. */
    public val revisions: Array<Double>?

    /** Recent-changes ids to tag. */
    public val recentChanges: Array<Double>?

    /** Log ids to tag. */
    public val logEntries: Array<Double>?

    /** Why, which is recorded in the tag log. */
    public val reason: String?
}

/**
 * What a wiki says about itself beyond its site information, reached as `wiki.meta`: fetched when first asked
 * for and kept for as long as the wiki is open.
 */
@JsExport
public class MetaService
internal constructor(private val wiki: ClientWiki, private val scope: CoroutineScope) {

    /**
     * One `siprop` block of the wiki's site information, such as `"rightsinfo"`, as plain JSON, or `null`
     * when the wiki does not have it.
     */
    public fun property(name: String): Promise<JsonValue?> = scope.promise {
        wiki.meta.property(name)?.let(::plainOf)
    }

    /** The interface messages [keys] the wiki has, in [language], the wiki's own unless given. */
    public fun messages(keys: Array<String>, language: String? = null): Promise<Record<String, String>> =
        scope.promise {
            wiki.meta.messages(keys.toList(), language).toRecord { it }
        }

    /** The interface message [key], or `null` when the wiki has no such message. */
    public fun message(key: String, language: String? = null): Promise<String?> = scope.promise {
        wiki.meta.message(key, language)
    }

    /** The magic words the wiki knows, by name, with every alias it accepts. */
    public fun magicWords(): Promise<Record<String, Array<String>>> = scope.promise {
        wiki.meta.magicWords().toRecord { it.toTypedArray() }
    }

    /** The wiki's own counters: `pages`, `articles`, `edits`, `users`, `activeusers`. */
    public fun statistics(): Promise<Record<String, Double>> = scope.promise {
        wiki.meta.statistics().toRecord { it.toDouble() }
    }

    /**
     * What the wiki knows about languages, by code: [codes], or every one it knows, several hundred, unless
     * given.
     */
    public fun languages(codes: Array<String>? = null): Promise<Record<String, LanguageInfo>> =
        scope.promise {
            wiki.languagesWithDefaults(codes?.asList()).mapKeys { it.key.code }.toRecord(::LanguageInfo)
        }

    /**
     * Adds or removes change tags on revisions, recent changes or log entries. Needs the `changetags` right,
     * and `applychangetags` for the tags themselves.
     */
    public fun applyTags(options: ApplyTagsOptions): Promise<Unit> = scope.promiseVoid {
        wiki.applyTagsWithDefaults(
            options.add?.asList(),
            options.remove?.asList(),
            options.revisions?.map { it.toLong() },
            options.recentChanges?.map { it.toLong() },
            options.logEntries?.map { it.toLong() },
            options.reason,
        )
    }

    /**
     * Defines, retires or removes a change tag. Needs the `managechangetags` right.
     *
     * @param tag the tag.
     * @param operation `"create"`, `"delete"`, `"activate"` or `"deactivate"`.
     * @param reason why, which is recorded in the tag log.
     */
    public fun manageTag(tag: String, operation: TagOperation, reason: String = ""): Promise<Unit> =
        scope.promiseVoid {
            wiki.meta.manageTag(
                tag,
                enumArgument<ClientTagOperation>("operation", operation.unsafeCast<String>()),
                reason,
            )
        }
}

/** What a wiki knows about a language. */
@JsExport
public class LanguageInfo internal constructor(private val info: ClientLanguageInfo) {

    /** The wiki's own code for it. */
    public val code: String
        get() = info.code.code

    /** The name in the wiki's content language, such as `"French"`. */
    public val name: String
        get() = info.name

    /** The name in the language itself, such as `"français"`. */
    public val autonym: String
        get() = info.autonym

    /** Which way it is written: `"left-to-right"` or `"right-to-left"`. */
    public val direction: TextDirection
        get() = info.direction.argumentName().unsafeCast<TextDirection>()

    /** The languages the wiki falls back to when this one has no translation, in order. */
    public val fallbacks: Array<String>
        get() = info.fallbacks.map { it.code }.toTypedArray()

    /** The IETF tag, which is not always the wiki's own code, or `null`. */
    public val bcp47: String?
        get() = info.bcp47
}
