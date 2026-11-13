package com.fenakhay.kwikibot.bindings.connect

/** What stands in for a secret in anything a listener hears. */
public const val REDACTED: String = "[redacted]"

/** The names that hold a secret whatever they end with: auth headers, cookies and the like. */
private val SECRET_NAMES = setOf("retype", "authorization", "cookie", "set-cookie")

/** Whether a parameter or a field called [name] holds a password, a token or an auth header. */
public fun namesSecret(name: String): Boolean {
    val lowered = name.lowercase()
    return lowered.endsWith("token") || lowered.endsWith("password") || lowered in SECRET_NAMES
}

/** [params], each one [namesSecret] says holds a secret replaced by [REDACTED]. */
public fun withoutSecrets(params: Map<String, String>): Map<String, String> =
    params.mapValues { (name, value) ->
        if (namesSecret(name)) REDACTED else value
    }
