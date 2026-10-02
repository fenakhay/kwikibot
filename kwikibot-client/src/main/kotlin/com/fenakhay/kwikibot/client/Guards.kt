package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.protocol.SiteInfo

/**
 * Checks a wiki can do what is about to be asked of it.
 *
 * Plain calls, intended to run once at the start of a run rather than per page. A bot that lacks a right
 * should fail before it reads ten thousand pages, not on its first attempt to use it.
 *
 * ```
 * wiki.requireRight("rollback")
 * wiki.requireExtension("WikibaseClient")
 * ```
 */

/**
 * Fails unless this session holds [right].
 *
 * @throws WikiError.Auth.MissingRight if it does not.
 */
public suspend fun Wiki.requireRight(right: String) {
    if (!hasRight(right)) throw WikiError.Auth.MissingRight(right)
}

/** Whether this session holds [right]. */
public suspend fun Wiki.hasRight(right: String): Boolean = users.current().hasRight(right)

/**
 * Fails unless the account is in [group], such as `bot` for a bot that must not run without its flag.
 *
 * Read from the identity the session opened with, so it costs no request. Prefer [requireRight] where a right
 * says what is needed; a group is for policy, such as a wiki that lets only flagged bots run.
 *
 * @throws WikiError.Auth.PermissionDenied if it is not.
 */
public fun Wiki.requireGroup(group: String) {
    if (group !in identity.groups) {
        throw WikiError.Auth.PermissionDenied("${identity.name} is not in the '$group' group")
    }
}

/**
 * Fails unless the wiki has [extension] installed.
 *
 * @throws WikiError.Configuration.MissingExtension if it does not.
 */
public fun Wiki.requireExtension(extension: String) {
    if (!hasExtension(extension)) throw WikiError.Configuration.MissingExtension(extension)
}

/** Whether the wiki has [extension] installed. */
public fun Wiki.hasExtension(extension: String): Boolean = info.hasExtension(extension)

/**
 * Fails unless the wiki runs at least [version].
 *
 * @throws WikiError.Configuration.VersionTooOld if it is older.
 */
public fun Wiki.requireVersion(version: MediaWikiVersion) {
    val running = MediaWikiVersion.parse(info.version)
    if (running < version) {
        throw WikiError.Configuration.VersionTooOld(
            required = version.toString(),
            actual = running.toString(),
        )
    }
}

/** Whether the wiki runs at least [version]. */
public fun Wiki.hasVersion(version: MediaWikiVersion): Boolean = info.hasVersion(version)

/**
 * Whether the wiki described by this site info runs at least [version].
 *
 * For code that holds the site info but not the wiki handle. Most differences between releases are better
 * read from the wiki's description of its API, [Wiki.paramInfo]; a version is for behaviour that cannot show.
 */
public fun SiteInfo.hasVersion(version: MediaWikiVersion): Boolean =
    MediaWikiVersion.parse(this.version) >= version

/**
 * A MediaWiki version, comparable the way MediaWiki numbers them.
 *
 * String comparison is not enough — `1.10` is newer than `1.9`, and `1.44.0-wmf.3` is older than `1.44.0` —
 * so the numbers are compared as numbers and a suffix loses to its absence.
 */
public class MediaWikiVersion
private constructor(
    private val numbers: List<Int>,
    private val suffix: String,
    private val text: String,
) : Comparable<MediaWikiVersion> {

    override fun compareTo(other: MediaWikiVersion): Int {
        val length = maxOf(numbers.size, other.numbers.size)
        for (index in 0 until length) {
            val mine = numbers.getOrElse(index) { 0 }
            val theirs = other.numbers.getOrElse(index) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }

        // A release candidate or a wmf build is older than the release it leads to, so having a
        // suffix loses to having none.
        return when {
            suffix == other.suffix -> 0
            suffix.isEmpty() -> 1
            other.suffix.isEmpty() -> -1
            else -> suffix.compareTo(other.suffix)
        }
    }

    override fun equals(other: Any?): Boolean = other is MediaWikiVersion && compareTo(other) == 0

    override fun hashCode(): Int = numbers.hashCode() * PRIME + suffix.hashCode()

    override fun toString(): String = text

    /** The rights and extensions worth checking for by name. */
    public companion object {
        private const val PRIME = 31

        /**
         * Reads a version as MediaWiki reports it: `1.44.0`, `1.45.0-wmf.6`, `1.43.0-rc.1`.
         *
         * Anything unparseable becomes version zero rather than throwing, since a wiki that reports a version
         * this library cannot read is still a wiki worth talking to.
         */
        public fun parse(raw: String): MediaWikiVersion {
            val trimmed = raw.trim().removePrefix("MediaWiki ")
            val numbers =
                trimmed.takeWhile { it.isDigit() || it == '.' }.split('.').mapNotNull { it.toIntOrNull() }
            val suffix = trimmed.dropWhile { it.isDigit() || it == '.' }

            return MediaWikiVersion(numbers, suffix, trimmed)
        }
    }
}
