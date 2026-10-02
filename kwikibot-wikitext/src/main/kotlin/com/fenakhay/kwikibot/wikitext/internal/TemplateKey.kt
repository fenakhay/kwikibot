package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.TitleRules
import com.fenakhay.kwikibot.wikitext.node.Argument
import com.fenakhay.kwikibot.wikitext.node.Template

/**
 * The page [template] transcludes under [rules], or `null`.
 *
 * A name built from a template or an argument, `{{ {{lang}}-noun }}`, names whatever that expands to, which
 * only the wiki knows, so it has no key.
 */
internal fun templateKey(template: Template, rules: TitleRules): String? {
    if (template.name.nodes.any { it is Template || it is Argument }) return null
    return rules.key(template.title, hasArguments = template.parameters.isNotEmpty())
}
