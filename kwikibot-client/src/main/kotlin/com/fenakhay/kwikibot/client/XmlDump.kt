package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.MwTimestamp
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.time.Instant

/** One page of an XML dump, with the one revision the dump carries. */
public data class DumpPage(
    /** The full title, with its namespace prefix. */
    val title: String,
    /** The namespace number, since a dump records the number and not the name. */
    val namespace: Int,
    /** The wiki's id for the page. */
    val pageId: Long,
    /** The revision this text came from. */
    val revisionId: Long,
    /** The wikitext, as of that revision. */
    val text: String,
    /** When the revision was made. */
    val timestamp: Instant? = null,
    /** Who made it, absent where the dump withheld it. */
    val contributor: String? = null,
    /** The edit summary. */
    val comment: String? = null,
    /** Whether the page is a redirect. */
    val isRedirect: Boolean = false,
) {
    /** Whether this page is in one of [namespaces]. Every namespace matches an empty set. */
    public fun inNamespaces(namespaces: Set<Int>): Boolean =
        namespaces.isEmpty() || namespace in namespaces
}

/**
 * Reads a MediaWiki XML dump without holding it in memory.
 *
 * A current-pages dump of a large wiki is tens of gigabytes uncompressed. Parsing it as a
 * document is not an option, so this pulls one page at a time off a streaming parser and hands
 * back a [Sequence] that reads as it is consumed.
 *
 * ```
 * XmlDump.pages(Path.of("enwiktionary-latest-pages-articles.xml.gz"))
 *     .filter { it.inNamespaces(setOf(0)) }
 *     .forEach { … }
 * ```
 *
 * **Compression.** Plain XML and gzip are read directly. Wikimedia publishes `.bz2`, which the
 * JDK cannot decompress and which is not worth a dependency here: pipe it in instead, with
 * `bzip2 -dc dump.xml.bz2 | …`, or hand [pages] an already-decompressing stream.
 *
 * **A truncated file throws**, after yielding the pages it did contain. An interrupted download
 * is common, and failing silently is unsafe: a bot that treats a partial dump as the whole
 * wiki concludes that every missing page has been deleted.
 */
public object XmlDump {

    /**
     * The pages of a dump file.
     *
     * The sequence can be consumed once, and the stream is closed when it is exhausted or when
     * iteration stops.
     */
    public fun pages(path: Path): Sequence<DumpPage> {
        val stream = path.inputStream()
        val decompressed = if (path.name.endsWith(".gz")) GZIPInputStream(stream) else stream
        return pages(decompressed)
    }

    /** The pages of a dump read from [input], which is closed when the sequence ends. */
    public fun pages(input: InputStream): Sequence<DumpPage> = sequence {
        val factory = XMLInputFactory.newInstance().apply {
            // A dump is untrusted input. Entity expansion and external references are how an
            // XML parser is induced to read the rest of the filesystem.
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
        }

        val reader = factory.createXMLStreamReader(input)
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT &&
                    reader.localName == PAGE
                ) {
                    readPage(reader)?.let { yield(it) }
                }
            }
        } finally {
            reader.close()
            input.close()
        }
    }

    /**
     * Reads one `<page>` element.
     *
     * Only the first revision is kept. A current-pages dump has exactly one; a full-history dump
     * has thousands per page, and holding them all in a single object is not what any caller of
     * this wants — later revisions are skipped rather than merged over the first.
     */
    private fun readPage(reader: XMLStreamReader): DumpPage? {
        val page = PageBuilder()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> page.startElement(reader)
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

    /** Accumulates the fields of one page as the parser walks over them. */
    private class PageBuilder {
        private var title = ""
        private var namespace = 0
        private var pageId = 0L
        private var revisionId = 0L
        private var text = ""
        private var timestamp: Instant? = null
        private var contributor: String? = null
        private var comment: String? = null
        private var isRedirect = false

        private var inRevision = false
        private var revisionsSeen = 0

        fun startElement(reader: XMLStreamReader) {
            // Everything after the first revision belongs to a version that is not requested.
            if (revisionsSeen > 0 && reader.localName != REVISION) return

            when (reader.localName) {
                REVISION -> inRevision = true
                "redirect" -> isRedirect = true

                // A page and its revision both have an <id>; which one it is depends on where
                // the parser is, not on the element name.
                "id" -> {
                    val id = reader.elementText.trim().toLongOrNull() ?: 0L
                    if (inRevision) revisionId = id else pageId = id
                }

                else -> textElement(reader.localName, reader)
            }
        }

        /** The elements whose value is simply their text. */
        private fun textElement(name: String, reader: XMLStreamReader) {
            when (name) {
                "title" -> title = reader.elementText
                "ns" -> namespace = reader.elementText.trim().toIntOrNull() ?: 0
                "timestamp" -> timestamp = MwTimestamp.parseOrNull(reader.elementText)
                "username", "ip" -> contributor = reader.elementText
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

        fun build() = DumpPage(
            title = title,
            namespace = namespace,
            pageId = pageId,
            revisionId = revisionId,
            text = text,
            timestamp = timestamp,
            contributor = contributor,
            comment = comment,
            isRedirect = isRedirect,
        )
    }

    private const val PAGE = "page"
    private const val REVISION = "revision"
}
