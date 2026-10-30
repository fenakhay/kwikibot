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
            val engines = mapOf("node" to ">=${libs.versions.node.get().substringBefore('.')}")
            packageJson {
                name = "kwikibot"
                version = packageVersion
                customField("description", "Read, parse and edit MediaWiki wikis from JavaScript.")
                customField("license", "MIT")
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
