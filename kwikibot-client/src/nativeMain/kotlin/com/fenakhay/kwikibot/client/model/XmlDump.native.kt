package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.model.dump.compressionOf
import com.fenakhay.kwikibot.client.model.dump.dumpFlow
import com.fenakhay.kwikibot.client.model.dump.scanDump
import com.fenakhay.kwikibot.client.model.dump.sequentialPages
import kotlinx.coroutines.flow.Flow
import kotlinx.io.Source
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

public actual object XmlDump {

    public actual fun pages(path: Path, namespaces: Set<Int>): Sequence<DumpPage> = sequence {
        yieldAll(sequentialPages(SystemFileSystem.source(path), compressionOf(path), namespaces))
    }

    public actual fun pages(
        source: Source,
        namespaces: Set<Int>,
        compression: Compression,
    ): Sequence<DumpPage> = sequentialPages(source, compression, namespaces)

    public actual suspend fun scan(
        path: Path,
        namespaces: Set<Int>,
        workers: Int,
        block: suspend (DumpPage) -> Unit,
    ) {
        require(workers > 0) { "a scan needs at least one worker" }
        scanDump(path, workers, namespaces, block)
    }

    public actual fun flow(path: Path, namespaces: Set<Int>, workers: Int): Flow<DumpPage> {
        require(workers > 0) { "a flow needs at least one worker" }
        return dumpFlow(path, workers, namespaces)
    }
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
public actual fun availableWorkers(): Int = kotlin.native.Platform.getAvailableProcessors()
