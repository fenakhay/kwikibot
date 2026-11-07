package com.fenakhay.kwikibot.client.model.dump

internal actual fun decodeUtf8(bytes: ByteArray, start: Int, end: Int): String =
    bytes.decodeToString(start, end)

internal actual fun platformBlockDecoders(workers: Int): BlockDecoders? = null
