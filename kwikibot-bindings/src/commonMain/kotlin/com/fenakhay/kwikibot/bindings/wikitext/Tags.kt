package com.fenakhay.kwikibot.bindings.wikitext

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.node.Attribute
import com.fenakhay.kwikibot.wikitext.node.Tag

/**
 * A tag called [name]: with no attribute and no padding, neither self-closing, closed implicitly nor
 * verbatim, unless told.
 */
@Suppress("LongParameterList")
public fun tagOf(
    name: String,
    contents: Markup? = null,
    attributes: List<Attribute>? = null,
    selfClosing: Boolean? = null,
    wikiMarkup: String? = null,
    padding: String? = null,
    implicitClose: Boolean? = null,
    closing: String? = null,
    verbatim: Boolean? = null,
): Tag =
    Tag(
        name,
        contents,
        attributes.orEmpty(),
        selfClosing ?: false,
        wikiMarkup,
        padding.orEmpty(),
        implicitClose ?: false,
        closing,
        verbatim ?: false,
    )
