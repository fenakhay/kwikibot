package com.fenakhay.kwikibot.bindings.titles

import com.fenakhay.kwikibot.model.title.InterwikiMap
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceInfo
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.model.title.TitleCase

/**
 * The namespace numbered [id], named [canonicalName] and [localName]: with no alias, its first letter
 * capitalised, and without subpages, unless told.
 */
public fun namespaceInfoOf(
    id: Int,
    canonicalName: String?,
    localName: String?,
    aliases: Collection<String>? = null,
    case: TitleCase? = null,
    subpages: Boolean? = null,
): NamespaceInfo =
    NamespaceInfo(
        Namespace(id),
        canonicalName,
        localName,
        aliases?.toList().orEmpty(),
        case ?: TitleCase.FIRST_LETTER,
        subpages ?: false,
    )

/**
 * [text] read as a title the way MediaWiki does: against MediaWiki's own namespaces, no interwiki prefix, and
 * as a page of the main namespace when it has no prefix, unless told.
 */
public fun parseTitleWithDefaults(
    text: String,
    namespaces: NamespaceMap? = null,
    interwiki: InterwikiMap? = null,
    defaultNamespace: Int? = null,
): Title =
    Title.parse(
        text,
        namespaces ?: NamespaceMap.CANONICAL,
        interwiki ?: InterwikiMap.EMPTY,
        defaultNamespace?.let(::Namespace) ?: Namespace.MAIN,
    )
