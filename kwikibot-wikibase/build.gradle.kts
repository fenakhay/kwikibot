plugins {
    id("kwikibot.kotlin-multiplatform")
    id("kwikibot.kotlin-serialization")
    id("kwikibot.published")
}

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":kwikibot-client")) }

        commonTest.dependencies { implementation(project(":kwikibot-testkit")) }
    }
}
