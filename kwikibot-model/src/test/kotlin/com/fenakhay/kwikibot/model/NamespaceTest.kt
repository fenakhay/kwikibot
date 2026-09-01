package com.fenakhay.kwikibot.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class NamespaceTest {

    @Test
    fun `odd numbered namespaces are talk spaces`() {
        Namespace.TALK.isTalk shouldBe true
        Namespace.CATEGORY_TALK.isTalk shouldBe true
        Namespace.MAIN.isTalk shouldBe false
        Namespace.CATEGORY.isTalk shouldBe false
    }

    @Test
    fun `virtual namespaces are neither talk nor subject spaces`() {
        Namespace.SPECIAL.isVirtual shouldBe true
        Namespace.SPECIAL.isTalk shouldBe false
        Namespace.SPECIAL.talkSpace.shouldBeNull()
        Namespace.MEDIA.subjectSpace.shouldBeNull()
    }

    @Test
    fun `talk and subject spaces pair up`() {
        Namespace.MAIN.talkSpace shouldBe Namespace.TALK
        Namespace.TEMPLATE.talkSpace shouldBe Namespace.TEMPLATE_TALK
        Namespace.TEMPLATE_TALK.subjectSpace shouldBe Namespace.TEMPLATE
    }

    @Test
    fun `talk space of a talk space is itself`() {
        Namespace.USER_TALK.talkSpace shouldBe Namespace.USER_TALK
        Namespace.USER.subjectSpace shouldBe Namespace.USER
    }

    @Test
    fun `custom namespaces follow the same numbering rule`() {
        val gadget = Namespace(2300)
        gadget.isTalk shouldBe false
        gadget.talkSpace shouldBe Namespace(2301)
    }
}
