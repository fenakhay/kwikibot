package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.service.FileAnswersTest.Companion.CHUNK_SIZE
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.toByteArray
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * The upload that depends on the JVM reading a file as it stands at each chunk. Native code reads through C's
 * buffered streams and Node reads the whole file at once, so a file rewritten mid-upload is seen only here.
 */
class FileAnswersJvmTest {

    @Test
    fun `a file cut short while it uploads sends only the bytes it still has`() = runTest {
        with(FileAnswersTest()) {
            val path = tempFile("A".repeat(CHUNK_SIZE * 2).encodeToByteArray())
            val chunks = mutableListOf<String>()
            val service = service { request ->
                tokenOr(request) {
                    chunks += request.body.toByteArray().decodeToString()
                    if (chunks.size == 1) path.writeBytes("B".repeat(CHUNK_SIZE + 2).encodeToByteArray())
                    respondJson("""{"upload":{"result":"Continue","filekey":"KEY${chunks.size}"}}""")
                }
            }

            service.upload(path, file, chunkSize = CHUNK_SIZE)

            chunks[1] shouldContain "\r\nBB\r\n"
            chunks[1] shouldNotContain "BBB"
        }
    }

    @Test
    fun `a file cut short while it uploads sends no chunk past the bytes it still has`() = runTest {
        with(FileAnswersTest()) {
            val path = tempFile("A".repeat(CHUNK_SIZE * 3).encodeToByteArray())
            val chunks = mutableListOf<String>()
            val service = service { request ->
                tokenOr(request) {
                    val body = request.body.toByteArray().decodeToString()
                    if ("filesize" in body) chunks += body
                    if (chunks.size == 1) path.writeBytes("B".repeat(CHUNK_SIZE + 2).encodeToByteArray())
                    respondJson("""{"upload":{"result":"Continue","filekey":"KEY${chunks.size}"}}""")
                }
            }

            service.upload(path, file, chunkSize = CHUNK_SIZE)

            chunks.size shouldBe 2
            chunks[1] shouldContain "\r\nBB\r\n"
            chunks.forEach { it shouldNotContain "\r\n${CHUNK_SIZE * 2}\r\n" }
        }
    }
}
