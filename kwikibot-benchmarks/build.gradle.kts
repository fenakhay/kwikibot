import java.util.zip.GZIPInputStream
import kotlinx.benchmark.gradle.JvmBenchmarkTarget
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    id("kwikibot.kotlin-multiplatform-base")
    alias(libs.plugins.benchmark)
    alias(libs.plugins.kotlin.allopen)
}

val corpora =
    listOf(
        layout.settingsDirectory.file("kwikibot-wikitext/src/commonTest/resources/roundtrip-pages.json.gz"),
        layout.projectDirectory.file("corpus/large-pages.json.gz"),
    )
val unpackedCorpus = layout.buildDirectory.dir("corpus")
val generatedCorpusSource = layout.buildDirectory.dir("generated/corpus")

val prepareCorpus =
    tasks.register("prepareCorpus") {
        description =
            "Unpacks the page dumps the benchmarks run over, where every platform's build reads them."
        group = "benchmark"

        val sources = corpora
        val target = unpackedCorpus
        inputs.files(sources).withPathSensitivity(PathSensitivity.NAME_ONLY).withPropertyName("corpora")
        outputs.dir(target)

        doLast {
            val out = target.get().asFile
            out.mkdirs()
            sources.forEach { dump ->
                val file = dump.asFile
                GZIPInputStream(file.inputStream()).use { input ->
                    out.resolve(file.name.removeSuffix(".gz")).outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

val generateCorpusSource =
    tasks.register("generateCorpusSource") {
        description = "Writes where the unpacked corpus is into the benchmarks' source."
        group = "benchmark"

        val unpacked = unpackedCorpus.map { it.asFile.absolutePath }
        val output = generatedCorpusSource
        inputs.property("corpus", unpacked)
        outputs.dir(output)

        doLast {
            val target = output.get().asFile.resolve("com/fenakhay/kwikibot/benchmarks")
            target.mkdirs()
            val directory = unpacked.get().replace("\\", "\\\\")
            target
                .resolve("CorpusDirectory.kt")
                .writeText(
                    "package com.fenakhay.kwikibot.benchmarks\n\n" +
                        "/** Where the build unpacked the page dumps, absolute so a benchmark finds it from anywhere. */\n" +
                        "internal const val CORPUS_DIRECTORY: String =\n    \"$directory\"\n"
                )
        }
    }

kotlin {
    explicitApi = ExplicitApiMode.Disabled

    sourceSets {
        commonMain {
            kotlin.srcDir(generateCorpusSource)

            dependencies {
                implementation(project(":kwikibot-wikitext"))
                implementation(libs.benchmark.runtime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.io.core)
            }
        }
        jvmMain.dependencies {
            implementation(project(":kwikibot-bot"))
            implementation(project(":kwikibot-testkit"))
        }
    }
}

tasks.withType<AbstractTestTask>().configureEach { dependsOn(prepareCorpus) }

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
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
        register("jvm") {
            this as JvmBenchmarkTarget
            jmhVersion = "1.37"
        }
        register("macosArm64")
    }
}

tasks.named { it.endsWith("Benchmark") }.configureEach { dependsOn(prepareCorpus) }

val jvmRuntime =
    files(
        kotlin.jvm().compilations.named("main").map { it.output.allOutputs },
        kotlin.jvm().compilations.named("main").map { it.runtimeDependencyFiles },
    )

val baselinePath = layout.projectDirectory.file("baseline.json").asFile.absolutePath
val reportsDir = layout.buildDirectory.dir("reports/benchmarks/main")

tasks.register("recordBaseline") {
    group = "benchmark"
    description = "Copies the last jvmBenchmark report over baseline.json, without the path of the JVM."

    dependsOn("jvmBenchmark")
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
                    ?.resolve("jvm.json")
            ) {
                "no benchmark report found"
            }

        val jvm = Regex(""""jvm" : "[^"]*"""")
        val recorded = report.readText().replace(jvm) { """"jvm" : "${'$'}JAVA_HOME/bin/java"""" }

        target.writeText(recorded)
        logger.lifecycle("baseline recorded from ${report.parentFile.name}")
    }
}

tasks.register<JavaExec>("compareToBaseline") {
    group = "benchmark"
    description = "Prints what changed since baseline.json, and fails if anything got slower."

    dependsOn("jvmBenchmark")
    classpath = jvmRuntime
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
                    ?.resolve("jvm.json")
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

    classpath = jvmRuntime
    mainClass.set("com.fenakhay.kwikibot.benchmarks.AllocationsKt")
}

tasks.register<JavaExec>("runRetention") {
    group = "benchmark"
    description = "Prints how much a finished run still holds."

    classpath = jvmRuntime
    mainClass.set("com.fenakhay.kwikibot.benchmarks.RunRetentionKt")
}

tasks.register<JavaExec>("recordAllocations") {
    group = "benchmark"
    description = "Writes the current allocation figures over allocations.json."

    classpath = jvmRuntime
    mainClass.set("com.fenakhay.kwikibot.benchmarks.AllocationsKt")
    args(allocationBaseline)
}

tasks.register<JavaExec>("compareFiles") {
    group = "benchmark"
    description = "Compares two JMH JSON reports named with -Pbefore and -Pafter."

    classpath = jvmRuntime
    mainClass.set("com.fenakhay.kwikibot.benchmarks.CompareKt")
    val before = providers.gradleProperty("before")
    val after = providers.gradleProperty("after")
    argumentProviders.add { listOf(before.get(), after.get()) }
}
