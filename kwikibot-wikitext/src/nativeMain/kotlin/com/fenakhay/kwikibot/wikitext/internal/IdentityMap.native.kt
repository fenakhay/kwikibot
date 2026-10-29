package com.fenakhay.kwikibot.wikitext.internal

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.identityHashCode

internal actual fun <K : Any, V : Any> identityMap(): IdentityMap<K, V> =
    object : IdentityMap<K, V> {
        private val map = HashMap<Identity, V>()

        override fun get(key: K): V? = map[Identity(key)]

        override fun set(key: K, value: V) {
            map[Identity(key)] = value
        }
    }

/** [value] as a map key that is equal only to itself. */
private class Identity(private val value: Any) {
    override fun equals(other: Any?): Boolean = other is Identity && other.value === value

    @OptIn(ExperimentalNativeApi::class) override fun hashCode(): Int = value.identityHashCode()
}
