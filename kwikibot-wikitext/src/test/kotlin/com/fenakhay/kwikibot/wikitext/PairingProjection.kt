package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.node.ExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink

/**
 * Whether the parser pairs HTML tags where MediaWiki's rendered page ends them.
 *
 * The cases mark their text with words that appear once each (`alpha`, `beta` …), so each word can be found
 * in MediaWiki's HTML and asked which elements surround it. For every tag the parser paired, MediaWiki must
 * surround each word inside it with an element of that name, one element for all of them, and the word just
 * after the closing tag must be outside it. For every closing tag the parser left as text, MediaWiki must not
 * have ended an element there: the words on either side are inside the same one, or neither is.
 *
 * A formatting element such as `<b>` is compared as the union of its pieces: MediaWiki splits it at a
 * paragraph break and starts a new one after, which reads as one element from opening tag to closing tag.
 *
 * Only names the case writes as tags take part, and not those its wiki markup also makes: `p` everywhere,
 * headings, and list or table elements where the case has lists or tables. An element MediaWiki made with
 * attributes of its own (a heading's wrapper, a reference's text) is not one the case wrote.
 */
internal object PairingProjection {

    /** What is wrong with the parser's pairing of [input], given the [html] MediaWiki rendered, or `null`. */
    fun compare(input: String, html: String): String? {
        val names = comparedNames(input)
        if (names.isEmpty()) return null
        val rendered = enclosing(html, names) ?: return null
        val events = events(Wikitext.parse(input).nodes, names)
        return paired(events, rendered) ?: stray(events, rendered)
    }

    // ------------------------------------------------------------------ the parser's side

    private sealed interface Event {
        data class Word(val word: String, val tags: List<Tag>) : Event

        class End(val tag: Tag) : Event

        data class Stray(val name: String) : Event
    }

    private fun events(nodes: List<Node>, names: Set<String>): List<Event> =
        mutableListOf<Event>().also { walk(nodes, names, emptyList(), it) }

    private fun walk(nodes: List<Node>, names: Set<String>, open: List<Tag>, out: MutableList<Event>) {
        for (node in nodes) {
            when (node) {
                is TextNode -> text(node.text, names, open, out)
                is Tag -> tag(node, names, open, out)
                is Template -> node.parameters.forEach { walk(it.value.nodes, names, open, out) }
                is Heading -> walk(node.title.nodes, names, open, out)
                is WikiLink -> node.text?.let { walk(it.nodes, names, open, out) }
                is ExternalLink -> node.title?.let { walk(it.nodes, names, open, out) }
                else -> Unit
            }
        }
    }

    private fun tag(tag: Tag, names: Set<String>, open: List<Tag>, out: MutableList<Event>) {
        val contents = tag.contents ?: return
        val compared = tag.wikiMarkup == null && tag.name.lowercase() in names
        walk(contents.nodes, names, if (compared) open + tag else open, out)
        if (compared) out += Event.End(tag)
    }

    private fun text(text: String, names: Set<String>, open: List<Tag>, out: MutableList<Event>) {
        for (match in TEXT_TOKEN.findAll(text)) {
            val closer = match.groups[1]?.value?.lowercase()
            when {
                closer == null -> out += Event.Word(match.value, open)
                closer in names -> out += Event.Stray(closer)
            }
        }
    }

    // ------------------------------------------------------------------ MediaWiki's side

    /** An element in MediaWiki's HTML: its name and which one it is. */
    private data class Element(val name: String, val id: Int)

    /** For each marker word in [html], the elements of [names] around it; `null` if a word appears twice. */
    private fun enclosing(html: String, names: Set<String>): Map<String, List<Element>>? {
        // Every open element by name, with the element itself when it is one the comparison counts.
        val stack = ArrayList<Pair<String, Element?>>()
        val words = HashMap<String, List<Element>>()
        var next = 0
        var at = 0
        for (tag in HTML_TAG.findAll(html)) {
            if (!words(html.substring(at, tag.range.first), stack, words)) return null
            at = tag.range.last + 1
            val (slash, rawName, attributes) = tag.destructured
            val name = rawName.lowercase()
            when {
                slash.isNotEmpty() -> pop(stack, name)
                tag.value.endsWith("/>") || name in VOID -> Unit
                else ->
                    stack += name to Element(name, next++).takeIf { attributes.isBlank() && name in names }
            }
        }
        return if (words(html.substring(at), stack, words)) words else null
    }

    private fun words(
        text: String,
        stack: List<Pair<String, Element?>>,
        into: MutableMap<String, List<Element>>,
    ): Boolean {
        for (word in WORD.findAll(text)) {
            if (word.value in into) return false
            into[word.value] = stack.mapNotNull { it.second }
        }
        return true
    }

    private fun pop(stack: MutableList<Pair<String, Element?>>, name: String) {
        val index = stack.indexOfLast { it.first == name }
        if (index >= 0) while (stack.size > index) stack.removeAt(stack.lastIndex)
    }

    // ------------------------------------------------------------------ the checks

    private fun paired(events: List<Event>, rendered: Map<String, List<Element>>): String? =
        events.withIndex().firstNotNullOfOrNull { (index, event) ->
            (event as? Event.End)?.let { pairedProblem(events, index, it.tag, rendered) }
        }

    private fun pairedProblem(
        events: List<Event>,
        index: Int,
        tag: Tag,
        rendered: Map<String, List<Element>>,
    ): String? {
        val name = tag.name.lowercase()
        val inside = events.filterIsInstance<Event.Word>().filter { word -> word.tags.any { it === tag } }
        val around = inside.mapNotNull { rendered[it.word] }.map { list -> list.filter { it.name == name } }
        val common = around.fold(around.firstOrNull().orEmpty()) { acc, list -> acc.intersect(list).toList() }
        val formatting = name in FORMATTING
        val after =
            wordAfter(events, index)?.let { rendered[it.word].orEmpty().filter { e -> e.name == name } }
        val goesOn = after != null && (if (formatting) after.isNotEmpty() else after.any { it in common })
        return when {
            around.any { it.isEmpty() } -> "<$name> pairs, but MediaWiki ends it before ${inside.words()}"
            !formatting && around.isNotEmpty() && common.isEmpty() ->
                "<$name> pairs, but MediaWiki splits ${inside.words()} into more than one"
            goesOn ->
                "<$name> pairs, but MediaWiki goes on past its closer to ${wordAfter(events, index)?.word}"
            else -> null
        }
    }

    private fun stray(events: List<Event>, rendered: Map<String, List<Element>>): String? =
        events.withIndex().firstNotNullOfOrNull { (index, event) ->
            (event as? Event.Stray)?.let { strayProblem(events, index, it.name, rendered) }
        }

    private fun strayProblem(
        events: List<Event>,
        index: Int,
        name: String,
        rendered: Map<String, List<Element>>,
    ): String? {
        val before = wordBefore(events, index) ?: return null
        val after = wordAfter(events, index) ?: return null
        val ended = rendered[before.word].orEmpty().filter { it.name == name }
        val following = rendered[after.word].orEmpty().filter { it.name == name }
        val continues = if (name in FORMATTING) following.isNotEmpty() else following.any { it in ended }
        return if (ended.isEmpty() || continues) {
            null
        } else {
            "</$name> is text, but MediaWiki ends the element there, between ${before.word} and ${after.word}"
        }
    }

    private fun wordBefore(events: List<Event>, index: Int): Event.Word? =
        events.take(index).lastOrNull { it is Event.Word } as? Event.Word

    private fun wordAfter(events: List<Event>, index: Int): Event.Word? =
        events.drop(index + 1).firstOrNull { it is Event.Word } as? Event.Word

    private fun List<Event.Word>.words(): String = joinToString(" ") { it.word }

    // ------------------------------------------------------------------ which names

    private fun comparedNames(input: String): Set<String> {
        val written = WRITTEN_TAG.findAll(input).map { it.groupValues[1].lowercase() }.toSet()
        var excluded = MARKUP_MADE
        if (LIST_LINE.containsMatchIn(input)) excluded = excluded + LIST_NAMES
        if (TABLE_LINE.containsMatchIn(input)) excluded = excluded + TABLE_NAMES
        return written - excluded - ParseOptions.DEFAULT.extensionTags
    }

    private val WORD = Regex("""\b(?:alpha|beta|gamma|delta|epsilon)\b""")
    private val TEXT_TOKEN =
        Regex("""</\s*([A-Za-z][A-Za-z0-9]*)\s*>|\b(?:alpha|beta|gamma|delta|epsilon)\b""")
    private val HTML_TAG = Regex("""<(/?)([A-Za-z][A-Za-z0-9]*)((?:[^>"'/]|"[^"]*"|'[^']*'|/(?!>))*)/?>""")
    private val WRITTEN_TAG = Regex("""</?([A-Za-z][A-Za-z0-9]*)""")
    private val LIST_LINE = Regex("""(?m)^[*#:;]""")
    private val TABLE_LINE = Regex("""(?m)^\s*\{\|""")

    private val VOID =
        setOf("br", "hr", "img", "wbr", "meta", "link", "input", "source", "track", "area", "col")
    private val FORMATTING =
        setOf("b", "big", "code", "em", "font", "i", "s", "small", "strike", "strong", "tt", "u")
    private val MARKUP_MADE = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6")
    private val LIST_NAMES = setOf("ul", "ol", "li", "dl", "dt", "dd")
    private val TABLE_NAMES = setOf("table", "tbody", "tr", "td", "th", "caption")
}
