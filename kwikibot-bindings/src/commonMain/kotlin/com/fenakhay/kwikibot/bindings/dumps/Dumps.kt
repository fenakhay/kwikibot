package com.fenakhay.kwikibot.bindings.dumps

import com.fenakhay.kwikibot.client.model.Compression
import com.fenakhay.kwikibot.client.model.DumpPage
import com.fenakhay.kwikibot.client.model.XmlDump
import com.fenakhay.kwikibot.client.model.availableWorkers
import kotlinx.coroutines.flow.Flow
import kotlinx.io.Source
import kotlinx.io.files.Path

/**
 * The compression [name] names: `"none"`, `"gzip"` or `"bzip2"`.
 *
 * @throws IllegalArgumentException when it names none of them.
 */
public fun compressionNamed(name: String): Compression =
    when (name) {
        "none" -> Compression.NONE
        "gzip" -> Compression.GZIP
        "bzip2" -> Compression.BZIP2
        else -> throw IllegalArgumentException("unknown compression '$name'; use none, gzip or bzip2")
    }

/** How many workers to read a dump on: [workers], or one per processor unless given. */
public fun workersOf(workers: Int?): Int = workers ?: availableWorkers()

/** The namespaces to read a dump's pages of: [namespaces], or every one, the empty set, unless given. */
public fun dumpNamespacesOf(namespaces: Collection<Int>?): Set<Int> = namespaces?.toSet().orEmpty()

/**
 * The pages of the dump at [path], in the dump's order: of every namespace, decoded on one worker per
 * processor, unless told.
 */
public fun dumpFlowWithDefaults(
    path: Path,
    namespaces: Collection<Int>? = null,
    workers: Int? = null,
): Flow<DumpPage> = XmlDump.flow(path, dumpNamespacesOf(namespaces), workersOf(workers))

/** The pages of a dump in [source], uncompressed and of every namespace unless told. */
public fun dumpPagesWithDefaults(
    source: Source,
    compression: Compression? = null,
    namespaces: Collection<Int>? = null,
): Sequence<DumpPage> = XmlDump.pages(source, dumpNamespacesOf(namespaces), compression ?: Compression.NONE)

/** Calls [block] with every page of the dump at [path], in no particular order, as [dumpFlowWithDefaults]. */
public suspend fun scanDumpWithDefaults(
    path: Path,
    namespaces: Collection<Int>? = null,
    workers: Int? = null,
    block: suspend (DumpPage) -> Unit,
) {
    XmlDump.scan(path, dumpNamespacesOf(namespaces), workersOf(workers), block)
}
