import com.fenakhay.kwikibot.bindings.connect.REDACTED
import com.fenakhay.kwikibot.bindings.connect.namesSecret
import com.fenakhay.kwikibot.bindings.connect.withoutSecrets as redacted
import com.fenakhay.kwikibot.net.transport.ApiRequest

private val secrets: dynamic = js("new WeakMap()")

/** Keeps [secret] for [owner] where nothing that walks [owner]'s properties can come across it. */
internal fun keepSecret(owner: Any, secret: Any) {
    secrets.set(owner, secret)
}

/** What [keepSecret] kept for [owner]. */
internal fun <T> secretOf(owner: Any): T = secrets.get(owner).unsafeCast<T>()

/**
 * [target], its fields made non-enumerable. It works as before, and `JSON.stringify`, `console.log`,
 * `Object.keys` and a spread see nothing of them, at any depth.
 */
internal fun <T : Any> hideFields(target: T): T {
    val objects: dynamic = js("Object")
    val names = objects.keys(target).unsafeCast<Array<String>>()
    for (name in names) {
        val hidden: dynamic = js("({ enumerable: false })")
        objects.defineProperty(target, name, hidden)
    }
    return target
}

/** Makes `util.inspect` write [target] as [name], however it is asked to dig. */
internal fun inspectedAs(target: Any, name: String) {
    val descriptor: dynamic = js("({ enumerable: false })")
    descriptor.value = { "$name {}" }
    js("Object").defineProperty(target, js("Symbol.for('nodejs.util.inspect.custom')"), descriptor)
}

/** The parameters of [request], as a listener may hear them. */
internal fun paramsWithoutSecrets(request: ApiRequest): Record<String, String> =
    redacted(request.params).toRecord { it }

/** [plain], a value `JSON.parse` made, with what [namesSecret] says replaced, wherever it is. */
internal fun <T> withoutSecrets(plain: T): T {
    val value: dynamic = plain
    when {
        js("Array").isArray(value) as Boolean ->
            value.unsafeCast<Array<Any?>>().forEach { withoutSecrets(it) }
        jsTypeOf(value) == "object" && value != null ->
            for (name in js("Object").keys(value).unsafeCast<Array<String>>()) {
                if (namesSecret(name)) value[name] = REDACTED else withoutSecrets(value[name])
            }
    }
    return plain
}
