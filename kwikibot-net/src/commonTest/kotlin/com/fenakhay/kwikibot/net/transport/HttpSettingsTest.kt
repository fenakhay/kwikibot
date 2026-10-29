package com.fenakhay.kwikibot.net.transport

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class HttpSettingsTest {

    @Test
    fun `settings that could not work are refused`() {
        assertFailsWith<IllegalArgumentException> { HttpSettings(maxConcurrentRequests = 0) }
        assertFailsWith<IllegalArgumentException> { HttpSettings(maxIdleConnections = -1) }
        assertFailsWith<IllegalArgumentException> { HttpSettings(timeout = Duration.ZERO) }
    }
}
