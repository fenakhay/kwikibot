package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.net.environmentVariable
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

/**
 * How fast a dump is read here, for the numbers in `docs/comparison.md`. Reads the file `KWIKI_DUMP` names,
 * and does nothing without it.
 */
class DumpSpeedTest {

    @OptIn(ExperimentalAtomicApi::class)
    @Test
    fun `reading the dump KWIKI_DUMP names takes this long`() =
        runTest(timeout = 2.hours) {
            val named = environmentVariable("KWIKI_DUMP") ?: return@runTest
            val path = Path(named)
            val threads =
                environmentVariable("KWIKI_DUMP_WORKERS")?.split(",")?.map { it.toInt() }
                    ?: listOf(1, 2, 4, 8)

            val started = TimeSource.Monotonic.markNow()
            val sequential = XmlDump.pages(path).count()
            println("sequence: ${started.elapsedNow().inWholeMilliseconds} ms, $sequential pages")

            for (workers in threads) {
                val scanStart = TimeSource.Monotonic.markNow()
                val seen = AtomicLong(0)
                XmlDump.scan(path, workers = workers) {
                    val _ = seen.fetchAndIncrement()
                }
                val scanned = scanStart.elapsedNow().inWholeMilliseconds

                val flowStart = TimeSource.Monotonic.markNow()
                XmlDump.flow(path, workers = workers).collect()
                println(
                    "workers $workers: scan $scanned ms, flow ${flowStart.elapsedNow().inWholeMilliseconds} ms, " +
                        "${seen.load()} pages"
                )
            }
        }
}
