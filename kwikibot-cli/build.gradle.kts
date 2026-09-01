import org.gradle.internal.os.OperatingSystem

plugins {
    id("kwikibot.kotlin-library")
    application
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

val platform = when {
    os.isWindows -> "windows"
    os.isMacOsX -> "macos"
    else -> "linux"
}

val architecture = when (val arch = System.getProperty("os.arch")) {
    "aarch64", "arm64" -> "arm64"
    "amd64", "x86_64" -> "x64"
    else -> arch
}

val jdkModules = listOf(
    "java.base",
    "java.instrument",
    "java.logging",
    "java.management",
    "java.naming",
    "java.xml",
    "jdk.crypto.ec",
    "jdk.unsupported",
)

val packageVersion = version.toString().substringBefore("-")

val jpackageDir = layout.buildDirectory.dir("jpackage")
val runtimeDir = jpackageDir.map { it.dir("runtime") }
val appImageDir = jpackageDir.map { it.dir("image") }

fun jdkTool(name: String): String {
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    val binary = if (os.isWindows) "$name.exe" else name
    return launcher.get().metadata.installationPath.dir("bin").file(binary).asFile.absolutePath
}

val clearRuntime = tasks.register<Delete>("clearRuntimeImage") {
    delete(runtimeDir)
}

val runtimeImage = tasks.register<Exec>("runtimeImage") {
    group = "distribution"
    description = "Links a JRE holding only the modules the tool uses."

    dependsOn(clearRuntime)

    executable = jdkTool("jlink")
    args(
        "--add-modules", jdkModules.joinToString(","),
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
        "--compress=zip-6",
        "--output", runtimeDir.get().asFile.absolutePath,
    )
}

val clearAppImage = tasks.register<Delete>("clearAppImage") {
    delete(appImageDir)
}

val appImage = tasks.register<Exec>("appImage") {
    group = "distribution"
    description = "Builds a self-contained kwikibot with its own runtime, needing no installed JDK."

    dependsOn(clearAppImage, runtimeImage, tasks.installDist)

    executable = jdkTool("jpackage")
    args(
        "--type", "app-image",
        "--name", "kwikibot",
        "--app-version", packageVersion,
        "--description", "Command-line tool for talking to a MediaWiki wiki",
        "--vendor", "fenakhay",
        "--copyright", "Copyright (c) 2026 fenakhay",
        "--input", layout.buildDirectory.dir("install/kwikibot/lib").get().asFile.absolutePath,
        "--main-jar", "${project.name}-$version.jar",
        "--main-class", application.mainClass.get(),
        "--runtime-image", runtimeDir.get().asFile.absolutePath,
        "--dest", appImageDir.get().asFile.absolutePath,
    )
    if (os.isWindows) args("--win-console")
}

fun linuxPackage(type: String): TaskProvider<Exec> {
    val onLinux = os.isLinux

    return tasks.register<Exec>("${type}Package") {
        group = "distribution"
        description = "Builds a .$type of the self-contained kwikibot."

        dependsOn(appImage)
        onlyIf { onLinux }

        executable = jdkTool("jpackage")
        args(
            "--type", type,
            "--name", "kwikibot",
            "--app-version", packageVersion,
            "--description", "Command-line tool for talking to a MediaWiki wiki",
            "--vendor", "fenakhay",
            "--copyright", "Copyright (c) 2026 fenakhay",
            "--app-image", appImageDir.get().dir("kwikibot").asFile.absolutePath,
            "--dest", layout.buildDirectory.dir("distributions").get().asFile.absolutePath,
            "--linux-package-name", "kwikibot",
            "--linux-app-category", "utils",
        )
    }
}

val debPackage = linuxPackage("deb")
val rpmPackage = linuxPackage("rpm")

tasks.register("linuxPackages") {
    group = "distribution"
    description = "Builds every native Linux package."
    dependsOn(debPackage, rpmPackage)
}

val archiveSetup: AbstractArchiveTask.() -> Unit = {
    group = "distribution"
    description = "Packs the self-contained kwikibot for release."

    dependsOn(appImage)

    archiveBaseName.set("kwikibot")
    archiveVersion.set(packageVersion)
    archiveClassifier.set("$platform-$architecture")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(appImageDir) {
        filesMatching(listOf("**/kwikibot", "**/bin/**", "**/lib/**/*.so", "**/*.dylib")) {
            permissions { unix("rwxr-xr-x") }
        }
    }
}

val appArchive = if (os.isWindows) {
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
