import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString

/** A path for test [name] under the system's temporary directory. */
internal fun temporaryPath(name: String): String =
    Path(SystemTemporaryDirectory, "kwikibot-js-records-$name").toString()

/** Writes [text] to the file at [path]. */
internal fun writeTemporary(path: String, text: String) {
    SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(text) }
}

/** What the file at [path] holds. */
internal fun readTemporary(path: String): String =
    SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

/** Deletes the file or empty directory at [path], if it is there. */
internal fun removeTemporary(path: String) {
    SystemFileSystem.delete(Path(path), mustExist = false)
}

class RunRecordsTest {

    private val login = BotPassword("TestBot", "task", "secret")
    private val pages = mapOf("Volcano" to "ash\n", "Etna" to "lava\n", "Keep" to "same\n")

    private val transform: (Page) -> Any? = { page ->
        when (page.title) {
            "Volcano" -> optionsOf<PageEdit>("text" to "tephra\n", "summary" to "naming it")
            "Etna" -> skipPage("not this one")
            else -> null
        }
    }

    private suspend fun outcomes(wiki: Wiki, vararg more: Pair<String, Any?>): List<PageOutcome> {
        val outcomes = mutableListOf<PageOutcome>()
        wiki
            .botRun(
                optionsOf(
                    "source" to pageSourceOfTitles(arrayOf("Volcano", "Etna", "Nope")),
                    "transform" to transform,
                    "readConcurrency" to 1,
                    "onOutcome" to { outcome: PageOutcome -> outcomes += outcome },
                    *more,
                )
            )
            .await()
        return outcomes
    }

    @Test
    fun `a run's log writes diffs and skips to functions`() = runTest {
        val wiki = MockWiki(pages).open(login)
        val diffs = StringBuilder()
        val skips = StringBuilder()
        val log =
            createRunLog(
                optionsOf(
                    "onDiff" to { text: String -> diffs.append(text) },
                    "onSkip" to { text: String -> skips.append(text) },
                    "context" to 0,
                    "namespaces" to wiki.namespaces,
                )
            )

        outcomes(wiki).forEach(log::record)

        diffs.toString() shouldStartWith "=== diff: Volcano ===\n--- old/Volcano"
        val records = skips.toString().trim().lines()
        records[0] shouldBe """{"title":"Etna","kind":"skipped","reason":"not this one"}"""
        records[1] shouldBe """{"title":"Nope","kind":"missing","reason":"page does not exist"}"""

        val silent = createRunLog()
        outcomes(wiki).forEach(silent::record)
        wiki.close()
    }

    @Test
    fun `a run's log writes to files in Node, and refuses them in a browser`() = runTest {
        if (!hasFileSystem()) {
            shouldThrow<IllegalStateException> { createRunLog(optionsOf("diffFile" to "d")) }
                .message
                .orEmpty() shouldContain "Node only"
            return@runTest
        }
        val wiki = MockWiki(pages).open(login)
        val diffFile = temporaryPath("diffs")
        val skipFile = temporaryPath("skips")
        removeTemporary(diffFile)
        removeTemporary(skipFile)
        val log = createRunLog(optionsOf("diffFile" to diffFile, "skipFile" to skipFile))

        outcomes(wiki).forEach(log::record)

        readTemporary(diffFile) shouldContain "-ash"
        readTemporary(skipFile) shouldContain "not this one"
        removeTemporary(diffFile)
        removeTemporary(skipFile)
        wiki.close()
    }

    @Test
    fun `a progress line says how far a run has come`() = runTest {
        val wiki = MockWiki(pages).open(login)
        val written = StringBuilder()
        var now = 0.0
        val progress =
            createProgress(
                optionsOf(
                    "total" to 3,
                    "write" to { text: String -> written.append(text) },
                    "width" to 200,
                    "now" to { now },
                )
            )

        outcomes(wiki).forEach {
            now += 1000.0
            progress.record(it)
        }
        progress.finish()

        written.toString() shouldContain "3/3"
        written.toString() shouldContain "would change 1"
        written.toString() shouldContain "skipped 2"
        written.endsWith("\n") shouldBe true

        val off = StringBuilder()
        val hidden =
            createProgress(optionsOf("enabled" to false, "write" to { text: String -> off.append(text) }))
        hidden.finish()
        off.toString() shouldBe ""

        createProgress(optionsOf("enabled" to false)).finish()
        wiki.close()
    }

    @Test
    fun `a run's state resumes, in Node, and is refused in a browser`() = runTest {
        if (!hasFileSystem()) {
            shouldThrow<IllegalStateException> { RunState("anywhere") }.message.orEmpty() shouldContain
                "Node only"
            return@runTest
        }
        val mock = MockWiki(pages)
        val wiki = mock.open(login)
        val directory = temporaryPath("state-${js("Date.now()")}")
        val state = RunState(directory)
        state.directory shouldBe directory

        outcomes(wiki, "dryRun" to false, "state" to state).map { it.kind } shouldBe
            listOf("saved", "skipped", "missing")
        state.finishedCount().await() shouldBe 3
        state.isFinished(wiki, "Volcano").await() shouldBe true
        state.isFinished(wiki, "Other").await() shouldBe false
        state.pin(wiki, "Volcano").await() shouldBe 9002.0
        state.pin(wiki, "Other").await() shouldBe null
        readTemporary("$directory/saves.jsonl") shouldContain "naming it"

        val resumed = outcomes(wiki, "dryRun" to false, "state" to state, "resume" to true)
        resumed shouldBe emptyList()

        val recorded = outcomes(wiki, "readConcurrency" to 1).first { it.kind.toString() == "skipped" }
        state.record(recorded, 5.0).await()
        state.close()

        listOf("finished.tsv", "pins.tsv", "saves.jsonl").forEach { removeTemporary("$directory/$it") }
        removeTemporary(directory)
        wiki.close()
    }
}
