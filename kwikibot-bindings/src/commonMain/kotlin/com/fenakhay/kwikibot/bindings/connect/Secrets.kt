package com.fenakhay.kwikibot.bindings.connect

/** What stands in for a secret in anything a listener hears. */
public const val REDACTED: String = "[redacted]"

private const val OAUTH_PREFIX = "oauth_"

/** The names that hold a secret whatever they end with: auth headers, cookies and the like. */
private val SECRET_NAMES = setOf("retype", "authorization", "cookie", "set-cookie", "sessionid")

/**
 * Whether a parameter or a field called [name] holds a password, a token, an auth header, or one of the
 * `oauth_` parameters an OAuth 1.0a signature is made of.
 */
public fun namesSecret(name: String): Boolean {
    val lowered = name.lowercase()
    return lowered.endsWith("token") ||
        lowered.endsWith("password") ||
        lowered.startsWith(OAUTH_PREFIX) ||
        lowered in SECRET_NAMES
}

/** [params], each one [namesSecret] says holds a secret replaced by [REDACTED]. */
public fun withoutSecrets(params: Map<String, String>): Map<String, String> =
    params.mapValues { (name, value) ->
        if (namesSecret(name)) REDACTED else value
    }
