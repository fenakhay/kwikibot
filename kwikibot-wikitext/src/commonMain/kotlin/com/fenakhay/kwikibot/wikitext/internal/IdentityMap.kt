package com.fenakhay.kwikibot.wikitext.internal

/**
 * A map that tells its keys apart by identity rather than by equality.
 *
 * Nodes are data classes, so two nodes with the same contents are equal wherever they sit in a tree; a map
 * keyed by equality would give both the range of whichever was stored last.
 */
internal interface IdentityMap<K : Any, V : Any> {
    operator fun get(key: K): V?

    operator fun set(key: K, value: V)
}

/** An empty [IdentityMap] backed by the platform's own identity-keyed map. */
internal expect fun <K : Any, V : Any> identityMap(): IdentityMap<K, V>
