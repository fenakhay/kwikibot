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
    repositories {
        mavenCentral()
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist") {
                    name = "Node.js distributions"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/yarnpkg/yarn/releases/download") {
                    name = "Yarn distributions"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.yarnpkg", "yarn") }
        }
    }
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
    "kwikibot-js",
    "kwikibot-tools",
    "kwikibot-benchmarks",
    "examples:compounds-not-linked",
)
