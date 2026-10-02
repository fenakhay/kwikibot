package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.model.MwTimestamp
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

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
    /** The constructor 1.1 compiled against, kept so code built then still links. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        title: String,
        namespace: Int,
        pageId: Long,
        revisionId: Long,
        text: String,
        timestamp: Instant? = null,
        contributor: String? = null,
        comment: String? = null,
        isRedirect: Boolean = false,
    ) : this(title, namespace, pageId, revisionId, text, timestamp, contributor, comment, isRedirect, null)

    /** Whether this page is in one of [namespaces]. Every namespace matches an empty set. */
    public fun inNamespaces(namespaces: Set<Int>): Boolean = namespaces.isEmpty() || namespace in namespaces
}

/**
 * Reads a MediaWiki XML dump without holding it in memory.
 *
 * A current-pages dump of a large wiki is tens of gigabytes uncompressed. Parsing it as a document is not an
 * option, so this pulls one page at a time off a streaming parser and hands back a [Sequence] that reads as
 * it is consumed, or through [scan] hands the pages to several workers at once.
 *
 * ```
 * XmlDump.pages(Path.of("enwiktionary-latest-pages-articles.xml.bz2"), namespaces = setOf(0))
 *     .forEach { … }
 * ```
 *
 * **Compression** is read from the file name: plain XML, `.gz`, and `.bz2`, which is what Wikimedia
 * publishes, including the multistream dumps that are many bzip2 streams end to end.
 *
 * **Namespaces** asked for are filtered as the dump is read: a page in any other namespace is passed over
 * before its text is, which on a dump of every namespace is most of the work.
 *
 * **Entity limits** the JDK puts on XML are lifted for this reader alone. They exist to stop a small document
 * expanding into a large one, and a large dump passes the default of fifty million entities on its own.
 *
 * **A truncated file throws**, after yielding the pages it did contain. An interrupted download is common,
 * and failing silently is unsafe: a bot that treats a partial dump as the whole wiki concludes that every
 * missing page has been deleted.
 */
public object XmlDump {

    /** The pages of a dump file. */
    public fun pages(path: Path): Sequence<DumpPage> = pages(path, emptySet())

    /**
     * The pages of a dump file in [namespaces], or every page when it is empty.
     *
     * The file is opened when the sequence is first read, not when this is called, and closed when it is read
     * to the end. A sequence abandoned midway leaves it open until it is collected; [scan] does not.
     */
    public fun pages(path: Path, namespaces: Set<Int>): Sequence<DumpPage> = sequence {
        open(path).use { input -> yieldAll(read(input, namespaces)) }
    }

    /** The pages of a dump read from [input], which is closed when the sequence ends. */
    public fun pages(input: InputStream): Sequence<DumpPage> = pages(input, emptySet())

    /** The pages of a dump read from [input] in [namespaces], or every page when it is empty. */
    public fun pages(input: InputStream, namespaces: Set<Int>): Sequence<DumpPage> = sequence {
        input.use { yieldAll(read(it, namespaces)) }
    }

    /**
     * Hands every page of a dump in [namespaces] to [block], on [workers] coroutines at once.
     *
     * One reader decompresses and parses while the workers run [block], so a bot whose work per page is the
     * slow part keeps every core busy. A multistream dump with its `-index.txt.bz2` beside it goes further:
     * its streams are independent, so each worker decompresses its own and decompression is shared out too.
     *
     * Pages are handed out in no particular order. The file is closed when the scan ends, however it ends,
     * and the first exception from [block] ends it.
     */
    public suspend fun scan(
        path: Path,
        workers: Int = Runtime.getRuntime().availableProcessors(),
        namespaces: Set<Int> = emptySet(),
        block: suspend (DumpPage) -> Unit,
    ) {
        require(workers > 0) { "a scan needs at least one worker" }
        val offsets = if (workers > 1) multistreamOffsets(path) else null
        if (offsets != null) {
            scanStreams(path, offsets, workers, namespaces, block)
        } else {
            scanOne(path, workers, namespaces, block)
        }
    }

    /** One reader feeding [workers] through a bounded queue, so a slow worker holds the reader back. */
    private suspend fun scanOne(
        path: Path,
        workers: Int,
        namespaces: Set<Int>,
        block: suspend (DumpPage) -> Unit,
    ) = coroutineScope {
        val queue = Channel<DumpPage>(workers * QUEUED_PER_WORKER)
        launch(Dispatchers.IO) {
            try {
                open(path).use { input -> for (page in read(input, namespaces)) queue.send(page) }
            } finally {
                queue.close()
            }
        }
        repeat(workers) { launch(Dispatchers.Default) { for (page in queue) block(page) } }
    }

    /** Each worker decompressing and reading whole streams of a multistream dump, between [offsets]. */
    private suspend fun scanStreams(
        path: Path,
        offsets: LongArray,
        workers: Int,
        namespaces: Set<Int>,
        block: suspend (DumpPage) -> Unit,
    ) = coroutineScope {
        val streams = Channel<LongRange>(Channel.UNLIMITED)
        val end = path.fileSize()
        offsets.forEachIndexed { index, start ->
            streams.trySend(start until (offsets.getOrNull(index + 1) ?: end))
        }
        streams.close()

        FileChannel.open(path, StandardOpenOption.READ).use { file ->
            coroutineScope {
                repeat(workers) {
                    launch(Dispatchers.Default) {
                        for (range in streams) {
                            for (page in read(stream(file, range), namespaces)) block(page)
                        }
                    }
                }
            }
        }
    }

    /**
     * One stream of a multistream dump, decompressed and made a document: each holds up to a hundred `<page>`
     * elements and nothing around them. The dump's closing tag is in a stream of its own at the end, which
     * the last range takes in.
     */
    private fun stream(file: FileChannel, range: LongRange): InputStream {
        val bytes = ByteBuffer.allocate((range.last - range.first + 1).toInt())
        while (bytes.hasRemaining()) {
            val read = file.read(bytes, range.first + bytes.position())
            check(read >= 0) { "the dump ends inside a stream that starts at ${range.first}" }
        }
        val xml =
            BZip2CompressorInputStream(ByteArrayInputStream(bytes.array()), true)
                .use { String(it.readBytes(), StandardCharsets.UTF_8) }
                .trimEnd()
                .removeSuffix(DUMP_CLOSE)
        return "$DUMP_OPEN$xml$DUMP_CLOSE".byteInputStream(StandardCharsets.UTF_8)
    }

    /**
     * Where each stream of a multistream dump starts, from the index Wikimedia publishes beside it, or `null`
     * when there is no index.
     *
     * The index has one line per page, `offset:id:title`; up to a hundred pages share each offset.
     */
    private fun multistreamOffsets(path: Path): LongArray? {
        val name = path.name
        if (!name.endsWith(MULTISTREAM_SUFFIX, ignoreCase = true)) return null
        val index = path.resolveSibling(name.dropLast(MULTISTREAM_SUFFIX.length) + INDEX_SUFFIX)
        if (!index.exists()) return null

        val offsets = ArrayList<Long>()
        BZip2CompressorInputStream(BufferedInputStream(index.inputStream()), true)
            .bufferedReader()
            .useLines { lines ->
                for (line in lines) {
                    val offset = line.substringBefore(':').toLongOrNull() ?: continue
                    if (offsets.isEmpty() || offsets.last() != offset) offsets += offset
                }
            }
        return offsets.toLongArray().takeIf { it.isNotEmpty() }
    }

    /** The dump at [path], decompressed by what its name says it is. */
    private fun open(path: Path): InputStream {
        val input = BufferedInputStream(path.inputStream(), BUFFER)
        val name = path.name.lowercase()
        return when {
            name.endsWith(".bz2") -> BZip2CompressorInputStream(input, true)
            name.endsWith(".gz") -> GZIPInputStream(input, BUFFER)
            else -> input
        }
    }

    /** The pages of the dump on [input], which the caller closes. */
    private fun read(input: InputStream, namespaces: Set<Int>): Sequence<DumpPage> = sequence {
        val reader = FACTORY.get().createXMLStreamReader(input)
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == PAGE) {
                    readPage(reader, namespaces)?.let { yield(it) }
                }
            }
        } finally {
            reader.close()
        }
    }

    /**
     * Reads one `<page>` element, or passes over it when it is in a namespace not asked for.
     *
     * Only the first revision is kept. A current-pages dump has exactly one; a full-history dump has
     * thousands per page, and holding them all in a single object is not what any caller of this wants —
     * later revisions are skipped rather than merged over the first.
     */
    private fun readPage(reader: XMLStreamReader, namespaces: Set<Int>): DumpPage? {
        val page = PageBuilder()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    page.startElement(reader)
                    // A dump puts a page's namespace before its text, so an unwanted page goes unread.
                    if (page.namespaceRead && namespaces.isNotEmpty() && page.namespace !in namespaces) {
                        skipPage(reader)
                        return null
                    }
                }
                XMLStreamConstants.END_ELEMENT -> {
                    if (reader.localName == PAGE) return page.build()
                    page.endElement(reader.localName)
                }
                else -> Unit
            }
        }

        // A dump that ends mid-page is truncated; there is no page to report.
        return null
    }

    /** Moves past the end of the page being read, reading nothing in it. */
    private fun skipPage(reader: XMLStreamReader) {
        var depth = 1
        while (depth > 0 && reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> depth++
                XMLStreamConstants.END_ELEMENT -> depth--
            }
        }
    }

    /** Accumulates the fields of one page as the parser walks over them. */
    private class PageBuilder {
        private var title = ""
        var namespace = 0
            private set

        var namespaceRead = false
            private set

        private var pageId = 0L
        private var revisionId = 0L
        private var text = ""
        private var timestamp: Instant? = null
        private var contributor: String? = null
        private var comment: String? = null
        private var isRedirect = false
        private var redirectTarget: String? = null

        private var inRevision = false
        private var revisionsSeen = 0

        fun startElement(reader: XMLStreamReader) {
            // Everything after the first revision belongs to a version that is not requested.
            if (revisionsSeen > 0 && reader.localName != REVISION) return

            when (reader.localName) {
                REVISION -> inRevision = true
                "redirect" -> {
                    isRedirect = true
                    redirectTarget = reader.getAttributeValue(null, "title")
                }

                // A page and its revision both have an <id>; which one it is depends on where
                // the parser is, not on the element name.
                "id" -> {
                    val id = reader.elementText.trim().toLongOrNull() ?: 0L
                    if (inRevision) revisionId = id else pageId = id
                }

                "ns" -> {
                    namespace = reader.elementText.trim().toIntOrNull() ?: 0
                    namespaceRead = true
                }

                else -> textElement(reader.localName, reader)
            }
        }

        /** The elements whose value is simply their text. */
        private fun textElement(name: String, reader: XMLStreamReader) {
            when (name) {
                "title" -> title = reader.elementText
                "timestamp" -> timestamp = MwTimestamp.parseOrNull(reader.elementText)
                "username",
                "ip" -> contributor = reader.elementText
                "comment" -> comment = reader.elementText
                "text" -> text = reader.elementText
                else -> Unit
            }
        }

        fun endElement(name: String) {
            if (name == REVISION) {
                inRevision = false
                revisionsSeen++
            }
        }

        fun build() =
            DumpPage(
                title = title,
                namespace = namespace,
                pageId = pageId,
                revisionId = revisionId,
                text = text,
                timestamp = timestamp,
                contributor = contributor,
                comment = comment,
                isRedirect = isRedirect,
                redirectTarget = redirectTarget,
            )
    }

    private const val PAGE = "page"
    private const val REVISION = "revision"
    private const val BUFFER = 1 shl 16
    private const val QUEUED_PER_WORKER = 4
    private const val MULTISTREAM_SUFFIX = ".xml.bz2"
    private const val INDEX_SUFFIX = "-index.txt.bz2"
    private const val DUMP_OPEN = "<mediawiki>"
    private const val DUMP_CLOSE = "</mediawiki>"

    /**
     * The JDK's limits on entities. Each `&amp;`, `&lt;` or other predefined entity in a dump counts one
     * towards the total size limit, fifty million by default, which a large dump passes on its own. Zero
     * lifts each one.
     *
     * Set on this reader's own factory rather than as the system properties of the same names, which every
     * XML parser the program built afterwards would read.
     */
    private val ENTITY_LIMITS =
        listOf(
            "jdk.xml.entityExpansionLimit",
            "jdk.xml.totalEntitySizeLimit",
            "jdk.xml.maxGeneralEntitySizeLimit",
            "jdk.xml.entityReplacementLimit",
        )

    /** One factory per thread: a factory is not promised to be thread-safe, and [scan] reads on several. */
    private val FACTORY: ThreadLocal<XMLInputFactory> = ThreadLocal.withInitial(::factory)

    private fun factory(): XMLInputFactory =
        XMLInputFactory.newInstance().apply {
            // A dump is untrusted input. Entity expansion and external references are how an
            // XML parser is induced to read the rest of the filesystem; with neither allowed,
            // the size limits protect against nothing a dump can do.
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            for (limit in ENTITY_LIMITS) runCatching { setProperty(limit, "0") }
        }
}
