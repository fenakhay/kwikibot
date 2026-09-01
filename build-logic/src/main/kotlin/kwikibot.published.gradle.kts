import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    org.jetbrains.dokka
    `maven-publish`
    signing
    id("com.gradleup.nmcp")
}

java {
    withSourcesJar()
}

val javadocJar = tasks.register<Jar>("javadocJar") {
    group = "documentation"
    description = "Documentation jar built from the KDoc."
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaGeneratePublicationHtml"))
    exclude("dokka-configuration.json")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(javadocJar)

            pom {
                name.set(project.name)
                description.set(
                    "kwikibot — a Kotlin library for writing MediaWiki bots (${project.name})",
                )
                url.set("https://github.com/fenakhay/kwikibot")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("fenakhay")
                        name.set("Fenakhay")
                        url.set("https://github.com/fenakhay")
                    }
                }

                scm {
                    url.set("https://github.com/fenakhay/kwikibot")
                    connection.set("scm:git:https://github.com/fenakhay/kwikibot.git")
                    developerConnection.set("scm:git:ssh://git@github.com/fenakhay/kwikibot.git")
                }
            }
        }
    }

    repositories {
        val githubUser = providers.gradleProperty("gpr.user")
            .orElse(providers.environmentVariable("GITHUB_ACTOR"))
        val githubToken = providers.gradleProperty("gpr.token")
            .orElse(providers.environmentVariable("GITHUB_TOKEN"))

        if (githubUser.isPresent && githubToken.isPresent) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/fenakhay/kwikibot")
                credentials {
                    username = githubUser.get()
                    password = githubToken.get()
                }
            }
        }
    }
}

signing {
    val key = providers.environmentVariable("SIGNING_KEY")
    val password = providers.environmentVariable("SIGNING_PASSWORD")

    if (key.isPresent && password.isPresent) {
        useInMemoryPgpKeys(key.get(), password.get())
        sign(publishing.publications)
    }
}
