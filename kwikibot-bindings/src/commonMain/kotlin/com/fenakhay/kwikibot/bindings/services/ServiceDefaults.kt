package com.fenakhay.kwikibot.bindings.services

import com.fenakhay.kwikibot.bindings.connect.userAgent
import com.fenakhay.kwikibot.client.model.PageviewAccess
import com.fenakhay.kwikibot.client.model.PageviewAgent
import com.fenakhay.kwikibot.client.model.PageviewGranularity
import com.fenakhay.kwikibot.client.model.Pageviews
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.UserAgent
import io.ktor.client.HttpClient

/** How finely pageviews are counted: [granularity], or by day unless given. */
public fun granularityOf(granularity: PageviewGranularity?): PageviewGranularity =
    granularity ?: PageviewGranularity.DAILY

/** Which views are counted: [access], or from every kind of site unless given. */
public fun accessOf(access: PageviewAccess?): PageviewAccess = access ?: PageviewAccess.ALL_ACCESS

/** Whose views are counted: [agent], or people's unless given. */
public fun agentOf(agent: PageviewAgent?): PageviewAgent = agent ?: PageviewAgent.USER

/**
 * Wikimedia's pageviews service over [http]: as this library for [contact], retried as the library retries,
 * at Wikimedia's own address, unless told.
 */
public fun pageviewsWithDefaults(
    http: HttpClient,
    contact: String,
    userAgent: UserAgent? = null,
    retry: RetryPolicy? = null,
    baseUrl: String? = null,
): Pageviews =
    Pageviews(http, userAgent(contact, userAgent), retry ?: RetryPolicy(), baseUrl ?: Pageviews.WIKIMEDIA)
