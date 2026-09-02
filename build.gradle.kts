import groovy.util.Node
import groovy.xml.XmlParser
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType

plugins {
    base
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.nmcp.aggregation)
    alias(libs.plugins.kover)
    alias(libs.plugins.spotless) apply false
}

group = "com.fenakhay.kwikibot"
val describedVersion = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "v[0-9]*", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().removePrefix("v") }.filter { it.isNotEmpty() }

version = runCatching {
    providers.gradleProperty("releaseVersion").orElse(describedVersion).get()
}.getOrElse { "0.0.0-SNAPSHOT" }

subprojects {
    group = rootProject.group
    version = rootProject.version
}

apiValidation {
    ignoredProjects += listOf("kwikibot-cli", "kwikibot-tools", "kwikibot-benchmarks", "compounds-not-linked")
}

nmcpAggregation {
    centralPortal {
        username = providers.environmentVariable("CENTRAL_PORTAL_USERNAME")
        password = providers.environmentVariable("CENTRAL_PORTAL_PASSWORD")
        publishingType = "USER_MANAGED"
    }
}

val covered = listOf(
    ":kwikibot-model",
    ":kwikibot-wikitext",
    ":kwikibot-net",
    ":kwikibot-protocol",
    ":kwikibot-client",
    ":kwikibot-wikibase",
    ":kwikibot-bot",
    ":kwikibot-testkit",
)

kover {
    reports {
        verify {
            rule("line coverage overall") {
                groupBy = GroupingEntityType.APPLICATION
                bound {
                    minValue = 90
                    coverageUnits = CoverageUnit.LINE
                }
            }
        }
    }
}

val minimumCoverage = 90.0
val coverageReport = layout.buildDirectory.file("reports/kover/report.xml")

val coverageByModule = tasks.register("coverageByModule") {
    group = "verification"
    description = "Fails when any module covers less than $minimumCoverage% of its lines."

    dependsOn(tasks.named("koverXmlReport"))
    val report = coverageReport
    val minimum = minimumCoverage

    doLast {
        val root = XmlParser().parse(report.get().asFile)
        val covered = mutableMapOf<String, Int>()
        val total = mutableMapOf<String, Int>()

        @Suppress("UNCHECKED_CAST")
        val packages = root.children() as List<Node>
        packages.filter { it.name() == "package" }.forEach { pkg ->
            val name = (pkg.attribute("name") as String).replace('/', '.')
            val module = name.split(".").take(4).joinToString(".")

            @Suppress("UNCHECKED_CAST")
            val counters = pkg.children() as List<Node>
            counters.filter { it.name() == "counter" && it.attribute("type") == "LINE" }
                .forEach { counter ->
                    val hit = (counter.attribute("covered") as String).toInt()
                    val missed = (counter.attribute("missed") as String).toInt()
                    covered[module] = (covered[module] ?: 0) + hit
                    total[module] = (total[module] ?: 0) + hit + missed
                }
        }

        val short = total.keys.sorted().mapNotNull { module ->
            val lines = total.getValue(module)
            val percent = 100.0 * covered.getValue(module) / lines
            logger.lifecycle("%-44s %5.1f%%".format(module, percent))
            if (percent < minimum) "$module is at %.1f%%".format(percent) else null
        }

        if (short.isNotEmpty()) {
            error("below $minimum% line coverage: ${short.joinToString("; ")}")
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("koverVerify"), coverageByModule)
}

dependencies {
    covered.forEach { kover(project(it)) }

    nmcpAggregation(project(":kwikibot"))
    nmcpAggregation(project(":kwikibot-model"))
    nmcpAggregation(project(":kwikibot-wikitext"))
    nmcpAggregation(project(":kwikibot-net"))
    nmcpAggregation(project(":kwikibot-protocol"))
    nmcpAggregation(project(":kwikibot-client"))
    nmcpAggregation(project(":kwikibot-wikibase"))
    nmcpAggregation(project(":kwikibot-bot"))
    nmcpAggregation(project(":kwikibot-testkit"))
}
