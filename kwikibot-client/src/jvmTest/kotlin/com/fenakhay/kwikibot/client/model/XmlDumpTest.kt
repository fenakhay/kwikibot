package com.fenakhay.kwikibot.client.model

import io.kotest.matchers.shouldBe
import javax.xml.stream.XMLStreamException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.toList

class XmlDumpTest {

    private val dump =
        """
        <mediawiki xmlns="http://www.mediawiki.org/xml/export-0.11/" version="0.11">
          <siteinfo><sitename>Wiktionary</sitename></siteinfo>
          <page>
            <title>volcano</title>
            <ns>0</ns>
            <id>1234</id>
            <revision>
              <id>9001</id>
              <timestamp>2026-08-01T12:00:00Z</timestamp>
              <contributor><username>Someone</username></contributor>
              <comment>a change</comment>
              <text xml:space="preserve">==English==
        A volcano.</text>
            </revision>
          </page>
          <page>
            <title>Talk:volcano</title>
            <ns>1</ns>
            <id>1235</id>
            <redirect title="Talk:mountain" />
            <revision>
              <id>9002</id>
              <text xml:space="preserve">#REDIRECT [[Talk:mountain]]</text>
            </revision>
          </page>
        </mediawiki>
        """
            .trimIndent()

    @Test
    fun `pages are read one at a time with their text`() {
        val pages = XmlDump.pages(dump.byteInputStream()).toList()

        pages.size shouldBe 2
        pages.first().title shouldBe "volcano"
        pages.first().pageId shouldBe 1234L
        pages.first().revisionId shouldBe 9001L
        pages.first().text.startsWith("==English==") shouldBe true
        pages.first().contributor shouldBe "Someone"
    }

    @Test
    fun `a page id and a revision id are not the same id`() {
        val page = XmlDump.pages(dump.byteInputStream()).first()

        (page.pageId != page.revisionId) shouldBe true
    }

    @Test
    fun `a redirect is marked as one`() {
        val pages = XmlDump.pages(dump.byteInputStream()).toList()

        pages.last().isRedirect shouldBe true
        pages.first().isRedirect shouldBe false
    }

    @Test
    fun `namespaces can be filtered before anything else is done with a page`() {
        val main = XmlDump.pages(dump.byteInputStream()).filter { it.inNamespaces(setOf(0)) }

        main.toList().map { it.title } shouldBe listOf("volcano")
    }

    @Test
    fun `a history dump keeps the first revision and skips the rest`() {
        val history =
            """
            <mediawiki>
              <page>
                <title>volcano</title><ns>0</ns><id>1</id>
                <revision><id>1</id><text>oldest</text></revision>
                <revision><id>2</id><text>newer</text></revision>
                <revision><id>3</id><text>newest</text></revision>
              </page>
            </mediawiki>
            """
                .trimIndent()

        val page = XmlDump.pages(history.byteInputStream()).single()

        page.revisionId shouldBe 1L
        page.text shouldBe "oldest"
    }

    @Test
    fun `an id or namespace that is not a number reads as zero and an address names an anonymous editor`() {
        val odd =
            """
            <mediawiki>
              <page>
                <title>volcano</title><ns>main</ns><id>first</id>
                <revision><id>9</id><contributor><ip>192.0.2.1</ip></contributor><text>lava</text></revision>
              </page>
            </mediawiki>
            """
                .trimIndent()

        val page = XmlDump.pages(odd.byteInputStream()).single()

        page.namespace shouldBe 0
        page.pageId shouldBe 0L
        page.revisionId shouldBe 9L
        page.contributor shouldBe "192.0.2.1"
        page.inNamespaces(emptySet()) shouldBe true
    }

    @Test
    fun `a truncated dump yields what it contained and then says it was truncated`() {
        val truncated = dump.substringBefore("  <page>\n    <title>Talk:volcano")
        val pages = XmlDump.pages(truncated.byteInputStream()).iterator()

        pages.next().title shouldBe "volcano"

        assertFailsWith<XMLStreamException> { pages.hasNext() }
    }

    private companion object {
        const val BLANK_LINE = "\n\n"
    }
}
