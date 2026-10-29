plugins {
    id("kwikibot.kotlin-multiplatform-base")
    id("kwikibot.nodejs")
}

kotlin {
    js {
        nodejs { testTask { useMocha { timeout = "60s" } } }
        useCommonJs()
        compilerOptions {
            target.set("es2015")
        }
    }
}
