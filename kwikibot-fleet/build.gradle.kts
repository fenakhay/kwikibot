plugins {
    id("kwikibot.kotlin-multiplatform")
    id("kwikibot.published")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kwikibot-bot"))

            implementation(libs.kotlin.logging)
        }

        commonTest.dependencies {
            implementation(project(":kwikibot-testkit"))
            implementation(libs.ktor.client.mock)
        }
    }
}
