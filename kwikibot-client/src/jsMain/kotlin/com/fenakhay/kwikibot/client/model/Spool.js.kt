package com.fenakhay.kwikibot.client.model

/** A spool in memory, since a browser has no filesystem and a spool here must work in one. */
public actual fun spool(prefix: String): Spool = MemorySpool()
