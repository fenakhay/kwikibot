plugins {
    id("kwikibot.kotlin-library")
    id("kwikibot.published")
}

dependencies {
    api(libs.kotlinx.datetime)

    testImplementation(libs.kotlinx.serialization.json)
}
