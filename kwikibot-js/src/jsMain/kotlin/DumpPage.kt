import com.fenakhay.kwikibot.client.model.Compression
import com.fenakhay.kwikibot.client.model.DumpPage as ClientDumpPage
import com.fenakhay.kwikibot.client.model.XmlDump
import com.fenakhay.kwikibot.client.model.availableWorkers
import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.write
import org.khronos.webgl.Uint8Array

/** One page of an XML dump, with the one revision the dump carries. */
@JsExport
public class DumpPage internal constructor(private val page: ClientDumpPage) {

    /** The full title, with its namespace prefix. */
    public val title: String
        get() = page.title

    /** The namespace number. */
    public val namespace: Int
        get() = page.namespace

    /** The wiki's id for the page. */
    public val pageId: Double
        get() = page.pageId.toDouble()

    /** The revision this text came from. */
    public val revisionId: Double
        get() = page.revisionId.toDouble()

    /** The wikitext, as of that revision. */
    public val text: String
        get() = page.text

    /** When the revision was made, if the dump says. */
    public val timestamp: Date?
        get() = page.timestamp?.let { Date(it.toEpochMilliseconds().toDouble()) }

    /** Who made it, absent where the dump withheld it. */
    public val contributor: String?
        get() = page.contributor

    /** The edit summary. */
    public val comment: String?
        get() = page.comment

    /** Whether the page is a redirect. */
    public val isRedirect: Boolean
        get() = page.isRedirect

    /** The page a redirect points to, or `null` for a page that is not one. */
    public val redirectTarget: String?
        get() = page.redirectTarget
}

/**
 * The pages of the dump file at [path], in the dump's order, as an async iterable. Plain XML, `.gz` or
 * `.bz2`, going by the name. Node only.
 *
 * bzip2 is decoded on [workers] worker threads, one per processor unless told otherwise, so reading a dump
 * leaves the event loop free. Stopping the loop early, with `break`, stops the reading and the threads.
 *
 * @param path the dump.
 * @param namespaces the namespaces wanted, or every one when `null`.
 * @param workers how many threads to decode on.
 */
@JsExport
@OptIn(DelicateCoroutinesApi::class)
public fun dumpPages(
    path: String,
    namespaces: Array<Int>? = null,
    workers: Int? = null,
): AsyncIterable<DumpPage> =
    XmlDump.flow(Path(path), namespaces.orEmptySet(), workers ?: availableWorkers())
        .toAsyncIterable(GlobalScope, ::DumpPage)

/**
 * The pages of a dump already in memory, which is the way to read one in a browser: on one thread, and only
 * for a dump small enough to hold.
 *
 * @param bytes the dump.
 * @param compression `"none"`, `"gzip"` or `"bzip2"`.
 * @param namespaces the namespaces wanted, or every one when `null`.
 */
@JsExport
public fun dumpPagesOf(
    bytes: Uint8Array,
    compression: String = "none",
    namespaces: Array<Int>? = null,
): Array<DumpPage> {
    val source = Buffer().apply { write(asByteArray(bytes)) }
    return XmlDump.pages(source, namespaces.orEmptySet(), compressionNamed(compression))
        .map(::DumpPage)
        .toList()
        .toTypedArray()
}

/**
 * Calls [block] with every page of the dump file at [path], which may return a promise. The fastest way
 * through a dump when the order does not matter, as pages come in no particular order. Rejects with the first
 * failure. Node only.
 *
 * @param path the dump.
 * @param block what to do with each page.
 * @param namespaces the namespaces wanted, or every one when `null`.
 * @param workers how many threads to decode on.
 */
@JsExport
@OptIn(DelicateCoroutinesApi::class)
public fun scanDump(
    path: String,
    block: (page: DumpPage) -> Any?,
    namespaces: Array<Int>? = null,
    workers: Int? = null,
): Promise<Unit> = GlobalScope.promise {
    XmlDump.scan(Path(path), namespaces.orEmptySet(), workers ?: availableWorkers()) { page ->
        val answer = block(DumpPage(page))
        if (answer is Promise<*>) answer.await()
    }
}

private fun Array<Int>?.orEmptySet(): Set<Int> = this?.toSet().orEmpty()

private fun compressionNamed(name: String): Compression =
    when (name) {
        "none" -> Compression.NONE
        "gzip" -> Compression.GZIP
        "bzip2" -> Compression.BZIP2
        else -> throw IllegalArgumentException("unknown compression '$name'; use none, gzip or bzip2")
    }

@Suppress("UNUSED_PARAMETER", "UnusedParameter")
private fun asByteArray(bytes: Uint8Array): ByteArray =
    js("new Int8Array(bytes.buffer, bytes.byteOffset, bytes.length)").unsafeCast<ByteArray>()
