import com.fenakhay.kwikibot.bot.Choice as ClientChoice
import com.fenakhay.kwikibot.bot.NonInteractive as ClientNonInteractive
import com.fenakhay.kwikibot.bot.Prompt as ClientPrompt
import com.fenakhay.kwikibot.bot.ScriptedPrompt as ClientScriptedPrompt
import com.fenakhay.kwikibot.bot.TerminalPrompt as ClientTerminalPrompt
import kotlin.js.Promise
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

/** One choice offered to a person. */
@JsExport
public external interface Choice {
    /** The single character that selects it, matched case-insensitively. */
    public val key: String

    /** What it does, shown in the prompt. */
    public val label: String

    /** What selecting it yields. */
    public val value: String
}

/**
 * Asking a person something, and what to do when no person is present. `TerminalPrompt` reads the terminal,
 * `NonInteractive` answers with a configured default, `ScriptedPrompt` has the answers written down, and
 * `promptFrom` makes one of your own.
 */
@JsExport
@OptIn(DelicateCoroutinesApi::class)
public open class Prompt internal constructor(internal val prompt: ClientPrompt) {

    /** Whether there is a person to ask. */
    public val isInteractive: Boolean
        get() = prompt.isInteractive

    /** Asks a yes-or-no question, whose answer is [fallback] when a person gives none. */
    public fun confirm(question: String, fallback: Boolean = false): Promise<Boolean> = GlobalScope.promise {
        prompt.confirm(question, fallback)
    }

    /** Asks for one of [choices], and answers with its value. */
    public fun choose(question: String, choices: Array<Choice>): Promise<String> = GlobalScope.promise {
        prompt.choose(question, choices.map(::choiceOf))
    }

    /** Asks for a line of text, whose answer is [fallback] when a person gives none. */
    public fun text(question: String, fallback: String = ""): Promise<String> = GlobalScope.promise {
        prompt.text(question, fallback)
    }
}

/**
 * A prompt with no person behind it: every question is answered with the configured default and counted, so a
 * bot can say in its report how many decisions it made on the operator's behalf. Of a list of choices, it
 * takes the first.
 */
@JsExport
public class NonInteractive(
    /** What to answer a yes-or-no question. */
    answer: Boolean = false,
    /** What to answer a request for text, or the question's default when empty. */
    textAnswer: String = "",
) : Prompt(ClientNonInteractive(answer, textAnswer)) {

    /** How many questions were answered without anybody being asked. */
    public val asked: Int
        get() = (prompt as ClientNonInteractive).asked
}

/**
 * A prompt that reads the terminal. If standard input reaches its end, the default is taken.
 *
 * @param output what the question is written with; standard output unless given.
 * @param input gives the next line typed, or `null` once input has ended, and may return a promise; standard
 *   input unless given, which needs Node.
 */
@JsExport
public class TerminalPrompt(
    output: ((text: String) -> Unit)? = null,
    input: (() -> Awaitable<String?>)? = null,
) : Prompt(terminalPrompt(output, input))

private fun terminalPrompt(output: ((String) -> Unit)?, input: (() -> Awaitable<String?>)?): ClientPrompt {
    val read: (suspend () -> String?)? = input?.let { { settledResult(it()).unsafeCast<String?>() } }
    return when {
        output != null && read != null -> ClientTerminalPrompt(output, read)
        output != null -> ClientTerminalPrompt(output)
        read != null -> ClientTerminalPrompt(input = read)
        else -> ClientTerminalPrompt()
    }
}

/**
 * A prompt with the answers written down in advance, for tests and for a run that knows what it wants. An
 * empty answer is the Enter key. Running out of answers falls back to [fallback], a `NonInteractive` unless
 * given.
 */
@JsExport
public class ScriptedPrompt(answers: Array<String>, fallback: Prompt? = null) :
    Prompt(ClientScriptedPrompt(answers.toList(), fallback?.prompt ?: ClientNonInteractive()))

/** A prompt written in JavaScript. Each function may return a promise. */
@JsExport
public external interface PromptHandlers {
    /** Whether there is a person to ask; `true` unless given. */
    public val isInteractive: Boolean?

    /** Asks a yes-or-no question. */
    public val confirm: (question: String, fallback: Boolean) -> Awaitable<Boolean>

    /** Asks for one of [choices], and answers with its value. */
    public val choose: (question: String, choices: Array<Choice>) -> Awaitable<String>

    /** Asks for a line of text. */
    public val text: (question: String, fallback: String) -> Awaitable<String>
}

/** A prompt that asks through [handlers]. */
@JsExport public fun promptFrom(handlers: PromptHandlers): Prompt = Prompt(HandledPrompt(handlers))

private class HandledPrompt(private val handlers: PromptHandlers) : ClientPrompt {
    override val isInteractive: Boolean
        get() = handlers.isInteractive ?: true

    override suspend fun confirm(question: String, default: Boolean): Boolean =
        settledResult(handlers.confirm(question, default)) == true

    override suspend fun choose(question: String, choices: List<ClientChoice<String>>): String =
        settledResult(handlers.choose(question, choices.map(::plainChoice).toTypedArray()))
            .unsafeCast<String>()

    override suspend fun text(question: String, default: String): String =
        settledResult(handlers.text(question, default)).unsafeCast<String>()
}

private fun choiceOf(choice: Choice): ClientChoice<String> {
    require(choice.key.length == 1) { "a choice's key is one character, not '${choice.key}'" }
    return ClientChoice(choice.key.single(), choice.label, choice.value)
}

private fun plainChoice(choice: ClientChoice<String>): Choice {
    val plain: dynamic = js("({})")
    plain.key = choice.key.toString()
    plain.label = choice.label
    plain.value = choice.value
    return plain.unsafeCast<Choice>()
}
