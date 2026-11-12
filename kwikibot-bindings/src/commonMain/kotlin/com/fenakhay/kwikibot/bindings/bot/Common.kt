package com.fenakhay.kwikibot.bindings.bot

import com.fenakhay.kwikibot.bot.CommonOptions
import kotlin.time.Duration
import kotlinx.io.files.Path

/**
 * The flags every bot takes, as a binding builds them: a dry run that does not resume and is not a trial,
 * unless told; everything else unset unless given.
 */
@Suppress("LongParameterList")
public fun commonOptionsOf(
    save: Boolean? = null,
    limit: Int? = null,
    diffLog: Path? = null,
    skipLog: Path? = null,
    stopPage: String? = null,
    readDelay: Duration? = null,
    writeDelay: Duration? = null,
    httpConnections: Int? = null,
    contact: String? = null,
    state: Path? = null,
    resume: Boolean? = null,
    trial: Boolean? = null,
): CommonOptions =
    CommonOptions(
        save ?: false,
        limit,
        diffLog,
        skipLog,
        stopPage,
        readDelay,
        writeDelay,
        httpConnections,
        contact,
        state,
        resume ?: false,
        trial ?: false,
    )
