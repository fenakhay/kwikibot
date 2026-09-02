package com.fenakhay.kwikibot.net

/**
 * What this library is, for the places that have to say so.
 *
 * The version is read from the jar's manifest when there is one and falls back to a constant
 * when running from a build directory, so a bot started from Gradle reports something sensible
 * rather than "unknown".
 */
public object Kwikibot {

    /** The library name, as it appears in a user agent. */
    public const val NAME: String = "kwikibot"

    /** Where to read about it, for a user agent that has nothing better to point at. */
    public const val URL: String = "https://github.com/fenakhay/kwikibot"

    /**
     * The library version.
     *
     * From the jar manifest, or [FALLBACK_VERSION] when there is no jar — which is every run
     * from a build directory, including every test.
     */
    public val version: String by lazy {
        runCatching { Kwikibot::class.java.`package`?.implementationVersion }.getOrNull()
            ?: FALLBACK_VERSION
    }

    /**
     * The runtime this bot is on, for a version report.
     *
     * A wiki operator asking why a bot is misbehaving wants this before anything else.
     */
    public val runtime: String
        get() = "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}"

    /** The operating system, likewise. */
    public val platform: String
        get() = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"

    /**
     * Everything worth putting in a version report, one line each.
     *
     * Deliberately not the machine name, the working directory or the user: a version report
     * gets pasted into a public bug report, and none of those belong there.
     */
    public fun report(): String = buildString {
        appendLine("$NAME $version")
        appendLine("runtime: $runtime")
        appendLine("platform: $platform")
    }

    /** What [version] reports when there is no jar to read it from. */
    private const val FALLBACK_VERSION = "dev"
}
