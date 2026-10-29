package com.fenakhay.kwikibot.client.internal

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where blocking file work runs: the pool kept for blocking I/O where the platform has one, and the default
 * dispatcher on JavaScript, which has a single thread and no blocking pool.
 */
internal expect val ioDispatcher: CoroutineDispatcher
