package com.fenakhay.kwikibot.client.model

/** A spool in a temporary file. */
public actual fun spool(prefix: String): Spool = FileSpool(prefix)
