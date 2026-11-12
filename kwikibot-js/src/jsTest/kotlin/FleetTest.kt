import io.kotest.matchers.shouldBe
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertFails
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class FleetTest {

    private val mock = MockWiki(mapOf("Volcano" to "ash\n"))

    private fun options(onAsk: Any? = null, lines: MutableList<String>? = null): FleetOptions {
        val options: dynamic = js("({})")
        options.server = "test.example.org"
        options.contact = "https://example.org/me"
        options.onAsk = onAsk
        options.login = BotPassword("TestBot", "task", "secret")
        if (lines != null) options.onLine = { line: String -> lines.add(line) }
        return options.unsafeCast<FleetOptions>()
    }

    private fun bot(id: String, schedule: Schedule? = null, run: (RunContext) -> Any?): BotSpec {
        val spec: dynamic = js("({})")
        spec.id = id
        spec.description = "$id does things"
        spec.schedule = schedule
        spec.run = run
        return spec.unsafeCast<BotSpec>()
    }

    private suspend fun open(
        vararg bots: BotSpec,
        onAsk: Any? = null,
        lines: MutableList<String>? = null,
    ): Fleet = createFleet(options(onAsk, lines), arrayOf(*bots), mock.engine).await()

    @Test
    fun `bots run and what comes of them can be read`() = runTest {
        val lines = mutableListOf<String>()
        val fleet =
            open(
                bot("good") { context ->
                    context.say("hello ${context.arguments.joinToString()}")
                    context.expect(2)
                    context.stopped("a reason")
                    context.save shouldBe false
                    null
                },
                bot("bad") { _ -> js("Promise.reject(new Error('it broke'))") },
                lines = lines,
            )

        fleet.start("good", arrayOf("x"))
        fleet.start("bad")
        fleet.idle().await()

        val runs = fleet.runs().associateBy { it.bot }
        runs.getValue("good").status shouldBe "done"
        runs.getValue("good").expected shouldBe 2
        runs.getValue("good").stoppedBecause shouldBe "a reason"
        runs.getValue("bad").status shouldBe "failed"
        lines shouldBe listOf("[good] hello x", "[bad] failed: it broke")
        fleet.close()
    }

    @Test
    fun `a bot runs over pages counted against its run under the fleet's account`() = runTest {
        val fleet =
            open(
                bot("editor") { context ->
                    context.run(
                        arrayOf("Volcano"),
                        "naming it",
                        { text: String, _: String -> answering(text + "more") },
                    )
                }
            )

        fleet.start("editor", save = true)
        fleet.idle().await()

        val run = fleet.runs().single()
        run.status shouldBe "done"
        run.seen shouldBe 1
        run.saved shouldBe 1
        fleet.close()
    }

    @Test
    fun `a question goes to the host and no ends the run as declined`() = runTest {
        val onAsk = { _: String, _: String, _: String -> false }
        val fleet =
            open(
                bot("asker") { context ->
                    context.ask("Go on?").then { yes -> if (!yes) throw DeclinedError("they said no") }
                },
                onAsk = onAsk,
            )

        fleet.start("asker")
        fleet.idle().await()

        fleet.runs().single().status shouldBe "declined"
        fleet.close()
    }

    @Test
    fun `steps cancelling and an unknown bot behave`() = runTest {
        val seen = mutableListOf<String>()
        val fleet =
            open(
                bot("stepper") { context ->
                    context.step("German") { inner ->
                        seen += inner.wiki.id
                        answering(inner.mayContinue())
                    }
                },
                bot("waiter", every(60_000.0)) { _ -> Promise<Any?> { _, _ -> } },
            )

        fleet.start("stepper")
        val waiting = fleet.start("waiter")
        cron("0 3 * * *", "Europe/Berlin")

        fleet.cancel(waiting) shouldBe true
        fleet.idle().await()

        seen.size shouldBe 1
        assertFails { fleet.start("nobody") }
        fleet.close()
    }
}
