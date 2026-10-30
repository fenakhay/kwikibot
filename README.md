<h1>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="brand/logo-light.svg">
    <img alt="kwikibot" src="brand/logo-dark.svg" height="48">
  </picture>
</h1>

A Kotlin Multiplatform library for MediaWiki bots and tools: read and edit any wiki, parse wikitext
that writes back byte for byte, and run bots that are dry runs until told otherwise. On the JVM,
macOS, Linux, Windows and JavaScript, with an npm package and a command-line tool.

[![Maven Central](https://img.shields.io/maven-central/v/com.fenakhay.kwikibot/kwikibot)](https://central.sonatype.com/artifact/com.fenakhay.kwikibot/kwikibot)
[![npm](https://img.shields.io/npm/v/@fenakhay/kwikibot)](https://www.npmjs.com/package/@fenakhay/kwikibot)
[![Kotlin](https://img.shields.io/badge/kotlin-2.5.0--Beta2-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)
[![CI](https://github.com/fenakhay/kwikibot/actions/workflows/ci.yml/badge.svg)](https://github.com/fenakhay/kwikibot/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/fenakhay/kwikibot/branch/main/graph/badge.svg)](https://codecov.io/gh/fenakhay/kwikibot)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

- **One API on five platforms.** The JVM, macOS on Apple Silicon, Linux (x64), Windows (x64) and
  JavaScript, from the same `commonMain` code.
- **Pages and listings.** Read one page or fifty in a request; edit, move, protect, undo; walk
  categories, backlinks, searches and logs as cold `Flow`s that page only as far as you collect.
- **A wikitext parser that reads pages as MediaWiki does.** Templates, links, headings and tags
  found where MediaWiki finds them, edits that change only what they touch, and text that writes
  back exactly as it was read.
- **Bots that save nothing until told to.** Conflict-checked saves and, once set, a stop page and
  `{{nobots}}`; resumable state, diffs and skip logs.
- **More than `api.php`.** Wikibase entities, SPARQL, EventStreams, PetScan, PagePile and, on the
  JVM, XML dumps.
- **Tests without a network.** `kwikibot-testkit` gives your bot a fake wiki.

## Contents

- [Quick start](#quick-start)
- [Installing](#installing)
- [Platforms](#platforms)
- [Opening a wiki](#opening-a-wiki)
- [Reading and editing](#reading-and-editing)
- [Wikitext](#wikitext)
- [Writing a bot](#writing-a-bot)
- [Wikibase, SPARQL, streams and dumps](#wikibase-sparql-streams-and-dumps)
- [Testing a bot](#testing-a-bot)
- [Configuration and logging](#configuration-and-logging)
- [Running on Wikimedia wikis](#running-on-wikimedia-wikis)
- [Coming from pywikibot](#coming-from-pywikibot)
- [From JavaScript and TypeScript](#from-javascript-and-typescript)
- [The kwikibot tool](#the-kwikibot-tool)
- [Compatibility](#compatibility)
- [Building](#building)

## Quick start

```kotlin
import com.fenakhay.kwikibot.client.Family
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.wikitext.Wikitext

suspend fun main() {
    val config = WikiConfig(UserAgent("MyBot", "1.0", "https://en.wiktionary.org/wiki/User:MyBot"))

    WikiClient(config).use { client ->
        val wiki = client.wiki(LangCode("en"), Family.WIKTIONARY)
        val page = wiki.pages.content(wiki.ref("volcano")) ?: return

        val code = Wikitext.parse(page.text)
        println("${page.ref.title} at revision ${page.revisionId}")
        println("${code.templates().size} templates, ${code.wikilinks().size} links")
        for (template in code.templates("col")) {
            println(template.parameters.drop(1).map { it.value.text.trim() })
        }
    }
}
```

```
volcano at revision 93733515
287 templates, 15 links
[hypervolcano, paleovolcano, pneumonoultramicroscopicsilicovolcanoconiosis, vog, volcanist, …]
```

The user agent is required, and its contact must be somewhere the wiki's operators can reach you:
Wikimedia blocks requests without one. Reading needs no account, and parsing touches no network.

## Installing

kwikibot is on Maven Central. Gradle picks the build for each platform from one set of
coordinates, so a multiplatform project depends on it once, in `commonMain`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.fenakhay.kwikibot:kwikibot:1.4.0")
        }
        commonTest.dependencies {
            implementation("com.fenakhay.kwikibot:kwikibot-testkit:1.4.0")
        }
    }
}
```

A JVM-only project uses the same coordinates:

```kotlin
dependencies {
    implementation("com.fenakhay.kwikibot:kwikibot:1.4.0")
    testImplementation("com.fenakhay.kwikibot:kwikibot-testkit:1.4.0")
}
```

With a version catalog:

```toml
[versions]
kwikibot = "1.4.0"

[libraries]
kwikibot = { module = "com.fenakhay.kwikibot:kwikibot", version.ref = "kwikibot" }
kwikibot-testkit = { module = "com.fenakhay.kwikibot:kwikibot-testkit", version.ref = "kwikibot" }
```

```kotlin
implementation(libs.kwikibot)
```

<details>
<summary>Maven</summary>

Maven does not read Gradle's metadata, so a Maven project names the JVM artifact, which ends in
`-jvm`:

```xml
<dependency>
  <groupId>com.fenakhay.kwikibot</groupId>
  <artifactId>kwikibot-jvm</artifactId>
  <version>1.4.0</version>
</dependency>
```

</details>

### Requirements

| | Needs |
|---|---|
| Kotlin/JVM project | Kotlin 2.4 or newer, and JDK 21 or newer to run. Gradle resolves `kotlin-stdlib` to 2.5.0-Beta2, the version kwikibot is built with |
| Kotlin/Native or Kotlin/JS project | Kotlin 2.5.0-Beta2 or newer: Kotlin 2.4 cannot read klibs built by 2.5 |
| npm package | Node 26 or newer |
| A wiki | MediaWiki 1.39 or newer, see [Compatibility](#compatibility) |

**On the JVM**, the library needs one more thing from you: it logs through SLF4J and ships no
binding. Without one, SLF4J prints a warning and drops every line, retries and logins included. A
command-line bot adds `runtimeOnly("org.slf4j:slf4j-simple:2.0.20")`. See [Logging](#logging).

On JavaScript, `wiki.signatures()` reads a wiki's time zone, such as `Europe/Paris`, through the
runtime's `Intl`, which Node and browsers ship, so nothing loads a time zone database.
kotlinx-datetime's own `TimeZone.of` knows no named zone there until `@js-joda/timezone` is
loaded, which matters only to code that calls it.

### Modules

`kwikibot` brings in every module but the test kit. Each is also published on its own, for a
program that wants less: a tool that only parses wikitext has no reason to pull in Ktor.

| Module | What it holds |
|---|---|
| `kwikibot` | Everything below except the test kit |
| `kwikibot-model` | Value types with no I/O: `Title`, `Namespace`, `PageRef`, `PageContent`, `Revision`, the sealed `WikiError` and `EditOutcome`, signature timestamps |
| `kwikibot-wikitext` | The wikitext parser and editor, with no dependencies of its own |
| `kwikibot-net` | The HTTP transport: user agent, credentials and login, tokens, throttle, maxlag, retries, response cache |
| `kwikibot-protocol` | Reading `api.php` responses: errors and warnings, site info, `paraminfo`, continuation, page decoding |
| `kwikibot-client` | `WikiClient`, the `Wiki` handle and its services, EventStreams, SPARQL, and XML dumps on the JVM |
| `kwikibot-wikibase` | Entities, statements and data values |
| `kwikibot-bot` | `botRun`, `botMain`, page sources and filters, `{{nobots}}`, stop pages, run state, diffs, logs, plural forms, prompts |
| `kwikibot-testkit` | `FakeWiki`, `FakePageService`, `MockTransport` and `FailOnDeprecation`, for testing a bot |

Depend on every module at the same version. Sources and KDoc are published with each artifact, so
the IDE has documentation to show and a debugger has code to step into. On Maven, `kwikibot-js` is
the umbrella's Kotlin/JS artifact, for Kotlin projects; the npm package is a separate thing,
[below](#from-javascript-and-typescript).

## Platforms

Every module builds for every platform below, with one API. Where they differ:

| | JVM | macOS, Linux, Windows | Node | Browser |
|---|---|---|---|---|
| HTTP engine | OkHttp | Darwin, Curl, WinHTTP | fetch | fetch |
| Files: `DiskCache`, file uploads and downloads, `BotConfig`, `RunState`, diff and skip logs | ✓ | ✓ | ✓ | – |
| XML dumps (`XmlDump`, `DumpPage`) | ✓ | – | – | – |
| `botMain`, `TerminalPrompt`, environment variables | ✓ | ✓ | ✓ | – |
| Tested by `./gradlew check` and CI | ✓ | ✓ | ✓ | the npm package, in headless Chrome |

Paths are `kotlinx.io.files.Path`, except `XmlDump`'s, which are `java.nio.file.Path`. The file APIs
fail in a browser, which has no filesystem; there, environment variables read as unset and a
prompt reads no answer. On JavaScript only the
request timeout applies, since fetch has no connect or socket timeout, and every request also
carries `Api-User-Agent`, because a browser will not send a page's own `User-Agent`. From a page on
a site other than the wiki's, requests carry `origin=*`, which MediaWiki answers as it would a
logged-out reader. The KDoc of each connection setting says which engines honour it.

## Opening a wiki

```kotlin
val config = WikiConfig(
    userAgent = UserAgent("MyBot", "1.0", "https://en.wiktionary.org/wiki/User:MyBot"),
    throttle = Throttle(read = 100.milliseconds, write = 10.seconds),
)

WikiClient(config, Credentials.fromEnvironment()).use { client ->
    val wiktionary = client.wiki(LangCode("en"), Family.WIKTIONARY)
    val wikidata = client.wiki(ApiEndpoint("www.wikidata.org"))
    val yours = client.wiki(ApiEndpoint("wiki.example.org", scriptPath = "/w"))
}
```

A `WikiClient` holds the connections, the login and the pacing, and hands out a `Wiki` for each
site it opens. Opening one logs in if there are credentials and reads the site's namespaces and
settings. `wiki.identity` says who the wiki thinks you are.

`Family` names the Wikimedia projects: Wikipedia, Wiktionary, Wikisource, Wikiquote, Wikibooks,
Wikinews, Wikiversity, Wikivoyage, Commons, Wikidata, Meta, mediawiki.org and test.wikipedia.org.
Any other wiki is an `ApiEndpoint`. When all you have is a URL, `ApiDetector.detect` finds its
`api.php`, which is what `kwikibot detect` does.

### Credentials

| | For | Where it comes from |
|---|---|---|
| `Credentials.BotPassword(account, botName, password)` | Most bots | [Special:BotPasswords](https://www.mediawiki.org/wiki/Manual:Bot_passwords) on the wiki. `account` is the account name alone; kwikibot adds `@botName` itself |
| `Credentials.OAuth2(accessToken)` | A bot that runs as its owner and never asks anyone else to log in | An [owner-only OAuth 2 consumer](https://www.mediawiki.org/wiki/OAuth/Owner-only_consumers), proposed at [Special:OAuthConsumerRegistration](https://meta.wikimedia.org/wiki/Special:OAuthConsumerRegistration/propose/oauth2) on Meta. The token is shown once |
| `Credentials.Anonymous` | Reading | The default |

Give a bot password the grants "High-volume (bot) access" and "Edit existing pages", and whatever
else the bot does, such as creating, moving or uploading. Without the first, even a flagged account
logs in without the `bot` and `apihighlimits` rights: its edits are not marked as a bot's, and a
query names at most 50 pages instead of 500.

An OAuth 2 token goes on every request as a bearer header, and the client keeps the session
cookies the wiki sets and sends them back with it, which Wikimedia asks of owner-only tokens so that
the account's own rate limit applies. Cookies are kept per client, so each account needs a
`WikiClient` of its own.

Keep secrets out of source and out of shell history. `Credentials.fromEnvironment()` reads
`KWIKIBOT_OAUTH_TOKEN`, or else `KWIKIBOT_ACCOUNT`, `KWIKIBOT_BOT_NAME` and `KWIKIBOT_PASSWORD`. With
none of them set it gives `Anonymous`, and with some but not all it fails rather than guess. That is
for a program that builds its own `WikiClient`; `botMain` and the `kwikibot` tool take the account
from `kwikibot.toml`, which names the variable to read a secret from instead of holding it.

Every request from a logged-in client asserts its account. When the wiki ends the session, the
client logs back in and repeats the request instead of carrying on anonymously, up to
`WikiConfig.relogins` times in ten minutes.

## Reading and editing

```kotlin
val page = wiki.pages.content(wiki.ref("volcano")) ?: return
val updated = Wikitext.parse(page.text)
    .mapTemplates("col") { it.withParameter("title", "terms derived from volcano") }

val outcome = wiki.pages.edit(page.ref) {
    text = updated.serialize()
    summary = "titling the derived terms"
    baseRevision = page.revisionId
}

when (outcome) {
    is EditOutcome.Saved -> println("saved as ${outcome.revision}")
    is EditOutcome.NoChange -> println("already done")
    is EditOutcome.Refused -> println("refused: ${outcome.detail}")
}
```

The diff on the wiki is the parameter that changed and nothing else. `baseRevision` is what lets
the wiki refuse the save if somebody else edited the page since it was read; without it, the later
edit silently wins.

Every refusal a wiki can return is a case of `EditOutcome.Refused` rather than an exception:
`Conflict`, `Protected`, `Filtered`, `RateLimited`, `CaptchaRequired`, `PermissionDenied`,
`PageStateChanged` and `Rejected`, each saying whether trying again could help. Failures that are
not about the edit, such as a wiki that cannot be reached, are a sealed `WikiError`.

`wiki.pages` also moves, deletes, undeletes, protects, rolls back, undoes, purges and watches, and
edits sections: `section = "new"` leaves a message on a talk page. `testActions` asks whether an
edit would be allowed before one is computed, and on a wiki with pending changes,
`wiki.extensions.flagged(refs)` says whether an edit is live or queued.

### Listings

```kotlin
wiki.lists.categoryMembers(wiki.ref("Category:English lemmas"), namespaces = setOf(Namespace.MAIN))
    .take(10)
    .collect { println(it.title) }
```

Category members, backlinks, transclusions, links, search, prefix search, all pages, special
pages, watchlists, contributions, recent changes, logs and page histories are all cold `Flow`s.
They fetch the next batch only when collection asks for it, so `take(10)` costs one request.

`inNamespaces`, `titleMatching`, `subpageDepthAtMost` and `distinctPages` compose on top of any of
them, filtering what arrives, so a narrow filter can page through a whole category before it finds
ten. A listing's own parameters, such as `namespaces` and `startSortKey`, filter on the wiki
instead. `intersect` and `excluding` read their second flow in full before the first.

### Knowing what a wiki supports

`wiki.paramInfo` reads the wiki's own description of its API: the limits an account gets, the
values a parameter takes, and which of those are deprecated. That is how a renamed value goes out
under the new name to a wiki that knows it and under the old name to one that does not.
`requireRight`, `requireExtension` and `requireVersion` fail at the start of a run instead of
halfway through. The extension services (GeoData, PageImages, TextExtracts, Linter, Echo, Thanks,
FlaggedRevs, AbuseFilter, PageTriage, ProofreadPage and the rest) refuse when their extension is
absent.

## Wikitext

```kotlin
val code = Wikitext.parse("==English==\n===Noun===\n{{en-noun}}\n\n{{col|en|vog|tephra}}\n")

code.templates("col").single().value("2")
code.wikilinks()
val english = code.outline().find("English", level = 2)

val edited = code
    .mapTemplates("col") { it.withParameter("4", "lahar") }
    .addCategory("Volcanoes")
edited.serialize()
```

`Wikitext.parse` never fails: no input is rejected, and markup that does not parse, such as an
unclosed template or a stray bracket, stays literal text, which is what MediaWiki renders. Nor
does any input nest too deep: templates, links or tags nested a hundred thousand levels deep parse,
compare and write back like any other page, on every platform and whatever the thread's stack. Nodes
are immutable values, every edit returns a new `Markup`, and every node that was not touched keeps
the exact text it was parsed from.

The parser finds templates, parameters and headings where MediaWiki does, and ends HTML tags where
MediaWiki's paragraphs and lists end them. It is checked against MediaWiki's own parse of 243
fragments, and against 230 real pages from six wikis that must write back byte for byte. Parsing
stays linear in the length of the page, also on the pathological shapes the tests throw at it.

What a wiki is configured with changes how its wikitext reads, so a wiki can say:

```kotlin
val options = wiki.parseOptions()
val rules = wiki.titleRules()
val code = Wikitext.parseExact(page.text, options)

code.templates(listOf("l", "link"), rules)
code.ranges()[code.templates().first()]
```

`parseOptions()` holds the wiki's extension tags and URL schemes, and `titleRules()` its namespace
names, first-letter case and parser functions. With the rules, `templates` finds `{{l}}`,
`{{Template:l}}` and `{{subst:l}}` alike, and treats `{{L}}` as the same template only where the
wiki does, which Wiktionary does not. Without rules, names are compared ignoring the case of their
first letter. `Template.key` is the page a template transcludes, resolved as MediaWiki resolves
it. `parseExact` throws if the tree would not write back as given, for a bot about to save an edit
built on it. `ranges()` says where each node, parameter and attribute sits in the text.
`Wikitext.parse(text)` without options uses the settings of Wikimedia's wikis.

Templates edit themselves: `mapTemplates`, `withParameter` and `withoutParameter`. Extension
functions in `com.fenakhay.kwikibot.wikitext.ops` add section outlines and section editing,
categories and language links, and `replaceText`, which changes prose and leaves templates, link
targets, comments, tag attributes and the contents of tags such as `<pre>` alone unless its
`TextScope` says otherwise. `Isbn` validates ISBNs, `CosmeticChanges` applies named passes with
nothing on by default, and `wiki.signatures()` reads signature timestamps.

What the parser does not do is expand templates. It reads the page as written, so markup that a
template produces, such as a table row or a category, is not in the tree. `wiki.pages.expandText`
and `wiki.renderer` ask the wiki for the expanded or rendered result.

## Writing a bot

```kotlin
val report = wiki.botRun {
    source(wiki.lists.transclusions(wiki.ref("Template:old")))

    transform { page ->
        val code = Wikitext.parse(page.text)
        if (code.templates("old").isEmpty()) skip("no {{old}} left")
        Edit(code.mapTemplates("old") { it.copy(name = Markup.of("new")) }.serialize(), "old → new")
    }

    exclusionPolicy = BotPolicy(wiki.identity.name)
    stopPolicy = StopPolicy.page(wiki.pages, wiki.ref("User:MyBot/Stop"))
    onOutcome = { outcome ->
        if (outcome is PageOutcome.Pending) {
            println(Diffs.unified(outcome.before, outcome.edit.text, outcome.ref.title.toString()))
        }
    }
}

println("${report.pending} pages would change, ${report.skipped} skipped")
```

`botRun` reads pages from a source, hands each to the transform, and saves what comes back. It
computes edits without sending them until `dryRun = false`, so a new bot shows you its diffs before
it touches a page. It reads ahead with bounded concurrency and writes one page at a time. Before
saving on a Wikimedia wiki, read [Running on Wikimedia wikis](#running-on-wikimedia-wikis).

The transform returns an `Edit`, or `null` to leave the page alone, which is the normal answer:
most pages a bot looks at need nothing done. `skip(reason)` leaves it alone with a reason, which
`onOutcome` and the skip log receive and the report counts. The transform runs on
`Dispatchers.Default`, several pages at once, so anything it shares needs its own protection. An
exception from it, other than a `WikiError`, ends the run.

A saving run checks the stop policy before it starts and before every save, and fails closed.
`StopPolicy.page` lets the bot go on only while the page says `false`, so create the page with that
text before the first saving run: a missing or empty page stops the bot. A dry run does not read
it. With an `exclusionPolicy`, a page whose `{{bots}}` or `{{nobots}}` excludes the bot is skipped
before the transform runs.

### A command-line bot

```kotlin
fun main(args: Array<String>) = botMain(args) {
    val report = wiki.botRun {
        options.applyTo(this, wiki)
        config.applyTo(this)
        source(PageSourceSpec.parseAll(arguments).pages(wiki))
        transform { page -> Edit(page.text.replace("{{old", "{{new"), "old → new") }
        exclusionPolicy = BotPolicy(wiki.identity.name)
        onOutcome = options.runLog(wiki)::record
    }
    if (report.clean && !report.stopped) 0 else 1
}
```

```bash
oldtonew transcludes:Template:old --diff-log diffs.log --skip-log skips.jsonl
oldtonew transcludes:Template:old --save --limit 20 --stop-page User:MyBot/Stop
```

`botMain` needs a `kwikibot.toml`, and logs in as the account its `[login]` or `[oauth]` names,
also for a dry run, unless `botMain(args, anonymous = true)` allows reading without one. With this
file, the bot password is read from `KWIKIBOT_PASSWORD`:

```toml
[bot]
name = "MyBot"
contact = "https://en.wiktionary.org/wiki/User:MyBot"

[wiki]
lang = "en"
family = "wiktionary"

[login]
account = "MyBot"
botName = "oldtonew"
```

It reads the common flags (`--save`, `--limit`, `--stop-page`, `--diff-log`, `--skip-log`,
`--state`, `--resume`, `--trial`, `--read-delay`, `--write-delay` and the rest) and hands everything
else to the bot as `arguments`, unchecked, so a mistyped flag reaches the bot rather than an error.
Page sources are the bot's to read: `PageSourceSpec.parseAll` takes `cat:English lemmas`,
`transcludes:Template:old`, `page:volcano`, `search:…`, `file:titles.txt` and the rest, and
`options.runLog(wiki)` writes the diff and skip logs the flags name. `botMain` refuses `--save`
unless the account is in the bot group or `--trial` says the run is a trial, says on standard error
which account it runs as, and exits with the status the block returns. When the bot cannot start,
the reason is one line on standard error and the status is 1, or 2 for a common flag with a missing
or bad value.

There is no command base class to subclass, because how a program starts is the program's own
business. `botMain` is one way to assemble the pieces; `CommonOptions.parse`, `PageSourceSpec`,
`RunLog`, `Progress` and `reportTo` are there for a bot that wants its own. The
[example bot](examples/compounds-not-linked) runs on en.wiktionary and assembles its run itself,
without `botMain`, in one file.

### Long runs

A run can last hours, and the client and `botRun` are built for that:

- Replication lag, `429`, `readonly` and database errors are retried with backoff, waiting as long
  as the wiki's `Retry-After` asks when it asks for longer. Retries are logged at `INFO`, and
  `WikiConfig.listener` hears every retry, every pause the wiki asks for and every re-login.
- A run that saves reads its pages and its stop page past any response cache. Each save carries the
  revision it was computed from and `nocreate`. If someone edited the page in the meantime, the
  wiki merges the two edits or, when it cannot, refuses the save. It also refuses a save to a page
  deleted since it was read.
- `state = RunState(dir)` records each finished page, the revision it was decided on and each save
  as they happen. `resume = true` carries on from there. The record assumes the same transform, so
  after changing the bot, resume into a fresh directory; one directory belongs to one wiki.
- A run that reads far more than it edits sets `readBatch = 50`, one request for fifty pages
  instead of one each.
- `RunLog` writes unified diffs and JSON Lines skip records, and `Progress` keeps the rate, the time
  left and what the run would change (or has saved) on one line that fits the terminal.

## Wikibase, SPARQL, streams and dumps

```kotlin
val wikidata = client.wiki(ApiEndpoint("www.wikidata.org"))
val douglasAdams = wikidata.wikibase().entity(EntityId("Q42")) ?: return

println(douglasAdams.label("en"))
val instanceOf = douglasAdams.best(EntityId("P31"))?.value
if (instanceOf is DataValue.EntityRef) println(instanceOf.id)
```

Items, properties, lexemes and MediaInfo arrive as a sealed `Entity`, with statements that keep
`novalue` and `somevalue` distinct. A data value survives a round trip even when its type is one
this library has never heard of, so an edit preserves what it did not touch.

```kotlin
WikiHttpClient.create().use { http ->
    val sparql = SparqlClient(http, config.userAgent)
    val volcanoes = sparql.entityIds("SELECT ?item WHERE { ?item wdt:P31 wd:Q8072 } LIMIT 10")

    EventStreams(http, config.userAgent).recentChanges()
        .filter { it.wiki == "enwiktionary" }
        .take(5)
        .collect { println(it.title) }
}
```

SPARQL runs against the Wikidata Query Service or any other endpoint; Commons' needs an OAuth
token, given as `SparqlAuth.wcqs(token)`. Each event carries an `offset`, and passed back as
`since`, it lets a listener that restarts carry on where it stopped. PetScan and PagePile are page
sources, for the working lists editors assemble by hand. On the JVM, `XmlDump` streams a dump,
plain or compressed, as a `Sequence` of pages, or scans it on every core:

```kotlin
XmlDump.pages(Path("enwiktionary-latest-pages-articles.xml.bz2"), namespaces = setOf(0))
    .filter { "{{col|" in it.text }
    .forEach { println(it.title) }
```

## Testing a bot

```kotlin
class VolcanoBotTest {
    @Test
    fun savesTheNewText() = runTest {
        val pages = FakePageService("volcano" to "old")
        val wiki = FakeWiki(pages)

        val report = wiki.botRun {
            source(flowOf(wiki.ref("volcano")))
            transform { page -> Edit(page.text + " more", "adding") }
            dryRun = false
        }

        assertEquals(1, report.saved)
        assertEquals("old more", pages.text("volcano"))
    }
}
```

`FakeWiki` is a `Wiki` over pages held in memory, so a bot's own tests run in milliseconds and
need no network. Only the pages are faked: any other service throws unless the test passes one in,
so a test gives the run a fixed source, as above, or a `lists` of its own. That is deliberate: a
fake that answered every listing with nothing would let a bot that does nothing pass.
`FakePageService` records every edit and can be told to refuse some. `MockTransport` answers raw
API requests from JSON, for testing at the level of the protocol, and `FailOnDeprecation` turns any
deprecation warning a wiki sends into a test failure.

## Configuration and logging

### kwikibot.toml

`kwikibot init-config` writes a commented starter file. kwikibot looks for one in the working
directory, then the home directory, then at `$KWIKIBOT_CONFIG`, then in
`$XDG_CONFIG_HOME/kwikibot` (or `~/.config/kwikibot`), then in `%APPDATA%\kwikibot`.

| Section | Keys |
|---|---|
| `[bot]` | `name`, `version`, `contact`: the user agent |
| `[wiki]` | `lang` and `family`, or `server` and `scriptPath` |
| `[throttle]` | `read`, `write` |
| `[login]` | `account`, `botName`, `passwordEnv`, `optional` |
| `[oauth]` | `tokenEnv`, `username`, `optional`; used in place of `[login]` when its token is set |
| `[run]` | `readConcurrency`, `writeConcurrency`, `readBatch` |
| `[http]` | `maxConcurrentRequests`, `maxIdleConnections`, `timeout` |
| `[retry]` | `maxRetries`, `initialDelay`, `maxDelay`, `relogins` |
| `[cache]` | `path`, `ttl` |
| top level | `maxlag` |

Durations are written `"100ms"` or `"10s"`. An unknown key is a warning, not an error. Without a
`[wiki]` section the wiki is en.wiktionary, and `[oauth]` is used in place of `[login]` only when
its token is set. The file holds no secrets: `passwordEnv` and `tokenEnv` name the environment
variables to read them from, `KWIKIBOT_PASSWORD` and `KWIKIBOT_OAUTH_TOKEN` unless they say
otherwise. Since the working and home directories are searched first, `$KWIKIBOT_CONFIG` does not
override a file in either.

### Logging

The library logs through [kotlin-logging](https://github.com/oshai/kotlin-logging), each line under
the name of the class that logs it, such as `com.fenakhay.kwikibot.net.auth.LoginManager`. The
names are the same on every platform. Where the lines go is not:

- On the JVM, to SLF4J. Pick a binding, such as `org.slf4j:slf4j-simple:2.0.20`.
- On macOS, to the unified log; on Linux and Windows, to standard output, errors to standard error.
  Both from `INFO` up.
- On JavaScript, to the console from `INFO` up.

A command-line bot on macOS, Linux or Windows usually wants its log on standard error, out of the
way of what it prints. In `nativeMain`, with `io.github.oshai:kotlin-logging:8.0.4` as a
dependency of your own:

```kotlin
@OptIn(ExperimentalForeignApi::class)
object StandardError : Appender {
    override fun log(loggingEvent: KLoggingEvent) {
        fputs(KotlinLoggingConfiguration.direct.formatter.formatMessage(loggingEvent) + "\n", stderr)
    }
}

fun quietLogging() {
    KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
    KotlinLoggingConfiguration.direct.logLevel = Level.WARN
    KotlinLoggingConfiguration.direct.appender = StandardError
}
```

`DirectLoggerFactory` is already the default on Linux and Windows; macOS needs it set.
kotlin-logging also prints a line of its own when it starts. `logStartupMessage = false` turns it
off, as does `KOTLIN_LOGGING_STARTUP_MESSAGE=false` in the environment.

## Running on Wikimedia wikis

A bot on a Wikimedia wiki runs under [the bot policy](https://meta.wikimedia.org/wiki/Bot_policy)
of that wiki, and under Wikimedia's
[User-Agent policy](https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_User-Agent_Policy),
[API etiquette](https://www.mediawiki.org/wiki/API:Etiquette) and
[rate limits](https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits). Before saving anything:

1. **Give the bot its own account**, and ask the wiki's community for approval as its policy says.
   Most allow a limited trial before approval.
2. **Run it dry first**, and read the diffs it would make. A trial comes before the bot flag, and
   `botMain` refuses `--save` for an account outside the bot group unless `--trial` says this is
   one: `--save --trial --limit 20`. A trial needs `--limit`, which counts pages taken from the
   source, not edits, so `--limit 20` works through twenty pages however many it changes.
3. **Ask for the bot flag** once approved. Flagged bots are exempt from Wikimedia's API rate
   limits, and their edits stay out of most people's recent changes. `kwikibot whoami` prints
   `bot: true` once the flag is there.
4. **Give people a way to stop it**: a stop page that says `false` until someone changes it, and an
   operator who answers on the bot's talk page.

What kwikibot does without being asked, and where to change it:

| | Default | Setting |
|---|---|---|
| User agent | Required, with a contact | `WikiConfig.userAgent` |
| `maxlag` | 5 seconds | `WikiConfig.maxlag` |
| Pacing | At least 100 ms between reads and 10 s between writes | `WikiConfig.throttle` |
| Retries | 5, waiting 1, 2, 4, 8 and 16 s, or as long as `Retry-After` asks | `WikiConfig.retry` |
| Session loss | Logs back in, up to 3 times in ten minutes | `WikiConfig.relogins` |
| Saving | A dry run | `dryRun`, `--save` |
| Requests in flight | 3 at a time, to every wiki of a client together | `HttpSettings.maxConcurrentRequests`, `--http-connections` |
| Concurrency of a run | 4 pages read and 1 written at a time | `readConcurrency`, `writeConcurrency` |
| `{{nobots}}` | Not checked | `exclusionPolicy` |
| Stop page | None | `stopPolicy`, `--stop-page` |

Wikimedia asks for no more than three requests at a time, and a client sends no more than that
however many pages a run works on: a request with no room waits for one in flight to be answered.
Raise `maxConcurrentRequests` only for a wiki whose operators allow more. Wikimedia also holds an
account without a bot flag to a number of requests a minute that the default read pacing can exceed.
A request over the limit is answered `429`, and kwikibot waits as long as the answer asks. Every
wiki opened on one `WikiClient` shares one throttle, so a pause Wikidata asks for holds back
Wiktionary too.

## Coming from pywikibot

| pywikibot | kwikibot |
|---|---|
| `pywikibot.Site("en", "wiktionary")` | `client.wiki(LangCode("en"), Family.WIKTIONARY)` |
| `Page(site, "volcano").text` | `wiki.pages.content(wiki.ref("volcano"))?.text` |
| `page.save(summary)` | `wiki.pages.edit(ref) { text = …; summary = …; baseRevision = … }` |
| `pagegenerators`, `-cat:`, `-transcludes:` | `wiki.lists` flows and their filters; `PageSourceSpec` with `cat:`, `transcludes:Template:…` |
| `ExistingPageBot.treat_page` | `wiki.botRun { source(…); transform { … } }` |
| `-simulate` | The default; `dryRun = false` or `--save` to write |
| `put_throttle`, `maxlag` | `Throttle(write = …)`, `WikiConfig.maxlag` |
| `user-config.py`, `user-password.py` | `kwikibot.toml` and environment variables |
| mwparserfromhell `parse`, `filter_templates`, `str()` | `Wikitext.parse`, `templates`, `serialize()` |
| `ItemPage(repo, "Q42").get()` | `wikidata.wikibase().entity(EntityId("Q42"))` |
| Confirming each save, `-always` | No prompts: a run is dry or it saves. `TerminalPrompt` is there for a bot that asks |
| `pwb login` | `kwikibot login`, `kwikibot whoami` |
| `pwb` scripts | Your own `main`; the `kwikibot` tool only diagnoses |

The differences that catch people out: a bot saves nothing until it is told to; `{{nobots}}` is
honoured only when a run sets `exclusionPolicy`; a stop page must say `false` for the bot to go on;
`botMain` saves only as an account with the bot flag, or for a `--trial`; and a page source names a
template by its full title, `transcludes:Template:col`, not `col`.

## From JavaScript and TypeScript

```bash
npm install @fenakhay/kwikibot
```

The package is CommonJS with TypeScript definitions, and needs Node 26 or newer. Node cannot read
the names a CommonJS module exports ahead of time, so an ES module imports the package whole:

```js
import kwikibot from "@fenakhay/kwikibot";

const contact = "https://en.wiktionary.org/wiki/User:MyBot";
const wiki = await kwikibot.connect("en.wiktionary.org", contact);

const page = await wiki.page("volcano");
for (const template of page.wikitext().templates("col")) {
  console.log(template.parameters.map((p) => p.value));
}

let seen = 0;
for await (const title of wiki.categoryMembers("English lemmas")) {
  console.log(title);
  if (++seen === 10) break;
}

wiki.close();
```

`page` resolves to `null` for a page that does not exist. Listings are async iterables that fetch
as the loop asks, so `break` ends the paging. A bot run takes a transform that returns the page's
new text, or `null` to leave the page alone, and may return a promise of either:

```js
const login = new kwikibot.BotPassword("MyBot", "task", process.env.MYBOT_PASSWORD);
const bot = await kwikibot.connect("en.wikipedia.org", contact, login);

const report = await bot.run(
  ["Mount Etna", "Mount Vesuvius"],
  "adding [[Category:Volcanoes]]",
  (text) => {
    const code = kwikibot.parse(text);
    if (code.categories().includes("Volcanoes")) return null;
    return code.addCategory("Volcanoes").toString();
  },
  false,
  (change) => console.log(change.diff),
);
console.log(`${report.pending} pages would change`);
bot.close();
```

The run above only computes its edits; `true` as the fourth argument saves them. A page the wiki
refuses is a problem in the report. A transform that throws, or returns anything but text or
`null`, stops the run, and its promise rejects with that error.

A run from JavaScript takes an array of titles; to run over a listing, collect it first with
`Array.fromAsync`. It has no stop page and does not check `{{nobots}}`, so a transform that should
respect it returns `null` when `code.templates("nobots").length` is not zero. Pacing is fixed at
100 ms between reads and 10 s between saves.

`connect` takes the wiki's host name, and a fourth argument for a `scriptPath` other than `/w`;
`kwikibot.detect(url, contact)` finds both from any page URL. Requests go out as
`kwikibot/<version>` with your contact; the bot's own name cannot be set.

The package covers the parser (`parse`), titles and signature times, reading and editing pages,
category members, search, transclusions and history, Wikibase entities, SPARQL (`sparql`) and bot
runs. Revision ids are numbers and lists are arrays. Everything else in the library is for Kotlin.
It runs in browsers too, through a bundler, where logging in works only from a page on the wiki's
own site (see [Platforms](#platforms)). It logs to the console from `INFO` up, the login included,
and the level cannot be set from JavaScript. The package is built from the
[kwikibot-js](kwikibot-js) directory.

## The kwikibot tool

`kwikibot` answers the first question of any misbehaving bot: is it me or the wiki? It is a
Kotlin/Native binary, so it needs no JDK and starts in milliseconds.

```bash
brew install fenakhay/tap/kwikibot
```

```bash
scoop bucket add kwikibot https://github.com/fenakhay/kwikibot
scoop install kwikibot
```

Debian and RPM packages for x64 Linux are attached to each
[release](https://github.com/fenakhay/kwikibot/releases), as are plain archives for macOS on Apple
Silicon, Linux and Windows. `SHA256SUMS` covers every file.

```
$ kwikibot --help
Usage: kwikibot [<options>] <command> [<args>]...

  Talk to a MediaWiki wiki.

Commands:
  whoami       Show the account the wiki sees.
  get          Print the wikitext of a page.
  login        Log in and report the result.
  init-config  Write a starter kwikibot.toml.
  config       Show the configuration in effect.
  detect       Find the api.php of the wiki at a URL.
  version      Show the library and runtime versions.

$ kwikibot detect https://en.wiktionary.org
api:        https://en.wiktionary.org/w/api.php
server:     en.wiktionary.org
scriptPath: /w
```

`whoami` and `login` say what the wiki makes of your credentials, `config` shows the configuration
actually in effect, and `version` gives you the line to paste into a bug report. The commands that
talk to a wiki take `--lang`, `--family`, `--contact`, `--config`, `--account` and `--bot-name`.
With the last two the password is read from `KWIKIBOT_PASSWORD`, and without them the login comes
from `kwikibot.toml`. Without a `kwikibot.toml`, `--contact` is required. It logs to standard
error.

## Compatibility

| MediaWiki | |
|---|---|
| Wikimedia production, the weekly `-wmf` builds | Supported. The live tests read from it, and the record of its API is checked weekly. |
| The current LTS and the latest release, 1.43 and 1.46 today | Supported. CI starts each in Docker and runs `LocalWikiTest` against it, failing on any warning the wiki gives. |
| 1.39 to 1.42 | Supported, not tested in CI. A value MediaWiki has since renamed is sent the way the wiki takes it. Before 1.41, opening a wiki draws one harmless warning, for a site property the release does not have. |
| Older than 1.39 | Not supported. Opening one logs a warning instead of refusing. |

The library checks a version number only where it cannot ask the wiki. A renamed value is chosen
from the wiki's `paraminfo` instead, so a third-party wiki on an old release is handled the same
way as Wikimedia's weekly builds.

### MediaWiki's deprecations

kwikibot looks for them in three places:

- Before sending a value MediaWiki has renamed, kwikibot asks the wiki which spellings it accepts
  and sends the first one it has not deprecated.
- A response that uses something deprecated carries a warning with the code `deprecation`.
  `WikiConfig.onWarning` hears every warning and by default logs each one once, deprecations at
  `WARN`. kwikibot avoids deprecated values, so a deprecation warning means something needs fixing.
- `api-surface.tsv` records the API of five Wikimedia wikis, and the build checks every value
  kwikibot sends against it. A weekly job compares the file with the wikis and reports what
  changed, marking anything kwikibot uses. The same report runs on the beta cluster, which gets
  each change minutes after it is merged and the week before it reaches production.

When MediaWiki deprecates the value behind one of kwikibot's enum entries, the entry is marked
`@Deprecated` with its replacement, and keeps working because it is sent as the replacement. When
the value is gone from every reference wiki, the deprecation becomes an error, and the entry is
removed at the next major release. Every message has the same form:

```
MediaWiki 1.46 (T319141) deprecated parse prop=sections; use TOC_DATA. Deprecated since kwikibot 1.2.0.
```

### kwikibot's own API

Kotlin's [ABI validation](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html)
records the public API of every published module, in `*/api/*.api` for the JVM and
`*/api/*.klib.api` for the other platforms, and the build fails on any change it does not expect.
[CHANGELOG.md](CHANGELOG.md) lists every release's changes, its deprecations and what is pending
removal. 1.4.0, the release that made kwikibot multiplatform, breaks compatibility with 1.3 despite
its number; the changelog says how.

## Building

The build runs on JDK 25, which has to be installed; the published JVM jars target Java 21. Gradle
downloads the rest on the first build: the Kotlin/Native toolchain into `~/.konan`, and Node.js 26
and Yarn for the JavaScript tests.

```bash
./gradlew check
```

That runs every module's tests on the JVM, on Node and as a native binary for the machine it runs
on, and the npm package's in headless Chrome as well, which has to be installed. It also runs
detekt, Spotless, the ABI check and the coverage gates. Coverage is measured on the JVM, which runs
every common test. The macOS targets build only on a Mac. A Mac compiles and links the Linux and
Windows tests without running them; CI runs them on Linux and Windows.

Tests never touch the network: they replay recorded API responses. The live tests run on the JVM,
read from production wikis and write **only** to `test.wikipedia.org` sandbox pages:

```bash
./gradlew liveTest
```

The same tests can run against a throwaway MediaWiki in Docker, where they may write anywhere. Any
tag of the official image works, such as `lts`, `latest` or `1.39`:

```bash
bash .github/mediawiki/start.sh lts
KWIKI_MEDIAWIKI=http://localhost:8080 ./gradlew :kwikibot-client:liveTest --tests '*LocalWikiTest'
docker rm -f kwikibot-mediawiki
```

The parser has [benchmarks](kwikibot-benchmarks/README.md) on the JVM and as a native macOS binary,
over the same 230 real pages the round-trip test replays, with a committed baseline:

```bash
./gradlew :kwikibot-benchmarks:compareToBaseline
```

That fails if anything got slower by more than its error bars. The committed baseline comes from
one machine, so run `recordBaseline` on yours before comparing. Memory is measured separately, with
`measureAllocations` for the bytes allocated per character parsed and `runRetention` for how much
of a finished run is still held, since a benchmark that runs faster can still be the one that will
not fit.

## License

MIT. See [LICENSE](LICENSE).
