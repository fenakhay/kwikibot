package com.fenakhay.kwikibot.cli

/**
 * The logger that reports each login, which the commands keep to warnings since they report the login
 * themselves.
 */
internal const val LOGIN_MANAGER: String = "com.fenakhay.kwikibot.net.auth.LoginManager"

/**
 * Sends what the library logs to standard error, at INFO and above, and [LOGIN_MANAGER] at WARN and above.
 */
internal expect fun configureLogging()
