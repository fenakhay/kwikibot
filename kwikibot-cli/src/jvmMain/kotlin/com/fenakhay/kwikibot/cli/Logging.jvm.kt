package com.fenakhay.kwikibot.cli

/**
 * Sets [LOGIN_MANAGER]'s level for slf4j-simple, which logs to standard error, unless a system property
 * already does.
 */
internal actual fun configureLogging() {
    System.getProperties().putIfAbsent("org.slf4j.simpleLogger.log.$LOGIN_MANAGER", "warn")
}
