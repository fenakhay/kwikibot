package com.fenakhay.kwikibot.bindings.bot

import com.fenakhay.kwikibot.bot.source.PageSource
import com.fenakhay.kwikibot.bot.source.repeating
import com.fenakhay.kwikibot.bot.source.withContent
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow

private val DEFAULT_REPEAT_INTERVAL = 1.minutes

private const val DEFAULT_REPEAT_MEMORY = 5000

/** This source read again [every] minute, remembering the last [remember] pages, 5000, unless told. */
public fun PageSource.repeatingWithDefaults(every: Duration? = null, remember: Int? = null): PageSource =
    repeating(every ?: DEFAULT_REPEAT_INTERVAL, remember ?: DEFAULT_REPEAT_MEMORY)

/** These pages with their text, read from [wiki] as many to a request as the account may, unless told. */
public fun Flow<PageRef>.withContentWithDefaults(
    wiki: Wiki,
    batch: Int? = null,
    concurrency: Int? = null,
): Flow<PageContent> = withContent(wiki, batch ?: wiki.identity.batchLimit, concurrency ?: 1)
