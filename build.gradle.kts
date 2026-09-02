import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType

plugins {
    base
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.nmcp.aggregation)
    alias(libs.plugins.kover)
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
            rule("line coverage, per module") {
                groupBy = GroupingEntityType.PACKAGE
                bound {
                    minValue = 90
                    coverageUnits = CoverageUnit.LINE
                }
            }
        }
    }
}

tasks.named("check") { dependsOn(tasks.named("koverVerify")) }

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
