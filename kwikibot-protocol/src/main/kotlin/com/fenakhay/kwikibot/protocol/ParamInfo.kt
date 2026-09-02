package com.fenakhay.kwikibot.protocol

import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One parameter of an API module, as the wiki describes it. */
public data class ParamDescription(
    /** The parameter's name without the module's prefix, as `paraminfo` reports it. */
    val name: String,
    /** Its type, `string` or `timestamp`, absent when it takes a fixed set instead. */
    val type: String? = null,
    /** The values it accepts, when it accepts a fixed set. */
    val values: List<String> = emptyList(),
    /** The most this account may ask for, for a parameter that takes a count. */
    val limit: Int? = null,
    /** The most an account with `apihighlimits` may ask for. */
    val highLimit: Int? = null,
    /** Whether the module refuses the request without it. */
    val required: Boolean = false,
    /** Whether it accepts several values joined by a pipe. */
    val multiValued: Boolean = false,
    /** What the wiki uses when the parameter is not sent. */
    val default: String? = null,
    /** Whether the wiki has announced this parameter is going away. */
    val deprecated: Boolean = false,
    /** Values still accepted but announced as going away, for a parameter taking a fixed set. */
    val deprecatedValues: List<String> = emptyList(),
    /** Whether the value is a credential, and so must never reach a URL or a log. */
    val sensitive: Boolean = false,
)

/** One API module, as the wiki describes it. */
public data class ModuleDescription(
    /** The module's own name, `usercontribs`. */
    val name: String,
    /** Its full path, `query+usercontribs`, which is how it is asked for. */
    val path: String,
    /** Its parameters, keyed by name. */
    val parameters: Map<String, ParamDescription>,
    /** Whether the module changes the wiki, which decides how it must be paced. */
    val isWrite: Boolean = false,
    /** Whether the module must be POSTed. */
    val mustBePosted: Boolean = false,
    /**
     * What provides the module: `MediaWiki` for core, otherwise the extension's name.
     *
     * The answer to "does this wiki have Thanks", without a separate `siteinfo` round trip.
     */
    val source: String? = null,
    /** The extension's display name, which differs from [source] for some extensions. */
    val sourceName: String? = null,
    /** For a submodule of `query`, which of `list`, `prop` or `meta` it belongs to. */
    val group: String? = null,
    /** The prefix the module's own parameters carry, `uc` for `query+usercontribs`. */
    val prefix: String = "",
    /** Whether the wiki has announced this module is going away. */
    val deprecated: Boolean = false,
    /** Whether the module is for MediaWiki's own use and carries no compatibility promise. */
    val internal: Boolean = false,
) {
    /** Whether this module takes a parameter of this name on this wiki. */
    public operator fun contains(parameter: String): Boolean = parameter in parameters

    /** One parameter, or `null` if this wiki's version of the module has none by that name. */
    public operator fun get(parameter: String): ParamDescription? = parameters[parameter]
}

/**
 * What a wiki says its own API accepts.
 *
 * Two things make this worth asking rather than assuming. A limit is not a constant: the same query returns
 * 50 results for one account and 500 for another, and hard-coding either wastes requests or gets them
 * refused. And a parameter is not permanent: MediaWiki adds and removes them between versions, so a bot that
 * must run against an old third-party wiki has to ask before it sends.
 *
 * Answers are cached for the life of the object, since they change only when the wiki is upgraded, and
 * concurrent callers asking for the same module produce one request.
 */
public class ParamInfo(private val transport: MediaWikiTransport) {

    private val mutex = Mutex()
    private val cached = mutableMapOf<String, ModuleDescription?>()

    /**
     * The description of [module], or `null` if this wiki has no such module.
     *
     * Modules are named as `paraminfo` names them: `query+categorymembers`, `edit`, `upload`.
     */
    public suspend fun module(module: String): ModuleDescription? {
        if (module in cached) return cached[module]

        return mutex.withLock {
            if (module in cached) return@withLock cached[module]
            fetch(module).also { cached[module] = it }
        }
    }

    /**
     * The most results [module] will return in one request for this account.
     *
     * `null` when the module has no such limit, or the wiki did not say. A caller that gets `null` should
     * send `max` and let the wiki decide, which is what it is for.
     */
    public suspend fun limit(module: String, parameter: String, highLimits: Boolean): Int? {
        val described = module(module)?.get(parameter) ?: return null
        return if (highLimits) described.highLimit ?: described.limit else described.limit
    }

    /** Whether [module] takes [parameter] on this wiki. */
    public suspend fun supports(module: String, parameter: String): Boolean =
        module(module)?.contains(parameter) == true

    /**
     * Every module matching [patterns], which may use the `*` the API accepts.
     *
     * `modules("*", "query+*")` describes a wiki's whole API surface in one request. Results join the cache,
     * so a later [module] call for any of them costs nothing.
     */
    public suspend fun modules(vararg patterns: String): List<ModuleDescription> {
        val described =
            request(patterns.joinToString("|"))
                .filterNot { it.containsKey("missing") }
                .map { it.toModule(it.string("path").orEmpty()) }

        mutex.withLock {
            described.forEach { cached[it.path] = it }
        }
        return described
    }

    private suspend fun fetch(module: String): ModuleDescription? {
        val described = request(module).firstOrNull() ?: return null

        // A module the wiki does not have comes back flagged rather than omitted.
        if (described.containsKey("missing")) return null

        return described.toModule(module)
    }

    /**
     * The raw module descriptions for a `modules` value.
     *
     * `helpformat=none` drops the prose, which is most of the payload and none of what this reads.
     */
    private suspend fun request(modules: String): List<JsonObject> {
        val response =
            transport
                .call(ApiRequest.of("paraminfo", "modules" to modules, "helpformat" to "none"))
                .throwOnError()

        return response["paraminfo"]?.jsonObject?.get("modules")?.jsonArray?.map { it.jsonObject }.orEmpty()
    }

    private fun JsonObject.toModule(fallback: String) =
        ModuleDescription(
            name = string("name") ?: fallback,
            path = string("path") ?: fallback,
            parameters =
                this["parameters"]
                    ?.jsonArray
                    ?.map { it.jsonObject }
                    ?.associate { parameter ->
                        val name = parameter.string("name").orEmpty()
                        name to parameter.toDescription(name)
                    }
                    .orEmpty(),
            isWrite = flag("writerights") || flag("mustbeposted"),
            mustBePosted = flag("mustbeposted"),
            source = string("source"),
            sourceName = string("sourcename"),
            group = string("group"),
            prefix = string("prefix").orEmpty(),
            deprecated = flag("deprecated"),
            internal = flag("internal"),
        )

    private fun JsonObject.toDescription(name: String) =
        ParamDescription(
            name = name,
            // "type" is a string for a simple type and a list when the parameter takes a fixed set.
            type =
                this["type"]?.let { element ->
                    runCatching { element.jsonPrimitive.content }.getOrNull()
                },
            values =
                this["type"]
                    ?.let { element ->
                        runCatching { element.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
                    }
                    .orEmpty(),
            limit = this["limit"]?.jsonPrimitive?.intOrNull,
            highLimit = this["highlimit"]?.jsonPrimitive?.intOrNull,
            required = flag("required"),
            multiValued = flag("multi"),
            default =
                this["default"]?.let { element ->
                    runCatching { element.jsonPrimitive.content }.getOrNull()
                },
            deprecated = flag("deprecated"),
            deprecatedValues =
                this["deprecatedvalues"]
                    ?.let { element ->
                        runCatching { element.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
                    }
                    .orEmpty(),
            sensitive = flag("sensitive"),
        )

    private fun JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun JsonObject.flag(key: String): Boolean =
        this[key]?.let { runCatching { it.jsonPrimitive.content != "false" }.getOrDefault(true) } ?: false
}
