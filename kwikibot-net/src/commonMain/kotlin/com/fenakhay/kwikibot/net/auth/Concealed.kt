package com.fenakhay.kwikibot.net.auth

/**
 * Keeps the fields of [target] from whatever lists an object's properties, where a program's objects can be
 * listed at all, so that logging an object holding credentials does not write them out.
 */
internal expect fun concealFields(target: Any)
