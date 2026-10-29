package com.fenakhay.kwikibot.model.file

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FileInfoTest {

    private val url = "https://upload.wikimedia.org/wikipedia/commons/a/a1/Volcano.jpg"

    @Test
    fun `a file has dimensions only when it is both wide and tall`() {
        FileInfo(url, width = 800, height = 600).hasDimensions shouldBe true
        FileInfo(url, width = 800).hasDimensions shouldBe false
        FileInfo(url, height = 600).hasDimensions shouldBe false
    }

    @Test
    fun `an upload is only a duplicate when every warning says so`() {
        UploadOutcome.Warned(mapOf("duplicate" to "Volcano.jpg"), "key").isDuplicateOnly shouldBe true
        UploadOutcome.Warned(mapOf("duplicate" to "a", "badfilename" to "b"), "key").isDuplicateOnly shouldBe
            false
        UploadOutcome.Warned(emptyMap(), "key").isDuplicateOnly shouldBe false
    }
}
