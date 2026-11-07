package com.fenakhay.kwikibot.fleet

import com.fenakhay.kwikibot.bot.CommonOptions
import com.fenakhay.kwikibot.bot.run.PageOutcome
import com.fenakhay.kwikibot.bot.run.StopPolicy
import com.fenakhay.kwikibot.client.Family
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.LangCode
import kotlinx.io.files.Path

/**
 * A [RunContext] for testing a bot with no fleet behind it: it writes down what the bot says, and answers as
 * told.
 *
 * ```
 * val run = RecordingContext(FakeWiki(pages))
 * MyBot().run(run)
 * run.said shouldContain "2 pages refreshed"
 * ```
 *
 * @param wiki what the bot edits, such as a `FakeWiki`.
 * @param stopPolicy what the bot answers to.
 * @param options the common flags to give it.
 * @param arguments the rest of its command line.
 * @param directory where it may write files.
 * @param answer what to say to a question.
 */
public class RecordingContext(
    override val wiki: Wiki,
    override val stopPolicy: StopPolicy = StopPolicy.NONE,
    override val options: CommonOptions = CommonOptions(),
    override val arguments: List<String> = emptyList(),
    override val directory: Path? = null,
    private val answer: (Question) -> Boolean = { false },
) : RunContext {

    /** Everything said, in order. */
    public val said: MutableList<String> = mutableListOf()

    /** Everything asked, in order. */
    public val asked: MutableList<Question> = mutableListOf()

    /** Every page handed to [outcomes], in order. */
    public val pages: MutableList<PageOutcome> = mutableListOf()

    /** The total of what was passed to [expect]. */
    public var expected: Int = 0
        private set

    /** The first reason passed to [stopped]. */
    public var stoppedBecause: String? = null
        private set

    override suspend fun open(language: LangCode, family: Family): Wiki = wiki

    override fun say(message: String, level: RunContext.Level) {
        said += message
    }

    override fun expect(items: Int) {
        expected += items
    }

    override fun stopped(reason: String) {
        stoppedBecause = stoppedBecause ?: reason
    }

    override suspend fun <T> step(name: String, work: suspend (RunContext) -> T): T = work(this)

    override suspend fun ask(question: Question): Boolean {
        asked += question
        return answer(question)
    }

    override suspend fun mayContinue(): Boolean = stopPolicy.mayContinue()

    override val outcomes: (PageOutcome) -> Unit = { pages += it }
}
