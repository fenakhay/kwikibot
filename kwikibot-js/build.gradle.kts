plugins {
    id("kwikibot.kotlin-multiplatform-common")
    id("kwikibot.nodejs")
}

kotlin {
    js {
        outputModuleName.set("core")

        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        nodejs { testTask { useMocha { timeout = "60s" } } }
        useCommonJs()
        binaries.library()
        generateTypeScriptDefinitions()

        compilerOptions {
            target.set("es2015")
            freeCompilerArgs.add("-Xexport-kdoc")
        }

        compilations.named("main") {
            val packageVersion = project.version.toString()
            val engines = mapOf("node" to ">=${libs.versions.nodeMinimum.get()}")
            packageJson {
                name = "kwikibot"
                version = packageVersion
                customField("description", "Read, parse and edit MediaWiki wikis from JavaScript.")
                customField("license", "MPL-2.0")
                customField("homepage", "https://github.com/fenakhay/kwikibot")
                customField(
                    "repository",
                    mapOf("type" to "git", "url" to "git+https://github.com/fenakhay/kwikibot.git"),
                )
                customField("engines", engines)
            }
        }
    }

    sourceSets {
        jsMain {
            languageSettings.optIn("kotlin.js.ExperimentalJsExport")
        }

        jsMain.dependencies {
            implementation(project(":kwikibot-wikitext"))
            implementation(project(":kwikibot-client"))
            implementation(project(":kwikibot-wikibase"))
            implementation(project(":kwikibot-bot"))
            implementation(project(":kwikibot-fleet"))
            implementation(project(":kwikibot-testkit"))
            implementation(project(":kwikibot-bindings"))
            implementation(libs.kotlin.logging)
        }

        jsTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}

tasks
    .withType<Copy>()
    .named { it.endsWith("LibraryDistribution") }
    .configureEach {
        from(layout.settingsDirectory.file("LICENSE"))
        from(layout.projectDirectory.file("README.md"))
        exclude("kwikibot.d.ts")

        val directory = destinationDir
        val areaTable = layout.projectDirectory.file("api/areas.tsv").asFile
        inputs.file(areaTable)
        val aliases =
            """
            export type Awaitable<T> = T | PromiseLike<T>;
            export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };
            export type ApiValue = string | number | boolean | Date | null | undefined | Array<string | number | Date>;
            export type MessageValue = string | number;
            export type Ignored = unknown;
            export type Thrown = unknown;
            export type ExitStatus = number | void;
            export type Negatable<T extends string> = T | `!${'$'}{T}`;
            export type RunStatus = "queued" | "running" | "waiting" | "done" | "failed" | "cancelled" | "declined";
            export type RunLevel = "info" | "saved" | "skipped" | "error";
            export type FleetEventKind = "said" | "changed";
            export type SparqlValueType = "uri" | "literal" | "bnode" | (string & {});
            export type TitleKind = "local" | "interwiki" | "invalid";
            export type TitleProblem = "empty" | "illegal-character" | "relative-path" | "signature" | "percent-escape" | "too-long" | "talk-of-non-main" | "unknown-namespace";
            export type EditRefusal = "conflict" | "protected" | "permission-denied" | "rate-limited" | "filtered" | "captcha" | "page-changed" | "rejected";
            export type EditStatus = "saved" | "unchanged" | EditRefusal;
            export type TextScopeName = "prose" | "everywhere";
            export type StopReason = "policy" | "policy-unreachable" | "auth" | "read-only" | "configuration" | "refused";
            export type PageOutcomeKind = "missing" | "skipped" | "unchanged" | "pending" | "saved" | "refused" | "failed" | "not-attempted";
            export type ProblemKind = "refused" | "failed";
            export type NewEntityKind = "item" | "property" | "lexeme" | "form" | "sense" | "mediainfo";
            export type EntityKind = NewEntityKind | "unknown";
            export type MergeConflict = "description" | "sitelink" | "statement";
            export type FamilyKind = "wikimedia" | "single-site";
            export type LogLevel = "trace" | "debug" | "info" | "warn" | "error" | "off";
            export type TitleCase = "first-letter" | "case-sensitive";
            export type ProofreadQuality = "without-text" | "not-proofread" | "problematic" | "proofread" | "validated";
            export type DecodedPageKind = "existing" | "missing" | "invalid";
            export type SnakKind = "value" | "somevalue" | "novalue";
            export type StatementRank = "preferred" | "normal" | "deprecated";
            export type SparqlAuthKind = "none" | "cookie";
            export type ChangeType = "edit" | "new" | "log" | "categorize" | "external";
            export type ErrorKind = "timeout" | "unreachable" | "server-error" | "refused" | "rate-limited" | "maxlag" | "api" | "not-logged-in" | "login-failed" | "no-credentials" | "missing-right" | "permission-denied" | "account-blocked" | "bad-token" | "missing-page" | "not-redirect" | "unresolvable-redirect" | "bad-title" | "off-wiki" | "unsupported-content-model" | "read-only" | "unknown-site" | "missing-extension" | "version-too-old" | "unsupported" | "round-trip" | "dump-format";
            export type DumpProblem = "unexpected-eof" | "bad-checksum" | "bad-data";
            export type DataValueKind = "text" | "entity-ref" | "time" | "quantity" | "globe-coordinate" | "monolingual" | "unknown";
            export type AbuseFilterState = "enabled" | "deleted" | "private" | "protected" | "suppressed";
            export type AbuseFilterProperty = "id" | "description" | "pattern" | "actions" | "hits" | "comments" | "last-editor" | "last-edit-time" | "flags" | "status" | "private" | "protected" | "suppressed";
            export type AbuseFilterOrder = "lowest-id-first" | "highest-id-first";
            export type NewPagesReviewed = "unreviewed" | "reviewed" | "all";
            export type NewPagesOrder = "newest-first" | "oldest-first" | "newest-review-first" | "oldest-review-first";
            export type TriageStatus = "unreviewed" | "reviewed" | "patrolled" | "autopatrolled";
            export type UploadKind = "uploaded" | "warned" | "refused";
            export type CategoryMemberType = "page" | "subcategory" | "file";
            export type CategorySort = "sortkey" | "timestamp";
            export type RedirectFilter = "all" | "only-redirects" | "no-redirects";
            export type SearchScope = "text" | "title" | "near-match";
            export type SearchSort = "relevance" | "last-edit-descending" | "last-edit-ascending" | "created-descending" | "created-ascending" | "title";
            export type TimeOrder = "newest-first" | "oldest-first";
            export type LogDetailsKind = "none" | "move" | "block" | "upload" | "protect" | "rights" | "patrol" | "unknown";
            export type TagOperation = "create" | "delete" | "activate" | "deactivate";
            export type TextDirection = "left-to-right" | "right-to-left";
            export type WatchMode = "no-change" | "preferences" | "watch" | "unwatch";
            export type ParseProperty = "text" | "links" | "templates" | "categories" | "images" | "external-links" | "limit-report" | "toc-data" | "sections";
            export type ParserName = "legacy" | "parsoid";
            export type RevisionPart = "content" | "comment" | "user";
            export type NodeKind = "text" | "comment" | "template" | "argument" | "wiki-link" | "external-link" | "heading" | "html-entity" | "tag";
            export type ModuleGroup = "action" | "format" | "list" | "prop" | "meta" | (string & {});
            export type EntitySearchKind = Exclude<NewEntityKind, "mediainfo">;
            export type EventType = ChangeType | (string & {});
            export type HexMarker = "x" | "X";
            export type QuoteMark = "\"" | "'";
            export type DumpCompression = "none" | "gzip" | "bzip2";
            export type ContributionFilter = Negatable<"minor" | "new" | "top" | "patrolled" | "autopatrolled">;
            export type BlockFilter = Negatable<"account" | "ip" | "range" | "temp">;
            export type RecentChangeFilter = Negatable<"minor" | "bot" | "anon" | "redirect" | "patrolled" | "autopatrolled"> | (string & {});
            export type WatchlistFilter = Negatable<"minor" | "bot" | "anon" | "patrolled" | "autopatrolled" | "unread"> | (string & {});
            """
                .trimIndent()
        val ktorLeftover =
            Regex(
                """export declare namespace io\.ktor\.client\.engine\.js \{.*?\n\}\n""",
                RegexOption.DOT_MATCHES_ALL,
            )
        val ownClass =
            Regex(
                """^(export declare (?:abstract )?class \w+(?:<[^>]*>)?(?: extends Error)? \{)$""",
                RegexOption.MULTILINE,
            )
        doLast {
            val declarations = directory.resolve("core.d.ts")
            val written = declarations.readText()
            val definitions =
                written
                    .replace(ktorLeftover, "")
                    .replace("describeError(error: Nullable<any>)", "describeError(error: unknown)")
            if (aliases !in written) {
                val nominal = ownClass.replace(definitions) { "${it.value}\n    private readonly __nominal;" }
                declarations.writeText("${nominal.trimEnd()}\n$aliases\n")
            }
            val complete = declarations.readText()
            val values =
                Regex(
                        """^export declare (?:abstract )?(?:class|function|const|namespace) (\w+)""",
                        RegexOption.MULTILINE,
                    )
                    .findAll(complete)
                    .map { it.groupValues[1] }
                    .toSet()
            val types =
                Regex("""^export (?:declare )?(?:interface|type) (\w+)""", RegexOption.MULTILINE)
                    .findAll(complete)
                    .map { it.groupValues[1] }
                    .filterNot { it in values }
                    .toSet()
            val exported = values + types
            val areaRows =
                areaTable
                    .readLines()
                    .filter { it.isNotBlank() }
                    .map { it.split('\t') }
                    .associate { (name, areas) -> name to areas.split(',') }
            val unplaced = exported - areaRows.keys
            val stale = areaRows.keys - exported
            check(unplaced.isEmpty() && stale.isEmpty()) {
                buildString {
                    if (unplaced.isNotEmpty())
                        append("Give these exports an area in api/areas.tsv: ${unplaced.sorted()}. ")
                    if (stale.isNotEmpty())
                        append("api/areas.tsv names what the package no longer exports: ${stale.sorted()}.")
                }
            }
            val entries =
                areaRows.entries
                    .flatMap { (name, areas) ->
                        areas.map { area -> (if (area == "root") "index" else area) to name }
                    }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, names) -> names.sortedBy { it.lowercase() } }
                    .toSortedMap()
            entries.forEach { (entry, names) ->
                val shown = names.filter { it in values }
                val typed = names.filter { it in types }
                val script = buildString {
                    append("\"use strict\";\n")
                    append("const core = require(\"./core.js\");\n")
                    if (shown.isNotEmpty()) {
                        append("const { ${shown.joinToString(", ")} } = core;\n")
                        append("module.exports = { ${shown.joinToString(", ")} };\n")
                    } else {
                        append("module.exports = {};\n")
                    }
                }
                directory.resolve("$entry.js").writeText(script)
                val typing = buildString {
                    if (shown.isNotEmpty())
                        append("export { ${shown.joinToString(", ")} } from \"./core\";\n")
                    if (typed.isNotEmpty())
                        append("export type { ${typed.joinToString(", ")} } from \"./core\";\n")
                }
                directory.resolve("$entry.d.ts").writeText(typing)
            }
            val areas = entries.keys - "index"
            val manifest = directory.resolve("package.json")
            @Suppress("UNCHECKED_CAST")
            val json = groovy.json.JsonSlurper().parse(manifest) as MutableMap<String, Any?>
            json["main"] = "index.js"
            json["types"] = "index.d.ts"
            json["exports"] =
                buildMap<String, Any> {
                    put(".", mapOf("types" to "./index.d.ts", "default" to "./index.js"))
                    areas.forEach { put("./$it", mapOf("types" to "./$it.d.ts", "default" to "./$it.js")) }
                    put("./package.json", "./package.json")
                }
            json["typesVersions"] = mapOf("*" to areas.associateWith { listOf("$it.d.ts") })
            manifest.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(json)) + "\n")
        }
    }

val distribution = layout.buildDirectory.dir("dist/js/productionLibrary")
val recordedDefinitions = layout.projectDirectory.dir("api/npm")

tasks.register<Sync>("updateJsApi") {
    description =
        "Records the TypeScript definitions the package exports, as updateKotlinAbi does for Kotlin."
    group = "verification"
    dependsOn("jsNodeProductionLibraryDistribution")
    from(distribution) { include("*.d.ts") }
    into(recordedDefinitions)
}

val checkJsApi =
    tasks.register("checkJsApi") {
        description =
            "Fails when the TypeScript definitions the package exports differ from the recorded ones."
        group = "verification"
        dependsOn("jsNodeProductionLibraryDistribution")
        mustRunAfter("updateJsApi")

        val built = distribution
        val recorded = recordedDefinitions
        inputs.dir(built)
        inputs.dir(recorded)
        doLast {
            fun definitions(directory: File) =
                directory
                    .listFiles { file -> file.name.endsWith(".d.ts") }
                    .orEmpty()
                    .associate { it.name to it.readText() }
            val same = definitions(built.get().asFile) == definitions(recorded.asFile)
            check(same) { "The package's exports changed. Check nothing was removed, then run updateJsApi." }
        }
    }

tasks.named("check") {
    dependsOn(checkJsApi)
}
