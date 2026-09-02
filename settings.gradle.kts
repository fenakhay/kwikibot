pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "kwikibot-root"

include(
    "kwikibot",
    "kwikibot-model",
    "kwikibot-wikitext",
    "kwikibot-net",
    "kwikibot-protocol",
    "kwikibot-client",
    "kwikibot-wikibase",
    "kwikibot-bot",
    "kwikibot-testkit",
    "kwikibot-cli",
    "kwikibot-tools",
    "kwikibot-benchmarks",
    "examples:compounds-not-linked",
)
