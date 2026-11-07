package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.model.dump.compressionOf
import com.fenakhay.kwikibot.client.model.dump.dumpFlow
import com.fenakhay.kwikibot.client.model.dump.scanDump
import com.fenakhay.kwikibot.client.model.dump.sequentialPages
import kotlinx.coroutines.flow.Flow
import kotlinx.io.Source
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Reads a MediaWiki XML dump without holding it in memory. */
public actual object XmlDump {

    /** See the common declaration. */
    public actual fun pages(path: Path, namespaces: Set<Int>): Sequence<DumpPage> = sequence {
        yieldAll(sequentialPages(SystemFileSystem.source(path), compressionOf(path), namespaces))
    }

    /** See the common declaration. */
    public actual fun pages(
        source: Source,
        namespaces: Set<Int>,
        compression: Compression,
    ): Sequence<DumpPage> = sequentialPages(source, compression, namespaces)

    /** See the common declaration. */
    public actual suspend fun scan(
        path: Path,
        namespaces: Set<Int>,
        workers: Int,
        block: suspend (DumpPage) -> Unit,
    ) {
        require(workers > 0) { "a scan needs at least one worker" }
        scanDump(path, workers, namespaces, block)
    }

    /** See the common declaration. */
    public actual fun flow(path: Path, namespaces: Set<Int>, workers: Int): Flow<DumpPage> {
        require(workers > 0) { "a flow needs at least one worker" }
        return dumpFlow(path, workers, namespaces)
    }
}

/** How many threads there are to work on. */
public actual fun availableWorkers(): Int {
    val process: dynamic = js("typeof process === 'undefined' ? null : process")
    if (process == null || process.getBuiltinModule == undefined) return 1
    val os = process.getBuiltinModule("os")
    return (os.availableParallelism() as Int).coerceAtLeast(1)
}
