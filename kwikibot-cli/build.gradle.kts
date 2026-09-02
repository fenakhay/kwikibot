import org.gradle.internal.os.OperatingSystem

plugins {
    id("kwikibot.kotlin-library")
    application
    alias(libs.plugins.graalvm.native)
}

configurations.runtimeClasspath {
    exclude(group = "com.github.ajalt.mordant", module = "mordant-jvm-jna")
}

dependencies {
    implementation(project(":kwikibot-bot"))
    implementation(libs.clikt)
    runtimeOnly(libs.slf4j.simple)
}

application {
    applicationName = "kwikibot"
    mainClass.set("com.fenakhay.kwikibot.cli.MainKt")
}

val os: OperatingSystem = OperatingSystem.current()

val platform =
    when {
        os.isWindows -> "windows"
        os.isMacOsX -> "macos"
        else -> "linux"
    }

val architecture =
    when (val arch = System.getProperty("os.arch")) {
        "aarch64",
        "arm64" -> "arm64"
        "amd64",
        "x86_64" -> "x64"
        else -> arch
    }

val packageVersion = version.toString().substringBefore("-")

graalvmNative {
    toolchainDetection.set(false)

    binaries {
        named("main") {
            imageName.set("kwikibot")
            mainClass.set("com.fenakhay.kwikibot.cli.MainKt")
            sharedLibrary.set(false)
            fallback.set(false)
            verbose.set(true)

            buildArgs.add("-H:+ReportExceptionStackTraces")
            buildArgs.add("--enable-url-protocols=https")
            buildArgs.add("-O2")
        }
    }

    metadataRepository {
        enabled.set(true)
    }
}

val nativeBinary =
    layout.buildDirectory.file(
        if (os.isWindows) "native/nativeCompile/kwikibot.exe" else "native/nativeCompile/kwikibot"
    )

val archiveSetup: AbstractArchiveTask.() -> Unit = {
    group = "distribution"
    description = "Packs the native kwikibot for release."

    dependsOn(tasks.named("nativeCompile"))

    archiveBaseName.set("kwikibot")
    archiveVersion.set(packageVersion)
    archiveClassifier.set("$platform-$architecture")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(nativeBinary) {
        filePermissions { unix("rwxr-xr-x") }
    }
}

val appArchive =
    if (os.isWindows) {
        tasks.register<Zip>("appArchive", archiveSetup)
    } else {
        tasks.register<Tar>("appArchive") {
            compression = Compression.GZIP
            archiveSetup()
        }
    }

tasks.named("assemble") {
    setDependsOn(dependsOn.filterNot { it == appArchive })
}
