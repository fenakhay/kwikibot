package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.title.Namespace

/**
 * Every name [template] is known by on this wiki: its own, and that of each redirect to it in the Template
 * namespace.
 *
 * A renamed template keeps working under its old name through a redirect, and pages go on using that name. A
 * bot looking for every use of a template has to look for all of them; the names this returns are what
 * `Markup.templates` takes:
 * ```
 * val uses = Wikitext.parse(page.text).templates(wiki.templateNames("l"), wiki.titleRules())
 * ```
 *
 * @param template the template's name, with or without its `Template:` prefix.
 */
public suspend fun Wiki.templateNames(template: String): Set<String> {
    val ref = ref(template, Namespace.TEMPLATE)
    val redirects = pages.redirectsTo(listOf(ref), setOf(Namespace.TEMPLATE))[ref].orEmpty()
    return (listOf(ref) + redirects).mapTo(LinkedHashSet()) { namespaces.format(it.title) }
}
