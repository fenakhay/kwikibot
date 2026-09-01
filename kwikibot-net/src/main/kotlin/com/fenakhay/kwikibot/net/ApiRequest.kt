package com.fenakhay.kwikibot.net

/**
 * One call to `api.php`, as parameters rather than a URL.
 *
 * Format parameters are added by the transport, so callers pass only what the action needs.
 */
public data class ApiRequest(
    /** Every parameter the call carries, including `action`, which is required. */
    val params: Map<String, String>,
    /** Whether this changes the wiki, which decides how the throttle paces it. */
    val kind: RequestKind = RequestKind.READ,
) {
    init {
        require(ACTION in params) { "an API request needs an 'action' parameter" }
    }

    /** The API action, for logging and error messages. */
    val action: String get() = params.getValue(ACTION)

    /** Whether this request changes the wiki, and must therefore be paced as a write. */
    val isWrite: Boolean get() = kind == RequestKind.WRITE

    /**
     * Whether this request must be POSTed regardless of its length.
     *
     * Writes must be. So must anything carrying a secret: a URL ends up in proxy logs, server
     * logs and browser history, so a password or token has no business in a query string.
     * MediaWiki enforces this too, refusing `action=login` over GET.
     */
    val requiresPost: Boolean
        get() = isWrite || params.keys.any { it.carriesSecret() }

    /**
     * The parameters in the order they must be sent.
     *
     * Anything whose name ends in `token` goes last: MediaWiki documents that a truncated
     * request must not be able to end with a valid token, and rejects requests where a token
     * is followed by more parameters.
     */
    public fun ordered(): List<Pair<String, String>> =
        params.entries
            .sortedBy { it.key.lowercase().endsWith(TOKEN_SUFFIX) }
            .map { it.key to it.value }

    private fun String.carriesSecret(): Boolean =
        lowercase().let { it.endsWith(TOKEN_SUFFIX) || it in SECRETS }

    /** Building a request, and the parameter names that need special handling. */
    public companion object {
        private const val ACTION = "action"
        private const val TOKEN_SUFFIX = "token"

        /** Parameter names that carry a credential and must never appear in a URL. */
        private val SECRETS = setOf("lgpassword", "password", "retype", "oldpassword")

        /** Builds a request from vararg pairs, dropping any whose value is null. */
        public fun of(
            action: String,
            vararg params: Pair<String, String?>,
            kind: RequestKind = RequestKind.READ,
        ): ApiRequest = ApiRequest(
            params = buildMap {
                put(ACTION, action)
                for ((key, value) in params) {
                    if (value != null) put(key, value)
                }
            },
            kind = kind,
        )
    }
}
