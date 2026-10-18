package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title

/**
 * Every name [template] is known by on this wiki, as a call writes it: its own, and that of each redirect to
 * it in the Template namespace.
 *
 * A renamed template keeps working under its old name through a redirect, and pages go on using that name. A
 * bot looking for every use of a template has to look for all of them; the names this returns are what
 * `Markup.templates` takes, and what goes between the braces of a call:
 * ```
 * val uses = Wikitext.parse(page.text).templates(wiki.templateNames("l"), wiki.titleRules())
 * ```
 *
 * A name comes without its `Template:` prefix, `l` and not `Template:l`, except one that would then name a
 * page in another namespace: `Template:Help:x` is called as `{{Template:Help:x}}`, since `{{Help:x}}`
 * transcludes `Help:x`.
 *
 * @param template the template's name, with or without its `Template:` prefix.
 */
public suspend fun Wiki.templateNames(template: String): Set<String> {
    val ref = ref(template, Namespace.TEMPLATE)
    val redirects = pages.redirectsTo(listOf(ref), setOf(Namespace.TEMPLATE))[ref].orEmpty()
    return (listOf(ref) + redirects).mapTo(LinkedHashSet()) { namespaces.callName(it.title) }
}

/** [title], a page in the Template namespace, as a call writes it. */
private fun NamespaceMap.callName(title: Title.Local): String {
    val prefix = title.text.substringBefore(':', missingDelimiterValue = "")
    return if (prefix.isNotEmpty() && byPrefix(prefix) != null) format(title) else title.text
}
