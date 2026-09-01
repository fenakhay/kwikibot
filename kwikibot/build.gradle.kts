plugins {
    id("kwikibot.kotlin-library")
    id("kwikibot.published")
}

val aggregated = listOf(
    ":kwikibot-model",
    ":kwikibot-wikitext",
    ":kwikibot-net",
    ":kwikibot-protocol",
    ":kwikibot-client",
    ":kwikibot-wikibase",
    ":kwikibot-bot",
)

dependencies {
    aggregated.forEach { module ->
        api(project(module))
        "dokka"(project(module))
    }
}
