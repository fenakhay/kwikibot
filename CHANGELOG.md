# Changelog

Notable changes to kwikibot, newest first.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). The public API of every
published module is recorded in `*/api/*.api` and checked on every build, so a breaking change
cannot reach a release without showing up as a diff first.

## [1.4.0] — 2026-10-30

kwikibot is a Kotlin Multiplatform library. Every library module builds for the JVM, macOS on
Apple Silicon, Linux (x64), Windows (x64) and JavaScript. The parser, the client and bot runs are
on npm as `@fenakhay/kwikibot`, and the `kwikibot` tool is a Kotlin/Native binary. A JSON value of
the wrong type now reads as absent instead of throwing, and the accessors that read JSON that way
are public. `Messages` takes its plural rules from CLDR 49. The build requires every module to cover
90% of its branches as well as 95% of its lines.

This release breaks both source and binary compatibility with 1.3.0, although its version is a minor
one. Paths are kotlinx-io's, the run logs write to a kotlinx-io `Sink`, `RunState` suspends, the
declarations kept for 1.1 and 1.2 are gone, and Maven projects depend on the `-jvm` artifacts.
Changed and Removed list every break, and every case where decoding behaves differently. A JVM
project compiles against this release with Kotlin 2.4 or newer.

### Added

- The targets `macosArm64`, `linuxX64`, `mingwX64` and `js` for every library module, alongside
  `jvm`. On JavaScript the library runs on Node, and in browsers without the file APIs. XML dumps
  stay on the JVM.
- `@fenakhay/kwikibot` on npm, CommonJS with TypeScript definitions, for Node 26 or newer and for
  browsers through a bundler. It has `parse`, with templates, sections, links, categories and
  `replaceText`; `parseTitle` and `signatureTimes`; `connect` and `detect`; reading and editing
  pages; category members, search, transclusions and history as async iterables; Wikibase
  entities; `sparql`; and bot runs with a JavaScript transform, which are dry runs unless told to
  save.
- `environmentVariable(name)` in `kwikibot-net`, which `Credentials.fromEnvironment` and
  `kwikibot-bot` read through. On JavaScript it reads `process.env`, and nothing in a browser.
- `HttpMessageBuilder.identifyAs(userAgent)`, which sets `User-Agent`, and on JavaScript
  `Api-User-Agent` as well.
- `Spool` and `spool(prefix)` in `kwikibot-client`, which hold a streamed response so it can be
  read line by line: in a temporary file on the JVM and the native platforms, and in memory on
  JavaScript.
- `KWIKIBOT_MODULES` in the `kwikibot` umbrella, the artifact ids it brings in. Kotlin/Native
  writes no klib for a module without sources (KT-47345), so the umbrella needs one declaration
  to be published for macOS, Linux and Windows.
- JSON accessors in `com.fenakhay.kwikibot.protocol.decode`: `string`, `long`, `int`, `double`,
  `boolean`, `flag`, `time`, `obj` and `array` on `JsonObject`, and `strings` and `objects` on
  `JsonArray?`. An absent key, JSON `null` and a value of the wrong type all read as absent.
- `--trial`, `CommonOptions.trial`: with `--save` and `--limit`, `botMain` saves as an account
  outside the bot group, for the trial edits a wiki asks for before it grants the bot flag, and
  says so on standard error. Without `--save` or `--limit` it does not start.
- `HttpClient.withRequestPermit` in `kwikibot-net`, where a request waits for room among the
  client's requests in flight.
- `SignatureFormat(language, pattern, months, zone: String)`, which takes a zone by its name, such
  as `Europe/Berlin`. On JavaScript the zone is read through the runtime's `Intl`, so
  `Wiki.signatures()` and the npm package need no `@js-joda/timezone`.

### Changed

- `mapNodes` and `replaceText` keep what they leave unchanged. A node in which nothing changed
  reaches `transform` as it was and comes back as the same instance, and so does the markup around
  it. Only identity shows the difference, and a pass that changes nothing now allocates next to
  nothing.
- Gradle resolves each platform's build from the same coordinates as before. Maven does not read
  Gradle's metadata, so a Maven project depends on the `-jvm` artifacts, such as
  `com.fenakhay.kwikibot:kwikibot-jvm`.
- Paths are `kotlinx.io.files.Path` instead of `java.nio.file.Path`: `DiskCache`,
  `FileService.download` and `upload`, `BotConfig.read` and `find`, `CommonOptions.diffLog`,
  `skipLog` and `state`, and `RunState` and its `directory`. On JavaScript they work on Node only.
  `XmlDump` keeps `java.nio.file.Path` and `InputStream`. `kotlinx-io-core` is part of
  `kwikibot-net`'s API.
- `RunLog` and `Progress` write to a `kotlinx.io.Sink` instead of an `Appendable`. They are no
  longer functions, so `onOutcome` and `reportTo` take `runLog::record` and `progress::record`.
- `RunState.finishedCount`, `isFinished`, `pin` and `record` suspend, and `finishedCount` is a
  function instead of a property.
- `botMain` is declared to return `Unit` instead of `Nothing`. On the JVM and the native platforms
  it still ends the process with the bot's status; on JavaScript it returns at once, and Node exits
  with the status when the bot is done. A failure the bot lets through prints its stack trace and exits
  with status 1 on every platform. Java calls it as `BotMain.botMain` instead of `BotMainKt.botMain`.
- `TerminalPrompt`'s `input` is a suspending function. On Node, the default reads standard input
  through `readline`, keeping the lines it has read ahead for the next question.
- `Diffs` renders unified diffs itself, over `kotlin-multiplatform-diff`, and gives the output
  `java-diff-utils` gave.
- Off the JVM, `Kwikibot.runtime` and `platform` describe Kotlin/Native and the operating system,
  Node and the operating system, or the browser.
- On JavaScript, every request also carries `Api-User-Agent`, since a browser will not send a
  page's own `User-Agent`. A request from a page on a site other than the wiki's sends `origin=*`,
  which MediaWiki answers as it would a logged-out reader.
- Each logger is named after its class on every platform, as on the JVM, such as
  `com.fenakhay.kwikibot.net.auth.LoginManager`.
- The `kwikibot` tool is a Kotlin/Native binary instead of a GraalVM native image, and logs to
  standard error on every system. `appArchive` is one task per target: `macosArm64AppArchive`,
  `linuxX64AppArchive` and `mingwX64AppArchive`. The archives keep their names.
- The release native executables, which are the tool and the native benchmarks, are linked with
  Kotlin/Native's experimental inlining pass, `preCodegenInlineThreshold=40`. Parsing runs about 10%
  faster, and the tool is about 11% larger.
- The parser benchmarks run on the JVM and as a native macOS binary: `mainBenchmark` is
  `jvmBenchmark`, joined by `macosArm64Benchmark`, and `large-pages.json.gz` moved to
  `kwikibot-benchmarks/corpus`.
- Kotlin's own ABI validation records the API. `checkKotlinAbi` and `updateKotlinAbi` replace
  `apiCheck` and `apiDump`, and each module has a `.klib.api` dump for its other platforms beside
  the JVM one.
- Built with Kotlin 2.5.0-Beta2 and Gradle 9.8.2.
- JSON `null` reads as absent. It used to read as the text `"null"`, in a login failure's reason,
  a user name, a group list such as `["bot", null]`, a statement id or a PagePile entry.
- An object, array or element of the wrong type reads as absent, or is skipped, instead of
  throwing. This covers the API services, Wikibase entities, SPARQL, EventStreams, PagePile lists
  and the disk cache. A field kwikibot cannot do without still fails.
- A response of `{"errors":{}}`, or any other error shape MediaWiki does not send, reads as no
  error.
- A flag sent as `""` or `1` reads as false, a revision timestamp that is not a MediaWiki
  timestamp reads as absent, and an interwiki row without a prefix is skipped. AbuseFilter's and
  PageTriage's flags are still read in every shape those extensions send.
- A magic word is case-sensitive only when its `case-sensitive` flag is `true`.
- `EventStreams` reads a `wiki`, `title`, `user`, `comment` or `type` sent as a number or a
  boolean as text.
- `ExtensionService.lintErrors` gives no `range` for a location that is not two numbers, instead
  of a range that starts or ends at 0.
- `Messages` takes its plural rules from CLDR 49, compiled into `kwikibot-bot` from CLDR's own data
  with no library behind it. Each language CLDR covers gets its own rule. The hand-written table
  had seventeen rules, and every other language, Czech, Lithuanian, Maltese, Hebrew and Welsh
  among them, took English's. Forms are numbered as MediaWiki numbers them, by the categories the
  language has, and a variant code such as `sr-ec` or `de-formal` takes its language's rule. Against
  CLDR 48, Tajik has a rule of its own, Galician a many form and Vietnamese a one form.
- `replaceText`, `containsText` and `textIn` leave an argument's name alone unless the scope
  includes templates. In `{{{colour|colour}}}`, prose reaches only the default.
- `Markup.allNodes()` walks the tree without a coroutine for each level, in the same order. It
  and `templates` and `wikilinks`, which are built on it like `tags` and `comments`, run 2 to 2.6
  times as fast on the JVM and 3.5 to 3.8 times as fast natively.
- A client from `WikiHttpClient`, and so every `WikiClient`, has at most
  `HttpSettings.maxConcurrentRequests` requests in flight at once, 3 unless told otherwise, which is
  what [Wikimedia's rate limits](https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits) ask.
  The limit holds on every platform, for API requests, uploads and file downloads to every wiki of
  the client together, and `--http-connections` sets it. A run still works on 4 pages at once,
  `readConcurrency`, and their requests wait for room. Before, nothing held a run to three requests,
  and the per-host limit of 32 applied only on the JVM and macOS.
- `HttpSettings.maxRequestsPerHost` is `maxConcurrentRequests`, and so is the `[http]` key in
  `kwikibot.toml`. The old key is now unknown, which is a warning, and the client keeps to 3.
  `kwikibot show-config` prints the limit as requests at once.
- `botMain` names `--trial` when it refuses to save as an account outside the bot group.
- `FakePageService.edits` is a `List` instead of a `MutableList`. Each read gives the edits applied
  so far, and does not grow as later edits are applied.

### Removed

- The declarations kept so code compiled against 1.1 and 1.2 still links, 22 in all: the old
  constructors of `BotConfig`, `BotConfig.WikiSelection`, `BotConfig.LoginSettings`, `Edit`,
  `PageOutcome.Pending`, `PageOutcome.Saved`, `BotReport`, `RunLog`, `Progress`, `WikiConfig`,
  `DumpPage`, `RecentChange`, `ApiRequest`, `KtorTransport`, `ParamDescription`,
  `FakePageService`, `FakeWiki`, `Comment`, `ExternalLink` and `Tag`, and both of the old
  `Flow<PageRef>.withContent` overloads.
- `java-diff-utils`, which was part of `kwikibot-bot`'s API, `ktoml-file`, and `kwikibot-client`'s
  unused `ktoml-core`. `kotlin-logging-jvm` is now `kotlin-logging`.
- The tool's JVM application tasks, such as `:kwikibot-cli:run`, and its GraalVM build.
- Decoding of formatversion=1 shapes: content and warning text under `"*"`, warnings keyed by
  module, log parameters under the log type's key, an empty `case-sensitive` flag, and the move
  parameters `new_ns`, `new_title` and `suppressedredirect`. kwikibot asks for formatversion=2.
  The errorformat=bc `error` object is still read.

### Fixed

- A chunked upload of a file cut short while it was being sent went on sending chunks whose offset
  passed the bytes the wiki had. The short chunk is now the last one sent, and the wiki refuses the
  incomplete file when it is published.
- One `EventStreams` event whose `meta` or `revision` was not an object ended the stream.
- `lists.languageLinksOn` failed on a link without a language code. It now skips it.
- `SiteInfo.decode` threw `IllegalArgumentException` on a response that names no wiki, such as one
  sent without `siprop=general`. It now throws `WikiError.Api` with the code `nositeinfo`.
- A suppressed move decoded to `LogDetails.Unknown` with an empty `target_title`, and a move whose
  target is not a title lost its `target_ns`. The first is now `LogDetails.None`, and the second
  keeps every field the wiki sent.
- Wikibase entities failed to decode when labels, descriptions, aliases, claims, statements or
  sitelinks came as `[]`, which is how PHP writes an empty map.
- A Wikibase data value not in the shape its type takes threw. It is now `DataValue.Unknown`.
- The disk cache threw on an entry of the wrong shape, and never deleted an entry without a
  response.
- A response of `{"errors":["maxlag"]}` or `{"error":"maxlag"}` threw `IllegalArgumentException`
  instead of a `WikiError`.
- `whoAmI` and token requests threw `IllegalArgumentException` on a `userinfo` or `tokens` block of
  the wrong shape, instead of `WikiError.Api`.
- A PagePile list failed as a whole when one entry was not a title.
- `Messages` gave Polish 21, 31, 101 and the like the few form instead of the many form, and gave
  `arz`, `ary`, `be-tarask`, `sr-ec` and `sr-el` the English plural rule. A language without a rule
  of its own now takes the rule of the language it falls back to, as MediaWiki does.
- `ScriptedPrompt` threw on an empty answer to `choose`, and ignored the default on an empty answer
  to `confirm` or `text`. An empty answer now takes the default, or the first choice, as Enter
  does at a terminal.
- `Markup.text` dropped a bare URL, so `Template.value` of `url=https://…` was empty.
- `Template.withParameter("3", value)` on a call with one positional parameter added the value as
  the second.
- `removeCategory` left the line break of a category on the first or last line.
- `containsText` and `textIn` read an external link's URL, which `replaceText` never changes.
- `CosmeticChanges.HTML_EMPHASIS` turned an empty `<b></b>` or `<i></i>` into apostrophes.
- `CosmeticChanges.HTML_ENTITIES` threw on `&#1114112;`. It also decoded numeric references that
  have to stay as written: ASCII such as `&#124;`, spaces, and control, format, private-use and
  unassigned code points.
- `FakePageService` dropped the text of a new section added with `prependText`.
- `Credentials.OAuth2` and `WikiHttpClient` said an OAuth 2 token has no session cookie to keep.
  The client has always kept the cookies the wiki sets and sent them back with the token, which
  Wikimedia asks of owner-only tokens so that the account's own rate limit applies, and a test now
  holds it to that.
- `FakePageService` could lose edits, or hang a Kotlin/Native test, when a bot run called it from
  several threads at once. It can now be called from any number of threads.
- `Wikitext.parse`, `serialize()`, `ranges()`, equality, hashing and the operations on a tree
  recursed once per level of nesting, so templates, links or tags nested a few hundred deep ran a
  Kotlin/Native process out of stack, which ends it, and threw `StackOverflowError` on the JVM. The
  tokenizer and the builder now keep the constructs they are in on stacks of their own, and every
  pass over a tree recurses for at most 32 levels and goes on with its own stack below that, so a
  page nested a hundred thousand deep parses, compares and writes back like any other.

## [1.3.0] — 2026-10-18

`botMain` sets up a bot in one call. `renderer.parse` takes a revision and a parser and reports
the parse's limits, `expandTexts` takes a sandbox and a revision, and old revisions can be read
many to a request. When a server refuses a request outright, the run stops at once.

Source-compatible, except that `WikiError.Transport` has a new case (`Refused`), `StopReason` and
`Title.Invalid.Reason` have new entries, and an override of `PageService.expandTexts` must take
two more parameters. Binary-compatible except for `PageService.expandTexts`, which gained `sandbox`
and `revision`, and the constructors and `copy` of `RenderRequest` and `ParsedPage`, which gained
properties.

### Added

- `botMain(args) { … }` with `BotStart`. It reads the common flags and `kwikibot.toml`, logs in,
  refuses an anonymous session and a save by an account outside the bot group, and ends with one
  line and an exit status when the bot cannot start.
- `RevisionService.contentsAt`, which reads the text of many old revisions in one request, and
  `PageDecoder.decodeContents`.
- `ParseProperty.LIMIT_REPORT` and `ParsedPage.limitReport`, with Lua time and memory and the
  expensive-function count. `RenderRequest.revision` and `parser`, with `Parser.LEGACY` and
  `PARSOID`.
- `sandbox` and `revision` on `PageService.expandTexts`.
- `Template.functionName` and `functionArgument`: a parser function's name, and what follows its
  colon.
- `WikiError.Transport.Refused`, `StopReason.REFUSED` and `Title.Invalid.Reason.UNKNOWN_NAMESPACE`.

### Changed

- The `kwikibot` tool is built with GraalVM 25.4, the latest innovation release.
- A 4xx whose body is not an API answer, such as a block on the user agent or the address, is
  raised at once as `WikiError.Transport.Refused` with what the server said, instead of being
  retried five times. A run stops on it with `StopReason.REFUSED`.
- A request that times out now fails with `WikiError.Transport.Timeout`. It used to be reported as
  `Unreachable`.
- A run whose stop policy refuses, or cannot be checked, before anything is read returns a stopped
  `BotReport` instead of throwing.
- `Wiki.templateNames` returns names as a call writes them, without the `Template:` prefix unless
  the bare name would mean another namespace.
- A title in a namespace the wiki does not have is refused: `Title.parse` returns it as invalid,
  and `NamespaceMap.format` throws `WikiError.Page.BadTitle` instead of writing it as a
  main-namespace title.
- `Title.parse` rejects a title containing U+FFFD, as MediaWiki does. A character reference to no
  valid character decodes to U+FFFD, so it is rejected too.

### Removed

- The macOS Intel build of the `kwikibot` tool. GraalVM 25.1 and later no longer build for macOS
  x86-64. Homebrew on an Intel Mac now refuses the formula instead of installing it.

### Fixed

- `CosmeticChanges.EMPTY_SECTIONS` removed a heading whose only content was its subsections, such as
  `==English==` directly followed by `===Noun===`. A section now includes its subsections, and goes only
  when they are empty too.
- `replaceText` with `TextScope(comments = true)` closed an unclosed `<!--`, even when nothing matched.
- `ReformatIsbns(toIsbn13 = true)` dropped an ISBN's hyphens, and rewrote ISBNs that were already
  thirteen digits. A converted ISBN keeps its own hyphens, and a thirteen-digit one is left as written.
- With `readBatch` above one, a page could reach `onOutcome` only after later saves in its batch
  had been sent. Each page is now reported as soon as it is done, and `onOutcome` calls never
  overlap.
- `lists.specialPage()` failed on every special page.
- `pages.categoryInfo` reported every category as hidden.
- `revisions.deletedHistory` returned nothing.
- `ParsedPage.images` kept the underscores of a database key.
- `revisions.contentAt` read an old revision's redirect target from the page as it is now.
- `Title.parse` threw on a character reference past U+10FFFF.
- The `kwikibot` tool exited with status 0 when a command failed, printed a stack trace for a
  configuration mistake, and reported a blank password variable as set.

## [1.2.0] — 2026-10-02

Long runs now survive a lost session and can resume after a stop, and the parser reads wikitext
the way MediaWiki does. kwikibot also follows MediaWiki's deprecations (see "Deprecations" in the
README).

Source-compatible, except that `PageOutcome` has a new case, `NotAttempted`. Binary-compatible
except for `copy` on classes that gained properties: `WikiConfig`, `ApiWarning`,
`ParamDescription`, `RecentChange`, `ApiRequest`, `Edit`, `BotReport`, `PageOutcome.Pending` and
`Saved`, `Tag`, `Comment`, `ExternalLink`, `BotConfig` with `WikiSelection` and `LoginSettings`,
and `DumpPage`.

### Added

- A renamed API value is sent under whichever name the wiki takes, read from its `paraminfo`.
  `ParseProperty.TOC_DATA`, `ParamInfo.values`, `isDeprecated` and `prefetch`,
  `WikiError.Configuration.Unsupported` and `SiteInfo.hasVersion`.
- `WikiConfig.onWarning`, `ApiWarning.code` and `isDeprecation`, and `FailOnDeprecation` in the
  testkit.
- `ExtensionService.abuseFilters`, and `newPages` and `newPageStats` for PageTriage.
- Automatic re-login: every request asserts its account, and when the wiki ends the session the
  client logs in again and repeats the request. `WikiConfig.relogins` caps it.
- `WikiConfig.listener`, a `TransportListener` that hears responses, retries, pauses and
  re-logins.
- `WikiConfig.http`, `Identity.batchLimit` and `withContent(..., concurrency)`.
- `PageService.freshContents`, `expandTexts` and `redirectsTo`, `RenderService.parse` with
  `TemplateSandbox`, and `Wiki.templateNames`, `requireGroup`, `parseOptions` and `titleRules`.
- `BotRunBuilder.createMissing`, `state` and `resume` with `RunState`, `Edit.tags`, `section` and
  `createOnly`, `BotReport.stopReason` and `StopPolicy.check()`.
- `CommonOptions.parse(args)`, `Credentials.fromEnvironment()`, `BotConfig.connect(client)`, and
  `[oauth]`, `[run]`, `[http]` and `[retry]` in `kwikibot.toml`.
- `XmlDump` reads `.bz2` and multistream dumps, and `XmlDump.scan(path, workers)` reads one in
  parallel. `DumpPage.redirectTarget`.
- `ParseOptions`, `Wikitext.parseExact` and `roundTrips`, `Template.key`, `rawName` and
  `rawValue`, `Parameter.rawKey` and `rawValue`, `TitleRules` and `MagicWords` with
  `Markup.templates(names, rules)` and `mapTemplates`, and `Markup.ranges()`.
- `<pre format="wikitext">`, `<inputbox>` and `<DynamicPageList>` bodies are read as MediaWiki
  expands them, with `ParseOptions.preprocessedTags`.
- `MockTransport`, `FakeWiki` taking any service, `FakePageService` refusing edits as a wiki does,
  and a public `EditBuilder.validate()`.

### Changed

- A saving run reads pages and the stop page past the response cache, and saves to existing
  pages with `nocreate`.
- A stopped run takes no more pages. Those it already held are `NotAttempted`, not `Skipped`.
- With `readBatch` above one, each page is reported as it finishes.
- `readonly` and database errors are retried with backoff, and retries are logged at `INFO`.
- A client allows 32 requests at once instead of five, and a bot account queries 500 titles at a
  time instead of 50. `Throttle` no longer holds its lock while a write waits.
- Parsed trees differ wherever 1.1 disagreed with MediaWiki. A stray `[` is text. A `|` or `=`
  inside an external link, HTML tag or bold splits a template parameter. A heading may contain `=`
  and be followed by a comment. Only allowed HTML tags and the wiki's extension tags are tags. An
  unclosed `<!--` runs to the end of the page. `Template.title` keeps nested templates, and
  `Markup.headings()` returns section headings only. `{{PAGENAME|x}}` is a template, since a
  variable takes no arguments.
- HTML tags pair where MediaWiki ends them. A `<span>` ends at a paragraph break, a list item, a
  heading or a table cell; a `<div>`, a `<blockquote>` or a `<b>` stays open across them, so a tag
  can hold a heading, which `headings()` and `outline()` still find.
- Serializing a tree is about twice as fast.
- `RunLog` names pages with their namespace, and `Progress` shows the rate and time left.
- `kwikibot.toml` is also looked for in the home directory, and an unknown key is a warning.
- `api-surface.tsv` merges the reference wikis. `wikiApiCheck` reports changes by kind, and
  `wikiApiBetaCheck` checks the beta cluster.
- CI runs the live tests against MediaWiki's LTS and latest releases. Opening a wiki older than
  1.39 logs a warning.
- Built with Kotlin 2.4.20, on Ktor 3.6.0 and SLF4J 2.0.20.

### Deprecated

- `ParseProperty.SECTIONS`: use `TOC_DATA` (MediaWiki 1.46, T319141).
- `AbuseFilterProperty.STATUS`, `PRIVATE`, `PROTECTED` and `SUPPRESSED`: use `FLAGS` (MediaWiki
  1.47, T435834).

### Fixed

- `recentChanges` and `watchlistChanges` failed for a session without the `patrol` or
  `patrolmarks` right, and the watchlist never reported a change as patrolled.
- `allUsers` asked for properties `list=allusers` does not take.
- `ParamInfo.limit` was always `null`.
- `lintErrors` did not check that Linter is installed.
- `EntityService.search` sent `type=mediainfo`, which the API refuses.
- `requireVersion` swapped the required and running versions in its error.
- `backlinks(includeRedirects = true)` dropped pages that link through a redirect.
- `backlinksOf`, `transclusionsOf` and `fileUsageOf` kept only the last continuation.
- `PageContent.redirectTarget` was never set.
- `Template.withParameter` could write a value that changed the template's parameters.
- `shortenUrl` sent a token it does not take, and uploads did not assert the account.
- A closing tag lost its spacing and case, a bare attribute lost its trailing space, and a bare
  URL could run past the end of a heading or parameter.
- `Markup.replace`, `mapNodes` and `replaceText` did not reach tag attributes.
- `CosmeticChanges.HTML_EMPHASIS` turned an unclosed `<b>`, or one spanning lines, into `'''`
  that ends sooner, and `EMPTY_SECTIONS` could join a heading to the line before it.
- `XmlDump` left a file open when its sequence was never read, and stopped at the JDK's entity
  limit.
- `FakePageService` treated `Template:X` and `X` as one page.

### Pending removal in 2.0

- `ParseProperty.SECTIONS` and the deprecated `AbuseFilterProperty` entries.
- The hidden 1.1 constructors and `withContent` signatures.

## [1.1.1] — 2026-09-06

### Fixed

- `selectStreamed` returned nothing: its `format` parameter made the service answer XML, not TSV.

## [1.1.0] — 2026-09-05

A pass over what the library holds in memory. A bot that sweeps a category the size of German
Wiktionary, 353,005 entries, could not finish, and the reasons were spread across the run, the
sources it reads from and the parser. Every figure below was measured rather than reasoned about,
using `:kwikibot-benchmarks:measureAllocations` and `runRetention`, both of which are new.

Source-compatible except where noted under Changed. Not binary-compatible with 1.0.x.

### Added

- `SparqlClient.selectStreamed(query) { rows -> … }`, which asks for TSV and hands the rows over as
  they are read. `select` holds the answer as text and again as a parsed tree, which a query naming
  a million files cannot fit. It asks for TSV rather than CSV because the service escapes tabs and
  newlines inside literals, so a line is always a row, whereas a CSV field may contain a raw
  newline.
- `ExternalSources.withPetScanTitles(...)`, the streamed form of `petScanTitles`. Both PetScan
  sources and the SPARQL source now spool the answer to a file as it arrives and read it back a
  line at a time, so none of them holds the list.
- `BotReport.problems` and `problemsTruncated`: the refusals and failures, capped at
  `BotReport.PROBLEM_LIMIT`.

### Changed

- `BotReport` carries counts rather than every outcome, and `outcomes` is gone. It held one
  `PageOutcome` per page for the whole run, and a `Pending` holds the text it would have written as
  well as the text that was there, so a dry run, the default, kept two full copies of every page it
  would have changed until the run ended. Over 20,000 pages of 2 KB the report held 45,397 KB, and
  it now holds none of it. Callers that read `outcomes` should take `BotRunBuilder.onOutcome`, which
  has always been given every outcome as it happens.
- PetScan is asked for `format=plain`, one title per line. For the German entry list that is 4.3 MB
  against 37.6 MB of JSON, measured against the live service. `ExternalSources.petScanTitles` is now
  `suspend`, and `titlesFromPetScan` is gone with the JSON path.
- A chunked upload reads a chunk at a time. `uploadInChunks` read the whole file and then cut it up,
  so a 2 GB video needed 2 GB of heap to be sent in 4 MB pieces, leaving it chunked on the wire
  only.
- Parsing allocates 23.26 bytes per source character, down from 24.72. `Markup` no longer copies a
  node list the parser built and handed straight over, and a tokenizer `Mark` remembers the pending
  text as a length rather than copying it. Nothing got measurably slower.

## [1.0.3] — 2026-09-03

### Added

- `SparqlClient` takes credentials and retries a busy service. `SparqlAuth.wcqs(token)` carries the
  cookie the Commons Query Service reads; without it that service returns an error page rather than
  a challenge, so a missing token looked like a query that found nothing. A `429` or a `5xx` retries
  on the existing `RetryPolicy`, honouring `Retry-After`.
- `SparqlClient.COMMONS` and `SparqlClient.LINGUA_LIBRE`, beside `WIKIDATA`.
- `ExternalSources.sparql(...)`, so a query service can be a `PageSource` like PetScan and PagePile.
- `ExternalSources.petScan(parameters, ...)`, taking the query itself rather than a saved PSID. A
  saved query is the better habit, but cannot be built at run time.
- `ExternalSources.petScanTitles(...)`, which returns the titles rather than pages to edit.
  Resolving a million of them against the wiki costs a title parse apiece and silently drops the
  ones that fail.
- `BotRunBuilder.readBatch`, which reads that many pages per request instead of one: a sweep of
  283,000 entries is 5,660 requests at fifty rather than 283,000 at one. Defaults to 1, which takes
  the single-page path as before.
- `Section.withSubsectionAt(index, subsection)`. `withSubsection` appends, which is only correct
  when the new section sorts last.

### Fixed

- `ExternalSources.petScan` and `pagePile` sent no `User-Agent`, against Wikimedia's user-agent
  policy. Both now require one, as every other off-`api.php` helper already did.
- `''italic''` and `'''bold'''` no longer run past the end of their line. An unmatched `'''` paired
  with the next one anywhere on the page, taking the headings in between inside the tag, so
  `outline()` stopped seeing whole sections. On the entry `1`, one inside a `<gallery>` paired with
  one in the Swedish section and hid English, Chinese and German. MediaWiki applies apostrophe
  markup a line at a time; now so does this.

### Changed

- Every public type moved into a subpackage named for what it does, so every import changes:
  `model.Title` is now `model.title.Title`, `client.PageService` is now
  `client.service.PageService`, and so on across all seven modules.
- Implementation classes left the public packages for `internal` subpackages, so a package listing
  shows only what a consumer can call.
- `ExternalSources.petScan` and `pagePile` take a `UserAgent`. Source- and binary-breaking.
- `SparqlClient`'s constructor gained two parameters with defaults. Source-compatible with 1.0.x,
  not binary-compatible.
- Formatting is enforced by Spotless with ktfmt, which also removes unused imports.

## [1.0.2] — 2026-09-02

### Changed

- `kwikibot` is a native binary rather than a bundled JVM, and starts in 94ms instead of 560ms.

## [1.0.1] — 2026-09-02

### Fixed

- The version comes from the jar manifest rather than a hardcoded constant, so a release no
  longer reports the one before it.
- A missing configuration prints a message instead of an uncaught `IllegalArgumentException`,
  a stack trace and `Failed to launch JVM`.
- The Homebrew formula points at the `.tgz` archives that are published, with their real
  checksums, and wraps the packaged launcher instead of symlinking it. A jpackage launcher
  reads its configuration relative to its own path, and the symlink sent it outside the app
  image.

### Changed

- `UserAgent.LIBRARY_VERSION` is a `val`, not a `const val`. Source-compatible with 1.0.0, not
  binary-compatible.
- With no jar to read, the version reports as `dev`.

## [1.0.0] — 2026-09-02

First release.

### Added

- **Pages** — reading singly and in batches, editing with a full `EditOutcome`, moving, deleting,
  undeleting, protecting, rollback, undo, watching and purging. Section edits, new talk-page
  sections, `testActions` for asking whether a write would be allowed, and `flagged()` for wikis
  with pending changes.
- **Listing** — category members, backlinks, transclusions, links, search, all pages,
  contributions, recent changes, logs, watchlists and files, each a cold `Flow` that stops paging
  when collection stops, with composable filters for namespace, title, subpage depth and set
  operations.
- **Wikitext** — a lossless parser and editor: `Markup`, section outlines, template and parameter
  editing, category and language-link handling, scoped text replacement, unified diffs, ISBN
  validation, signature timestamps and `CosmeticChanges`.
- **Wikibase** — items, properties, lexemes and MediaInfo as a sealed `Entity`, statements,
  qualifiers, references and data values, with encoding verified by round-trip.
- **Beyond `api.php`** — EventStreams with resumable offsets, SPARQL, XML dump streaming, PetScan
  and PagePile.
- **Site and protocol** — `Family` and `SiteMatrix`, `ApiDetector`, `paramInfo` introspection,
  `requireRight` / `requireExtension` / `requireVersion`, throttling with server-requested
  penalties, `maxlag` and rate-limit retries, a disk response cache, and continuation as a `Flow`.
- **Extensions** — GeoData, PageImages, TextExtracts, WikibaseClient, Linter, Echo, Thanks,
  UrlShortener, FlaggedRevs, GlobalUsage and ProofreadPage, each refusing when absent.
- **Bots** — `botRun`, dry-run by default, with bounded concurrency, a fail-closed `StopPolicy`,
  `{{nobots}}` exclusion, page sources, filters, `RunLog` and `Progress`.
- **Testing** — `kwikibot-testkit` with `FakeWiki` and `FakePageService`, so a bot's tests need no
  network.
- **Tooling** — `kwikibot`, a self-contained command-line tool for answering whether a problem is
  the bot or the wiki, distributed through Scoop, Homebrew, Debian and RPM packages and plain
  archives.

[1.4.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.4.0
[1.3.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.3.0
[1.2.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.2.0
[1.1.1]: https://github.com/fenakhay/kwikibot/releases/tag/v1.1.1
[1.1.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.1.0
[1.0.3]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.3
[1.0.2]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.2
[1.0.1]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.1
[1.0.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.0
