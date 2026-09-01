plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.detekt.plugin)
    implementation(libs.kover.plugin)
    implementation(libs.dokka.plugin)
    implementation(libs.nmcp.plugin)
}
