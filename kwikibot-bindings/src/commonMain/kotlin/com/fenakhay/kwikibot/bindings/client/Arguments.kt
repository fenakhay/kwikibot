package com.fenakhay.kwikibot.bindings.client

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.protocol.decode.OptionSet

/** [numbers] as namespaces, or [default] when not given. */
public fun namespacesOf(numbers: Collection<Int>?, default: Set<Namespace> = emptySet()): Set<Namespace> =
    numbers?.map(::Namespace)?.toSet() ?: default

/** [options], each an option's name, with a leading `!` when it has to be off; none when not given. */
public fun optionSetOf(options: Collection<String>?): OptionSet =
    options?.let { OptionSet.parse(it.joinToString("|")) } ?: OptionSet()

/** The namespace numbered [number], or [default] when not given. */
public fun namespaceOf(number: Int?, default: Namespace = Namespace.MAIN): Namespace =
    number?.let(::Namespace) ?: default

/** The title of [ref] as this wiki writes it, prefix included. */
public fun Wiki.titleOf(ref: PageRef): String = namespaces.format(ref.title)

/** [titles] as pages of this wiki, each a page of [namespace] when it has no prefix of its own. */
public fun Wiki.refs(titles: Collection<String>, namespace: Namespace = Namespace.MAIN): List<PageRef> =
    titles.map {
        ref(it, namespace)
    }
