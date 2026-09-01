# kwikibot

A Kotlin library for writing MediaWiki bots.

[![Maven Central](https://img.shields.io/maven-central/v/com.fenakhay.kwikibot/kwikibot)](https://central.sonatype.com/artifact/com.fenakhay.kwikibot/kwikibot)
[![CI](https://github.com/fenakhay/kwikibot/actions/workflows/ci.yml/badge.svg)](https://github.com/fenakhay/kwikibot/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Coroutines-first, immutable domain types, no hidden I/O behind property getters, and a wikitext
parser that round-trips byte for byte.

Reading and editing pages, listing and filtering them, uploads, logs, users, Wikibase entities,
event streams, XML dumps, and a bot pipeline that is a dry run by default.

## A first edit

```kotlin
val wiki = client.wiki(LangCode("en"), Family.WIKTIONARY)

val page = wiki.pages.content(wiki.ref("volcano")) ?: return
val updated = Wikitext.parse(page.text)
    .mapTemplates("col") { it.withParameter("2", "hypervolcano") }

wiki.pages.edit(page.ref) {
    text = updated.serialize()
    summary = "adding hypervolcano"
    baseRevision = page.revisionId
}
```

Parsing touches no network, and every node writes itself back exactly as it was read, so the diff
on the wiki is the parameter that changed and nothing else. `baseRevision` is what lets the wiki
refuse the edit if somebody else got there first.

## What's in it

### Pages and listings

Read one page or fifty in a request; edit, move, delete, undelete, protect, rollback, undo, watch,
purge. Section edits too, and `section = "new"` for leaving a message on a talk page. Every
refusal a wiki can return is a case of `EditOutcome` rather than an exception. `testActions` asks
whether an edit would be allowed before one is computed, and on a wiki with pending changes,
`flagged()` says whether your edit is live or queued.

Category members, backlinks, transclusions, links, search, all pages, contributions, recent
changes, logs, watchlists and files all come back as cold `Flow`s that stop paging when you stop
collecting, so `take(10)` costs one request. Filters compose on top of them: namespaces, title
patterns, subpage depth, `distinctPages`, `intersect`, `excluding`.

### Wikitext

The parser gives back exactly what it was given. Closure is decided in one pass before anything is
parsed, so it stays linear in page length and cannot be driven into backtracking.

On top of that: section outlines, template and parameter editing, category and language-link
handling, scoped text replacement that cannot reach into a link target, unified diffs, ISBN
validation, signature timestamps, and `CosmeticChanges` as named passes with nothing on by
default.

### Wikibase, streams and dumps

Items, properties, lexemes and MediaInfo arrive as a sealed `Entity`, with statements that keep
`novalue` and `somevalue` distinct. Data values survive a round trip even when the type is one
this library has never heard of, so an edit preserves what it did not touch.

Outside `api.php` there are EventStreams with resumable offsets, SPARQL against a query service,
XML dumps streamed as a `Sequence`, and PetScan and PagePile for the working lists editors
assemble by hand.

### Knowing what a wiki supports

`paramInfo` reads the wiki's own description of its API, so query limits are read rather than
assumed and a bot can check for an extension without a second round trip. `requireRight`,
`requireExtension` and `requireVersion` fail at the start of a run instead of halfway through. The
extension services (GeoData, PageImages, TextExtracts, Linter, Echo, Thanks, FlaggedRevs,
ProofreadPage and the rest) refuse politely when their extension is absent.

### Writing bots

`botRun` is a dry run by default, with bounded read and write concurrency, a fail-closed
`StopPolicy` checked before every save, and `{{nobots}}` honoured before a transform runs.
`RunLog` writes unified diffs and JSON Lines skip records, and `Progress` keeps one line on
stderr. `kwikibot-testkit` supplies `FakeWiki`, so a bot's own tests need no network.

## Design in one screen

```kotlin
val config = WikiConfig(
    userAgent = UserAgent("MyBot", "1.0", "https://en.wiktionary.org/wiki/User:MyBot"),
    throttle = Throttle(read = 100.milliseconds, write = 10.seconds),
)

WikiClient(config, credentials = Credentials.BotPassword("MyBot", "task", password)).use { client ->
    val wiki = client.wiki(LangCode("en"), Family.WIKTIONARY)   // logs in, reads site info

    wiki.botRun {
        source(wiki.lists.categoryMembers(wiki.ref("Category:English lemmas")))

        transform { page ->
            val code = Wikitext.parse(page.text)                // network-free, byte-exact
            val english = code.outline().find("English", level = 2) ?: skip("no English section")
            val derived = english.find("Derived terms") ?: skip("no derived terms section")

            val updated = code.replaceSection(
                derived,
                derived.withContent(addTerm(derived.content, "vog")),
            )
            Edit(updated.serialize(), "adding derived term")
        }

        stopPolicy = StopPolicy.page(wiki.pages, wiki.ref("User:MyBot/Stop"))
    }
}
```

`botRun` computes edits without sending them until a run sets `dryRun = false`.

There is no command base class to subclass, because how a program starts is the program's own
business. kwikibot supplies the pieces a front end drives: `botRun`, `PageSourceSpec`, the
`Flow<PageRef>` filters, `RunLog`, `Progress` and `reportTo`. Your `main` decides how they fit.
The [example bot](examples/compounds-not-linked) is a real one that runs on en.wiktionary, with
the whole assembly in one readable file.

Principles: explicit API mode everywhere, `data`/`value` classes over mutable state, sealed
hierarchies for closed sets, `Flow` for every generator, exceptions for the exceptional and
sealed results for the expected, constructor injection with no singletons.

## Installing

```kotlin
dependencies {
    implementation("com.fenakhay.kwikibot:kwikibot:1.0.0")
}
```

That is the whole library. Testing a bot wants the fakes as well:

```kotlin
testImplementation("com.fenakhay.kwikibot:kwikibot-testkit:1.0.0")
```

The modules are published separately too, for the cases where that earns something. A tool that
only parses wikitext has no reason to pull in Ktor:

```kotlin
implementation("com.fenakhay.kwikibot:kwikibot-wikitext:1.0.0")
```

They are released together, so give them the same version. Sources and KDoc are published
alongside every jar, so a debugger has something to step into and the IDE has something to show.

## Modules

| Module | Contents |
|---|---|
| `kwikibot` | The umbrella artifact: depend on this and get everything |
| `kwikibot-model` | Pure value types, zero I/O: `Title`, `Namespace`, `PageRef`, `PageContent`, `Revision`, sealed `WikiError`, `EditOutcome`, signature timestamps |
| `kwikibot-wikitext` | Standalone, dependency-free wikitext parser and editor with lossless round-trip |
| `kwikibot-net` | Ktor transport: auth, token store, throttle, maxlag, retry, disk cache |
| `kwikibot-protocol` | Typed `api.php` actions, response models, continuation |
| `kwikibot-client` | The `Wiki` handle and its services (`pages`, `lists`, `revisions`, `users`, `logs`, `files`, `extensions`, `proofread`, `meta`), plus EventStreams, SPARQL and XML dumps |
| `kwikibot-wikibase` | Entities, claims, data values |
| `kwikibot-bot` | Bot pipeline, policies, page sources and filters, `{{nobots}}`, i18n, prompts, run logs |
| `kwikibot-testkit` | `FakeWiki`, `FakePageService`, fixture loaders, for testing your bot |

Each of those is a Maven artifact. The `kwikibot` command-line tool below is not; it ships as a
binary, and there is nothing to depend on.

## The kwikibot tool

`kwikibot` answers the first question of any misbehaving bot: is it me or the wiki? It ships as a
self-contained binary with its own bundled runtime, so it needs no JDK installed.

```bash
scoop bucket add kwikibot https://github.com/fenakhay/kwikibot
scoop install kwikibot
```

```bash
brew install fenakhay/tap/kwikibot
```

Debian and RPM packages are attached to each [release](https://github.com/fenakhay/kwikibot/releases),
as are plain archives for Windows, macOS (Intel and Apple Silicon) and Linux. Unpack one and put
its directory on your path. `SHA256SUMS` covers every file. To build it yourself:
`./gradlew :kwikibot-cli:appArchive`.

```
$ kwikibot detect https://en.wiktionary.org
api:        https://en.wiktionary.org/w/api.php
server:     en.wiktionary.org
scriptPath: /w
```

`whoami` and `login` say what the wiki makes of your credentials, `config` shows the configuration
actually in effect, `get` prints a page, and `version` gives you the line to paste into a bug
report.

## Building

The published jars target Java 21, so a consumer needs JDK 21 or newer. The build itself runs on
JDK 25: `gradle/gradle-daemon-jvm.properties` pins the daemon to it and the foojay resolver
provisions one if it is absent.

```bash
./gradlew build detekt apiCheck
```

Tests never touch the network. They replay recorded API fixtures. Live tests are opt-in:

```bash
KWIKI_LIVE=1 ./gradlew liveTest
```

They read from production wikis but write **only** to `test.wikipedia.org` sandbox pages.

The wikitext parser is checked against MediaWiki rather than against another parser: 88 fragments
recorded from `action=parse`, and 230 real pages from eight wikis and namespaces that must survive
parse and serialize byte for byte. Wikitext has no specification, so MediaWiki's behaviour is the
only thing there is to be correct against.

[binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator) locks
the API surface of every published module. A public addition shows up as a diff in `*/api/*.api`,
and `./gradlew apiDump` updates it.

The benchmarks measure the parser against a committed baseline, over the same 230 real pages the
round-trip test replays:

```bash
./gradlew :kwikibot-benchmarks:compareToBaseline
```

That fails if anything got slower by more than its error bars. See
[kwikibot-benchmarks](kwikibot-benchmarks/README.md). A baseline is only comparable to a run from
the same machine.

## License

MIT. See [LICENSE](LICENSE). Release notes are in [CHANGELOG.md](CHANGELOG.md).
