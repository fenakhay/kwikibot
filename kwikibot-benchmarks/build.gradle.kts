import kotlinx.benchmark.gradle.JvmBenchmarkTarget

plugins {
    id("kwikibot.kotlin-library")
    alias(libs.plugins.benchmark)
    alias(libs.plugins.kotlin.allopen)
}

dependencies {
    implementation(project(":kwikibot-wikitext"))
    implementation(project(":kwikibot-bot"))
    implementation(project(":kwikibot-testkit"))
    implementation(libs.benchmark.runtime)
    implementation(libs.kotlinx.serialization.json)
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

sourceSets {
    main {
        resources {
            srcDir(rootProject.layout.projectDirectory.dir("kwikibot-wikitext/src/test/resources"))
            include("roundtrip-pages.json.gz", "large-pages.json.gz")
        }
    }
}

benchmark {
    configurations {
        named("main") {
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "ms"
            mode = "avgt"
            reportFormat = "json"
        }

        register("quick") {
            warmups = 1
            iterations = 3
            iterationTime = 500
            iterationTimeUnit = "ms"
            outputTimeUnit = "ms"
            mode = "avgt"
            reportFormat = "text"
        }
    }

    targets {
        register("main") {
            this as JvmBenchmarkTarget
            jmhVersion = "1.37"
        }
    }
}

val baselinePath = layout.projectDirectory.file("baseline.json").asFile.absolutePath
val reportsDir = layout.buildDirectory.dir("reports/benchmarks/main")

tasks.register("recordBaseline") {
    group = "benchmark"
    description = "Copies the last mainBenchmark report over baseline.json."

    dependsOn("mainBenchmark")
    val target = File(baselinePath)
    val reports = reportsDir
    doLast {
        val report =
            requireNotNull(
                reports
                    .get()
                    .asFile
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory }
                    .maxByOrNull { it.lastModified() }
                    ?.resolve("main.json")
            ) {
                "no benchmark report found"
            }

        report.copyTo(target, overwrite = true)
        logger.lifecycle("baseline recorded from ${report.parentFile.name}")
    }
}

tasks.register<JavaExec>("compareToBaseline") {
    group = "benchmark"
    description = "Prints what changed since baseline.json, and fails if anything got slower."

    dependsOn("mainBenchmark")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.fenakhay.kwikibot.benchmarks.CompareKt")

    val baseline = baselinePath
    val reports = reportsDir
    argumentProviders.add {
        val report =
            requireNotNull(
                reports
                    .get()
                    .asFile
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory }
                    .maxByOrNull { it.lastModified() }
                    ?.resolve("main.json")
            ) {
                "no benchmark report found"
            }

        listOf(baseline, report.absolutePath)
    }
}

val allocationBaseline = layout.projectDirectory.file("allocations.json").asFile.absolutePath

tasks.register<JavaExec>("measureAllocations") {
    group = "benchmark"
    description = "Prints how many bytes each workload allocates."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.fenakhay.kwikibot.benchmarks.AllocationsKt")
}

tasks.register<JavaExec>("runRetention") {
    group = "benchmark"
    description = "Prints how much a finished run still holds."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.fenakhay.kwikibot.benchmarks.RunRetentionKt")
}

tasks.register<JavaExec>("recordAllocations") {
    group = "benchmark"
    description = "Writes the current allocation figures over allocations.json."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.fenakhay.kwikibot.benchmarks.AllocationsKt")
    args(allocationBaseline)
}

tasks.register<JavaExec>("compareFiles") {
    group = "benchmark"
    description = "Compares two JMH JSON reports named with -Pbefore and -Pafter."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.fenakhay.kwikibot.benchmarks.CompareKt")
    val before = providers.gradleProperty("before")
    val after = providers.gradleProperty("after")
    argumentProviders.add { listOf(before.get(), after.get()) }
}

kover {
    currentProject {
        instrumentation {
            disabledForAll = true
        }
    }
}
