package com.fenakhay.kwikibot.net

/**
 * What this library is, for the places that have to say so.
 *
 * The version is compiled in by the build, so it is the same whether this runs from a jar, from a build
 * directory or from a native image.
 */
public object Kwikibot {

    /** The library name, as it appears in a user agent. */
    public const val NAME: String = "kwikibot"

    /** Where to read about it, for a user agent that has nothing better to point at. */
    public const val URL: String = "https://github.com/fenakhay/kwikibot"

    /**
     * The library version.
     *
     * Compiled in by the build rather than read from the jar manifest. A native image has no manifest, so
     * reading one would make every native build report the wrong version while still appearing to work.
     */
    public val version: String
        get() = BUILD_VERSION

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
     * Deliberately not the machine name, the working directory or the user: a version report gets pasted into
     * a public bug report, and none of those belong there.
     */
    public fun report(): String = buildString {
        appendLine("$NAME $version")
        appendLine("runtime: $runtime")
        appendLine("platform: $platform")
    }
}
