package com.fenakhay.kwikibot.tools

import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlin.time.TimeSource

/**
 * What a tool reads from and writes to on the machine it runs on, which a test replaces.
 *
 * Run for real, a tool goes to the wikis over the network, paces itself by the system clock and prints to
 * standard output and standard error. Under test the requests go to a mock engine, the clock is the test's
 * virtual one and what is printed is kept to be read back.
 */
internal class ToolEnvironment(
    /** The Ktor engine requests go through, or `null` for the one on the classpath. */
    val engine: HttpClientEngine?,
    /** The clock requests are paced by, which must be the one the coroutines are scheduled on. */
    val timeSource: TimeSource,
    /** The value of an environment variable, or `null` when it is not set. */
    val variables: (String) -> String?,
    /** Where what the tool produced goes: the report, and which file was written. */
    val output: Appendable,
    /** Where progress and failures go. */
    val log: Appendable,
) {
    /** A client configured the way a wiki expects, which the caller closes. */
    fun client(): HttpClient = WikiHttpClient.create(engine = engine)

    companion object {
        /** The machine the tool runs on. */
        val SYSTEM =
            ToolEnvironment(
                engine = null,
                timeSource = TimeSource.Monotonic,
                variables = System::getenv,
                output = System.out,
                log = System.err,
            )
    }
}
