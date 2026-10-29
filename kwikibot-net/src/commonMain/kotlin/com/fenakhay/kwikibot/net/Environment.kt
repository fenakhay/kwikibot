package com.fenakhay.kwikibot.net

/**
 * The environment variable [name] of this process, or `null` when it is not set or the platform gives a
 * program no environment, as a browser gives none.
 */
public expect fun environmentVariable(name: String): String?
