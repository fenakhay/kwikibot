plugins {
    id("kwikibot.kotlin-serialization")
    id("kwikibot.published")
}

dependencies {
    api(project(":kwikibot-client"))

    testImplementation(project(":kwikibot-testkit"))
}
