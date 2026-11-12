plugins {
    id("kwikibot.kotlin-multiplatform")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kwikibot-wikitext"))
            api(project(":kwikibot-client"))
            api(project(":kwikibot-wikibase"))
            api(project(":kwikibot-bot"))
            api(project(":kwikibot-fleet"))
            api(project(":kwikibot-testkit"))
            implementation(libs.kotlin.logging)
        }

        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
