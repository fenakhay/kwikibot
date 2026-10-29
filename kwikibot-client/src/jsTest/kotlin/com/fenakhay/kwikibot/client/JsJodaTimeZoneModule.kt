package com.fenakhay.kwikibot.client

/**
 * The IANA time zone database for kotlinx-datetime, which has only UTC and the system zone on JS without it.
 */
@JsModule("@js-joda/timezone") @JsNonModule external object JsJodaTimeZoneModule

/** Exported so that the module is loaded with the tests, as kotlinx-datetime's README sets it up. */
@OptIn(ExperimentalJsExport::class) @JsExport val jsJodaTz: JsJodaTimeZoneModule = JsJodaTimeZoneModule
