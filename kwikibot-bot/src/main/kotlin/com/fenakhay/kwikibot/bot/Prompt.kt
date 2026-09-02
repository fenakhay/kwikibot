package com.fenakhay.kwikibot.bot

/**
 * One choice offered to a person.
 *
 * @param T the type of [value].
 * @param key the single character that selects it, matched case-insensitively.
 * @param label what it does, shown in the prompt.
 * @param value what selecting it yields.
 */
public data class Choice<out T>(
    /** The single character that selects it, matched case-insensitively. */
    val key: Char,
    /** What it does, shown in the prompt. */
    val label: String,
    /** What selecting it yields. */
    val value: T,
)

/**
 * Asking a person something, and what to do when no person is present.
 *
 * A prompt that reads the terminal blocks indefinitely when a process runs unattended, so [NonInteractive] is
 * a full implementation rather than a stub: it answers with a configured default and counts the questions it
 * answered.
 *
 * ```
 * val prompt = if (System.console() != null) TerminalPrompt() else NonInteractive(answer = false)
 * ```
 */
public interface Prompt {

    /** Whether there is a person to ask. */
    public val isInteractive: Boolean

    /** Asks a yes-or-no question. */
    public suspend fun confirm(question: String, default: Boolean = false): Boolean

    /** Asks for one of [choices]. */
    public suspend fun choose(question: String, choices: List<Choice<String>>): String

    /** Asks for a line of text. */
    public suspend fun text(question: String, default: String = ""): String
}

/**
 * A prompt with no person behind it.
 *
 * Every question is answered with the configured default and counted. A bot can check [asked] afterwards and
 * say in its report how many decisions it made on the operator's behalf, which is the honest version of "it
 * ran unattended".
 */
public class NonInteractive(
    private val answer: Boolean = false,
    private val textAnswer: String = "",
) : Prompt {

    /** How many questions were answered without anybody being asked. */
    public var asked: Int = 0
        private set

    override val isInteractive: Boolean
        get() = false

    override suspend fun confirm(question: String, default: Boolean): Boolean {
        asked++
        return answer
    }

    override suspend fun choose(question: String, choices: List<Choice<String>>): String {
        asked++
        // The first choice, because a caller that lists options puts the safe one first.
        return choices.first().value
    }

    override suspend fun text(question: String, default: String): String {
        asked++
        return textAnswer.ifEmpty { default }
    }
}

/**
 * A prompt that reads the terminal.
 *
 * Reading is blocking, which is what a terminal is; nothing here is called often enough for that to matter,
 * and pretending otherwise would mean a thread pool for the sake of one `readLine`.
 *
 * If standard input reaches its end — a closed pipe, a job sent to the background — the default is taken
 * rather than looping on an empty read, which is how a script that lost its terminal spins forever.
 */
public class TerminalPrompt(
    private val output: (String) -> Unit = ::print,
    private val input: () -> String? = ::readlnOrNull,
) : Prompt {

    override val isInteractive: Boolean
        get() = true

    override suspend fun confirm(question: String, default: Boolean): Boolean {
        val hint = if (default) "[Y/n]" else "[y/N]"
        output("$question $hint ")

        return when (input()?.trim()?.lowercase()) {
            null -> default
            "" -> default
            "y",
            "yes" -> true
            "n",
            "no" -> false
            else -> confirm(question, default)
        }
    }

    override suspend fun choose(question: String, choices: List<Choice<String>>): String {
        require(choices.isNotEmpty()) { "a choice needs something to choose between" }

        val keys = choices.joinToString(", ") { "${it.key} = ${it.label}" }
        output("$question ($keys) ")

        val typed = input()?.trim()?.lowercase() ?: return choices.first().value

        val chosen = choices.firstOrNull { it.key.lowercaseChar().toString() == typed }
        return chosen?.value ?: choose(question, choices)
    }

    override suspend fun text(question: String, default: String): String {
        val hint = if (default.isEmpty()) "" else " [$default]"
        output("$question$hint ")

        return input()?.trim()?.ifEmpty { default } ?: default
    }
}

/**
 * A prompt with the answers written down in advance.
 *
 * For tests, and for a run that has been through the questions once already and knows what it wants. Running
 * out of answers falls back to [fallback] rather than blocking.
 */
public class ScriptedPrompt(
    answers: List<String>,
    private val fallback: Prompt = NonInteractive(),
) : Prompt {

    private val remaining = ArrayDeque(answers)

    override val isInteractive: Boolean
        get() = false

    override suspend fun confirm(question: String, default: Boolean): Boolean {
        val answer = remaining.removeFirstOrNull() ?: return fallback.confirm(question, default)
        return answer.lowercase() in setOf("y", "yes", "true")
    }

    override suspend fun choose(question: String, choices: List<Choice<String>>): String {
        val answer = remaining.removeFirstOrNull() ?: return fallback.choose(question, choices)
        return choices.firstOrNull { it.key.lowercaseChar() == answer.first().lowercaseChar() }?.value
            ?: choices.first().value
    }

    override suspend fun text(question: String, default: String): String =
        remaining.removeFirstOrNull() ?: fallback.text(question, default)
}
