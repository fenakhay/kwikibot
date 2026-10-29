package com.fenakhay.kwikibot.wikitext.internal

/** JavaScript's `Map`, which compares object keys by identity. */
@JsName("Map")
private external class JsMap<K, V> {
    fun get(key: K): V?

    fun set(key: K, value: V)
}

internal actual fun <K : Any, V : Any> identityMap(): IdentityMap<K, V> =
    object : IdentityMap<K, V> {
        private val map = JsMap<K, V>()

        override fun get(key: K): V? = map.get(key)

        override fun set(key: K, value: V) {
            map.set(key, value)
        }
    }
