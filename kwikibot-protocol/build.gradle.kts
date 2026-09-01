plugins {
    id("kwikibot.kotlin-serialization")
    id("kwikibot.published")
}

dependencies {
    api(project(":kwikibot-model"))
    api(project(":kwikibot-net"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.turbine)
}
