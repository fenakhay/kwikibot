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
    implementation(libs.kotlin.logging)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.turbine)
}
