plugins {
    id("kwikibot.kotlin-multiplatform")
    id("kwikibot.published")
}

val aggregated =
    listOf(
        ":kwikibot-model",
        ":kwikibot-wikitext",
        ":kwikibot-net",
        ":kwikibot-protocol",
        ":kwikibot-client",
        ":kwikibot-wikibase",
        ":kwikibot-bot",
    )

val generatedModulesDir = layout.buildDirectory.dir("generated/modules")

val generateModulesSource =
    tasks.register("generateModulesSource") {
        val output = generatedModulesDir
        val artifacts = aggregated.map { it.removePrefix(":") }

        inputs.property("artifacts", artifacts)
        outputs.dir(output)

        doLast {
            val target = output.get().asFile.resolve("com/fenakhay/kwikibot")
            target.mkdirs()
            val entries = artifacts.joinToString("") { "        \"$it\",\n" }
            target
                .resolve("Modules.kt")
                .writeText(
                    """
                    |package com.fenakhay.kwikibot
                    |
                    |/**
                    | * The artifacts the `kwikibot` umbrella brings in, by artifact id.
                    | *
                    | * It is also what every platform compiles here. Kotlin/Native writes no klib for a module
                    | * without sources (KT-47345), and without one the umbrella could not be published for macOS,
                    | * Linux or Windows.
                    | */
                    |public val KWIKIBOT_MODULES: List<String> =
                    |    listOf(
                    |"""
                        .trimMargin() + entries + "    )\n"
                )
        }
    }

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(generateModulesSource)

            dependencies { aggregated.forEach { api(project(it)) } }
        }
    }
}

dependencies {
    aggregated.forEach { "dokka"(project(it)) }
}
