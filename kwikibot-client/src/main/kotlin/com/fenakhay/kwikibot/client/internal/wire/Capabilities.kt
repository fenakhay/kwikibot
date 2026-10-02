package com.fenakhay.kwikibot.client.internal.wire

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.protocol.ParamInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = KotlinLogging.logger {}

/**
 * Which spelling of each [Wire.Choice] one wiki takes, decided by asking it.
 *
 * MediaWiki describes its own API, including which values it has deprecated, so a spelling is chosen by what
 * the wiki says rather than by its version number. An old third-party release and Wikimedia's current build
 * each get a spelling they accept, and no version comparison has to be kept up to date.
 *
 * The first time any choice is resolved, the modules of every registered choice are described in one request.
 * Each answer is then kept for the life of the wiki handle, since it changes only when the wiki is upgraded.
 *
 * @param paramInfo the wiki's description of its API.
 * @param wiki the wiki's name, for the warning a deprecated spelling logs.
 * @param choices the choices whose modules are described together.
 */
internal class Capabilities(
    private val paramInfo: ParamInfo,
    private val wiki: String,
    private val choices: List<Wire.Choice> = Registry.CHOICES,
) {

    private val mutex = Mutex()
    private val resolved = mutableMapOf<Wire.Choice, List<String>>()
    private var described = false

    /**
     * The values to send for [choice] on this wiki.
     *
     * The first spelling whose values the wiki lists and does not deprecate. Failing that, the first it lists
     * at all, logged once as a deprecation. When the wiki does not list the parameter's values, or cannot be
     * asked, the preferred spelling: a wiki that says nothing about itself is assumed to be current.
     *
     * @throws WikiError.Configuration.Unsupported if the wiki lists the values it takes and none of the
     *   spellings is among them.
     */
    suspend fun resolve(choice: Wire.Choice): List<String> = mutex.withLock {
        resolved[choice] ?: negotiate(choice).also { resolved[choice] = it }
    }

    private suspend fun negotiate(choice: Wire.Choice): List<String> {
        val accepted = accepted(choice.parameter) ?: return choice.preferred

        val listed = choice.alternatives.filter { accepted.values.containsAll(it) }
        listed
            .firstOrNull { spelling -> spelling.none { it in accepted.deprecated } }
            ?.let {
                return it
            }

        val fallback =
            listed.firstOrNull()
                ?: throw WikiError.Configuration.Unsupported(
                    choice.parameter.toString(),
                    choice.alternatives.map { it.joinToString("|") },
                )

        log.warn {
            "$wiki accepts only deprecated values for ${choice.parameter}: sending " +
                "${fallback.joinToString("|")}. Expect it to stop working when the wiki is upgraded."
        }
        return fallback
    }

    /** What the wiki says [parameter] accepts, or `null` when it does not say. */
    private suspend fun accepted(parameter: Wire.Parameter): Accepted? {
        val module =
            try {
                if (!described) {
                    paramInfo.prefetch((choices.map { it.parameter.module } + parameter.module).distinct())
                    described = true
                }
                paramInfo.module(parameter.module)
            } catch (e: WikiError) {
                log.debug(e) { "$wiki did not describe its API; assuming the current spellings" }
                null
            } ?: return null

        val description = module[parameter.name.removePrefix(module.prefix)] ?: return null
        if (description.values.isEmpty()) return null

        return Accepted(description.values.toSet(), description.deprecatedValues.toSet())
    }

    private class Accepted(val values: Set<String>, val deprecated: Set<String>)
}
