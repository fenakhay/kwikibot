package com.fenakhay.kwikibot.wikitext

/**
 * Replaces text matching [pattern], leaving markup alone.
 *
 * The replacement runs only on text nodes [scope] allows, so a rename that should not touch
 * template parameters or the inside of a `<nowiki>` block does not, without anyone writing a
 * regex to describe where those are.
 *
 * ```
 * code.replaceText(Regex("colour"), "color")            // prose only
 * code.replaceText(Regex("colour"), "color", TextScope.EVERYWHERE)
 * ```
 */
public fun Markup.replaceText(
    pattern: Regex,
    replacement: String,
    scope: TextScope = TextScope.PROSE,
): Markup = replaceText(pattern, scope) { replacement }

/** Replaces text matching [pattern], computing each replacement from the match. */
public fun Markup.replaceText(
    pattern: Regex,
    scope: TextScope = TextScope.PROSE,
    replacement: (MatchResult) -> String,
): Markup = Markup(nodes.map { it.replaceTextIn(pattern, scope, replacement) })

/** Whether any text this [scope] allows matches [pattern]. */
public fun Markup.containsText(pattern: Regex, scope: TextScope = TextScope.PROSE): Boolean =
    textIn(scope).any { pattern.containsMatchIn(it) }

/** The text this [scope] allows, node by node. */
public fun Markup.textIn(scope: TextScope): List<String> = buildList {
    for (node in nodes) {
        when (node) {
            is TextNode -> add(node.text)
            else -> addAll(node.scoped(scope).flatMap { it.textIn(scope) })
        }
    }
}

private fun Node.replaceTextIn(
    pattern: Regex,
    scope: TextScope,
    replacement: (MatchResult) -> String,
): Node = when (this) {
    is TextNode -> TextNode(pattern.replace(text, replacement))

    is Template -> replaceInTemplate(pattern, scope, replacement)

    is WikiLink -> replaceInLink(pattern, scope, replacement)

    is ExternalLink -> copy(title = title?.replaceText(pattern, scope, replacement))

    is Heading -> if (scope.headings) copy(title = title.replaceText(pattern, scope, replacement)) else this

    is Tag -> if (isRaw && !scope.rawTags) {
        this
    } else {
        copy(contents = contents?.replaceText(pattern, scope, replacement))
    }

    is Comment -> if (scope.comments) Comment(pattern.replace(contents, replacement)) else this

    is Argument -> copy(
        name = name.replaceText(pattern, scope, replacement),
        default = default?.replaceText(pattern, scope, replacement),
    )

    is HtmlEntity -> this
}

private fun WikiLink.replaceInLink(
    pattern: Regex,
    scope: TextScope,
    replacement: (MatchResult) -> String,
): WikiLink = copy(
    // Rewriting a target silently repoints the link at a different page, so it is opt-in.
    target = if (scope.linkTargets) target.replaceText(pattern, scope, replacement) else target,
    text = text?.replaceText(pattern, scope, replacement),
)

private fun Template.replaceInTemplate(
    pattern: Regex,
    scope: TextScope,
    replacement: (MatchResult) -> String,
): Template {
    if (!scope.templates) return this
    return copy(
        name = name.replaceText(pattern, scope, replacement),
        parameters = parameters.map {
            it.copy(
                name = it.name.replaceText(pattern, scope, replacement),
                value = it.value.replaceText(pattern, scope, replacement),
            )
        },
    )
}

/** The wikicode inside this node that [scope] allows reading. */
private fun Node.scoped(scope: TextScope): List<Markup> = when (this) {
    is Template -> if (scope.templates) children() else emptyList()
    is WikiLink -> listOfNotNull(text, target.takeIf { scope.linkTargets })
    is Heading -> if (scope.headings) listOf(title) else emptyList()
    is Tag -> when {
        isRaw && !scope.rawTags -> emptyList()
        scope.tagAttributes -> children()
        else -> listOfNotNull(contents)
    }

    is Comment -> if (scope.comments) listOf(Markup.of(contents)) else emptyList()
    else -> children()
}

/** Whether this tag's contents are taken verbatim by MediaWiki. */
private val Tag.isRaw: Boolean
    get() = name.lowercase() in setOf("nowiki", "pre", "syntaxhighlight", "source", "math", "score")
