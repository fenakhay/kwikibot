package com.fenakhay.kwikibot.protocol

/**
 * A `show=` parameter, where each option is on, off, or not mentioned.
 *
 * MediaWiki spells these as a pipe-joined list in which a leading `!` means "not": `rcshow` of
 * `bot|!minor` asks for bot edits that are not minor. Three states rather than two matters — not
 * mentioning `minor` returns both minor and non-minor edits, while `!minor` excludes them, and a
 * `Map<String, Boolean>` cannot say the difference between "off" and "unset".
 *
 * ```
 * OptionSet().on("bot").off("minor").toParam()   // "bot|!minor"
 * ```
 */
public class OptionSet private constructor(private val states: Map<String, Boolean>) {

    public constructor() : this(emptyMap())

    /** Requires [option] to be true. */
    public fun on(vararg option: String): OptionSet =
        OptionSet(states + option.associateWith { true })

    /** Requires [option] to be false. */
    public fun off(vararg option: String): OptionSet =
        OptionSet(states + option.associateWith { false })

    /** Stops constraining [option] either way. */
    public fun unset(vararg option: String): OptionSet = OptionSet(states - option.toSet())

    /** Whether [option] is constrained, and to what. `null` means it is not mentioned. */
    public operator fun get(option: String): Boolean? = states[option]

    /** Whether anything is constrained. */
    public val isEmpty: Boolean get() = states.isEmpty()

    /** The options this set mentions, in the order they were added. */
    public val names: Set<String> get() = states.keys

    /**
     * The parameter value, or `null` when nothing is constrained.
     *
     * `null` rather than an empty string, because an empty `show=` is not the same as no `show=`
     * on every module.
     */
    public fun toParam(): String? = states.entries
        .takeIf { it.isNotEmpty() }
        ?.joinToString("|") { (name, wanted) -> if (wanted) name else "!$name" }

    override fun toString(): String = toParam() ?: "(unconstrained)"

    override fun equals(other: Any?): Boolean = other is OptionSet && other.states == states

    override fun hashCode(): Int = states.hashCode()

    /** Reading a `show=` value that was built elsewhere. */
    public companion object {

        /**
         * Reads a `show=` value back.
         *
         * For a request built elsewhere, and for tests that assert what a service sent.
         */
        public fun parse(value: String): OptionSet = OptionSet(
            value.split('|')
                .filter { it.isNotBlank() }
                .associate { option ->
                    if (option.startsWith("!")) option.drop(1) to false else option to true
                },
        )
    }
}
