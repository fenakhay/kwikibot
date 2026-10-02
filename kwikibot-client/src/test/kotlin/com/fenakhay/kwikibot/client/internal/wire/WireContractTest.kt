package com.fenakhay.kwikibot.client.internal.wire

import com.fenakhay.kwikibot.client.service.ParseProperty
import java.lang.reflect.Field
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.readLines
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.fail

/**
 * Checks what this library sends against what the reference wikis say they accept.
 *
 * `api-surface.tsv` records every parameter of every module on the reference wikis, with the values each
 * takes and the ones MediaWiki has deprecated. This test walks every [Wire] in [Registry] and every enum
 * carrying [MediaWikiParameter], and fails when:
 * - a value is not one the parameter takes, or the parameter does not exist;
 * - a [Wire.Choice] prefers a deprecated spelling, or has no spelling that is not deprecated;
 * - an enum entry is `@Deprecated` when MediaWiki has not deprecated its value, or the other way round. A
 *   value MediaWiki deprecated calls for `WARNING`; one it has removed altogether calls for `ERROR`;
 * - a deprecation message is not in the form a reader can act on.
 *
 * It also writes `build/wire-registry.tsv`, every value sent and where it is declared, which the weekly
 * surface report uses to mark the changes that touch this library.
 */
class WireContractTest {

    private val surface = Surface.read(surfaceFile())

    @Test
    fun `every fixed value list is one the wiki accepts, and nothing in it is deprecated`() {
        val problems =
            registered<Wire.Values>().flatMap { (name, values) ->
                val parameter =
                    surface[values.parameter] ?: return@flatMap listOf("$name: no ${values.parameter}")
                values.values.mapNotNull { value ->
                    when {
                        !parameter.accepts(value) -> "$name: ${values.parameter} does not take '$value'"
                        value in parameter.deprecated -> "$name: ${values.parameter}=$value is deprecated"
                        else -> null
                    }
                }
            }

        assertNone(problems)
    }

    @Test
    fun `every choice prefers a spelling the wiki accepts and has not deprecated`() {
        val problems =
            registered<Wire.Choice>().flatMap { (name, choice) ->
                val parameter =
                    surface[choice.parameter] ?: return@flatMap listOf("$name: no ${choice.parameter}")
                val current =
                    choice.alternatives.filter { spelling -> spelling.none { it in parameter.deprecated } }

                listOfNotNull(
                    "$name: every spelling of ${choice.parameter} is deprecated".takeIf { current.isEmpty() },
                    choice.preferred
                        .filterNot { parameter.accepts(it) }
                        .takeIf { it.isNotEmpty() }
                        ?.let { "$name: ${choice.parameter} does not take the preferred $it" },
                    choice.preferred
                        .filter { it in parameter.deprecated }
                        .takeIf { it.isNotEmpty() }
                        ?.let { "$name: the preferred spelling $it of ${choice.parameter} is deprecated" },
                )
            }

        assertNone(problems)
    }

    @Test
    fun `every choice is described with the others the first time one is needed`() {
        val unlisted = registered<Wire.Choice>().filter { (_, choice) -> choice !in Registry.CHOICES }

        assertNone(unlisted.map { (name, _) -> "$name is not in Registry.CHOICES" })
    }

    @Test
    fun `every enum sent as a parameter says which, and every value it sends is accepted`() {
        val problems =
            wireEnums().flatMap { type ->
                val parameters = type.parameters()
                if (parameters.isEmpty())
                    return@flatMap listOf("${type.simpleName} has an apiValue but no @MediaWikiParameter")

                parameters.flatMap { parameter ->
                    val recorded =
                        surface[parameter] ?: return@flatMap listOf("${type.simpleName}: no $parameter")
                    type.entries().mapNotNull { entry ->
                        val removed = entry.deprecation?.level == DeprecationLevel.ERROR
                        "${entry.symbol}: $parameter does not take '${entry.value}'"
                            .takeIf { !removed && !recorded.accepts(entry.value) }
                    }
                }
            }

        assertNone(problems)
    }

    @Test
    fun `an entry is deprecated exactly when MediaWiki deprecated its value`() {
        val problems =
            wireEnums().flatMap { type ->
                val parameters = type.parameters().mapNotNull { surface[it] }
                type.entries().mapNotNull { entry ->
                    val expected =
                        when {
                            parameters.isNotEmpty() && parameters.none { it.accepts(entry.value) } ->
                                DeprecationLevel.ERROR
                            parameters.any { entry.value in it.deprecated } -> DeprecationLevel.WARNING
                            else -> null
                        }
                    val actual = entry.deprecation?.level

                    when {
                        expected == actual -> null
                        expected == null ->
                            "${entry.symbol} is @Deprecated but MediaWiki still takes '${entry.value}'"
                        actual == null ->
                            "${entry.symbol} should be @Deprecated($expected): '${entry.value}' is " +
                                if (expected == DeprecationLevel.ERROR) "gone" else "deprecated upstream"
                        else -> "${entry.symbol} is @Deprecated($actual) and should be $expected"
                    }
                }
            }

        assertNone(problems)
    }

    @Test
    fun `a deprecation says what MediaWiki did, what to use instead, and since when`() {
        val problems =
            wireEnums().flatMap { type ->
                val parameters = type.parameters()
                type.entries().mapNotNull { entry ->
                    val deprecation = entry.deprecation ?: return@mapNotNull null
                    val match =
                        MESSAGE.matchEntire(deprecation.message)
                            ?: return@mapNotNull "${entry.symbol}: '${deprecation.message}' is not in the " +
                                "form \"MediaWiki <ver> (T<task>) deprecated <module> <param>=<value>; " +
                                "use <X>. Deprecated since kwikibot <ver>.\""

                    val (module, parameter, value) = match.destructured
                    val replacement = match.groupValues[REPLACEMENT]
                    val replaceWith = deprecation.replaceWith.expression
                    when {
                        Wire.Parameter(module, parameter) !in parameters ->
                            "${entry.symbol}: the message names $module $parameter, which the enum is not sent as"
                        value != entry.value ->
                            "${entry.symbol}: the message names '$value', not '${entry.value}'"
                        !replaceWith.endsWith(".$replacement") ->
                            "${entry.symbol}: the message says use $replacement, ReplaceWith says $replaceWith"
                        else -> null
                    }
                }
            }

        assertNone(problems)
    }

    @Test
    fun `the values sent are written down for the surface report`() {
        val target = System.getProperty("kwikibot.wireRegistry")?.let { Path(it) } ?: return

        val rows = buildList {
            registered<Wire.Values>().forEach { (name, values) ->
                values.values.forEach { add(Row(values.parameter, it, "Registry.$name")) }
            }
            registered<Wire.Choice>().forEach { (name, choice) ->
                choice.alternatives.flatten().distinct().forEach {
                    add(Row(choice.parameter, it, "Registry.$name"))
                }
            }
            wireEnums().forEach { type ->
                type.parameters().forEach { parameter ->
                    type.entries().forEach { add(Row(parameter, it.value, it.symbol)) }
                }
            }
        }

        target.createParentDirectories()
        target.writeText(
            (listOf("module\tparameter\tvalue\tsymbol") +
                    rows.distinct().sortedWith(ROW_ORDER).map { it.render() })
                .joinToString("\n", postfix = "\n")
        )
    }

    private data class Row(val parameter: Wire.Parameter, val value: String, val symbol: String) {
        fun render(): String = "${parameter.module}\t${parameter.name}\t$value\t$symbol"
    }

    private class Entry(val symbol: String, val value: String, val deprecation: Deprecated?)

    /** Every declaration of type [T] in [Registry], by name. */
    private inline fun <reified T : Wire> registered(): List<Pair<String, T>> =
        Registry::class
            .java
            .declaredFields
            .filter { T::class.java.isAssignableFrom(it.type) }
            .map { field ->
                field.isAccessible = true
                field.name to T::class.java.cast(field.get(Registry))
            }

    /**
     * Every enum in the client that carries an `apiValue`, found on disk so that a new one cannot go
     * unchecked by being left off a list.
     */
    private fun wireEnums(): List<Class<*>> {
        val root = Path.of(ParseProperty::class.java.protectionDomain.codeSource.location.toURI())
        check(root.isDirectory()) { "expected the client's classes as a directory, found $root" }

        return Files.walk(root)
            .use { paths ->
                paths
                    .filter { it.extension == "class" }
                    .map {
                        it.relativeTo(root)
                            .invariantSeparatorsPathString
                            .removeSuffix(".class")
                            .replace('/', '.')
                    }
                    .toList()
            }
            .map { Class.forName(it, false, javaClass.classLoader) }
            .filter { it.isEnum && it.apiValueField() != null }
            .sortedBy { it.name }
    }

    private fun Class<*>.apiValueField(): Field? = declaredFields.firstOrNull { it.name == "apiValue" }

    private fun Class<*>.parameters(): List<Wire.Parameter> =
        getAnnotationsByType(MediaWikiParameter::class.java).flatMap { annotation ->
            annotation.parameters.map { Wire.Parameter(annotation.module, it) }
        }

    private fun Class<*>.entries(): List<Entry> {
        val apiValue = checkNotNull(apiValueField()).apply { isAccessible = true }
        return enumConstants.map { constant ->
            val name = (constant as Enum<*>).name
            Entry(
                symbol = "$simpleName.$name",
                value = apiValue.get(constant) as String,
                deprecation = getField(name).getAnnotation(Deprecated::class.java),
            )
        }
    }

    private fun assertNone(problems: List<String>) {
        if (problems.isNotEmpty()) fail(problems.joinToString("\n", prefix = "\n"))
    }

    /** `api-surface.tsv`, as far as this test reads it: what each parameter takes. */
    private class Surface(private val parameters: Map<Wire.Parameter, Recorded>) {

        operator fun get(parameter: Wire.Parameter): Recorded? = parameters[parameter]

        companion object {
            fun read(file: Path): Surface {
                val lines = file.readLines().filter { it.isNotBlank() }
                val header = lines.first().split('\t')
                val column = { name: String ->
                    header.indexOf(name).also { check(it >= 0) { "no $name column" } }
                }
                val module = column("module")
                val parameter = column("parameter")
                val detail = column("detail")

                return Surface(
                    lines.drop(1).associate { line ->
                        val cells = line.split('\t')
                        val fields = details(cells.getOrElse(detail) { "" })
                        Wire.Parameter(cells[module], cells[parameter]) to
                            Recorded(
                                values = fields["enum"]?.split('|'),
                                deprecated = fields["deprecatedvalues"]?.split('|').orEmpty().toSet(),
                            )
                    }
                )
            }

            /** The `key=value` fields of a detail cell, whose values may themselves contain spaces. */
            private fun details(cell: String): Map<String, String> =
                cell
                    .split(FIELD)
                    .filter { '=' in it }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
        }
    }

    /**
     * One recorded parameter.
     *
     * [values] is `null` for a parameter that takes free text, and for one whose values are the wiki's own
     * configuration, recorded as `<config>`: any value can be right there, so none can be checked.
     */
    private class Recorded(private val values: List<String>?, val deprecated: Set<String>) {
        fun accepts(value: String): Boolean =
            values == null || values.singleOrNull()?.startsWith("<config") == true || value in values
    }

    private companion object {
        val FIELD =
            Regex(" (?=(?:enum|type|default|limit|highlimit|max|highmax|deprecatedvalues|internalvalues)=)")

        val MESSAGE =
            Regex(
                """MediaWiki \d+\.\d+ \(T\d+\) deprecated (\S+) (\S+)=(\S+); use (\S+)\. """ +
                    """Deprecated since kwikibot \d+\.\d+\.\d+\."""
            )

        /** The group of [MESSAGE] naming what to use instead. */
        const val REPLACEMENT = 4

        val ROW_ORDER: Comparator<Row> =
            compareBy({ it.parameter.module }, { it.parameter.name }, { it.value })

        fun surfaceFile(): Path {
            System.getProperty("kwikibot.apiSurface")?.let {
                return Path(it)
            }

            // Run from an IDE without Gradle's system property: look upwards for the recorded file.
            var directory: Path? = Path("").toAbsolutePath()
            while (directory != null && !directory.resolve(SURFACE).exists()) directory = directory.parent
            return checkNotNull(directory) { "cannot find $SURFACE above the working directory" }
                .resolve(SURFACE)
        }

        const val SURFACE = "api-surface.tsv"
    }
}
