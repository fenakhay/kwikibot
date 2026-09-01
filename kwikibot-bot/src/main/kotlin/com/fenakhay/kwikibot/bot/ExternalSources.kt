package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.PageRef
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Page lists built somewhere other than the wiki.
 *
 * PetScan and PagePile are Toolforge tools that Wikimedia editors use to build a working list —
 * "every article in this category tree that has no image" — and then hand to a bot. Supporting
 * them is how a bot takes a list a person assembled rather than making the person describe it
 * again in bot arguments.
 *
 * These are separate from [PageSourceSpec] until [register] is called, because they need an HTTP
 * client and a source spec is parsed before there is one.
 */
public object ExternalSources {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The pages a PetScan query returns.
     *
     * @param id the PSID of a saved query. A saved query rather than the parameters themselves,
     *   because a PetScan query has forty of them and a bot repeating one should repeat exactly
     *   the query a person checked.
     * @param client the HTTP client to fetch with. PetScan is not a wiki, so the wiki's own
     *   transport does not apply.
     */
    public fun petScan(id: String, client: HttpClient): PageSource = PageSource { wiki ->
        flow {
            val body = client.get(PETSCAN) {
                parameter("psid", id)
                parameter("format", "json")
                parameter("doit", "1")
            }.bodyAsText()

            titlesFromPetScan(body).forEach { title -> emit(wiki.refOrSkip(title) ?: return@forEach) }
        }
    }

    /**
     * The pages of a PagePile.
     *
     * A pile is a saved, fixed list: it returns the same pages next week, which a category
     * does not.
     */
    public fun pagePile(id: String, client: HttpClient): PageSource = PageSource { wiki ->
        flow {
            val body = client.get(PAGE_PILE) {
                parameter("id", id)
                parameter("action", "get_data")
                parameter("format", "json")
                parameter("doit", "1")
            }.bodyAsText()

            titlesFromPagePile(body).forEach { title -> emit(wiki.refOrSkip(title) ?: return@forEach) }
        }
    }

    /** Titles out of a PetScan result. */
    internal fun titlesFromPetScan(body: String): List<String> =
        json.parseToJsonElement(body).jsonObject["*"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("a")?.jsonObject
            ?.get("*")?.jsonArray
            ?.mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.content }
            .orEmpty()

    /** Titles out of a PagePile result. */
    internal fun titlesFromPagePile(body: String): List<String> =
        json.parseToJsonElement(body).jsonObject["pages"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .orEmpty()

    /**
     * A title the wiki accepts, or `null` if it does not.
     *
     * An external list is unvalidated input and will contain titles this wiki rejects or that
     * name another project. Skipping them keeps one bad row from ending a long run.
     */
    private fun Wiki.refOrSkip(title: String): PageRef? = runCatching { ref(title) }.getOrNull()

    private const val PETSCAN = "https://petscan.wmcloud.org/"
    private const val PAGE_PILE = "https://pagepile.toolforge.org/api.php"
}

/**
 * Repeats a source, emitting only what it has not emitted before.
 *
 * The shape of a bot that watches a wiki rather than sweeping it: poll, act on what is new, wait,
 * poll again. The seen set is bounded so that a long-running process does not accumulate every
 * title it has encountered.
 *
 * The flow never ends on its own. Cancel the collecting coroutine to stop it.
 *
 * @param every how long to wait between rounds. Shorter than the wiki's own pace is pointless:
 *   recent changes does not update faster than edits arrive.
 * @param remember how many titles to keep in the seen set, which bounds what a
 *   long-running process accumulates.
 */
public fun PageSource.repeating(
    every: Duration = DEFAULT_INTERVAL,
    remember: Int = DEFAULT_MEMORY,
): PageSource = PageSource { wiki ->
    flow {
        val seen = ArrayDeque<PageRef>()
        val seenSet = mutableSetOf<PageRef>()

        while (true) {
            pages(wiki).collect { ref ->
                if (seenSet.add(ref)) {
                    seen.addLast(ref)
                    // Bounded on purpose: a bot watching a wiki for a month would otherwise hold
                    // a month of titles, and the oldest of them can no longer come round again.
                    if (seen.size > remember) seenSet.remove(seen.removeFirst())
                    emit(ref)
                }
            }
            delay(every)
        }
    }
}

/** How long a repeating source waits between rounds. */
private val DEFAULT_INTERVAL = 1.minutes

/** How many pages a repeating source remembers having emitted. */
private const val DEFAULT_MEMORY = 5000
