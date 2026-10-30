@file:OptIn(KotlinNativeCacheApi::class)

import java.net.URI
import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    id("kwikibot.kotlin-multiplatform-base")
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = "kwikibot"
            entryPoint = "com.fenakhay.kwikibot.cli.main"
        }
    }

    if (HostManager.hostIsLinux) {
        linuxX64()
            .binaries
            .matching { it.buildType == NativeBuildType.DEBUG }
            .configureEach {
                disableNativeCache(
                    DisableCacheInKotlinVersion.`2_5_0`,
                    "clikt and clikt-mordant both define Context.selfAndAncestors, and ld.lld refuses the two copies " +
                        "when each library is cached on its own",
                    URI("https://youtrack.jetbrains.com/issue/KT-81760"),
                )
            }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kwikibot-bot"))
            implementation(libs.clikt)
            implementation(libs.kotlin.logging)
        }

        jvmMain.dependencies {
            runtimeOnly(libs.slf4j.simple)
        }

        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}

val packageVersion = version.toString().substringBefore("-")

kotlin.targets.withType<KotlinNativeTarget>().configureEach {
    val family = konanTarget.family
    val platform =
        when (family) {
            Family.OSX -> "macos"
            Family.LINUX -> "linux"
            Family.MINGW -> "windows"
            else -> error("no archive for $family")
        }
    val classifier = "$platform-${konanTarget.architecture.name.lowercase()}"
    val executable = binaries.getExecutable(NativeBuildType.RELEASE)
    val fileName = if (family == Family.MINGW) "kwikibot.exe" else "kwikibot"

    val archiveSetup: AbstractArchiveTask.() -> Unit = {
        group = "distribution"
        description = "Packs the release kwikibot executable for $classifier."

        archiveBaseName.set("kwikibot")
        archiveVersion.set(packageVersion)
        archiveClassifier.set(classifier)
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))

        from(executable.linkTaskProvider) {
            include(executable.outputFile.name)
            rename { fileName }
            filePermissions { unix("rwxr-xr-x") }
        }
    }

    if (family == Family.MINGW) {
        tasks.register<Zip>("${name}AppArchive", archiveSetup)
    } else {
        tasks.register<Tar>("${name}AppArchive") {
            compression = Compression.GZIP
            archiveSetup()
        }
    }
}
