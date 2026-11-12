package com.fenakhay.kwikibot.bindings.client

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.LanguageInfo

/** What the wiki knows about the languages [codes], or about every one it knows unless given. */
public suspend fun Wiki.languagesWithDefaults(
    codes: Collection<String>? = null
): Map<LangCode, LanguageInfo> = meta.languages(codes?.map(::LangCode).orEmpty())

/**
 * Adds the tags [add] to and takes [remove] from the [revisions], [recentChanges] and [logEntries] given,
 * each none unless given.
 */
@Suppress("LongParameterList")
public suspend fun Wiki.applyTagsWithDefaults(
    add: Collection<String>? = null,
    remove: Collection<String>? = null,
    revisions: Collection<Long>? = null,
    recentChanges: Collection<Long>? = null,
    logEntries: Collection<Long>? = null,
    reason: String? = null,
) {
    meta.applyTags(
        add?.toSet().orEmpty(),
        remove?.toSet().orEmpty(),
        revisions?.toList().orEmpty(),
        recentChanges?.toList().orEmpty(),
        logEntries?.toList().orEmpty(),
        reason.orEmpty(),
    )
}
