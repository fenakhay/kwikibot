plugins {
    id("kwikibot.kotlin-serialization")
    id("kwikibot.published")
}

dependencies {
    api(project(":kwikibot-client"))
    api(libs.diff.utils)

    implementation(libs.kotlin.logging)
    implementation(libs.ktoml.core)

    testImplementation(project(":kwikibot-testkit"))
    testImplementation(libs.turbine)
}
