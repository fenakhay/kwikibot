package com.fenakhay.kwikibot.net.transport

/**
 * Where a wiki's entry points live.
 *
 * @param server the host, without a scheme (`en.wiktionary.org`).
 * @param scriptPath the path MediaWiki is installed under, `/w` on Wikimedia wikis and often empty on
 *   third-party installs.
 * @param secure whether to speak HTTPS. Off only for local test wikis.
 */
public data class ApiEndpoint(
    val server: String,
    val scriptPath: String = "/w",
    val secure: Boolean = true,
) {
    init {
        require(server.isNotBlank()) { "server must not be blank" }
        require(!server.contains("://")) { "server is a host name, not a URL: $server" }
        require(scriptPath.isEmpty() || scriptPath.startsWith("/")) {
            "script path must start with a slash: $scriptPath"
        }
    }

    private val origin: String
        get() = "${if (secure) "https" else "http"}://$server"

    /** The `api.php` URL every request goes to. */
    val apiUrl: String
        get() = "$origin$scriptPath/api.php"

    /** The `index.php` URL, for permalinks and human-facing links. */
    val indexUrl: String
        get() = "$origin$scriptPath/index.php"

    /** The article path for a page title, as it appears in a browser. */
    public fun articleUrl(title: String): String = "$origin/wiki/${title.replace(' ', '_')}"

    /** Building an endpoint for a wiki whose layout is already known. */
    public companion object {
        /** A Wikimedia project endpoint, such as `en` + `wiktionary.org`. */
        public fun wikimedia(code: String, domain: String): ApiEndpoint =
            ApiEndpoint(server = "$code.$domain", scriptPath = "/w")
    }
}
