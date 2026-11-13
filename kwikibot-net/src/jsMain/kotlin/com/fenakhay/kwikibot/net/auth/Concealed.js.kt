package com.fenakhay.kwikibot.net.auth

/**
 * Makes the fields non-enumerable, which `JSON.stringify`, `console.log`, `Object.keys` and a spread go by.
 */
internal actual fun concealFields(target: Any) {
    val objects: dynamic = js("Object")
    for (name in objects.keys(target).unsafeCast<Array<String>>()) {
        val hidden: dynamic = js("({ enumerable: false })")
        objects.defineProperty(target, name, hidden)
    }
}
