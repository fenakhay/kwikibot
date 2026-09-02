package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.net.UserAgent
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One change, as the public event stream reports it.
 *
 * Wikimedia's stream carries every wiki at once, which is why [wiki] is on every event: a bot watching one
 * project has to filter, and filtering on the wrong field means acting on another wiki's edits.
 */
public data class WikiEvent(
    /** The database name of the wiki it happened on: `enwiktionary`. */
    val wiki: String,
    /** `edit`, `new`, `log`, `categorize`. */
    val type: String,
    /** The full title, with its namespace prefix. */
    val title: String,
    /** The namespace number, since the stream sends the number and not the name. */
    val namespace: Int,
    /** Who made the change, absent where the stream withheld it. */
    val user: String?,
    /** The edit summary or log reason. */
    val comment: String?,
    /** When it happened, as the stream reported it. */
    val timestamp: Instant?,
    /** The revision the change produced, absent on a log event. */
    val revisionId: Long? = null,
    /** The revision it was made from, absent on a page creation. */
    val previousRevisionId: Long? = null,
    /** Whether it was flagged a bot edit. */
    val isBot: Boolean = false,
    /** Whether the editor marked it minor. */
    val isMinor: Boolean = false,
    /**
     * The position in the stream this event was at.
     *
     * Kept so a bot that stops can resume from where it left off instead of from now, which is the difference
     * between missing an hour of edits and not.
     */
    val offset: String? = null,
) {
    /** Whether the event is from [wiki] and in one of [namespaces]. */
    public fun matches(wiki: String, namespaces: Set<Int> = emptySet()): Boolean =
        this.wiki == wiki && (namespaces.isEmpty() || namespace in namespaces)
}

/**
 * The live feed of changes across Wikimedia wikis.
 *
 * Server-sent events: one long-lived HTTP response that never finishes, with events separated by blank lines.
 * The framing is handled here rather than by a plugin, because it is a dozen lines and doing it in the open
 * makes the stream testable without a server.
 *
 * ```
 * EventStreams(client, userAgent).recentChanges()
 *     .filter { it.matches("enwiktionary", setOf(0)) }
 *     .collect { … }
 * ```
 *
 * The flow does not end on its own. Cancel the collecting coroutine to stop it; a dropped connection surfaces
 * as an exception rather than a silent end, so a caller cannot mistake a network failure for a quiet wiki.
 */
public class EventStreams(
    private val client: HttpClient,
    private val userAgent: UserAgent,
    private val baseUrl: String = WIKIMEDIA,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Recent changes, as they happen.
     *
     * @param since where to resume from: an offset from a previous [WikiEvent.offset], or a timestamp. `null`
     *   starts from now.
     */
    public fun recentChanges(since: String? = null): Flow<WikiEvent> = events("recentchange", since)

    /** Page creations, as they happen. */
    public fun pageCreations(since: String? = null): Flow<WikiEvent> = events("page-create", since)

    /**
     * The raw events of one stream.
     *
     * Exposed because Wikimedia adds streams faster than any library can name them.
     */
    public fun events(stream: String, since: String? = null): Flow<WikiEvent> = flow {
        client
            .prepareGet("$baseUrl/v2/stream/$stream") {
                header(HttpHeaders.UserAgent, userAgent.headerValue)
                header(HttpHeaders.Accept, "text/event-stream")
                // The server holds the connection open; a read timeout would end it every minute.
                header(HttpHeaders.CacheControl, "no-cache")
                since?.let { parameter("since", it) }
            }
            .execute { response ->
                readEvents(response.bodyAsChannel()) { event -> emit(event) }
            }
    }

    /**
     * Reads the server-sent-event framing off a channel.
     *
     * The format is one field per line, a blank line ending an event. Everything else — comment lines
     * starting with a colon, which the server sends as a keep-alive, and unrecognised fields — is ignored
     * rather than treated as an error.
     *
     * An event with no blank line after it is not emitted. That is the specified behaviour and the safe one:
     * a connection cut mid-event would otherwise deliver half a payload as though it were whole.
     */
    private suspend fun readEvents(channel: ByteReadChannel, emit: suspend (WikiEvent) -> Unit) {
        val data = StringBuilder()
        var id: String? = null

        while (true) {
            val line = channel.readLine() ?: return

            when {
                line.isEmpty() -> {
                    flush(data, id, emit)
                    data.clear()
                }

                // A comment line: the server sends one every so often to hold the connection.
                line.startsWith(":") -> Unit
                line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                else -> Unit
            }
        }
    }

    private suspend fun flush(
        data: StringBuilder,
        id: String?,
        emit: suspend (WikiEvent) -> Unit,
    ) {
        if (data.isEmpty()) return
        decode(data.toString(), id)?.let { emit(it) }
    }

    /**
     * Turns one event payload into a [WikiEvent], or `null` if it is not one.
     *
     * A payload this library cannot read is skipped rather than thrown: the stream is public and shared, and
     * one malformed event is no reason to end a bot run that has been watching for hours.
     */
    private fun decode(payload: String, id: String?): WikiEvent? {
        val event = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null

        val meta = event["meta"]?.jsonObject
        val title = event.string("title") ?: return null

        return WikiEvent(
            wiki = event.string("wiki") ?: meta?.string("domain").orEmpty(),
            type = event.string("type").orEmpty(),
            title = title,
            namespace = event["namespace"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            user = event.string("user"),
            comment = event.string("comment"),
            timestamp =
                event["timestamp"]?.jsonPrimitive?.longOrNull?.let { Instant.fromEpochSeconds(it) }
                    ?: meta?.string("dt")?.let { MwTimestamp.parseOrNull(it) },
            revisionId = event["revision"]?.jsonObject?.get("new")?.jsonPrimitive?.longOrNull,
            previousRevisionId = event["revision"]?.jsonObject?.get("old")?.jsonPrimitive?.longOrNull,
            isBot = event["bot"]?.jsonPrimitive?.content == "true",
            isMinor = event["minor"]?.jsonPrimitive?.content == "true",
            offset = id,
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

    /** Where Wikimedia serves the streams, and how long to wait before reconnecting. */
    public companion object {
        /** Wikimedia's public stream, which needs no credentials. */
        public const val WIKIMEDIA: String = "https://stream.wikimedia.org"
    }
}
