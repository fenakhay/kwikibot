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
    /**
     * For a parameter that takes a count, the largest it accepts: `max` in `paraminfo`.
     *
     * For a `type=limit` parameter this is the most results a request returns to an account without
     * `apihighlimits`.
     */
    val limit: Int? = null,
    /** The most an account with `apihighlimits` may ask for, `highmax` in `paraminfo`. */
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
    /**
     * For a multi-valued parameter, the most values this account may join in one request.
     *
     * Not a result count: `titles` taking fifty titles at once is this, and `cmlimit` returning five hundred
     * members is [limit]. `paraminfo` calls this one `limit`, which makes the two easy to confuse.
     */
    val valueLimit: Int? = null,
    /** For a multi-valued parameter, the most values an account with `apihighlimits` may join. */
    val highValueLimit: Int? = null,
    /** Values accepted but marked internal or unstable, which may change without notice. */
    val internalValues: List<String> = emptyList(),
) {
    /** The constructor 1.1 compiled against, kept so code built then still links. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        name: String,
        type: String? = null,
        values: List<String> = emptyList(),
        limit: Int? = null,
        highLimit: Int? = null,
        required: Boolean = false,
        multiValued: Boolean = false,
        default: String? = null,
        deprecated: Boolean = false,
        deprecatedValues: List<String> = emptyList(),
        sensitive: Boolean = false,
    ) : this(
        name,
        type,
        values,
        limit,
        highLimit,
        required,
        multiValued,
        default,
        deprecated,
        deprecatedValues,
        sensitive,
        valueLimit = null,
    )
}

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
     * The values [parameter] of [module] accepts on this wiki.
     *
     * `null` when the wiki has no such module or parameter, or the parameter takes free text: there is then
     * no list to check a value against. [parameter] is named without the module's prefix, as `paraminfo`
     * names it.
     */
    public suspend fun values(module: String, parameter: String): List<String>? =
        module(module)?.get(parameter)?.values?.takeIf { it.isNotEmpty() }

    /**
     * Whether this wiki has announced that [value] of [parameter] is going away.
     *
     * A deprecated value still works, but every response to a request that sends it carries a warning. A
     * caller that knows the replacement can ask first and send that instead.
     */
    public suspend fun isDeprecated(module: String, parameter: String, value: String): Boolean =
        module(module)?.get(parameter)?.deprecatedValues?.contains(value) == true

    /**
     * Fetches each of [modules] not already known, [MAX_MODULES] to a request.
     *
     * For a caller about to ask about several modules, which would otherwise cost a request each. A module
     * the wiki lacks is remembered as absent, so a later [module] call for it sends no request.
     */
    public suspend fun prefetch(modules: Collection<String>) {
        val wanted = mutex.withLock { modules.distinct().filterNot { it in cached } }

        for (batch in wanted.chunked(MAX_MODULES)) {
            val described = modules(*batch.toTypedArray()).mapTo(mutableSetOf()) { it.path }
            mutex.withLock { batch.filterNot { it in described }.forEach { cached.putIfAbsent(it, null) } }
        }
    }

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
            // "limit" and "highlimit" count the values a multi-valued parameter takes, not the results a
            // query returns; a type=limit parameter reports those as "max" and "highmax".
            limit = this["max"]?.jsonPrimitive?.intOrNull,
            highLimit = this["highmax"]?.jsonPrimitive?.intOrNull,
            required = flag("required"),
            multiValued = flag("multi"),
            default =
                this["default"]?.let { element ->
                    runCatching { element.jsonPrimitive.content }.getOrNull()
                },
            deprecated = flag("deprecated"),
            deprecatedValues = strings("deprecatedvalues"),
            sensitive = flag("sensitive"),
            valueLimit = this["limit"]?.jsonPrimitive?.intOrNull,
            highValueLimit = this["highlimit"]?.jsonPrimitive?.intOrNull,
            internalValues = strings("internalvalues"),
        )

    private fun JsonObject.strings(key: String): List<String> =
        this[key]
            ?.let { element ->
                runCatching { element.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
            }
            .orEmpty()

    private fun JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun JsonObject.flag(key: String): Boolean =
        this[key]?.let { runCatching { it.jsonPrimitive.content != "false" }.getOrDefault(true) } ?: false

    /** How many modules one request may name. */
    public companion object {
        /** What `modules` takes from an account without `apihighlimits`, which is safe for any account. */
        public const val MAX_MODULES: Int = 50
    }
}
