package com.fenakhay.kwikibot.client.internal.wire

import com.fenakhay.kwikibot.client.MediaWikiVersion

/**
 * The few things this library decides by MediaWiki version, in one place.
 *
 * A wiki describes its own API through `paraminfo`, including which values it has deprecated, so a respelled
 * value is a [Wire.Choice] negotiated per wiki by [Capabilities] instead of a version comparison. That is how
 * `parse prop=tocdata` (MediaWiki 1.43.6, T328605) and AbuseFilter's `abfprop=flags` (MediaWiki 1.47,
 * T435834) are handled, and neither has a constant here.
 *
 * A version belongs here only for behaviour `paraminfo` cannot describe: a response that changed shape under
 * an unchanged parameter, or a bug fixed in a release. Each constant names the Phabricator task and says why
 * `paraminfo` is not enough, so it can be deleted with the code it guards once the oldest supported release
 * has moved past it.
 */
internal object MediaWikiBehaviour {

    /**
     * The oldest release this library is written and tested against: the 1.39 long-term support release.
     *
     * Not a capability, so `paraminfo` has nothing to say about it. Opening an older wiki logs a warning
     * rather than refusing, since most of the API is older still and a bot may need only that part.
     */
    val OLDEST_SUPPORTED: MediaWikiVersion = MediaWikiVersion.parse("1.39.0")
}
