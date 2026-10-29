plugins {
    id("kwikibot.kotlin-multiplatform")
    id("kwikibot.published")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kwikibot-client"))
            api(libs.ktor.client.mock)
            api(libs.kotlinx.coroutines.test)
        }
    }
}
