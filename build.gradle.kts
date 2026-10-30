import groovy.util.Node
import groovy.xml.XmlParser
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.nmcp.aggregation)
    alias(libs.plugins.kover)
    alias(libs.plugins.spotless) apply false
    id("kwikibot.nodejs")
}

group = "com.fenakhay.kwikibot"
version = providers.gradleProperty("releaseVersion").orNull ?: "0.0.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version
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
    ":kwikibot-cli",
    ":kwikibot-tools",
    ":kwikibot-benchmarks",
)

kover {
    reports {
        verify {
            rule("coverage overall") {
                groupBy = GroupingEntityType.APPLICATION
                bound {
                    minValue = 95
                    coverageUnits = CoverageUnit.LINE
                }
                bound {
                    minValue = 90
                    coverageUnits = CoverageUnit.BRANCH
                }
            }
        }
    }
}

val minimumCoverage = mapOf("LINE" to 95.0, "BRANCH" to 90.0)
val coverageReport = layout.buildDirectory.file("reports/kover/report.xml")

val coverageByModule = tasks.register("coverageByModule") {
    group = "verification"
    description =
        "Fails when any module covers less of its lines or branches than " +
            minimumCoverage.entries.joinToString(" and ") { (unit, percent) -> "$percent% ${unit.lowercase()}" } +
            "."

    dependsOn(tasks.named("koverXmlReport"))
    val report = coverageReport
    val minimums = minimumCoverage

    doLast {
        val root = XmlParser().parse(report.get().asFile)
        val covered = mutableMapOf<Pair<String, String>, Int>()
        val total = mutableMapOf<Pair<String, String>, Int>()

        @Suppress("UNCHECKED_CAST")
        val packages = root.children() as List<Node>
        packages.filter { it.name() == "package" }.forEach { pkg ->
            val name = (pkg.attribute("name") as String).replace('/', '.')
            val module = name.split(".").take(4).joinToString(".")

            @Suppress("UNCHECKED_CAST")
            val counters = pkg.children() as List<Node>
            counters.filter { it.name() == "counter" && it.attribute("type") in minimums }
                .forEach { counter ->
                    val key = module to counter.attribute("type") as String
                    val hit = (counter.attribute("covered") as String).toInt()
                    val missed = (counter.attribute("missed") as String).toInt()
                    covered[key] = (covered[key] ?: 0) + hit
                    total[key] = (total[key] ?: 0) + hit + missed
                }
        }

        val short = total.keys.sortedWith(compareBy({ it.first }, { it.second })).mapNotNull { key ->
            val (module, unit) = key
            val count = total.getValue(key)
            if (count == 0) return@mapNotNull null
            val percent = 100.0 * covered.getValue(key) / count
            val minimum = minimums.getValue(unit)
            logger.lifecycle("%-44s %-6s %5.1f%%".format(module, unit.lowercase(), percent))
            if (percent < minimum) "$module ${unit.lowercase()} at %.1f%% (needs $minimum%%)".format(percent)
            else null
        }

        if (short.isNotEmpty()) {
            error("below the coverage bounds: ${short.joinToString("; ")}")
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
