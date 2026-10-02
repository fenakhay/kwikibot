package com.fenakhay.kwikibot.client.internal.wire

/**
 * Something this library sends to MediaWiki, written down once.
 *
 * A value typed inline where a request is built cannot be checked, and a wrong one in a list of values shows
 * only as an API warning. Declared here, every value is checked against the recorded API surface by
 * `WireContractTest`, and the ones MediaWiki has respelled are chosen per wiki by [Capabilities].
 */
internal sealed interface Wire {

    /** The parameter this goes out as. */
    val parameter: Parameter

    /**
     * One parameter of one module, by the name it goes out under: `abfprop` on `query+abusefilters`.
     *
     * The prefixed name rather than the one `paraminfo` reports, because that is what a request carries and
     * what `api-surface.tsv` records.
     */
    data class Parameter(val module: String, val name: String) {
        override fun toString(): String = "$module $name"
    }

    /** Values always sent together, such as the properties a query asks for. */
    data class Values(override val parameter: Parameter, val values: List<String>) : Wire {

        constructor(
            module: String,
            name: String,
            vararg values: String,
        ) : this(Parameter(module, name), values.toList())

        /** The values joined as the parameter carries them. */
        val joined: String
            get() = values.joinToString("|")
    }

    /**
     * One thing MediaWiki has spelled more than one way, the preferred spelling first.
     *
     * `[[tocdata], [sections]]` is a page's table of contents, asked for the new way where a wiki knows it
     * and the old way where it does not. Each alternative is a list because one spelling can take several
     * values to say what one now does: `flags` replaced `status`, `private` and `protected` together.
     */
    data class Choice(override val parameter: Parameter, val alternatives: List<List<String>>) : Wire {
        init {
            require(alternatives.isNotEmpty() && alternatives.none { it.isEmpty() }) {
                "a choice needs at least one spelling, and every spelling at least one value"
            }
        }

        /** The spelling a wiki that says nothing about itself is sent. */
        val preferred: List<String>
            get() = alternatives.first()

        override fun toString(): String =
            "$parameter=${alternatives.joinToString(" or ") { it.joinToString("|") }}"
    }
}

/**
 * Names the parameter an enum's `apiValue`s are sent as.
 *
 * Read by `WireContractTest`, which checks that every value is one the reference wikis accept and that an
 * entry is `@Deprecated` when, and only when, MediaWiki has deprecated its value. An enum sent as several
 * parameters carries one annotation per module.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
internal annotation class MediaWikiParameter(val module: String, vararg val parameters: String)
