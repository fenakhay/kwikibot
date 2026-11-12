import com.fenakhay.kwikibot.model.SignatureFormat as ClientSignatureFormat
import com.fenakhay.kwikibot.model.Signatures as ClientSignatures
import kotlin.js.RegExp

/**
 * Finds the timestamps that `~~~~` leaves behind, which is the only record of when a discussion last had a
 * reply. `wiki.signatures()` gives them as a wiki writes them; these functions give the ones the library
 * ships.
 */
@JsExport
public class Signatures internal constructor(internal val signatures: ClientSignatures) {

    /** The formats tried, in order. */
    public val formats: Array<SignatureFormat>
        get() = signatures.formats.map(::SignatureFormat).toTypedArray()

    /** Every signature time in [wikitext], in the order they appear. */
    public fun findAll(wikitext: String): Array<SignatureTime> =
        signatures.findAll(wikitext).map(::SignatureTime).toTypedArray()

    /** The most recent signature time, or `null` when the text has none. */
    public fun latest(wikitext: String): SignatureTime? = signatures.latest(wikitext)?.let(::SignatureTime)

    /** The earliest signature time, which is when a discussion started, or `null`. */
    public fun earliest(wikitext: String): SignatureTime? =
        signatures.earliest(wikitext)?.let(::SignatureTime)

    /** These formats and [other]'s. */
    public fun plus(other: Signatures): Signatures = Signatures(signatures + other.signatures)

    /** The patterns these formats match. */
    public fun patterns(): Array<RegExp> = signatures.patterns().map(::regExpOf).toTypedArray()
}

/** Signatures tried with [formats], in order. */
@JsExport
public fun signaturesOf(formats: Array<SignatureFormat>): Signatures =
    Signatures(ClientSignatures(formats.map { it.format }))

/** The format shipped for [language], which is English's for a language not shipped; they read as UTC. */
@JsExport
public fun signaturesFor(language: String): Signatures = Signatures(ClientSignatures.forLanguage(language))

/** Every signature format the library ships. */
@JsExport public fun allSignatures(): Signatures = Signatures(ClientSignatures.ALL)

/**
 * One wiki's signature format, for [language]. [pattern] has to name the groups `hour`, `minute`, `day`,
 * `month` and `year`; [months] are the names lowercased, with their numbers; [zone] is where the times are,
 * such as `"Europe/Berlin"`.
 */
@JsExport
public fun signatureFormat(
    language: String,
    pattern: RegExp,
    months: Record<String, Int>,
    zone: String = "UTC",
): SignatureFormat = SignatureFormat(ClientSignatureFormat(language, pattern.toRegex(), months.toMap(), zone))

/** One wiki's signature format. */
@JsExport
public class SignatureFormat internal constructor(internal val format: ClientSignatureFormat) {

    /** The pattern this format matches. */
    public val pattern: RegExp
        get() = regExpOf(format.pattern)

    /** Every timestamp in [wikitext] this format matches. Comments are left out. */
    public fun findAll(wikitext: String): Array<SignatureTime> =
        format.findAll(wikitext).map(::SignatureTime).toTypedArray()
}

internal fun regExpOf(regex: Regex): RegExp =
    RegExp(
        regex.pattern,
        "u" +
            (if (RegexOption.IGNORE_CASE in regex.options) "i" else "") +
            (if (RegexOption.MULTILINE in regex.options) "m" else ""),
    )
