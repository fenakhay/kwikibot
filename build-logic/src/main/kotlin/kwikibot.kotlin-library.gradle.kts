import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("dev.detekt")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.dokka")
    `java-library`
}

val libs = the<LibrariesForLibs>()

kotlin {
    explicitApi()
    jvmToolchain(25)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

dependencies {
    "testImplementation"(platform(libs.junit.bom))
    "testImplementation"(kotlin("test"))
    "testImplementation"(libs.junit.jupiter)
    "testImplementation"(libs.kotest.assertions)
    "testImplementation"(libs.kotlinx.coroutines.test)
    "testRuntimeOnly"(libs.junit.platform.launcher)
}

tasks.named<KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

tasks.withType<Test>().configureEach {
    if (name != "liveTest") {
        useJUnitPlatform { excludeTags("live") }
    }
}

kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("liveTest")
        }
    }
}

tasks.register<Test>("liveTest") {
    description = "Runs the tests that talk to a real wiki. Reads production wikis; writes only to test.wikipedia.org."
    group = "verification"

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    environment("KWIKI_LIVE", "1")
    outputs.upToDateWhen { false }

    failOnNoDiscoveredTests = false
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt.yml"))
    basePath = rootProject.layout.projectDirectory
}
