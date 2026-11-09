plugins {
    id("kwikibot.kotlin-multiplatform-common")
    id("kwikibot.nodejs")
}

kotlin {
    js {
        outputModuleName.set("kwikibot")

        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        nodejs { testTask { useMocha { timeout = "60s" } } }
        useCommonJs()
        binaries.library()
        generateTypeScriptDefinitions()

        compilerOptions {
            target.set("es2015")
            freeCompilerArgs.add("-Xexport-kdoc")
        }

        compilations.named("main") {
            val packageVersion = project.version.toString()
            val engines = mapOf("node" to ">=${libs.versions.nodeMinimum.get()}")
            packageJson {
                name = "kwikibot"
                version = packageVersion
                customField("description", "Read, parse and edit MediaWiki wikis from JavaScript.")
                customField("license", "MPL-2.0")
                customField("homepage", "https://github.com/fenakhay/kwikibot")
                customField(
                    "repository",
                    mapOf("type" to "git", "url" to "git+https://github.com/fenakhay/kwikibot.git"),
                )
                customField("engines", engines)
            }
        }
    }

    sourceSets {
        jsMain {
            languageSettings.optIn("kotlin.js.ExperimentalJsExport")
        }

        jsMain.dependencies {
            implementation(project(":kwikibot-wikitext"))
            implementation(project(":kwikibot-client"))
            implementation(project(":kwikibot-wikibase"))
            implementation(project(":kwikibot-bot"))
            implementation(project(":kwikibot-fleet"))
            implementation(libs.kotlin.logging)
        }

        jsTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}

tasks
    .withType<Copy>()
    .named { it.endsWith("LibraryDistribution") }
    .configureEach {
        from(layout.settingsDirectory.file("LICENSE"))
        from(layout.projectDirectory.file("README.md"))
    }

val typeScriptDefinitions = layout.buildDirectory.file("dist/js/productionLibrary/kwikibot.d.ts")
val recordedDefinitions = layout.projectDirectory.file("api/kwikibot.d.ts")

tasks.register("updateJsApi") {
    description =
        "Records the TypeScript definitions the package exports, as updateKotlinAbi does for Kotlin."
    group = "verification"
    dependsOn("jsNodeProductionLibraryDistribution")

    val built = typeScriptDefinitions
    val recorded = recordedDefinitions
    doLast { built.get().asFile.copyTo(recorded.asFile, overwrite = true) }
}

val checkJsApi =
    tasks.register("checkJsApi") {
        description =
            "Fails when the TypeScript definitions the package exports differ from the recorded ones."
        group = "verification"
        dependsOn("jsNodeProductionLibraryDistribution")

        val built = typeScriptDefinitions
        val recorded = recordedDefinitions
        inputs.file(built)
        inputs.file(recorded)
        doLast {
            val same = built.get().asFile.readText() == recorded.asFile.readText()
            check(same) { "The package's exports changed. Check nothing was removed, then run updateJsApi." }
        }
    }

tasks.named("check") {
    dependsOn(checkJsApi)
}
