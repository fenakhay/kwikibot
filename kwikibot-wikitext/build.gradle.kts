plugins {
    id("kwikibot.kotlin-library")
    id("kwikibot.published")
}

dependencies {
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.diff.utils)
}
