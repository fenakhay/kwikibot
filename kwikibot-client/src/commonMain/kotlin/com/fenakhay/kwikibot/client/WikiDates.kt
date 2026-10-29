package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.SignatureFormat
import com.fenakhay.kwikibot.model.Signatures
import com.fenakhay.kwikibot.model.page.DatePages
import kotlinx.datetime.TimeZone

/**
 * Month names and signature formats taken from the wiki rather than from a table.
 *
 * An internationalisation library would answer a different question. CLDR says what French says; MediaWiki's
 * message files are translated separately and are what the wiki actually writes, and where they differ the
 * wiki is right by definition — it is the thing producing the text being parsed. So the names come from
 * `allmessages` and the zone from siteinfo, and the tables in `kwikibot-model` stay as the offline answer for
 * a dump reader or a test with no session.
 *
 * ```
 * val signatures = wiki.signatures()
 * val lastReply = signatures.latest(page.text)
 * ```
 */

/**
 * The month names this wiki writes, lowercased, mapped to their number.
 *
 * Both forms are included where a language has them. Polish writes `styczeń` on its own and `stycznia` in a
 * date, Russian `январь` and `января`; a parser that knows only one of the two fails on every signature or
 * half of every date page.
 */
public suspend fun Wiki.monthNames(): Map<String, Int> {
    val keys = MONTH_KEYS + MONTH_KEYS.map { "$it-gen" }
    val messages = meta.messages(keys)

    return buildMap {
        MONTH_KEYS.forEachIndexed { index, key ->
            val number = index + 1
            messages[key]?.let { put(it.lowercase(), number) }
            messages["$key-gen"]?.let { put(it.lowercase(), number) }
        }
    }
}

/**
 * Signature formats for this wiki, in its own words and its own zone.
 *
 * The shape of a signature — which field comes first, whether a comma follows the time — is a MediaWiki date
 * format that the API does not publish, so the patterns still come from the small table in [Signatures]. What
 * the wiki supplies is everything that varies more: the month names and the zone the times are written in.
 *
 * That zone is why this exists. A signature on de.wikipedia reads `21:43, 31. Aug. 2026 (CEST)` and the time
 * in it is Berlin time; reading it as UTC dates the reply two hours early.
 */
public suspend fun Wiki.signatures(): Signatures {
    val months = monthNames()
    val zone = runCatching { TimeZone.of(info.timezone) }.getOrDefault(TimeZone.UTC)

    return Signatures(
        Signatures.forLanguage(info.language.code).patterns().map { pattern ->
            SignatureFormat(
                language = info.language.code,
                pattern = pattern,
                months = months,
                zone = zone,
            )
        }
    )
}

/**
 * The day-page format for this wiki, or `null` if none is registered for its language.
 *
 * Only the month names come from the wiki. How a day page is *named* is a decision that community made —
 * de.wikipedia writes `15. Januar` where CLDR would say `15 Januar`, es `15 de enero` where CLDR says `15
 * enero`, and fr `1er janvier` for the first of the month — so the pattern stays in the registered format and
 * only the words are replaced.
 */
public suspend fun Wiki.dayTitleFormat(): DatePages.DayFormat? {
    val registered = DatePages.format(info.language.code) ?: return null
    val names = meta.messages(MONTH_KEYS)

    // A wiki missing a month message would otherwise yield a format with a hole in it, which
    // builds a title naming no month at all.
    val ordered = MONTH_KEYS.map { names[it] ?: return registered }
    return registered.copy(months = ordered)
}

/** The message keys MediaWiki stores month names under, in calendar order. */
private val MONTH_KEYS =
    listOf(
        "january",
        "february",
        "march",
        "april",
        "may",
        "june",
        "july",
        "august",
        "september",
        "october",
        "november",
        "december",
    )
