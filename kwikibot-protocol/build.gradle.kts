plugins {
    id("kwikibot.kotlin-multiplatform")
    id("kwikibot.kotlin-serialization")
    id("kwikibot.published")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kwikibot-model"))
            api(project(":kwikibot-net"))
            api(libs.kotlinx.serialization.json)

            implementation(libs.kotlin.logging)
        }

        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.turbine)
        }
    }
}
