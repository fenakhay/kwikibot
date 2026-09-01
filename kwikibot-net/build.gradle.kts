plugins {
    id("kwikibot.kotlin-library")
    id("kwikibot.published")
}

dependencies {
    api(project(":kwikibot-model"))
    api(libs.ktor.client.core)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlin.logging)

    testImplementation(libs.ktor.client.mock)
}
