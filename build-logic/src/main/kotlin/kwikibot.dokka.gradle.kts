plugins {
    id("org.jetbrains.dokka")
}

dokka {
    pluginsConfiguration.html {
        customAssets.from(rootProject.layout.projectDirectory.file("brand/logo-icon.svg"))
    }
}
