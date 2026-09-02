package com.fenakhay.kwikibot.client

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ApiDetectorTest {

    private val wikimedia =
        """
        <html><head>
        <link rel="EditURI" type="application/rsd+xml"
              href="//en.wiktionary.org/w/api.php?action=rsd"/>
        </head><body>…</body></html>
        """
            .trimIndent()

    @Test
    fun `a protocol-relative link takes the scheme of the page it was found on`() {
        val endpoint = ApiDetector.endpointFrom(wikimedia, "https://en.wiktionary.org/wiki/volcano")

        endpoint?.server shouldBe "en.wiktionary.org"
        endpoint?.scriptPath shouldBe "/w"
        endpoint?.apiUrl shouldBe "https://en.wiktionary.org/w/api.php"
    }

    @Test
    fun `an absolute link is used as it stands`() {
        val html = """<link rel="EditURI" href="https://wiki.example.org/mw/api.php?action=rsd"/>"""

        val endpoint = ApiDetector.endpointFrom(html, "https://wiki.example.org/wiki/Main_Page")

        endpoint?.server shouldBe "wiki.example.org"
        endpoint?.scriptPath shouldBe "/mw"
    }

    @Test
    fun `a root-relative link is resolved against the page host`() {
        val html = """<link rel="EditURI" href="/w/api.php?action=rsd"/>"""

        val endpoint = ApiDetector.endpointFrom(html, "https://wiki.example.org/wiki/Main_Page")

        endpoint?.apiUrl shouldBe "https://wiki.example.org/w/api.php"
    }

    @Test
    fun `a wiki installed at the root has no script path`() {
        val html = """<link rel="EditURI" href="https://wiki.example.org/api.php?action=rsd"/>"""

        val endpoint = ApiDetector.endpointFrom(html, "https://wiki.example.org/Main_Page")

        endpoint?.scriptPath shouldBe ""
        endpoint?.apiUrl shouldBe "https://wiki.example.org/api.php"
    }

    @Test
    fun `entities in the attribute are decoded`() {
        val html = """<link rel="EditURI" href="//example.org/w/api.php?action=rsd&amp;x=1"/>"""

        ApiDetector.endpointFrom(html, "https://example.org/wiki/A")?.apiUrl shouldBe
            "https://example.org/w/api.php"
    }

    @Test
    fun `single quotes and attribute order do not matter`() {
        val html = """<link href='//example.org/w/api.php?action=rsd' rel='EditURI'/>"""

        ApiDetector.endpointFrom(html, "https://example.org/wiki/A")?.apiUrl shouldBe
            "https://example.org/w/api.php"
    }

    @Test
    fun `a page that advertises nothing gives nothing, rather than a guess`() {
        ApiDetector.endpointFrom("<html><head></head></html>", "https://example.org/").shouldBeNull()
    }

    @Test
    fun `a link that is not an api endpoint is rejected`() {
        val html = """<link rel="EditURI" href="https://example.org/rsd.xml"/>"""

        ApiDetector.endpointFrom(html, "https://example.org/").shouldBeNull()
    }
}
