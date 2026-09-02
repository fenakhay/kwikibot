plugins {
    id("kwikibot.kotlin-library")
    id("kwikibot.published")
}

val generatedVersionDir = layout.buildDirectory.dir("generated/version")
val libraryVersion = project.version.toString()

val generateVersionSource =
    tasks.register("generateVersionSource") {
        val output = generatedVersionDir
        val value = libraryVersion

        inputs.property("version", value)
        outputs.dir(output)

        doLast {
            val target = output.get().asFile.resolve("com/fenakhay/kwikibot/net")
            target.mkdirs()
            target
                .resolve("BuildVersion.kt")
                .writeText(
                    """
            package com.fenakhay.kwikibot.net

            internal const val BUILD_VERSION: String = "$value"
            """
                        .trimIndent() + "\n"
                )
        }
    }

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateVersionSource)
}

dependencies {
    api(project(":kwikibot-model"))
    api(libs.ktor.client.core)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlin.logging)

    testImplementation(libs.ktor.client.mock)
}
