plugins {
    id("kwikibot.kotlin-library")
    id("kwikibot.published")
}

dependencies {
    api(project(":kwikibot-client"))
    api(libs.ktor.client.mock)
    api(libs.kotlinx.coroutines.test)
}
