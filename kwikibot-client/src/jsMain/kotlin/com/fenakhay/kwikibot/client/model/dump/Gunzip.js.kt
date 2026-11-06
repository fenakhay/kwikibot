package com.fenakhay.kwikibot.client.model.dump

import kotlinx.io.RawSource

internal actual fun gunzipping(source: RawSource): RawSource = GzipSource(source)
