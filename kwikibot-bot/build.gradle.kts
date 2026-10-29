import groovy.json.JsonSlurper

plugins {
    id("kwikibot.kotlin-multiplatform")
    id("kwikibot.kotlin-serialization")
    id("kwikibot.published")
}

val cldrPlurals = layout.projectDirectory.file("cldr/plurals.json")
val generatedPluralSource = layout.buildDirectory.dir("generated/cldr/kotlin")

val generatePluralRules =
    tasks.register("generatePluralRules") {
        description = "Writes CLDR's cardinal plural rules out as Kotlin, from cldr/plurals.json."
        group = "build"

        val input = cldrPlurals
        val output = generatedPluralSource
        inputs.file(input).withPathSensitivity(PathSensitivity.NONE).withPropertyName("plurals")
        outputs.dir(output)

        doLast {
            val supplemental = (JsonSlurper().parse(input.asFile) as Map<*, *>)["supplemental"] as Map<*, *>
            val version = (supplemental["version"] as Map<*, *>)["_cldrVersion"]
            val cardinal = supplemental["plurals-type-cardinal"] as Map<*, *>
            val order = listOf("zero", "one", "two", "few", "many")

            val languages =
                cardinal.entries
                    .sortedBy { it.key as String }
                    .joinToString(",\n") { (language, rules) ->
                        val conditions =
                            (rules as Map<*, *>)
                                .entries
                                .map { (key, rule) ->
                                    (key as String).removePrefix("pluralRule-count-") to
                                        (rule as String).substringBefore('@').trim()
                                }
                                .filter { (category, _) -> category != "other" }
                                .sortedBy { (category, _) -> order.indexOf(category) }
                                .joinToString(", ") { (category, condition) ->
                                    "\"$category\" to \"$condition\""
                                }
                        val list =
                            if (conditions.isEmpty()) "emptyList<Pair<String, String>>()"
                            else "listOf($conditions)"
                        "        \"$language\" to $list"
                    }

            val target = output.get().asFile.resolve("com/fenakhay/kwikibot/bot/fix")
            target.mkdirs()
            target
                .resolve("PluralRuleData.kt")
                .writeText(
                    "package com.fenakhay.kwikibot.bot.fix\n\n" +
                        "/**\n" +
                        " * CLDR $version's cardinal plural rules, written out from `cldr/plurals.json`: each language's\n" +
                        " * categories besides `other`, in CLDR's order, with the condition that selects each.\n" +
                        " */\n" +
                        "internal val CARDINAL_RULES: Map<String, List<Pair<String, String>>> =\n" +
                        "    mapOf(\n" +
                        languages +
                        ",\n    )\n"
                )
        }
    }

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(generatePluralRules)

            dependencies {
                api(project(":kwikibot-client"))

                implementation(libs.kotlin.logging)
                implementation(libs.ktoml.core)
                implementation(libs.kotlin.multiplatform.diff)
            }
        }

        commonTest.dependencies {
            implementation(project(":kwikibot-testkit"))
            implementation(libs.turbine)
        }
    }
}
