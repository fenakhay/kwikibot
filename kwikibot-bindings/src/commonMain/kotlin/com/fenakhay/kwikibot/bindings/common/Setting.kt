package com.fenakhay.kwikibot.bindings.common

/**
 * A setting a binding's caller can leave alone, turn off, or give a value: what JavaScript tells apart as
 * `undefined`, `null` and a value, for settings such as maxlag whose default is not "off".
 */
public sealed interface Setting<out T> {
    /** Left alone: the library's default applies. */
    public data object Default : Setting<Nothing>

    /** Turned off. */
    public data object Off : Setting<Nothing>

    /** Set to [value]. */
    public data class Value<out T>(val value: T) : Setting<T>

    /** Building a setting from a value that may be absent. */
    public companion object {
        /** [Value] of [value], or [Off] when it is `null`. */
        public fun <T : Any> ofNullable(value: T?): Setting<T> = if (value == null) Off else Value(value)
    }
}

/** What this setting resolves to, given the library's [default]: `null` when it is turned off. */
public fun <T : Any> Setting<T>.resolve(default: T?): T? =
    when (this) {
        Setting.Default -> default
        Setting.Off -> null
        is Setting.Value -> value
    }
