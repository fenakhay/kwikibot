import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    id("kwikibot.kotlin-multiplatform-common")
    id("org.jetbrains.kotlinx.kover")
}

val libs = the<LibrariesForLibs>()

kotlin {
    jvmToolchain(25)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
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
        }
    }
}

val jvmTestCompilation = kotlin.jvm().compilations.getByName("test")

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
