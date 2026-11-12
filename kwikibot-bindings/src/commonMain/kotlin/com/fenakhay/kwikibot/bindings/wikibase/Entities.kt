package com.fenakhay.kwikibot.bindings.wikibase

import com.fenakhay.kwikibot.wikibase.EntityService
import com.fenakhay.kwikibot.wikibase.value.EntityId

/** Merges the item [from] into [into], overwriting none of the conflicting parts unless told. */
public suspend fun EntityService.mergeItemsWithDefaults(
    from: String,
    into: String,
    ignoreConflicts: Collection<String>? = null,
    summary: String? = null,
) {
    mergeItems(EntityId(from), EntityId(into), ignoreConflicts?.toSet().orEmpty(), summary.orEmpty())
}
