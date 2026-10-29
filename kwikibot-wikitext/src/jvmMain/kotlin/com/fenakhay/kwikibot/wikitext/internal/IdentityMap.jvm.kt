package com.fenakhay.kwikibot.wikitext.internal

import java.util.IdentityHashMap

internal actual fun <K : Any, V : Any> identityMap(): IdentityMap<K, V> =
    object : IdentityMap<K, V> {
        private val map = IdentityHashMap<K, V>()

        override fun get(key: K): V? = map[key]

        override fun set(key: K, value: V) {
            map[key] = value
        }
    }
