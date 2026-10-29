package com.fenakhay.kwikibot.net

/** Read from Node's `process.env`; a browser has no `process`, so every variable reads as unset there. */
public actual fun environmentVariable(name: String): String? =
    js("typeof process === 'undefined' ? undefined : process.env[name]")
