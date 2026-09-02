package com.fenakhay.kwikibot.net

/**
 * The `User-Agent` every request carries.
 *
 * Wikimedia's user-agent policy requires a descriptive string with a way to reach the operator; requests
 * without one are refused. This type makes that contact detail impossible to forget.
 *
 * @param tool the bot or tool name, e.g. `FenaBot`.
 * @param version the tool version, e.g. `1.0`.
 * @param contact a URL or email an operator can be reached at — typically the bot's user page.
 */
public data class UserAgent(
    val tool: String,
    val version: String,
    val contact: String,
) {
    init {
        require(tool.isNotBlank()) { "user agent needs a tool name" }
        require(contact.isNotBlank()) {
            "user agent needs contact details: Wikimedia refuses requests without them"
        }
    }

    /** The header value, in the form Wikimedia's policy asks for. */
    public val headerValue: String
        get() = "$tool/$version ($contact) kwikibot/$LIBRARY_VERSION"

    override fun toString(): String = headerValue

    /** What this library reports about itself in the header. */
    public companion object {
        /**
         * Reported alongside the tool so wiki operators can identify the client library.
         *
         * Read from the jar manifest, so a release cannot report the version of the one before it.
         */
        public val LIBRARY_VERSION: String
            get() = Kwikibot.version
    }
}
