package com.fenakhay.kwikibot.model.title

import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TitleNormalizationTest {

    private val fixture: JsonObject by lazy {
        val stream =
            checkNotNull(javaClass.getResourceAsStream("/title-normalization.json")) {
                "title-normalization.json missing from test resources"
            }
        Json.parseToJsonElement(stream.reader().readText()).jsonObject
    }

    private val site: JsonObject
        get() = fixture["site"]!!.jsonObject

    private val namespaces: NamespaceMap by lazy {
        NamespaceMap(
            site["namespaces"]!!.jsonArray.map { element ->
                val ns = element.jsonObject
                NamespaceInfo(
                    id = Namespace(ns["id"]!!.jsonPrimitive.int),
                    canonicalName = ns["canonical"]!!.jsonPrimitive.content,
                    localName = ns["local"]!!.jsonPrimitive.content,
                    aliases = ns["aliases"]!!.jsonArray.map { it.jsonPrimitive.content },
                    case =
                        when (ns["case"]!!.jsonPrimitive.content) {
                            "case-sensitive" -> TitleCase.CASE_SENSITIVE
                            else -> TitleCase.FIRST_LETTER
                        },
                )
            }
        )
    }

    private val interwiki: InterwikiMap by lazy {
        InterwikiMap(
            prefixes = site["interwiki"]!!.jsonArray.map { it.jsonPrimitive.content },
            selfPrefixes = site["selfInterwiki"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `parsing matches MediaWiki for every recorded title`() {
        val mismatches = fixture["titles"]!!.jsonArray.mapNotNull { compare(it.jsonObject) }

        if (mismatches.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("${mismatches.size} title(s) parsed differently from MediaWiki:")
                    mismatches.forEach { appendLine("  $it") }
                }
            )
        }
    }

    @Test
    fun `the fixture covers the namespaces and prefixes the parser relies on`() {
        val main = checkNotNull(namespaces[Namespace.MAIN]) { "main space missing from fixture" }
        check(main.case == TitleCase.CASE_SENSITIVE) { "en.wiktionary main space is case-sensitive" }

        val alias = checkNotNull(namespaces.byPrefix("CAT")) { "alias CAT should resolve" }
        check(alias.id == Namespace.CATEGORY) { "CAT should be the Category namespace" }

        check("w" in interwiki) { "interwiki map should contain w" }
        check(interwiki.isSelf("wikt")) { "wikt should point back at this wiki" }
    }

    private fun compare(row: JsonObject): String? {
        val raw = row["raw"]!!.jsonPrimitive.content
        val actual = Title.parse(raw, namespaces, interwiki)

        val detail =
            when (val kind = row["kind"]!!.jsonPrimitive.content) {
                "local" -> compareLocal(row, actual)
                "interwiki" -> compareInterwiki(row, actual)

                "invalid",
                "unresolved" -> {
                    val reason = row["reason"]?.jsonPrimitive?.content ?: "unresolved"
                    if (actual is Title.Invalid) null else "got $actual, expected invalid ($reason)"
                }

                else -> "fixture has unknown kind '$kind'"
            }

        return detail?.let { "${raw.abbreviate()} — $it" }
    }

    private fun compareLocal(row: JsonObject, actual: Title): String? {
        val wanted = row["title"]!!.jsonPrimitive.content
        val wantedNamespace = Namespace(row["ns"]!!.jsonPrimitive.int)

        if (actual !is Title.Local) return "got $actual, expected local page '$wanted'"
        if (actual.namespace != wantedNamespace) {
            return "namespace ${actual.namespace.id}, expected ${wantedNamespace.id}"
        }

        val rendered = namespaces.format(actual.copy(fragment = null))
        return if (rendered == wanted) null else "rendered '$rendered', expected '$wanted'"
    }

    private fun compareInterwiki(row: JsonObject, actual: Title): String? {
        val wanted = row["prefix"]!!.jsonPrimitive.content

        if (actual !is Title.Interwiki) return "got $actual, expected interwiki '$wanted:'"
        return if (actual.prefix.equals(wanted, ignoreCase = true)) {
            null
        } else {
            "interwiki prefix '${actual.prefix}', expected '$wanted'"
        }
    }

    private fun String.abbreviate(): String =
        if (length <= ABBREVIATE_AT) "'$this'" else "'${take(20)}…' ($length chars)"

    private companion object {
        const val ABBREVIATE_AT = 40
    }
}
