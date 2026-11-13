import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.coroutines.resume
import kotlin.js.Promise
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest

class RunLifetimeTest {

    private val mock = MockWiki(mapOf("Volcano" to "ash\n"))

    private fun options(vararg more: Pair<String, Any?>): FleetOptions =
        optionsOf(
            "server" to "test.example.org",
            "contact" to "https://example.org/me",
            "login" to BotPassword("TestBot", "task", "secret"),
            *more,
        )

    private fun bot(id: String, run: (RunContext) -> Any?): BotSpec = optionsOf("id" to id, "run" to run)

    private suspend fun open(vararg bots: BotSpec, options: FleetOptions = options()): Fleet =
        createFleet(options, arrayOf(*bots), mock.engine).await()

    private class Started {
        private val waiters = mutableListOf<() -> Unit>()
        private var fired = false

        fun fire() {
            fired = true
            waiters.toList().forEach { it() }
            waiters.clear()
        }

        suspend fun await() {
            if (fired) return
            suspendCancellableCoroutine { waiting -> waiters += { waiting.resume(Unit) } }
        }
    }

    private fun never(): Promise<Any?> = Promise { _, _ -> }

    private fun reasonName(signal: AbortSignal): String = signal.asDynamic().reason.name as String

    private fun aborted(signal: AbortSignal): Boolean = signal.asDynamic().aborted as Boolean

    @Test
    fun `closing a wiki from open leaves the run going`() = runTest {
        val lines = mutableListOf<String>()
        val fleet =
            open(
                bot("reader") { context ->
                    context.open("fr", "wiktionary").then { other ->
                        other.close()
                        context.wiki.page("Volcano").then { page ->
                            context.say("read ${page?.title}")
                        }
                    }
                },
                options = options("onLine" to { line: String -> lines.add(line) }),
            )

        fleet.start("reader")
        fleet.idle().await()

        val run = fleet.runs().single()
        run.status shouldBe "done"
        run.failure shouldBe null
        lines shouldBe listOf("[reader] read Volcano")
        fleet.close()
    }

    @Test
    fun `a wiki from open still works after it is closed`() = runTest {
        var title: String? = null
        val fleet =
            open(
                bot("reader") { context ->
                    context.open("fr", "wiktionary").then { other ->
                        other.close()
                        other.page("Volcano").then { page -> title = page?.title }
                    }
                }
            )

        fleet.start("reader")
        fleet.idle().await()

        fleet.runs().single().status shouldBe "done"
        title shouldBe "Volcano"
        fleet.close()
    }

    @Test
    fun `closing the run's own wiki leaves the run and the next one going`() = runTest {
        val fleet =
            open(
                bot("closer") { context ->
                    context.wiki.close()
                    context.mayContinue()
                },
                bot("reader") { context -> context.wiki.page("Volcano") },
            )

        fleet.start("closer")
        fleet.start("reader")
        fleet.idle().await()

        fleet.runs().map { it.status } shouldBe listOf("done", "done")
        fleet.close()
    }

    @Test
    fun `closing the wiki of a recording context leaves the context working`() = runTest {
        val wiki = mock.open()
        val recording = RecordingContext(wiki)

        recording.context.wiki.close()

        recording.context.mayContinue().await() shouldBe true
        wiki.close()
    }

    @Test
    fun `cancelling aborts the signal of a bot whose promise never settles`() = runTest {
        val started = Started()
        var signal: AbortSignal? = null
        val fleet =
            open(
                bot("waiter") { context ->
                    signal = context.signal
                    started.fire()
                    never()
                }
            )

        val id = fleet.start("waiter")
        started.await()
        aborted(signal!!) shouldBe false

        fleet.cancel(id) shouldBe true

        aborted(signal) shouldBe true
        reasonName(signal) shouldBe "AbortError"
        fleet.idle().await()
        fleet.runs().single().status shouldBe "cancelled"
        fleet.close()
    }

    @Test
    fun `the abort event reaches a listener`() = runTest {
        val started = Started()
        val heard = mutableListOf<String>()
        val fleet =
            open(
                bot("waiter") { context ->
                    val listener = { _: dynamic -> heard.add("abort") }
                    context.signal.asDynamic().addEventListener("abort", listener)
                    started.fire()
                    never()
                }
            )

        val id = fleet.start("waiter")
        started.await()
        fleet.cancel(id)

        heard shouldBe listOf("abort")
        fleet.close()
    }

    @Test
    fun `a cancel before the bot first awaits aborts the signal`() = runTest {
        var seenWithin: Boolean? = null
        val box = arrayOfNulls<Fleet>(1)
        val ids = arrayOfNulls<Double>(1)
        val fleet =
            open(
                bot("self") { context ->
                    box[0]!!.cancel(ids[0]!!)
                    seenWithin = aborted(context.signal)
                    null
                }
            )
        box[0] = fleet

        ids[0] = fleet.start("self")
        fleet.idle().await()

        seenWithin shouldBe true
        fleet.runs().single().status shouldBe "cancelled"
        fleet.close()
    }

    @Test
    fun `closing the fleet aborts the signal`() = runTest {
        val started = Started()
        var signal: AbortSignal? = null
        val fleet =
            open(
                bot("waiter") { context ->
                    signal = context.signal
                    started.fire()
                    never()
                }
            )

        fleet.start("waiter")
        started.await()
        fleet.close()

        aborted(signal!!) shouldBe true
    }

    @Test
    fun `a run that ends by itself does not abort its signal`() = runTest {
        var signal: AbortSignal? = null
        val fleet =
            open(
                bot("quick") { context ->
                    signal = context.signal
                    null
                }
            )

        fleet.start("quick")
        fleet.idle().await()
        fleet.close()

        aborted(signal!!) shouldBe false
    }

    @Test
    fun `a bot ignoring the signal ends cancelled without its promise and frees its lane`() = runTest {
        val started = Started()
        var finish: ((Any?) -> Unit)? = null
        var runs = 0
        val fleet =
            open(
                bot("waiter") { _ ->
                    runs++
                    started.fire()
                    if (runs == 1) Promise<Any?> { resolve, _ -> finish = resolve } else null
                }
            )

        val id = fleet.start("waiter")
        started.await()
        fleet.cancel(id)
        fleet.idle().await()
        fleet.runs().single().status shouldBe "cancelled"

        fleet.start("waiter")
        fleet.idle().await()

        fleet.runs().map { it.status } shouldBe listOf("cancelled", "done")
        finish!!(null)
        fleet.close()
    }

    @Test
    fun `a line callback throwing does not touch the run and is said once`() = runTest {
        val said = withConsoleErrors {
            val fleet =
                open(
                    bot("talker") { context ->
                        context.say("one")
                        context.say("two")
                        null
                    },
                    options =
                        options("onLine" to { _: String -> throw IllegalStateException("onLine broke") }),
                )
            fleet.start("talker")
            fleet.idle().await()

            val run = fleet.runs().single()
            run.status shouldBe "done"
            run.failure shouldBe null
            fleet.close()
        }

        said.size shouldBe 1
        said.single() shouldContain "onLine broke"
    }

    @Test
    fun `a line callback throwing a string does not touch the run`() = runTest {
        val thrower: () -> Unit = { js("(function () { throw 'not an error'; })")() }
        val said = withConsoleErrors {
            val fleet =
                open(
                    bot("talker") { context ->
                        context.say("one")
                        null
                    },
                    options = options("onLine" to { _: String -> thrower() }),
                )
            fleet.start("talker")
            fleet.idle().await()

            fleet.runs().single().status shouldBe "done"
            fleet.close()
        }

        said.single() shouldContain "not an error"
    }

    @Test
    fun `an asynchronous line callback rejecting does not touch the run`() = runTest {
        val rejecter: () -> Any? = { js("Promise.reject(new Error('later'))") }
        val said = withConsoleErrors {
            val fleet =
                open(
                    bot("talker") { context ->
                        context.say("one")
                        null
                    },
                    options = options("onLine" to { _: String -> rejecter() }),
                )
            fleet.start("talker")
            fleet.idle().await()
            Promise.resolve(null).await()
            Promise.resolve(null).await()

            fleet.runs().single().status shouldBe "done"
            fleet.close()
        }

        said.single() shouldContain "later"
    }

    @Test
    fun `a line callback throwing on a fleet with accounts does not touch the run`() = runTest {
        val accounts =
            createAccounts(
                "https://example.org/me",
                null,
                { _: String? -> BotPassword("TestBot", "task", "secret") },
                mock.engine,
            )
        val endpoint = mock.open().endpoint
        val said = withConsoleErrors {
            val fleet =
                createFleetWith(
                    optionsOf(
                        "accounts" to accounts,
                        "endpoint" to endpoint,
                        "onLine" to { _: String -> throw IllegalStateException("host line") },
                    ),
                    arrayOf(
                        bot("talker") { context ->
                            context.say("one")
                            null
                        }
                    ),
                )
            fleet.start("talker")
            fleet.idle().await()

            fleet.runs().single().status shouldBe "done"
            fleet.close()
            accounts.close()
        }

        said.single() shouldContain "host line"
    }

    @Test
    fun `starting a closed fleet says so and queues nothing`() = runTest {
        val fleet = open(bot("late") { null })
        fleet.close()

        val refused = shouldThrow<IllegalStateException> { fleet.start("late") }
        val refusedToo = shouldThrow<IllegalStateException> { fleet.startWith("late") }

        refused.message.orEmpty() shouldContain "closed"
        refusedToo.message.orEmpty() shouldContain "closed"
        fleet.runs().size shouldBe 0
    }

    @Test
    fun `idle resolves when the fleet is closed under it`() = runTest {
        val started = Started()
        val fleet =
            open(
                bot("waiter") { _ ->
                    started.fire()
                    never()
                }
            )

        fleet.start("waiter")
        started.await()
        val waiting = fleet.idle()
        fleet.close()

        waiting.await()
        fleet.runs().single().status shouldBe "cancelled"
        fleet.idle().await()
    }

    @Test
    fun `closing a fleet and then leaving a walk of its events ends the walk`() = runTest {
        val fleet = open(bot("talker") { null })
        val iterator = fleet.events().asDynamic()[js("Symbol.asyncIterator")]()
        val step = iterator.next().unsafeCast<Promise<dynamic>>()

        fleet.close()

        isDone(step) shouldBe true
        isDone(iterator["return"]()) shouldBe true
        isDone(iterator.next()) shouldBe true
    }

    @Test
    fun `leaving a walk of a wiki's listing after closing the wiki is clean`() = runTest {
        val wiki = MockWiki(members = listOf("Lava", "Ash")).open()
        val iterator = wiki.categoryMembers("Volcanoes").asDynamic()[js("Symbol.asyncIterator")]()
        valueOf(iterator.next()) shouldBe "Lava"

        wiki.close()

        isDone(iterator["return"]()) shouldBe true
    }

    @Test
    fun `a walk of a wiki's listing still going when it is closed rejects`() = runTest {
        val wiki = MockWiki(members = listOf("Lava", "Ash")).open()
        val iterator = wiki.categoryMembers("Volcanoes").asDynamic()[js("Symbol.asyncIterator")]()
        valueOf(iterator.next()) shouldBe "Lava"

        wiki.close()

        shouldThrow<Throwable> { iterator.next().unsafeCast<Promise<dynamic>>().await() }
    }

    private suspend fun isDone(step: dynamic): Boolean =
        step.unsafeCast<Promise<dynamic>>().await().done == true

    private suspend fun valueOf(step: dynamic): Any? =
        step.unsafeCast<Promise<dynamic>>().await().value.unsafeCast<Any?>()

    private suspend fun withConsoleErrors(block: suspend () -> Unit): List<String> {
        val console: dynamic = js("console")
        val real = console.error
        val said = mutableListOf<String>()
        console.error = { line: dynamic -> said.add(line.toString()) }
        try {
            block()
        } finally {
            console.error = real
        }
        return said
    }
}
