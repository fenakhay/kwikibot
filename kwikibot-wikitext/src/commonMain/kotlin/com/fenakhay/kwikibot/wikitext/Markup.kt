package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.internal.Builder
import com.fenakhay.kwikibot.wikitext.internal.IdentityMap
import com.fenakhay.kwikibot.wikitext.internal.NodeStack
import com.fenakhay.kwikibot.wikitext.internal.NodeWalk
import com.fenakhay.kwikibot.wikitext.internal.Rebuild
import com.fenakhay.kwikibot.wikitext.internal.TagNames
import com.fenakhay.kwikibot.wikitext.internal.Tokenizer
import com.fenakhay.kwikibot.wikitext.internal.TreeComparison
import com.fenakhay.kwikibot.wikitext.internal.Writer
import com.fenakhay.kwikibot.wikitext.internal.identityMap
import com.fenakhay.kwikibot.wikitext.internal.isLeaf
import com.fenakhay.kwikibot.wikitext.internal.sameKind
import com.fenakhay.kwikibot.wikitext.internal.sectionHeadings
import com.fenakhay.kwikibot.wikitext.internal.slot
import com.fenakhay.kwikibot.wikitext.internal.slots
import com.fenakhay.kwikibot.wikitext.node.Argument
import com.fenakhay.kwikibot.wikitext.node.Attribute
import com.fenakhay.kwikibot.wikitext.node.Comment
import com.fenakhay.kwikibot.wikitext.node.ExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.HtmlEntity
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Parameter
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink

/**
 * Parsed wikitext: a sequence of nodes that can be queried, edited and written back.
 *
 * Immutable. Every edit returns a new `Markup`, and every node that was not touched keeps the exact text it
 * was parsed from — so a bot that changes one template parameter produces a diff of one template parameter,
 * not of the whole page.
 *
 * ```
 * val code = Wikitext.parse(page.text)
 * val updated = code.mapTemplates("col") { it.withParameter("2", "hypervolcano") }
 * updated.serialize()
 * ```
 */
public class Markup private constructor(nodes: List<Node>, owned: Boolean) {

    /**
     * The nodes, in source order.
     *
     * A list passed in from outside is copied; one the parser hands over is used as it is.
     */
    public val nodes: List<Node> = if (owned) nodes else nodes.toList()

    /**
     * [hashCode] once worked out, and whether it came out as zero, which [hash] cannot tell from not yet.
     *
     * Markup never changes, so its hash is worked out once, for it and everything nested in it, and kept. Two
     * threads that both work it out store the same value, which is why no lock is needed.
     */
    private var hash = 0
    private var hashIsZero = false

    /** Whether [hashCode] has been worked out, so that comparing hashes costs nothing. */
    internal val hasHash: Boolean
        get() = hash != 0 || hashIsZero

    /** Takes a copy of [nodes]. */
    public constructor(nodes: List<Node>) : this(nodes, owned = false)

    /** This wikitext, byte for byte as it was parsed. */
    public fun serialize(): String = Writer.write(this)

    /**
     * Where each node, parameter, attribute and piece of markup in this wikitext sits in [serialize]'s
     * output.
     *
     * Worked out in one pass over the tree when asked, and kept nowhere else: nodes carry no offsets, so two
     * equal nodes stay equal wherever they were parsed from. Lookups are by identity, so ask with the node a
     * query returned.
     */
    public fun ranges(): SourceRanges {
        val found = identityMap<Any, IntRange>()
        Writer.append(StringBuilder(), this) { item, start, end -> found[item] = start until end }
        return SourceRanges(found)
    }

    /**
     * The visible text, with markup removed.
     *
     * Templates and comments contribute nothing, since neither is text a reader sees; a wikilink contributes
     * its display text, and an external link its label, or the URL itself when it is a bare one in running
     * text. Use this for comparing content, never for writing back.
     */
    public val text: String
        get() {
            (nodes.singleOrNull() as? TextNode)?.let {
                return it.text
            }
            val out = StringBuilder()
            val pending = NodeStack().apply { push(nodes) }
            while (true) {
                when (val node = pending.next() ?: break) {
                    is TextNode -> out.append(node.text)
                    is HtmlEntity -> out.append(node.serialize())
                    is WikiLink -> pending.push((node.text ?: node.target).nodes)
                    is ExternalLink ->
                        (if (node.brackets) node.title else node.url)?.let { pending.push(it.nodes) }
                    is Heading -> pending.push(node.title.nodes)
                    is Tag -> node.contents?.let { pending.push(it.nodes) }
                    is Template,
                    is Argument,
                    is Comment -> Unit
                }
            }
            return out.toString()
        }

    /**
     * This wikitext as written, less its comments and `<noinclude>`-style markers: what MediaWiki compares a
     * name or a key by, since its preprocessor drops both before anything reads the name.
     */
    internal fun withoutComments(): String {
        (nodes.singleOrNull() as? TextNode)?.let {
            return it.text
        }
        val out = StringBuilder()
        val pending = NodeStack().apply { push(nodes) }
        while (true) {
            val node = pending.next() ?: break
            when {
                node is Comment -> Unit
                node is Tag && node.wikiMarkup == null && node.name in TagNames.TRANSCLUSION ->
                    node.contents?.let { pending.push(it.nodes) }
                else -> Writer.append(out, node, null)
            }
        }
        return out.toString()
    }

    /** Every node in the tree, including those nested inside templates, links and tags. */
    public fun allNodes(): Sequence<Node> = Sequence { NodeWalk(nodes) }

    /**
     * The templates in this wikitext, including nested ones.
     *
     * @param name when given, only templates with this name, compared ignoring the case of the first letter
     *   and treating spaces and underscores alike. For the comparison MediaWiki makes (namespaces, `subst:`,
     *   wikis whose templates keep case), pass names and [TitleRules] to the other overload.
     */
    public fun templates(name: String? = null): List<Template> =
        allNodes()
            .filterIsInstance<Template>()
            .filter { name == null || it.title.matchesTemplateName(name) }
            .toList()

    /**
     * The templates in this wikitext, nested ones included, that transclude one of [names] under [rules].
     *
     * Each name is read as a page (`l`, `Template:l` and `{{subst:l}}` all name `Template:L` unless the rules
     * keep case), so a bot can pass a template's name together with the names of every redirect to it and
     * find each way it is written. A name such as `PAGENAME` finds `{{PAGENAME|x}}`, which transcludes that
     * page, and not the variable `{{PAGENAME}}`.
     */
    public fun templates(names: Collection<String>, rules: TitleRules = TitleRules.DEFAULT): List<Template> {
        val keys = names.mapNotNullTo(HashSet()) { rules.key(it, hasArguments = true) }
        return allNodes().filterIsInstance<Template>().filter { it.key(rules) in keys }.toList()
    }

    /** The wikilinks in this wikitext, including nested ones. */
    public fun wikilinks(): List<WikiLink> = allNodes().filterIsInstance<WikiLink>().toList()

    /**
     * The headings that open sections: those at the top of this wikitext, and those inside an HTML tag there,
     * such as a `<div>`, which MediaWiki also counts as sections.
     *
     * A heading inside a template parameter, a link or a `<ref>` is still a heading, and is among [allNodes],
     * but it does not divide the page into sections.
     */
    public fun headings(): List<Heading> = nodes.sectionHeadings()

    /** The tags in this wikitext, including nested ones. */
    public fun tags(name: String? = null): List<Tag> =
        allNodes()
            .filterIsInstance<Tag>()
            .filter { name == null || it.name.equals(name, ignoreCase = true) }
            .toList()

    /** The comments in this wikitext. */
    public fun comments(): List<Comment> = allNodes().filterIsInstance<Comment>().toList()

    /**
     * This wikitext with every occurrence of [target] replaced by [replacement].
     *
     * Nested occurrences are replaced too, so a template inside a link can be edited without the caller
     * having to find and rebuild the link.
     */
    public fun replace(target: Node, replacement: Node): Markup = rewrite(target, replacement)

    /** This wikitext with [target] removed. */
    public fun remove(target: Node): Markup = rewrite(target, null)

    /**
     * This wikitext with every template named [name] passed through [transform].
     *
     * The common shape of a bot edit: find the templates that matter, change them, leave everything else
     * exactly as it was.
     */
    public fun mapTemplates(name: String? = null, transform: (Template) -> Template): Markup =
        mapEach(templates(name), transform)

    /**
     * This wikitext with every template transcluding one of [names] under [rules] passed through [transform].
     */
    public fun mapTemplates(
        names: Collection<String>,
        rules: TitleRules = TitleRules.DEFAULT,
        transform: (Template) -> Template,
    ): Markup = mapEach(templates(names, rules), transform)

    private fun mapEach(templates: List<Template>, transform: (Template) -> Template): Markup {
        var result = this
        for (template in templates) {
            val updated = transform(template)
            if (updated != template) result = result.replace(template, updated)
        }
        return result
    }

    /** This wikitext followed by [other]. */
    public operator fun plus(other: Markup): Markup = Markup(nodes + other.nodes)

    override fun toString(): String = serialize()

    /**
     * Whether [other] is the same wikitext: the same nodes, and inside them the same nodes, all the way down.
     *
     * Compared level by level with a stack rather than through the nodes' own `equals`, which would recurse
     * once per level, and settled at once by the hashes when both are known and differ.
     */
    override fun equals(other: Any?): Boolean =
        this === other || (other is Markup && TreeComparison().same(this, other))

    /**
     * The same as the hash of [nodes], worked out for everything nested in this first, innermost first, so
     * that no node's own `hashCode` has more than one level to look at, and kept.
     */
    override fun hashCode(): Int {
        when {
            hasHash -> Unit
            nodes.all { it.isLeaf } -> storeHash()
            else -> withoutHashes().asReversed().forEach { it.storeHash() }
        }
        return hash
    }

    /** This and the markup nested in it whose hash is not yet known, every one after the markup it is in. */
    private fun withoutHashes(): List<Markup> {
        val found = ArrayList<Markup>()
        val pending = arrayListOf(this)
        while (pending.isNotEmpty()) {
            val markup = pending.removeAt(pending.lastIndex)
            if (markup.hasHash) continue
            found += markup
            for (node in markup.nodes) pushWithoutHashes(node, pending)
        }
        return found
    }

    private fun pushWithoutHashes(node: Node, pending: MutableList<Markup>) {
        for (slot in 0 until node.slots) {
            val inner = node.slot(slot)
            if (inner != null && !inner.hasHash) pending += inner
        }
    }

    private fun storeHash() {
        val value = nodes.hashCode()
        hash = value
        hashIsZero = value == 0
    }

    /** Building wikitext from nodes, and the empty document. */
    public companion object {
        /** Markup holding one piece of literal text. */
        public fun of(text: String): Markup = Markup(listOf(TextNode(text)))

        /** Empty wikicode. */
        public val EMPTY: Markup = Markup(emptyList())

        /**
         * Markup over [nodes] without copying it.
         *
         * The caller must not keep the list or change it after this returns.
         */
        internal fun owning(nodes: List<Node>): Markup = Markup(nodes, owned = true)
    }
}

/**
 * Where the pieces of a parsed page sit in its text, from [Markup.ranges].
 *
 * Every range is half-open, `start until end`, and is looked up by identity: ask about the node a query on
 * the same [Markup] returned, not an equal one built elsewhere.
 */
public class SourceRanges internal constructor(private val ranges: IdentityMap<Any, IntRange>) {

    /** Where [node] was written, or `null` if it is not part of the markup these ranges came from. */
    public operator fun get(node: Node): IntRange? = ranges[node]

    /** Where [parameter] was written, its name and `=` included for a named one. */
    public operator fun get(parameter: Parameter): IntRange? = ranges[parameter]

    /** Where [attribute] was written, the whitespace before it included. */
    public operator fun get(attribute: Attribute): IntRange? = ranges[attribute]

    /** Where [markup], such as a parameter's value, was written. */
    public operator fun get(markup: Markup): IntRange? = ranges[markup]
}

/** A parse that would not write back the text it was given, from [Wikitext.parseExact]. */
public class WikitextRoundTripException(
    /** The first offset where the text written back differs from the text parsed. */
    public val offset: Int,
    message: String,
) : IllegalStateException(message)

/** Parses and serializes wikitext. */
public object Wikitext {

    /**
     * Parses [wikitext] into an editable tree, the way Wikimedia's wikis read it.
     *
     * Total: no input is rejected. Markup that does not parse — an unclosed template, a stray bracket — stays
     * literal text, which is what MediaWiki renders. However deeply the input nests, neither parsing it nor
     * anything done with the tree afterwards needs more of the thread's stack than a page nested a few levels
     * deep: below that, each keeps its place on a stack of its own, on the heap.
     */
    public fun parse(wikitext: String): Markup = parse(wikitext, ParseOptions.DEFAULT)

    /** Parses [wikitext] the way a wiki configured with [options] reads it. */
    public fun parse(wikitext: String, options: ParseOptions): Markup =
        Markup.owning(Builder(Tokenizer(options).tokenize(wikitext)).build())

    /**
     * Parses [wikitext], and checks that writing the tree back gives the same text.
     *
     * Every parse is meant to round-trip, and the parser is tested for it against a corpus of real pages and
     * a fuzzer. This checks one page, for a bot about to save an edit built on the tree.
     *
     * @throws WikitextRoundTripException naming the first offset that would change.
     */
    public fun parseExact(wikitext: String, options: ParseOptions = ParseOptions.DEFAULT): Markup {
        val markup = parse(wikitext, options)
        val written = markup.serialize()
        if (written != wikitext) {
            val offset =
                written
                    .zip(wikitext)
                    .indexOfFirst { (a, b) -> a != b }
                    .let {
                        if (it < 0) minOf(written.length, wikitext.length) else it
                    }
            throw WikitextRoundTripException(
                offset,
                "the parsed page would not write back as it was, from $offset",
            )
        }
        return markup
    }

    /** Whether [wikitext] parses into a tree that writes back unchanged. */
    public fun roundTrips(wikitext: String, options: ParseOptions = ParseOptions.DEFAULT): Boolean =
        parse(wikitext, options).serialize() == wikitext
}

/**
 * This wikitext with every node equal to [target] replaced by [replacement], or removed where [replacement]
 * is `null`, wherever it appears, in one pass.
 *
 * Only a node of the same kind as [target] is compared with it, and by its hash first, which is kept, so a
 * page costs one look at each node. What contains no occurrence keeps its identity and its exact text, and
 * when nothing does, this wikitext itself comes back.
 */
private fun Markup.rewrite(target: Node, replacement: Node?): Markup {
    val hash = target.hashCode()
    return object : Rebuild(settles = true) {
            override fun before(node: Node): Any? =
                if (sameKind(node, target) && node.hashCode() == hash && node == target) replacement
                else DESCEND

            override fun rebuilt(original: Node, node: Node): Node = node
        }
        .rebuild(this)
}

/**
 * Whether two template names refer to the same template.
 *
 * MediaWiki ignores the case of the first letter and treats underscores as spaces, so `{{col}}`, `{{Col}}`
 * and `{{c ol}}` are not all the same but the first two are.
 */
private fun String.matchesTemplateName(other: String): Boolean {
    val left = trim().replace('_', ' ')
    val right = other.trim().replace('_', ' ')
    return left.equals(right, ignoreCase = false) ||
        left.replaceFirstChar { it.uppercaseChar() } == right.replaceFirstChar { it.uppercaseChar() }
}
