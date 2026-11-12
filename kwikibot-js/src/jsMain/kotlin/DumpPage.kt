import com.fenakhay.kwikibot.bindings.dumps.compressionNamed
import com.fenakhay.kwikibot.bindings.dumps.dumpFlowWithDefaults
import com.fenakhay.kwikibot.bindings.dumps.dumpPagesWithDefaults
import com.fenakhay.kwikibot.bindings.dumps.scanDumpWithDefaults
import com.fenakhay.kwikibot.client.model.DumpPage as ClientDumpPage
import com.fenakhay.kwikibot.client.model.availableWorkers
import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
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

    /** Whether this page is in one of [namespaces]. Every namespace matches an empty array. */
    public fun inNamespaces(namespaces: Array<Int>): Boolean = page.inNamespaces(namespaces.toSet())
}

/** How many threads `dumpPages` and `scanDump` decode on unless told. */
@JsExport public fun availableDumpWorkers(): Int = availableWorkers()

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
    dumpFlowWithDefaults(Path(path), namespaces?.asList(), workers).toAsyncIterable(GlobalScope, ::DumpPage)

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
    compression: DumpCompression = "none".unsafeCast<DumpCompression>(),
    namespaces: Array<Int>? = null,
): Array<DumpPage> {
    val source = Buffer().apply { write(asByteArray(bytes)) }
    return dumpPagesWithDefaults(
            source,
            compressionNamed(compression.unsafeCast<String>()),
            namespaces?.asList(),
        )
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
    block: (page: DumpPage) -> Awaitable<Ignored>,
    namespaces: Array<Int>? = null,
    workers: Int? = null,
): Promise<Unit> = GlobalScope.promiseVoid {
    scanDumpWithDefaults(Path(path), namespaces?.asList(), workers) { page ->
        val _ = settledResult(block(DumpPage(page)))
    }
}

@Suppress("UNUSED_PARAMETER", "UnusedParameter")
private fun asByteArray(bytes: Uint8Array): ByteArray =
    js("new Int8Array(bytes.buffer, bytes.byteOffset, bytes.length)").unsafeCast<ByteArray>()
