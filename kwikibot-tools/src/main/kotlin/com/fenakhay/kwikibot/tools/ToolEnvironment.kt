package com.fenakhay.kwikibot.tools

import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.time.TimeSource

/**
 * What a tool reads from and writes to on the machine it runs on, which a test replaces.
 *
 * Run for real, a tool goes to the wikis over the network, paces itself by the system clock and prints to
 * standard output and standard error, and takes a relative path from the directory it was started in. Under
 * test the requests go to a mock engine, the clock is the test's virtual one, what is printed is kept to be
 * read back and a relative path is taken from a directory of the test's own.
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
    /** The directory a relative path is taken from. */
    val workingDirectory: Path,
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
                workingDirectory = Path(""),
            )
    }
}
