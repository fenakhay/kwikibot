package com.fenakhay.kwikibot.bindings.common

import com.fenakhay.kwikibot.wikibase.value.EntityId

/** The entries a binding does not spell as plain kebab-case, with how it spells them. */
private val SPELLED_APART: Map<Enum<*>, String> = mapOf(EntityId.Kind.MEDIA_INFO to "mediainfo")

/**
 * [Enum.name] as a binding spells it: `NEWEST_FIRST` is `"newest-first"`, apart from the few entries Wikibase
 * spells as one word, such as `"mediainfo"`.
 */
public fun Enum<*>.argumentName(): String = SPELLED_APART[this] ?: name.lowercase().replace('_', '-')

/**
 * The entry of [E] that [value], the argument called [parameter], names.
 *
 * @throws IllegalArgumentException when it names none, saying which it could have been.
 */
public inline fun <reified E : Enum<E>> enumArgument(parameter: String, value: String): E {
    val entries = enumValues<E>()
    return entries.firstOrNull { it.argumentName() == value }
        ?: throw IllegalArgumentException(
            "$parameter cannot be '$value': it is one of ${entries.joinToString { "'${it.argumentName()}'" }}"
        )
}

/**
 * The entry of [E] that [value], the argument called [parameter], names, or `null` when it was not given.
 *
 * @throws IllegalArgumentException when it names none, saying which it could have been.
 */
public inline fun <reified E : Enum<E>> enumArgumentOrNull(parameter: String, value: String?): E? =
    value?.let {
        enumArgument<E>(parameter, it)
    }
