package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.wikitext.ParseOptions
import com.fenakhay.kwikibot.wikitext.TitleRules
import com.fenakhay.kwikibot.wikitext.Wikitext
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ParserSettingsTest {

    /**
     * A `meta=siteinfo` answer shaped like en.wiktionary's, where most namespaces keep case. It is trimmed,
     * and changed where a test needs it: language conversion is off, Appendix takes `first-letter`, and
     * `subst` has a German alias.
     */
    private val wiktionary =
        Json.parseToJsonElement(
                """
                {"general":{"langconversion":false},
                 "namespaces":{
                   "0":{"id":0,"name":"","case":"case-sensitive"},
                   "6":{"id":6,"name":"File","canonical":"File","case":"case-sensitive"},
                   "10":{"id":10,"name":"Template","canonical":"Template","case":"case-sensitive"},
                   "100":{"id":100,"name":"Appendix","canonical":"Appendix","case":"first-letter"}},
                 "namespacealiases":[{"id":6,"alias":"Image"},{"id":10,"alias":"T"}],
                 "extensiontags":["<ref>","<pre>","<nowiki>"],
                 "protocols":["https://","//"],
                 "functionhooks":["lc","if","int","formatnum","defaultsort"],
                 "variables":["pagename","!"],
                 "magicwords":[
                   {"name":"lc","aliases":["LC:"],"case-sensitive":false},
                   {"name":"if","aliases":["if"],"case-sensitive":false},
                   {"name":"int","aliases":["INT:"],"case-sensitive":false},
                   {"name":"formatnum","aliases":["FORMATNUM"],"case-sensitive":false},
                   {"name":"defaultsort","aliases":["DEFAULTSORT:","DEFAULTSORTKEY:"],"case-sensitive":true},
                   {"name":"pagename","aliases":["PAGENAME"],"case-sensitive":true},
                   {"name":"!","aliases":["!"],"case-sensitive":true},
                   {"name":"subst","aliases":["SUBST:","ERSETZEN:"],"case-sensitive":false}]}
                """
            )
            .jsonObject

    private val decoded = ParserSettings.decode(wiktionary)
    private val options = decoded.first
    private val rules = decoded.second

    @Test
    fun `a wiki's tags, schemes and file names are its own`() {
        options.extensionTags shouldBe setOf("ref", "pre", "nowiki")
        options.protocols shouldBe listOf("https://", "//")
        options.languageConversion shouldBe false
        options.fileNamespaces shouldBe setOf("File", "Image")
    }

    @Test
    fun `parsing with them reads the page as the wiki does`() {
        // `gallery` is not one of this wiki's tags, so its body is ordinary wikitext here.
        Wikitext.parse("<gallery>{{t}}</gallery>", options).tags().size shouldBe 0
        Wikitext.parse("{{t|-{a|b}-}}", options).templates().single().parameters.size shouldBe 2
    }

    @Test
    fun `templates keep case where the wiki says so`() {
        rules.key("l") shouldBe "l"
        rules.key("T:l") shouldBe "l"
        rules.key("Appendix:x") shouldBe "Appendix:X"
        rules.key("ersetzen:l") shouldBe "l"
    }

    @Test
    fun `functions and variables are told apart, and keep case, as the wiki registers them`() {
        rules.isParserFunction("lc:ABC") shouldBe true
        rules.isParserFunction("INT:x") shouldBe true
        rules.isParserFunction("formatnum:1") shouldBe true
        rules.isParserFunction("if:x") shouldBe false
        rules.isParserFunction("#if:x") shouldBe true
        rules.isParserFunction("DEFAULTSORTKEY:x") shouldBe true
        rules.isParserFunction("defaultsort:x") shouldBe false
        rules.isParserFunction("lc :x") shouldBe false
        rules.isParserFunction("PAGENAME") shouldBe true
        rules.isParserFunction("pagename") shouldBe false
        rules.isParserFunction("!") shouldBe true
    }

    @Test
    fun `a wiki that says nothing gets MediaWiki's defaults`() {
        val (options, rules) = ParserSettings.decode(JsonObject(emptyMap()))

        options shouldBe ParseOptions.DEFAULT
        rules shouldBe TitleRules.DEFAULT
    }
}
