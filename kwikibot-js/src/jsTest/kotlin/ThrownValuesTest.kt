import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.js.Promise
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class ThrownValuesTest {

    private val mock = MockWiki(mapOf("Volcano" to "ash\n"))

    private fun fakeWiki(): Wiki =
        createFakeWiki(optionsOf("pages" to FakePageService(optionsOf("pages" to pagesOf()))))

    private fun pagesOf(): Record<String, String> = mapOf("Volcano" to "ash").toRecord { it }

    private fun runOptions(vararg more: Pair<String, Any?>): BotRunOptions =
        optionsOf("source" to pageSourceOfTitles(arrayOf("Volcano")), *more)

    private fun throwing(value: dynamic): () -> Any? = { js("(function (v) { throw v; })")(value) }

    private fun rejecting(value: dynamic): () -> Any? = { js("Promise").reject(value) }

    private fun fleetOptions(): FleetOptions =
        optionsOf(
            "server" to "test.example.org",
            "contact" to "https://example.org/me",
            "login" to BotPassword("TestBot", "task", "secret"),
        )

    private fun bot(id: String, run: (RunContext) -> Any?): BotSpec = optionsOf("id" to id, "run" to run)

    private suspend fun rejection(promise: Promise<*>): ThrownValue =
        shouldThrow<ThrownValue> { promise.await() }

    private suspend fun assertRunFailsAlone(run: (RunContext) -> Any?, expected: String) {
        val fleet =
            createFleet(fleetOptions(), arrayOf(bot("bad", run), bot("good") { null }), mock.engine).await()
        fleet.start("bad")
        fleet.start("good")
        fleet.idle().await()

        val runs = fleet.runs().associateBy { it.bot }
        runs.getValue("bad").status shouldBe "failed"
        runs.getValue("bad").failure.orEmpty() shouldContain expected
        runs.getValue("good").status shouldBe "done"
        fleet.close()
    }

    @Test
    fun `a transform throwing a string rejects the run with the string`() = runTest {
        val thrower = throwing("boom")
        val thrown = rejection(fakeWiki().botRun(runOptions("transform" to { _: Page -> thrower() })))

        thrown.value shouldBe "boom"
        thrown.message.orEmpty() shouldContain "boom"
    }

    @Test
    fun `a transform rejecting with nothing rejects the run with nothing`() = runTest {
        val rejecter = rejecting(undefined)
        val thrown = rejection(fakeWiki().botRun(runOptions("transform" to { _: Page -> rejecter() })))

        js("typeof thrown.value").unsafeCast<String>() shouldBe "undefined"
    }

    @Test
    fun `a transform throwing null rejects the run with null`() = runTest {
        val thrower = throwing(null)
        val thrown = rejection(fakeWiki().botRun(runOptions("transform" to { _: Page -> thrower() })))

        js("thrown.value === null").unsafeCast<Boolean>() shouldBe true
    }

    @Test
    fun `a transform rejecting with a plain object keeps the object`() = runTest {
        val plain: dynamic = js("({ code: 7 })")
        val rejecter = rejecting(plain)
        val thrown = rejection(fakeWiki().botRun(runOptions("transform" to { _: Page -> rejecter() })))

        js("thrown.value === plain").unsafeCast<Boolean>() shouldBe true
    }

    @Test
    fun `an Error stays the Error it was`() = runTest {
        val error: dynamic = js("new RangeError('out of it')")
        val rejecter = rejecting(error)
        val caught =
            shouldThrow<Throwable> {
                fakeWiki().botRun(runOptions("transform" to { _: Page -> rejecter() })).await()
            }

        (caught.asDynamic() === error) shouldBe true
    }

    @Test
    fun `createMissing throwing a string rejects the run with the string`() = runTest {
        val thrower = throwing("no page")
        val options =
            optionsOf<BotRunOptions>(
                "source" to pageSourceOfTitles(arrayOf("Nowhere")),
                "transform" to { _: Page -> null },
                "createMissing" to { _: String -> thrower() },
            )

        rejection(fakeWiki().botRun(options)).value shouldBe "no page"
    }

    @Test
    fun `an onOutcome throwing a string rejects the run with the string`() = runTest {
        val thrower = throwing("no outcome")
        val options =
            runOptions("transform" to { _: Page -> null }, "onOutcome" to { _: PageOutcome -> thrower() })

        rejection(fakeWiki().botRun(options)).value shouldBe "no outcome"
    }

    @Test
    fun `the simple run's transform throwing a string rejects with the string`() = runTest {
        val thrower = throwing("simple")
        val transform = { _: String, _: String -> untyped<String?>(thrower()) }

        rejection(fakeWiki().run(arrayOf("Volcano"), "s", transform)).value shouldBe "simple"
    }

    @Test
    fun `a stop policy throwing a string rejects its check with the string`() = runTest {
        val thrower = throwing("no policy")
        val policy = stopPolicyOf { untyped(thrower()) }

        rejection(policy.mayContinue()).value shouldBe "no policy"
    }

    @Test
    fun `a bot throwing a string fails its run alone`() = runTest {
        val thrower = throwing("boom")
        assertRunFailsAlone({ thrower() }, "boom")
    }

    @Test
    fun `a bot rejecting with nothing fails its run alone`() = runTest {
        val rejecter = rejecting(undefined)
        assertRunFailsAlone({ rejecter() }, "undefined")
    }

    @Test
    fun `a bot throwing null fails its run alone`() = runTest {
        val thrower = throwing(null)
        assertRunFailsAlone({ thrower() }, "null")
    }

    @Test
    fun `a bot throwing a plain object fails its run alone`() = runTest {
        val thrower = throwing(js("({ code: 7 })"))
        assertRunFailsAlone({ thrower() }, "object")
    }

    @Test
    fun `a bot throwing what cannot be written out fails its run alone`() = runTest {
        val thrower = throwing(js("Object.create(null)"))
        assertRunFailsAlone({ thrower() }, "object")
    }

    @Test
    fun `a step throwing a string fails its run alone`() = runTest {
        val thrower = throwing("in a step")
        assertRunFailsAlone(
            { context -> context.step<Any?>("a step") { untyped(thrower()) } },
            "in a step",
        )
    }
}
