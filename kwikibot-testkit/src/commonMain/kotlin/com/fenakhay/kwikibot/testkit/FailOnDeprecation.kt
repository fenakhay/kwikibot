package com.fenakhay.kwikibot.testkit

import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.protocol.ApiWarning
import com.fenakhay.kwikibot.protocol.ApiWarningListener

/**
 * Fails any request a wiki answers with a deprecation warning.
 *
 * For tests against a real wiki. kwikibot asks each wiki which spellings it takes and avoids the deprecated
 * ones, so a deprecation reaching here means something it sends needs a replacement, which a test should
 * catch before the release that removes it. Every other warning goes to [others].
 *
 * ```
 * WikiConfig(userAgent, onWarning = FailOnDeprecation())
 * ```
 *
 * @param others hears every warning that is not a deprecation.
 */
public class FailOnDeprecation(private val others: ApiWarningListener = ApiWarningListener.LOG) :
    ApiWarningListener {

    override fun onWarning(request: ApiRequest, warning: ApiWarning) {
        if (!warning.isDeprecation) return others.onWarning(request, warning)

        throw AssertionError(
            "action=${request.action} drew a deprecation warning from ${warning.module}: ${warning.text}"
        )
    }
}
