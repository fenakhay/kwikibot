package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.hasExtension
import com.fenakhay.kwikibot.client.requireExtension
import com.fenakhay.kwikibot.client.service.ProofreadQuality
import com.fenakhay.kwikibot.client.service.ProofreadService
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.SiteInfo
import com.fenakhay.kwikibot.protocol.decode.Continuation
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class ApiProofreadService(
    transport: MediaWikiTransport,
    private val decoder: PageDecoder,
    private val namespaces: NamespaceMap,
    private val info: SiteInfo,
    private val batchSize: Int = DEFAULT_BATCH,
) : ProofreadService {

    private val continuation = Continuation(transport)

    override suspend fun quality(refs: Collection<PageRef>): Map<PageRef, ProofreadQuality> {
        requireExtension()
        if (refs.isEmpty()) return emptyMap()

        val found = mutableMapOf<Title.Local, ProofreadQuality>()
        for (batch in refs.map { it.title }.distinct().chunked(batchSize)) {
            continuation
                .pages(
                    ApiRequest.of(
                        "query",
                        "prop" to "proofread",
                        "titles" to batch.joinToString("|") { namespaces.format(it) },
                    )
                )
                .toList()
                .forEach { page ->
                    val ref = decoder.refOf(page) ?: return@forEach
                    val level = page["proofread"]?.jsonObject?.get("quality")?.jsonPrimitive?.intOrNull
                    ProofreadQuality.of(level ?: return@forEach)?.let { found[ref.title] = it }
                }
        }

        return refs.mapNotNull { ref -> found[ref.title]?.let { ref to it } }.toMap()
    }

    override suspend fun indexOf(ref: PageRef): PageRef? {
        requireExtension()

        val page =
            continuation
                .pages(
                    ApiRequest.of(
                        "query",
                        "prop" to "proofread",
                        "titles" to namespaces.format(ref.title),
                    )
                )
                .toList()
                .firstOrNull() ?: return null

        val index = page["proofread"]?.jsonObject?.get("index")?.jsonPrimitive?.content
        return index?.let { decoder.refOf(it, INDEX_NAMESPACE) }
    }

    override suspend fun pagesOf(index: PageRef): List<PageRef> {
        requireExtension()

        return continuation
            .list(
                ApiRequest.of(
                    "query",
                    "list" to "embeddedin",
                    "eititle" to namespaces.format(index.title),
                    "einamespace" to PAGE_NAMESPACE.toString(),
                    "eilimit" to "max",
                ),
                "embeddedin",
            )
            .toList()
            .mapNotNull { decoder.refOf(it) }
    }

    private fun requireExtension() {
        if (!info.hasExtension(EXTENSION)) {
            throw WikiError.Configuration.MissingExtension(EXTENSION)
        }
    }

    private companion object {
        const val EXTENSION = "ProofreadPage"
        const val DEFAULT_BATCH = 50

        /** The namespaces the extension defines. They are the same number on every Wikisource. */
        const val PAGE_NAMESPACE = 104
        const val INDEX_NAMESPACE = 106
    }
}
