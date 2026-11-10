import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    id("kwikibot.kotlin-multiplatform-common")
    id("org.jetbrains.kotlinx.kover")
}

val libs = the<LibrariesForLibs>()

/**
 * Whether this machine runs [target]'s tests. Only its own platform can, so the tests of the others are not
 * compiled or linked either: macOS checks macOS, Linux checks Linux and Windows checks Windows.
 */
fun runsTestsOf(target: KotlinNativeTarget): Boolean = target.konanTarget == HostManager.host

kotlin {
    jvmToolchain(25)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjdk-release=21")
        }

        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform { excludeTags("live") }
            }
        }
    }

    macosArm64()
    linuxX64()
    mingwX64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.matching { it.buildType == NativeBuildType.RELEASE }.configureEach {
            binaryOption("preCodegenInlineThreshold", "40")
        }
        if (!runsTestsOf(this)) {
            val target = name
            compilations.named("test") { compileTaskProvider.configure { enabled = false } }
            binaries.withType<TestExecutable>().configureEach { linkTaskProvider.configure { enabled = false } }
            tasks.matching { it.name == "${target}Test" }.configureEach { enabled = false }
        }
    }

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

val implementationTitle = project.name
val implementationVersion = project.version.toString()

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to implementationTitle,
            "Implementation-Version" to implementationVersion,
        )
    }
}

kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("liveTest")
            disabledForTestTasks.add("jvm21Test")
        }
    }
}

val jvmTestCompilation = kotlin.jvm().compilations.getByName("test")

tasks.register<Test>("jvm21Test") {
    description = "Runs the JVM tests on JDK 21, the oldest the library supports."
    group = "verification"

    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    useJUnitPlatform { excludeTags("live") }
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })

    failOnNoDiscoveredTests = false
}

tasks.register<Test>("liveTest") {
    description = "Runs the tests that talk to a real wiki. Reads production wikis; writes only to test.wikipedia.org."
    group = "verification"

    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    useJUnitPlatform { includeTags("live") }
    environment("KWIKI_LIVE", "1")
    outputs.upToDateWhen { false }

    failOnNoDiscoveredTests = false
}
