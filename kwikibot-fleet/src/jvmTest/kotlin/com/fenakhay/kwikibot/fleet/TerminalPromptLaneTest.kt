package com.fenakhay.kwikibot.fleet

import com.fenakhay.kwikibot.bot.BotConfig
import com.fenakhay.kwikibot.bot.BotStart
import com.fenakhay.kwikibot.bot.CommonOptions
import com.fenakhay.kwikibot.bot.TerminalPrompt
import com.fenakhay.kwikibot.testkit.FakePageService
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.shouldBe
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/** The whole program on one thread, as `botMain` runs it, with a real blocking read of standard input. */
class TerminalPromptLaneTest {

    @Test
    fun `a person yet to answer holds up only the lane that asked`() {
        val keyboard = PipedOutputStream()
        val stdin = System.`in`
        System.setIn(PipedInputStream(keyboard))
        try {
            val asking = CompletableDeferred<Unit>()
            var answer: Boolean? = null
            val bots =
                listOf(
                    FleetTestBot(
                        "asker",
                        work = {
                            asking.complete(Unit)
                            answer = it.ask(Question("Go on?"))
                        },
                    ),
                    FleetTestBot(
                        "other",
                        work = {
                            asking.await()
                            keyboard.write("y\n".toByteArray())
                            keyboard.flush()
                        },
                    ),
                )
            val start =
                BotStart(
                    FakeWiki(FakePageService("User:FakeBot/Stop" to "false")),
                    CommonOptions(),
                    listOf("run", "--all"),
                    BotConfig.parse(
                        """
                        [bot]
                        name = "T"
                        version = "1"
                        contact = "https://example.org"
                        """
                            .trimIndent()
                    ),
                )
            var status: Int? = null

            val program = thread {
                status = runBlocking {
                    start.runCommand(bots, TerminalPrompt(output = {}), {}, accountsOnMockWiki())
                }
            }
            program.join(PATIENCE)
            val finished = !program.isAlive
            if (!finished) {
                keyboard.write("n\n".toByteArray())
                keyboard.flush()
                program.join(PATIENCE)
            }

            finished shouldBe true
            status shouldBe 0
            answer shouldBe true
        } finally {
            System.setIn(stdin)
        }
    }

    private companion object {
        const val PATIENCE = 60_000L
    }
}
