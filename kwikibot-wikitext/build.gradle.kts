plugins {
    id("kwikibot.kotlin-multiplatform")
    id("kwikibot.published")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.xmlutil.core)
        }
    }
}
