plugins {
    id("kwikibot.kotlin-multiplatform")
    id("kwikibot.published")
}

kotlin {
    sourceSets {
        commonMain.dependencies { api(libs.kotlinx.datetime) }

        commonTest.dependencies { implementation(libs.kotlinx.serialization.json) }
    }
}
