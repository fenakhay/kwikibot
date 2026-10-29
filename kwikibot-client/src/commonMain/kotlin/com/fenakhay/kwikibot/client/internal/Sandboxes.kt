package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.service.TemplateSandbox
import com.fenakhay.kwikibot.model.title.NamespaceMap

/** [sandbox] as TemplateSandbox's parameters, which `action=parse` and `action=expandtemplates` both take. */
internal fun NamespaceMap.sandboxParams(sandbox: TemplateSandbox?): Array<Pair<String, String?>> =
    if (sandbox == null) {
        emptyArray()
    } else {
        arrayOf(
            "templatesandboxprefix" to sandbox.prefixes.takeIf { it.isNotEmpty() }?.joinToString("|"),
            "templatesandboxtitle" to sandbox.title?.let { format(it.title) },
            "templatesandboxtext" to sandbox.text,
            "templatesandboxcontentmodel" to sandbox.contentModel,
        )
    }
