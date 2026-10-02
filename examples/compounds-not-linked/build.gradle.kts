plugins {
    id("kwikibot.kotlin-library")
    application
}

dependencies {
    implementation(project(":kwikibot-bot"))
    // The library logs through SLF4J and ships no binding, so a program chooses one. Without one, SLF4J
    // prints a warning and drops every log line.
    runtimeOnly(libs.slf4j.simple)

    testImplementation(project(":kwikibot-testkit"))
    testImplementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("com.fenakhay.kwikibot.examples.compounds.CompoundsKt")
    applicationName = "compounds"
}
