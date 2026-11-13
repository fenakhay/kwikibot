package com.fenakhay.kwikibot.client.model

import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.io.Source
import kotlinx.io.files.Path

/**
 * One page of an XML dump, with the one revision the dump carries.
 *
 * @param title the full title, with its namespace prefix.
 * @param namespace the namespace number, since a dump records the number and not the name.
 * @param pageId the wiki's id for the page.
 * @param revisionId the revision this text came from.
 * @param text the wikitext, as of that revision.
 * @param timestamp when the revision was made.
 * @param contributor who made it, absent where the dump withheld it.
 * @param comment the edit summary.
 * @param isRedirect whether the page is a redirect.
 * @param redirectTarget the page a redirect points to, as the dump names it, or `null` for a page that is not
 *   one.
 */
public data class DumpPage(
    val title: String,
    val namespace: Int,
    val pageId: Long,
    val revisionId: Long,
    val text: String,
    val timestamp: Instant? = null,
    val contributor: String? = null,
    val comment: String? = null,
    val isRedirect: Boolean = false,
    val redirectTarget: String? = null,
) {

    /** Whether this page is in one of [namespaces]. Every namespace matches an empty set. */
    public fun inNamespaces(namespaces: Set<Int>): Boolean = namespaces.isEmpty() || namespace in namespaces
}

/** How a dump is compressed. */
public enum class Compression {
    /** Plain XML. */
    NONE,

    /** gzip. */
    GZIP,

    /** bzip2, which is what Wikimedia publishes, including multistream dumps. */
    BZIP2,
}

/**
 * Reads a MediaWiki XML dump without holding it in memory.
 *
 * A current-pages dump of a large wiki is tens of gigabytes uncompressed, so this cuts it into pages, and
 * parses one at a time as it is read.
 *
 * ```
 * XmlDump.pages(Path("enwiktionary-latest-pages-articles.xml.bz2"), namespaces = setOf(0))
 *     .forEach { … }
 * ```
 *
 * **Compression** is read from the file name for a path: plain XML, `.gz`, or `.bz2`.
 *
 * **Threads.** [pages] takes one and blocks it. [scan] and [flow] use as many as they are given, reading on
 * one, and decompressing and parsing on the rest: bzip2 block by block, wherever the blocks fall, with or
 * without a multistream index. gzip has no boundaries to split on, so its decompression takes one thread, and
 * only the parsing shares out. On JavaScript there is one thread, and these keep yielding it so the page or
 * server stays live.
 *
 * **A truncated or corrupt file throws** a [com.fenakhay.kwikibot.client.model.dump.DumpFormatException],
 * after yielding every whole page before the damage, however many threads read it. An interrupted download is
 * common, and failing silently is unsafe: a bot that treats a partial dump as the whole wiki concludes that
 * every missing page has been deleted. A read on many threads checks the same things as one on a single
 * thread, every block and stream checksum and marker among them, and gives the same pages or throws.
 *
 * **History dumps.** Only a page's first revision is held and read, so a page with thousands of them takes no
 * more memory than a page with one.
 *
 * **Untrusted input.** A DTD is refused and nothing but XML's own five entities and character references is
 * expanded, so a dump can neither read other files nor blow up in size. A dump that says it is in anything
 * but UTF-8 is refused, and so is a page past 256 MiB before the end of its first revision. MediaWiki writes
 * no CDATA sections; a dump from elsewhere with `</page>` inside one is not read correctly.
 */
public expect object XmlDump {

    /**
     * The pages of a dump file in [namespaces], or every page when it is empty.
     *
     * The file is opened when the sequence is first read, not when this is called, and closed when it is read
     * to the end. A sequence abandoned midway leaves it open until it is collected; [scan] and [flow] do not.
     */
    public fun pages(path: Path, namespaces: Set<Int> = emptySet()): Sequence<DumpPage>

    /**
     * The pages of a dump read from [source], compressed as [compression] says, in [namespaces] or every page
     * when it is empty. [source] is closed when the sequence ends.
     */
    public fun pages(
        source: Source,
        namespaces: Set<Int> = emptySet(),
        compression: Compression = Compression.NONE,
    ): Sequence<DumpPage>

    /**
     * Hands every page of a dump in [namespaces] to [block], on [workers] coroutines at once.
     *
     * The fastest way through a dump, when the order does not matter. Pages are handed out in no particular
     * order. The file is closed when the scan ends, however it ends, and the first exception from [block]
     * ends it.
     *
     * @param path the dump.
     * @param namespaces the namespaces wanted, or every one when it is empty.
     * @param workers how many coroutines to run; one per processor by default. Decompression takes no more
     *   threads than there are processors, however many this asks for.
     * @param block what to do with each page.
     */
    public suspend fun scan(
        path: Path,
        namespaces: Set<Int> = emptySet(),
        workers: Int = availableWorkers(),
        block: suspend (DumpPage) -> Unit,
    )

    /**
     * The pages of a dump in [namespaces], in the dump's order, decoded on [workers] coroutines ahead of what
     * has been collected.
     *
     * @param path the dump.
     * @param namespaces the namespaces wanted, or every one when it is empty.
     * @param workers how many coroutines to run; one per processor by default. Decompression takes no more
     *   threads than there are processors, however many this asks for.
     */
    public fun flow(
        path: Path,
        namespaces: Set<Int> = emptySet(),
        workers: Int = availableWorkers(),
    ): Flow<DumpPage>
}

/** How many threads there are to work on: the processors here, or one on JavaScript. */
public expect fun availableWorkers(): Int
