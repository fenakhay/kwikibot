package com.fenakhay.kwikibot.client

/**
 * What a write should do to the account's watchlist.
 *
 * Without this every write follows the account's preferences, and an account configured to watch
 * what it edits builds a watchlist no one can read. A bot usually wants [NO_CHANGE]: it is not a
 * person, and nothing reads its watchlist.
 */
public enum class WatchMode(internal val apiValue: String) {
    /** Leave the watchlist exactly as it is. */
    NO_CHANGE("nochange"),

    /** Do whatever the account's preferences say, which is what the wiki does when unasked. */
    PREFERENCES("preferences"),

    /** Add the page to the watchlist. */
    WATCH("watch"),

    /** Remove the page from the watchlist. */
    UNWATCH("unwatch"),
}

/**
 * Adds the parameter to a write, when it says anything the wiki does not already assume.
 *
 * [WatchMode.PREFERENCES] is what a wiki does unasked, so sending it would add a parameter to
 * every write for no effect.
 */
internal fun WatchMode.applyTo(params: MutableMap<String, String>) {
    if (this != WatchMode.PREFERENCES) params["watchlist"] = apiValue
}
