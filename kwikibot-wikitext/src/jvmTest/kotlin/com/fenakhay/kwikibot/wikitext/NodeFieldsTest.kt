package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.node.Argument
import com.fenakhay.kwikibot.wikitext.node.Attribute
import com.fenakhay.kwikibot.wikitext.node.Comment
import com.fenakhay.kwikibot.wikitext.node.ExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.HtmlEntity
import com.fenakhay.kwikibot.wikitext.node.Parameter
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.test.Test

/**
 * The fields of every node class, as `internal/TreeComparison` compares them.
 *
 * Markup compares its trees without the nodes' own `equals`, so a field added to a node class and not to the
 * comparison would be ignored by every comparison of trees. This fails first, naming the class.
 */
class NodeFieldsTest {

    @Test
    fun `every field of every node is compared`() {
        fieldsOf(TextNode::class) shouldBe setOf("text")
        fieldsOf(Comment::class) shouldBe setOf("contents", "closed")
        fieldsOf(HtmlEntity::class) shouldBe setOf("value", "numeric", "hexChar")
        fieldsOf(Template::class) shouldBe setOf("name", "parameters")
        fieldsOf(Parameter::class) shouldBe setOf("name", "value", "showKey")
        fieldsOf(Argument::class) shouldBe setOf("name", "default")
        fieldsOf(WikiLink::class) shouldBe setOf("target", "text")
        fieldsOf(ExternalLink::class) shouldBe setOf("url", "title", "brackets", "separator")
        fieldsOf(Heading::class) shouldBe setOf("title", "level")
        fieldsOf(Attribute::class) shouldBe
            setOf("name", "value", "quote", "padFirst", "padBeforeEq", "padAfterEq")
        fieldsOf(Tag::class) shouldBe
            setOf(
                "name",
                "contents",
                "attributes",
                "selfClosing",
                "wikiMarkup",
                "padding",
                "implicitClose",
                "closing",
                "verbatim",
            )
    }

    private fun fieldsOf(type: KClass<*>): Set<String> =
        type.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
}
