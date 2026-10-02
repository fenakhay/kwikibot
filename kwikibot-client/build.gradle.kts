plugins {
    id("kwikibot.kotlin-library")
    id("kwikibot.published")
}

dependencies {
    api(project(":kwikibot-model"))
    api(project(":kwikibot-protocol"))
    api(project(":kwikibot-wikitext"))

    implementation(libs.ktoml.core)
    implementation(libs.ktoml.file)
    // For bzip2, which Wikimedia publishes its dumps in and the JDK cannot read. Pure Java, so it
    // also builds into a native image.
    implementation(libs.commons.compress)
    implementation(libs.kotlin.logging)

    testImplementation(project(":kwikibot-testkit"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.turbine)
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

tasks.register<Test>("wireContractTest") {
    description = "Checks the values the client sends against api-surface.tsv, and lists them for the report."
    group = "verification"

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*.WireContractTest") }
    outputs.file(wireRegistry)
    systemProperty("kwikibot.wireRegistry", wireRegistry.get().asFile.absolutePath)
}
