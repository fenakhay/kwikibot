plugins {
    id("kwikibot.kotlin-multiplatform")
    id("kwikibot.published")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kwikibot-model"))
            api(project(":kwikibot-protocol"))
            api(project(":kwikibot-wikitext"))

            implementation(libs.kotlin.logging)
        }

        // For bzip2, which Wikimedia publishes its dumps in and the JDK cannot read.
        jvmMain.dependencies { implementation(libs.commons.compress) }

        commonTest.dependencies {
            implementation(project(":kwikibot-testkit"))
            implementation(libs.ktor.client.mock)
            implementation(libs.turbine)
        }
    }
}

// WireContractTest checks every value the client sends against the surface the reference wikis report.
val apiSurface = rootProject.layout.projectDirectory.file("api-surface.tsv")

tasks.withType<Test>().configureEach {
    inputs.file(apiSurface).withPropertyName("apiSurface").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("kwikibot.apiSurface", apiSurface.asFile.absolutePath)
}

// LocalWikiTest runs only when KWIKI_MEDIAWIKI names a wiki, which .github/mediawiki/start.sh provides.
// Read through providers so that setting or clearing one invalidates the configuration cache, which would
// otherwise replay the environment of whichever run stored it.
tasks.named<Test>("liveTest") {
    listOf(
            "KWIKI_MEDIAWIKI",
            "KWIKI_MEDIAWIKI_ACCOUNT",
            "KWIKI_MEDIAWIKI_BOT",
            "KWIKI_MEDIAWIKI_BOT_PASSWORD",
        )
        .forEach { name -> environment(name, providers.environmentVariable(name).getOrElse("")) }
}

val wireRegistry = layout.buildDirectory.file("wire-registry.tsv")

val jvmTestCompilation = kotlin.jvm().compilations.getByName("test")

tasks.register<Test>("wireContractTest") {
    description = "Checks the values the client sends against api-surface.tsv, and lists them for the report."
    group = "verification"

    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    useJUnitPlatform()
    filter { includeTestsMatching("*.WireContractTest") }
    outputs.file(wireRegistry)
    systemProperty("kwikibot.wireRegistry", wireRegistry.get().asFile.absolutePath)
}
