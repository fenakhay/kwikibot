@file:Suppress("TooManyFunctions")

package com.fenakhay.kwikibot.bindings.client

import com.fenakhay.kwikibot.bindings.common.Setting
import com.fenakhay.kwikibot.bindings.common.resolve
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.model.AbuseFilter
import com.fenakhay.kwikibot.client.model.Notification
import com.fenakhay.kwikibot.client.model.TriagePage
import com.fenakhay.kwikibot.client.model.TriageStats
import com.fenakhay.kwikibot.client.service.AbuseFilterOrder
import com.fenakhay.kwikibot.client.service.AbuseFilterProperty
import com.fenakhay.kwikibot.client.service.NewPagesOrder
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageRef
import kotlinx.coroutines.flow.Flow

private const val DEFAULT_RADIUS = 1000
private const val DEFAULT_NEARBY = 10
private const val DEFAULT_SENTENCES = 2
private const val DEFAULT_NOTIFICATIONS = 25
private const val DEFAULT_SOURCE = "kwikibot"

/** The pages near a point, within [radius] metres, 1000 unless given, at most [limit], 10 unless given. */
public suspend fun Wiki.nearbyWithDefaults(
    latitude: Double,
    longitude: Double,
    radius: Int? = null,
    limit: Int? = null,
): List<PageRef> = extensions.nearby(latitude, longitude, radius ?: DEFAULT_RADIUS, limit ?: DEFAULT_NEARBY)

/** The opening [sentences] sentences of each of [titles], 2 unless given, or the whole lead with `0`. */
public suspend fun Wiki.extractsWithDefaults(
    titles: Collection<String>,
    sentences: Int? = null,
): Map<PageRef, String> = extensions.extracts(refs(titles), sentences ?: DEFAULT_SENTENCES)

/** This session's notifications, read or not unless told, at most [limit], 25 unless given. */
public suspend fun Wiki.notificationsWithDefaults(
    unreadOnly: Boolean? = null,
    limit: Int? = null,
): List<Notification> = extensions.notifications(unreadOnly ?: false, limit ?: DEFAULT_NOTIFICATIONS)

/** Thanks the author of [revision], saying it came from [source], `"kwikibot"` unless given. */
public suspend fun Wiki.thankWithDefaults(revision: RevisionId, source: String? = null) {
    extensions.thank(revision, source ?: DEFAULT_SOURCE)
}

/** Marks [revision] reviewed with [flags], the wiki's defaults unless given, and [comment]. */
public suspend fun Wiki.reviewWithDefaults(
    revision: RevisionId,
    flags: Map<String, Int>? = null,
    comment: String? = null,
) {
    extensions.review(revision, flags.orEmpty(), comment.orEmpty())
}

/** The abuse filters: every one, with the usual properties, lowest id first unless told. */
public fun Wiki.abuseFiltersWithDefaults(
    show: Collection<String>? = null,
    properties: Collection<AbuseFilterProperty>? = null,
    order: AbuseFilterOrder? = null,
    limit: Int? = null,
): Flow<AbuseFilter> =
    extensions.abuseFilters(
        optionSetOf(show),
        properties?.toSet() ?: AbuseFilterProperty.DEFAULT,
        order ?: AbuseFilterOrder.LOWEST_ID_FIRST,
        limit,
    )

/**
 * Which pages of the new pages feed [name] asks for: `"unreviewed"`, the default, `"reviewed"`, or `"all"`,
 * which is either.
 *
 * @throws IllegalArgumentException when it names none of them.
 */
public fun reviewedNamed(name: String?): Setting<Boolean> =
    when (name) {
        null -> Setting.Default
        "unreviewed" -> Setting.Value(false)
        "reviewed" -> Setting.Value(true)
        "all" -> Setting.Off
        else ->
            throw IllegalArgumentException(
                "reviewed cannot be '$name': it is one of 'unreviewed', 'reviewed', 'all'"
            )
    }

/**
 * The pages in the new pages feed: in the main namespace, unreviewed, no redirects, by anyone, own pages
 * included, newest first unless told.
 */
@Suppress("LongParameterList")
public fun Wiki.newPagesWithDefaults(
    namespace: Int? = null,
    reviewed: Setting<Boolean> = Setting.Default,
    redirects: Boolean? = null,
    creators: Collection<String>? = null,
    hideOwnPages: Boolean? = null,
    order: NewPagesOrder? = null,
    limit: Int? = null,
): Flow<TriagePage> =
    extensions.newPages(
        namespaceOf(namespace),
        reviewed.resolve(false),
        redirects ?: false,
        creators?.toSet().orEmpty(),
        hideOwnPages ?: false,
        order ?: NewPagesOrder.NEWEST_FIRST,
        limit,
    )

/** How long the new pages backlog is: in the main namespace, by anyone, own pages included unless told. */
public suspend fun Wiki.newPageStatsWithDefaults(
    namespace: Int? = null,
    creators: Collection<String>? = null,
    hideOwnPages: Boolean? = null,
): TriageStats =
    extensions.newPageStats(namespaceOf(namespace), creators?.toSet().orEmpty(), hideOwnPages ?: false)
