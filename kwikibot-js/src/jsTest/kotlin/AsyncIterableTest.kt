import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.js.Promise
import kotlin.test.Test
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AsyncIterableTest {

    private val uncaught = mutableListOf<Throwable>()
    private val scope =
        CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, failure -> uncaught += failure })

    private suspend fun <T> real(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }

    private fun <T> Flow<T>.iterator(): dynamic =
        toAsyncIterable(scope) { it }.asDynamic()[js("Symbol.asyncIterator")]()

    private class Step(val value: Any?, val done: Boolean)

    private suspend fun step(iterator: dynamic): Step = stepOf(iterator.next().unsafeCast<Promise<dynamic>>())

    private suspend fun stepOf(promise: Promise<dynamic>): Step {
        val answer = promise.await()
        return Step(answer.value, answer.done == true)
    }

    /** A flow whose helper fails while the collector waits for the walk to take what it was given. */
    private fun failingBehind(): Flow<Int> = flow {
        coroutineScope {
            launch {
                delay(10)
                error("the helper failed")
            }
            emit(1)
            emit(2)
            emit(3)
        }
    }

    @Test
    fun `a failure while the collector waits for the walk rejects the walk`() = runTest {
        real {
            val iterator = failingBehind().iterator()

            step(iterator).value shouldBe 1
            delay(50)

            val failure = shouldThrow<IllegalStateException> { step(iterator) }

            failure.message.orEmpty() shouldContain "the helper failed"
            uncaught shouldBe emptyList()
        }
    }

    @Test
    fun `a failure before the first item rejects the first step`() = runTest {
        real {
            val iterator = flow<Int> { error("at once") }.iterator()

            shouldThrow<IllegalStateException> { step(iterator) }.message.orEmpty() shouldContain "at once"
            uncaught shouldBe emptyList()
        }
    }

    @Test
    fun `a walk ended early stops the flow with nothing thrown`() = runTest {
        real {
            val iterator = failingBehind().iterator()

            step(iterator).value shouldBe 1
            iterator["return"]().unsafeCast<Promise<dynamic>>().await()
            delay(50)

            uncaught shouldBe emptyList()
        }
    }

    @Test
    fun `a walk of a flow that ends ends`() = runTest {
        real {
            val iterator = flow { emit(1) }.iterator()

            step(iterator).value shouldBe 1
            step(iterator).done shouldBe true
        }
    }

    @Test
    fun `a walk that ends with its scope ends rather than rejects`() = runTest {
        real {
            val ending =
                CoroutineScope(
                    SupervisorJob() + CoroutineExceptionHandler { _, failure -> uncaught += failure }
                )
            val walked = flow {
                emit(1)
                delay(60_000)
            }
                .toAsyncIterableEndingWith(ending) { it }
            val iterator = walked.asDynamic()[js("Symbol.asyncIterator")]()

            step(iterator).value shouldBe 1
            val waiting = iterator.next().unsafeCast<Promise<dynamic>>()
            delay(10)
            ending.cancel()

            stepOf(waiting).done shouldBe true
            uncaught shouldBe emptyList()
        }
    }

    @Test
    fun `leaving a walk whose scope is gone is clean, in either kind of walk`() = runTest {
        real {
            for (ending in listOf(false, true)) {
                val gone = CoroutineScope(SupervisorJob())
                val walked =
                    if (ending) flow { emit(1) }.toAsyncIterableEndingWith(gone) { it }
                    else flow { emit(1) }.toAsyncIterable(gone) { it }
                val iterator = walked.asDynamic()[js("Symbol.asyncIterator")]()
                step(iterator).value shouldBe 1

                gone.cancel()

                stepOf(iterator["return"]().unsafeCast<Promise<dynamic>>()).done shouldBe true
                step(iterator).done shouldBe true
            }
        }
    }

    @Test
    fun `a walk left early answers done from then on`() = runTest {
        real {
            val iterator = flow {
                emit(1)
                emit(2)
                delay(60_000)
            }
                .iterator()
            step(iterator).value shouldBe 1

            iterator["return"]().unsafeCast<Promise<dynamic>>().await()

            step(iterator).done shouldBe true
        }
    }

    @Test
    fun `a listing the wiki refuses rejects its walk`() = runTest {
        val mock = MockWiki()
        mock.canned["categorymembers"] =
            """{"error":{"code":"internal_api_error","info":"the wiki fell over"}}"""
        val wiki = mock.open()

        val failure = shouldThrow<Throwable> { wiki.categoryMembers("Volcanoes").take() }

        failure.message.orEmpty() shouldContain "the wiki fell over"
        wiki.close()
    }
}
