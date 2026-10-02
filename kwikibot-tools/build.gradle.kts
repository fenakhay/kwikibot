plugins {
    id("kwikibot.kotlin-library")
    application
}

dependencies {
    implementation(project(":kwikibot-protocol"))
    implementation(project(":kwikibot-net"))
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.ktor.client.mock)
}

application {
    mainClass.set("com.fenakhay.kwikibot.tools.ApiSurfaceKt")
}

val surfaceFile = rootProject.layout.projectDirectory.file("api-surface.tsv")
val betaSurfaceFile = rootProject.layout.projectDirectory.file("api-surface-beta.tsv")

// Written by :kwikibot-client:wireContractTest, so the report can mark what the library sends.
val wireRegistry = rootProject.layout.projectDirectory.file("kwikibot-client/build/wire-registry.tsv")
val reports = layout.buildDirectory.dir("reports/api-surface")

tasks.register<JavaExec>("wikiApiDump") {
    group = "verification"
    description = "Rewrites api-surface.tsv from what the reference wikis report."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args(surfaceFile.asFile.absolutePath)
}

tasks.register<JavaExec>("wikiApiCheck") {
    group = "verification"
    description = "Reports how the reference wikis differ from api-surface.tsv, and fails if they do."

    dependsOn(":kwikibot-client:wireContractTest")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args(
        surfaceFile.asFile.absolutePath,
        "--check",
        "--registry",
        wireRegistry.asFile.absolutePath,
        "--report",
        reports.get().file("production.md").asFile.absolutePath,
    )
}

tasks.register<JavaExec>("wikiApiBetaDump") {
    group = "verification"
    description = "Rewrites api-surface-beta.tsv from what the beta cluster reports."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args(betaSurfaceFile.asFile.absolutePath, "--lane", "beta")
}

tasks.register<JavaExec>("wikiApiBetaCheck") {
    group = "verification"
    description =
        "Reports how the beta cluster differs from api-surface-beta.tsv. Never fails on a difference."

    dependsOn(":kwikibot-client:wireContractTest")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args(
        betaSurfaceFile.asFile.absolutePath,
        "--check",
        "--lane",
        "beta",
        "--registry",
        wireRegistry.asFile.absolutePath,
        "--report",
        reports.get().file("beta.md").asFile.absolutePath,
    )
}

val corpusFile =
    rootProject.layout.projectDirectory.file("kwikibot-wikitext/src/test/resources/wikitext-cases.json")

tasks.register<JavaExec>("wikitextCorpusDump") {
    group = "verification"
    description = "Records what MediaWiki makes of each wikitext case, for the parser to be checked against."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.fenakhay.kwikibot.tools.WikitextCorpusKt")
    args(corpusFile.asFile.absolutePath)
    // Only new cases are recorded unless asked for everything with -PcorpusAll.
    if (providers.gradleProperty("corpusAll").isPresent) args("--all")
}

val roundTripFile =
    rootProject.layout.projectDirectory.file("kwikibot-wikitext/src/test/resources/roundtrip-pages.json.gz")

tasks.register<JavaExec>("wikitextRoundTripDump") {
    group = "verification"
    description = "Records real page text from several wikis for the round-trip assertion."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.fenakhay.kwikibot.tools.RealPagesKt")
    args(roundTripFile.asFile.absolutePath)
}

val largePagesFile =
    rootProject.layout.projectDirectory.file("kwikibot-benchmarks/src/main/resources/large-pages.json.gz")

tasks.register<JavaExec>("wikitextLargePageDump") {
    group = "verification"
    description = "Records the biggest pages the wikis have, for benchmarking the parallel pre-scan."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.fenakhay.kwikibot.tools.LargePagesKt")
    args(largePagesFile.asFile.absolutePath)
}

kover {
    currentProject {
        instrumentation {
            disabledForAll = true
        }
    }
}
