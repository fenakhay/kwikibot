import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class EntityServiceTest {

    private val mock = MockWiki()

    private fun runWiki(block: suspend TestScope.(EntityService) -> Unit) = runTest {
        val wiki = mock.open(BotPassword("Bot", "task", "secret"))
        block(wiki.entities)
        wiki.close()
    }

    private val saved =
        """{"entity":{"type":"item","id":"Q7","lastrevid":12,
        "labels":{"en":{"language":"en","value":"Lava"}}},"success":1}"""

    private val savedClaim =
        """{"claim":{"mainsnak":{"snaktype":"novalue","property":"P9"},
        "type":"statement","id":"Q7${'$'}9","rank":"normal"},"success":1}"""

    private fun last(action: String) = mock.requests.last { it["action"] == action }

    @Test
    fun `entities are read one or many at a time, by id`() = runWiki { entities ->
        entities.entity("Q42").await()?.id shouldBe "Q42"
        last("wbgetentities")["ids"] shouldBe "Q42"

        val found = entities.entities(arrayOf("Q42", "Q43")).await()
        last("wbgetentities")["ids"] shouldBe "Q42|Q43"
        found.toMap().keys shouldBe setOf("Q42")
        found.toMap().getValue("Q42").label("en") shouldBe "Douglas Adams"
    }

    @Test
    fun `the entities about pages come with the titles asked for`() = runWiki { entities ->
        mock.canned["wbgetentities"] =
            """{"entities":{"Q42":{"type":"item","id":"Q42",
              "sitelinks":{"enwiki":{"site":"enwiki","title":"Douglas Adams","badges":["Q1"]}}}}}"""

        val found = entities.forPages("enwiki", arrayOf("Douglas Adams", "Nobody")).await().single()

        last("wbgetentities")["sites"] shouldBe "enwiki"
        last("wbgetentities")["titles"] shouldBe "Douglas Adams|Nobody"
        found.title shouldBe "Douglas Adams"
        val link = found.entity.siteLink("enwiki") ?: error("no link")
        listOf(link.site, link.title) shouldBe listOf("enwiki", "Douglas Adams")
        link.badges.toList() shouldBe listOf("Q1")
        found.entity.siteLinks?.toMap()?.keys shouldBe setOf("enwiki")
    }

    @Test
    fun `a search says which entities match`() = runWiki { entities ->
        mock.canned["wbsearchentities"] =
            """{"search":[{"id":"P31","label":"instance of","description":"that class"}]}"""

        val hit = entities.search("instance", "fr", literal("property"), 3).await().single()

        listOf(hit.id, hit.label, hit.description) shouldBe listOf("P31", "instance of", "that class")
        val asked = last("wbsearchentities")
        listOf(asked["search"], asked["language"], asked["type"], asked["limit"]) shouldBe
            listOf("instance", "fr", "property", "3")
    }

    @Test
    fun `a search defaults to items and refuses what the wiki cannot search`() = runWiki { entities ->
        mock.canned["wbsearchentities"] = """{"search":[]}"""

        entities.search("x").await().size shouldBe 0
        last("wbsearchentities")["type"] shouldBe "item"
        last("wbsearchentities")["limit"] shouldBe ENTITY_SEARCH_LIMIT.toString()
        shouldThrow<Throwable> { entities.search("x", kind = literal("mediainfo")).await() }
        shouldThrow<IllegalArgumentException> { entities.search("x", kind = literal("word")).await() }
            .message!!
            .shouldContain("'lexeme'")
    }

    @Test
    fun `an edit sends every part of its options`() = runWiki { entities ->
        mock.canned["wbeditentity"] = saved

        val entity =
            entities
                .edit(
                    "Q7",
                    optionsOf(
                        "labels" to recordOf("en" to "Lava"),
                        "descriptions" to recordOf("en" to "Molten rock"),
                        "aliases" to recordOf("en" to arrayOf("Magma")),
                        "siteLinks" to recordOf("enwiki" to EntitySiteLink("enwiki", "Lava", arrayOf("Q1"))),
                        "statements" to recordOf("P9" to arrayOf(claim(noValueSnak("P9")))),
                        "summary" to "tidy",
                        "bot" to false,
                        "baseRevision" to 11.0,
                        "clear" to true,
                    ),
                )
                .await()

        entity.lastRevision shouldBe 12.0
        val sent = last("wbeditentity")
        listOf(sent["id"], sent["summary"], sent["baserevid"], sent["clear"], sent["bot"]) shouldBe
            listOf("Q7", "tidy", "11", "1", null)
        val data = JSON.parse<dynamic>(sent.getValue("data"))
        (data.labels.en.value.unsafeCast<Any?>()) shouldBe "Lava"
        (data.descriptions.en.value.unsafeCast<Any?>()) shouldBe "Molten rock"
        (data.aliases.en[0].value.unsafeCast<Any?>()) shouldBe "Magma"
        (data.sitelinks.enwiki.title.unsafeCast<Any?>()) shouldBe "Lava"
        (data.sitelinks.enwiki.badges[0].unsafeCast<Any?>()) shouldBe "Q1"
        (data.claims.P9[0].mainsnak.snaktype.unsafeCast<Any?>()) shouldBe "novalue"
    }

    @Test
    fun `creating names the kind and a bot's edit is marked unless told otherwise`() = runWiki { entities ->
        mock.canned["wbeditentity"] = saved

        entities.create(literal("item"), optionsOf("labels" to recordOf("en" to "Lava"))).await().id shouldBe
            "Q7"
        last("wbeditentity")["new"] shouldBe "item"
        last("wbeditentity")["bot"] shouldBe "1"
        last("wbeditentity")["token"] shouldBe "c+\\"
        shouldThrow<IllegalArgumentException> { entities.create(literal("word"), optionsOf()).await() }
    }

    @Test
    fun `a statement is set and removed`() = runWiki { entities ->
        mock.canned["wbsetclaim"] = savedClaim
        mock.canned["wbremoveclaims"] = """{"claims":["Q7${'$'}9"],"success":1}"""

        val claim = entities.setStatement("Q7", claim(noValueSnak("P9")), "add", 11.0).await()
        claim.id shouldBe "Q7$9"
        val sent = last("wbsetclaim")
        listOf(sent["summary"], sent["baserevid"], sent["bot"]) shouldBe listOf("add", "11", "1")
        (JSON.parse<dynamic>(sent.getValue("claim")).mainsnak.property.unsafeCast<Any?>()) shouldBe "P9"

        entities.removeStatements(arrayOf("Q7$9", "Q7$10"), "gone", 12.0).await()
        val removed = last("wbremoveclaims")
        listOf(removed["claim"], removed["summary"], removed["baserevid"]) shouldBe
            listOf("Q7$9|Q7$10", "gone", "12")
    }

    @Test
    fun `items and lexemes merge and an empty entity redirects`() = runWiki { entities ->
        mock.canned["wbmergeitems"] = """{"success":1}"""
        mock.canned["wblmergelexemes"] = """{"success":1}"""
        mock.canned["wbcreateredirect"] = """{"success":1}"""

        entities.mergeItems("Q1", "Q2", literal(arrayOf("description", "sitelink")), "dupe").await()
        val items = last("wbmergeitems")
        listOf(items["fromid"], items["toid"], items["ignoreconflicts"], items["summary"]) shouldBe
            listOf("Q1", "Q2", "description|sitelink", "dupe")

        entities.mergeLexemes("L1", "L2", "same word").await()
        val lexemes = last("wblmergelexemes")
        listOf(lexemes["source"], lexemes["target"], lexemes["summary"]) shouldBe
            listOf("L1", "L2", "same word")

        entities.redirect("Q3", "Q2").await()
        listOf(last("wbcreateredirect")["from"], last("wbcreateredirect")["to"]) shouldBe listOf("Q3", "Q2")
    }

    @Test
    fun `values are parsed by the repository`() = runWiki { entities ->
        mock.canned["wbparsevalue"] =
            """{"results":[{"raw":"1952","type":"quantity","value":{"amount":"+1952","unit":"1"}}]}"""

        val value = entities.parseValues("quantity", arrayOf("1952")).await().single()

        value.kind shouldBe "quantity"
        value.amount shouldBe "+1952"
        listOf(last("wbparsevalue")["datatype"], last("wbparsevalue")["values"]) shouldBe
            listOf("quantity", "1952")
    }

    @Test
    fun `the batch size is what the repository takes and a kind is read off an id`() = runTest {
        ENTITY_BATCH_SIZE shouldBe 50
        listOf("Q1", "P1", "L1", "L1-F1", "L1-S1", "M1", "X1").map(::entityKind) shouldBe
            listOf("item", "property", "lexeme", "form", "sense", "mediainfo", "unknown")
    }
}

/** A table with [entries], as a TypeScript author writes one. */
fun <V> recordOf(vararg entries: Pair<String, V>): Record<String, V> = optionsOf(*entries)
