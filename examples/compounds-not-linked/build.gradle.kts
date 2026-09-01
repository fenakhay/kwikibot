plugins {
    id("kwikibot.kotlin-library")
    application
}

dependencies {
    implementation(project(":kwikibot-bot"))

    testImplementation(project(":kwikibot-testkit"))
    testImplementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("com.fenakhay.kwikibot.examples.compounds.CompoundsKt")
    applicationName = "compounds"
}
