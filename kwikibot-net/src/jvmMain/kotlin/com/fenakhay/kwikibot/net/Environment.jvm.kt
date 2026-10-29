@file:JvmName("Environment")

package com.fenakhay.kwikibot.net

/** Read with [System.getenv]. */
public actual fun environmentVariable(name: String): String? = System.getenv(name)
