package com.fenakhay.kwikibot.client.model.dump

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class OwnModuleFileTest {

    private val own = "/app/node_modules/kwikibot/kwikibot-root-kwikibot-client.js"

    private fun moduleAt(filename: String?): dynamic {
        val module: dynamic = js("({ exports: {} })")
        if (filename != null) module.filename = filename
        return module
    }

    @Test
    fun `the package's own file as Node loaded it is one a worker can run`() {
        ownModuleFile(own, moduleAt(own)) shouldBe own
    }

    @Test
    fun `a path written the way Windows writes it is recognised`() {
        val windows = "C:\\app\\kwikibot-root-kwikibot-client.js"

        ownModuleFile(windows, moduleAt(windows)) shouldBe windows
    }

    @Test
    fun `a bundler's module object, which has no filename, is not one`() {
        ownModuleFile("/app/bundle.cjs", moduleAt(null)) shouldBe null
        ownModuleFile(own, moduleAt(null)) shouldBe null
    }

    @Test
    fun `a concatenated file, which Node loaded under its own name, is not one`() {
        ownModuleFile("/app/bot.js", moduleAt("/app/bot.js")) shouldBe null
    }

    @Test
    fun `no file or no module, as in an ES module or a browser, is not one`() {
        ownModuleFile(null, moduleAt(own)) shouldBe null
        ownModuleFile(own, null) shouldBe null
        ownModuleFile(js("'/index.js'"), moduleAt("/other.js")) shouldBe null
    }
}
