plugins {
    id("kwikibot.kotlin-library")
    application
}

dependencies {
    implementation(project(":kwikibot-protocol"))
    implementation(project(":kwikibot-net"))
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass.set("com.fenakhay.kwikibot.tools.ApiSurfaceKt")
}

val surfaceFile = rootProject.layout.projectDirectory.file("api-surface.tsv")

val wikiApiDump = tasks.register<JavaExec>("wikiApiDump") {
    group = "verification"
    description = "Rewrites api-surface.tsv from what the reference wikis report."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args(surfaceFile.asFile.absolutePath)
}

val corpusFile = rootProject.layout.projectDirectory
    .file("kwikibot-wikitext/src/test/resources/wikitext-cases.json")

tasks.register<JavaExec>("wikitextCorpusDump") {
    group = "verification"
    description = "Records what MediaWiki makes of each wikitext case, for the parser to be checked against."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.fenakhay.kwikibot.tools.WikitextCorpusKt")
    args(corpusFile.asFile.absolutePath)
}

val roundTripFile = rootProject.layout.projectDirectory
    .file("kwikibot-wikitext/src/test/resources/roundtrip-pages.json.gz")

tasks.register<JavaExec>("wikitextRoundTripDump") {
    group = "verification"
    description = "Records real page text from several wikis for the round-trip assertion."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.fenakhay.kwikibot.tools.RealPagesKt")
    args(roundTripFile.asFile.absolutePath)
}

val largePagesFile = rootProject.layout.projectDirectory
    .file("kwikibot-benchmarks/src/main/resources/large-pages.json.gz")

tasks.register<JavaExec>("wikitextLargePageDump") {
    group = "verification"
    description = "Records the biggest pages the wikis have, for benchmarking the parallel pre-scan."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.fenakhay.kwikibot.tools.LargePagesKt")
    args(largePagesFile.asFile.absolutePath)
}

tasks.register<JavaExec>("wikiApiCheck") {
    group = "verification"
    description = "Fails when the reference wikis offer something api-surface.tsv does not record."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args(surfaceFile.asFile.absolutePath, "--check")
}

kover {
    currentProject {
        instrumentation {
            disabledForAll = true
        }
    }
}

tasks.named("check") {
    setDependsOn(dependsOn.filterNot { it == wikiApiDump })
}
