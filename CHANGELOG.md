# Changelog

Notable changes to kwikibot, newest first.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). The public API of every
published module is recorded in `*/api/*.api` and checked on every build, so a breaking change
cannot reach a release without showing up as a diff first.

## [Unreleased]

### Fixed

- The Linux `kwikibot` binary no longer aborts now and then with `corrupted double-linked list` on exit.

## [1.4.0] — 2026-10-30

Every library module builds for macOS, Linux, Windows and JavaScript as well as the JVM, and the parser,
the client and bot runs are on npm. This release breaks source and binary compatibility with 1.3.0 despite
its minor version: Changed and Removed list every break.

### Added

- The `macosArm64`, `linuxX64`, `mingwX64` and `js` targets for every library module, beside `jvm`. XML
  dumps stay on the JVM, and a browser has no file APIs.
- `kwikibot` on npm, with TypeScript definitions, for Node 26 or newer and bundlers: the parser, `connect`,
  pages, lists as async iterables, Wikibase entities, `sparql` and bot runs.
- `environmentVariable(name)` in `kwikibot-net`, which reads `process.env` on JavaScript.
- `HttpMessageBuilder.identifyAs(userAgent)`, which sets `User-Agent`, and `Api-User-Agent` on JavaScript.
- `Spool` and `spool(prefix)` in `kwikibot-client`, which hold a streamed response to read a line at a
  time.
- `KWIKIBOT_MODULES` in the `kwikibot` umbrella, the artifact ids it brings in.
- JSON accessors in `com.fenakhay.kwikibot.protocol.decode`, from `string` to `objects`, which read an
  absent key, `null` and a value of the wrong type as absent.
- `--trial` and `CommonOptions.trial`: with `--save` and `--limit`, a bot saves as an account outside the
  bot group, for a wiki's trial edits.
- `HttpClient.withRequestPermit` in `kwikibot-net`, which holds a request until the client has room for it.
- `SignatureFormat(language, pattern, months, zone: String)`, which takes a zone by name, such as
  `Europe/Berlin`. The npm package needs no `@js-joda/timezone`.

### Changed

- Maven projects depend on the `-jvm` artifacts, such as `com.fenakhay.kwikibot:kwikibot-jvm`. Gradle
  resolves the same coordinates as before.
- A JVM project needs Kotlin 2.4 or newer to compile against kwikibot.
- Paths are `kotlinx.io.files.Path`, not `java.nio.file.Path`, in `DiskCache`, `FileService`, `BotConfig`,
  `CommonOptions` and `RunState`. `XmlDump` keeps `java.nio.file.Path` and `InputStream`.
- `RunLog` and `Progress` write to a `kotlinx.io.Sink`, not an `Appendable`, and are passed as
  `runLog::record` and `progress::record`.
- `RunState.finishedCount`, `isFinished`, `pin` and `record` suspend, and `finishedCount()` is a function.
- `botMain` returns `Unit`, not `Nothing`, and Java calls it as `BotMain.botMain`, not `BotMainKt.botMain`.
- `TerminalPrompt`'s `input` suspends.
- The `kwikibot` tool is a Kotlin/Native binary, not a GraalVM native image, and logs to standard error
  everywhere.
- `mapNodes` and `replaceText` hand back unchanged nodes as the same instances, so a pass that changes
  nothing allocates next to nothing.
- JSON `null` reads as absent, where 1.3.0 read it as the text `"null"`.
- A value of the wrong type reads as absent, or is skipped, instead of throwing. A field kwikibot cannot do
  without still fails.
- `{"errors":{}}`, and any other error shape MediaWiki does not send, reads as no error.
- A flag sent as `""` or `1` reads as false, a malformed revision timestamp as absent, and an interwiki row
  without a prefix is skipped.
- A magic word is case-sensitive only when its `case-sensitive` flag is `true`.
- `EventStreams` reads a `wiki`, `title`, `user`, `comment` or `type` sent as a number or a boolean as
  text.
- `ExtensionService.lintErrors` gives no `range` for a location that is not two numbers.
- `Messages` has a plural rule for every language CLDR 49 covers, where 1.3.0 had seventeen and gave the
  others English's.
- `replaceText`, `containsText` and `textIn` leave an argument's name alone unless the scope includes
  templates.
- `Markup.allNodes()`, `templates` and `wikilinks` run 2 to 2.6 times as fast on the JVM.
- A client has at most 3 requests in flight, as Wikimedia's rate limits ask, where 1.3.0 allowed 32 per
  host on the JVM and macOS. `--http-connections` sets it.
- `HttpSettings.maxRequestsPerHost` and its `[http]` key are now `maxConcurrentRequests`. The old key warns
  as unknown.
- `FakePageService.edits` is a `List`, not a `MutableList`, and gives the edits so far on each read.

### Removed

- The 22 hidden declarations kept so code compiled against 1.1 and 1.2 still links, such as the old
  constructors of `BotConfig`, `Edit` and `WikiConfig`.
- The `java-diff-utils`, `ktoml-file` and `ktoml-core` dependencies. `kotlin-logging-jvm` is now
  `kotlin-logging`.
- Decoding of formatversion=1 answers, such as text under `"*"`. kwikibot asks for formatversion=2.

### Fixed

- A chunked upload of a file cut short while it was sent stops at the short chunk, and the wiki refuses the
  incomplete file.
- An `EventStreams` event whose `meta` or `revision` is not an object no longer ends the stream.
- `lists.languageLinksOn` skips a link without a language code instead of failing.
- `SiteInfo.decode` throws `WikiError.Api` with the code `nositeinfo` on an answer that names no wiki.
- A suppressed move decodes to `LogDetails.None`, and a move to a target that is not a title keeps its
  fields.
- Wikibase entities decode with labels, descriptions, aliases, claims, statements or sitelinks sent as
  `[]`.
- A Wikibase data value in the wrong shape is `DataValue.Unknown` instead of throwing.
- The disk cache no longer throws on an entry in the wrong shape, and deletes an entry without a response.
- `{"errors":["maxlag"]}` and `{"error":"maxlag"}` throw a `WikiError`, not `IllegalArgumentException`.
- `whoAmI` and token requests throw `WikiError.Api` on a `userinfo` or `tokens` block in the wrong shape.
- A PagePile list no longer fails as a whole when one entry is not a title.
- `Messages` gives Polish 21, 31 and 101 the many form, and `arz`, `ary`, `be-tarask`, `sr-ec` and `sr-el`
  the rule of the language they fall back to.
- An empty answer to `ScriptedPrompt` takes the default, or the first choice, as Enter does at a terminal.
- `Markup.text` keeps a bare URL, so `Template.value` of `url=https://…` is no longer empty.
- `Template.withParameter("3", value)` on a call with one positional parameter sets the third, not the
  second.
- `removeCategory` takes a category's line break with it on the first or last line too.
- `containsText` and `textIn` no longer read an external link's URL, which `replaceText` never changes.
- `CosmeticChanges.HTML_EMPHASIS` leaves an empty `<b></b>` or `<i></i>` alone.
- `CosmeticChanges.HTML_ENTITIES` no longer throws on `&#1114112;`, and keeps references such as `&#124;`
  and spaces as written.
- `FakePageService` keeps the text of a new section added with `prependText`.
- The `Credentials.OAuth2` and `WikiHttpClient` docs say the client keeps the wiki's cookies with a token,
  as it always has.
- `FakePageService` no longer loses edits when a bot run calls it from several threads.
- Templates, links or tags nested a few hundred deep no longer overflow the stack in `Wikitext.parse`,
  `serialize()` and the tree operations.

## [1.3.0] — 2026-10-18

`botMain` sets up a bot in one call, `renderer.parse` and `expandTexts` take a revision, and old revisions
are read many to a request. Source-compatible but for new cases of `WikiError.Transport`, `StopReason` and
`Title.Invalid.Reason` and two new parameters on `PageService.expandTexts`, and binary-compatible but for
`expandTexts` and the constructors and `copy` of `RenderRequest` and `ParsedPage`.

### Added

- `botMain(args) { … }` with `BotStart`, which reads the common flags and `kwikibot.toml`, logs in, and
  refuses an anonymous session and a save outside the bot group.
- `RevisionService.contentsAt`, which reads many old revisions in one request, and
  `PageDecoder.decodeContents`.
- `ParseProperty.LIMIT_REPORT` and `ParsedPage.limitReport`, and `RenderRequest.revision` and `parser`,
  with `Parser.LEGACY` and `PARSOID`.
- `sandbox` and `revision` on `PageService.expandTexts`.
- `Template.functionName` and `functionArgument`, a parser function's name and what follows its colon.
- `WikiError.Transport.Refused`, `StopReason.REFUSED` and `Title.Invalid.Reason.UNKNOWN_NAMESPACE`.

### Changed

- A 4xx whose body is not an API answer is raised at once as `WikiError.Transport.Refused`, where 1.2.0
  retried it five times, and stops a run with `StopReason.REFUSED`.
- A request that times out fails with `WikiError.Transport.Timeout`, not `Unreachable`.
- A run whose stop policy refuses, or cannot be checked, before anything is read returns a stopped
  `BotReport` instead of throwing.
- `Wiki.templateNames` gives names as a call writes them, without `Template:` unless the bare name means
  another namespace.
- A title in a namespace the wiki lacks is invalid to `Title.parse`, and `NamespaceMap.format` throws
  `WikiError.Page.BadTitle` on it.
- `Title.parse` rejects a title holding U+FFFD, as MediaWiki does, and so a reference to no valid
  character.

### Removed

- The macOS Intel build of the `kwikibot` tool. Homebrew on an Intel Mac refuses the formula.

### Fixed

- `CosmeticChanges.EMPTY_SECTIONS` keeps a heading whose subsections have content, such as `==English==`
  above `===Noun===`.
- `replaceText` with `TextScope(comments = true)` no longer closes an unclosed `<!--`.
- `ReformatIsbns(toIsbn13 = true)` keeps an ISBN's hyphens and leaves a thirteen-digit one as written.
- With `readBatch` above one, each page reaches `onOutcome` as soon as it is done, and the calls never
  overlap.
- `lists.specialPage()` and `revisions.deletedHistory` work, and `pages.categoryInfo` calls only hidden
  categories hidden.
- `ParsedPage.images` names files with spaces, not underscores.
- `revisions.contentAt` reads an old revision's redirect target from that revision.
- `Title.parse` no longer throws on a character reference past U+10FFFF.
- The `kwikibot` tool exits with a failure status when a command fails, prints no stack trace for a
  configuration mistake, and calls a blank password variable unset.

## [1.2.0] — 2026-10-02

Long runs survive a lost session and resume after a stop, and the parser reads wikitext as MediaWiki does.
Source-compatible but for `PageOutcome.NotAttempted`, and binary-compatible but for `copy` on the classes
that gained properties.

### Added

- A renamed API value is sent under the name the wiki takes, read from its `paraminfo`.
- `ParseProperty.TOC_DATA`, `ParamInfo.values`, `isDeprecated` and `prefetch`,
  `WikiError.Configuration.Unsupported` and `SiteInfo.hasVersion`.
- `WikiConfig.onWarning`, `ApiWarning.code` and `isDeprecation`, and `FailOnDeprecation` in the testkit.
- `ExtensionService.abuseFilters`, and `newPages` and `newPageStats` for PageTriage.
- Re-login: when the wiki ends a session, the client logs in again and repeats the request, at most
  `WikiConfig.relogins` times.
- `WikiConfig.listener`, a `TransportListener` that hears responses, retries, pauses and re-logins.
- `WikiConfig.http`, `Identity.batchLimit` and `withContent(..., concurrency)`.
- `PageService.freshContents`, `expandTexts` and `redirectsTo`, `RenderService.parse` with
  `TemplateSandbox`, and `Wiki.templateNames`, `requireGroup`, `parseOptions` and `titleRules`.
- `BotRunBuilder.createMissing`, `state` and `resume` with `RunState`, `Edit.tags`, `section` and
  `createOnly`, `BotReport.stopReason` and `StopPolicy.check()`.
- `CommonOptions.parse(args)`, `Credentials.fromEnvironment()`, `BotConfig.connect(client)`, and `[oauth]`,
  `[run]`, `[http]` and `[retry]` in `kwikibot.toml`.
- `XmlDump` reads `.bz2` and multistream dumps, `XmlDump.scan(path, workers)` reads one in parallel, and
  `DumpPage.redirectTarget`.
- `ParseOptions`, `Wikitext.parseExact` and `roundTrips`, raw keys and values on templates and parameters,
  `TitleRules`, `MagicWords`, `mapTemplates` and `Markup.ranges()`.
- `<pre format="wikitext">`, `<inputbox>` and `<DynamicPageList>` bodies are read as MediaWiki expands
  them, with `ParseOptions.preprocessedTags`.
- `MockTransport`, `FakeWiki` taking any service, `FakePageService` refusing edits as a wiki does, and
  `EditBuilder.validate()`.

### Changed

- A saving run reads pages and the stop page past the response cache, and saves to existing pages with
  `nocreate`.
- A stopped run takes no more pages, and those it held are `NotAttempted`, not `Skipped`.
- With `readBatch` above one, each page is reported as it finishes.
- `readonly` and database errors are retried with backoff, and retries are logged at `INFO`.
- A client allows 32 requests at once, not five, and a bot account queries 500 titles at a time, not 50.
- Parsed trees follow MediaWiki where 1.1 did not, for stray `[`, `|` and `=`, headings, unclosed `<!--`,
  which HTML tags count, and `{{PAGENAME|x}}`.
- `Template.title` keeps nested templates, and `Markup.headings()` gives section headings only.
- HTML tags pair where MediaWiki ends them: a `<span>` ends at a paragraph break or a heading, a `<div>`
  does not.
- Serializing a tree is about twice as fast.
- `RunLog` names pages with their namespace, and `Progress` shows the rate and time left.
- `kwikibot.toml` is looked for in the home directory too, and an unknown key warns.
- Opening a wiki older than MediaWiki 1.39 logs a warning.
- Built on Kotlin 2.4.20, Ktor 3.6.0 and SLF4J 2.0.20.

### Deprecated

- `ParseProperty.SECTIONS`: use `TOC_DATA` (MediaWiki 1.46, T319141).
- `AbuseFilterProperty.STATUS`, `PRIVATE`, `PROTECTED` and `SUPPRESSED`: use `FLAGS` (MediaWiki 1.47,
  T435834).
- The 1.1 constructors and `withContent` signatures, hidden so code compiled against 1.1 still links.

### Fixed

- `recentChanges` and `watchlistChanges` work without the `patrol` or `patrolmarks` right, and the
  watchlist reports patrolled changes.
- `allUsers` asks only for properties `list=allusers` takes.
- `ParamInfo.limit` is set.
- `lintErrors` checks that Linter is installed.
- `EntityService.search` no longer sends `type=mediainfo`, which the API refuses.
- `requireVersion` names the required and running versions the right way round.
- `backlinks(includeRedirects = true)` keeps pages that link through a redirect.
- `backlinksOf`, `transclusionsOf` and `fileUsageOf` follow every continuation.
- `PageContent.redirectTarget` is set.
- `Template.withParameter` no longer writes a value that changes the template's parameters.
- `shortenUrl` sends no token, and uploads assert the account.
- A closing tag keeps its spacing and case, a bare attribute its trailing space, and a bare URL ends with
  its heading or parameter.
- `Markup.replace`, `mapNodes` and `replaceText` reach tag attributes.
- `CosmeticChanges.HTML_EMPHASIS` no longer turns an unclosed or multi-line `<b>` into `'''` that ends
  sooner, and `EMPTY_SECTIONS` no longer joins a heading to the line before.
- `XmlDump` closes a file whose sequence is never read, and reads past the JDK's entity limit.
- `FakePageService` keeps `Template:X` and `X` apart.

## [1.1.1] — 2026-09-06

### Fixed

- `SparqlClient.selectStreamed` returns the rows, where 1.1.0 returned none.

## [1.1.0] — 2026-09-05

A bot holds far less in memory, so a sweep of German Wiktionary's 353,005 entries finishes.
Source-compatible except where Changed says, and not binary-compatible with 1.0.x.

### Added

- `SparqlClient.selectStreamed(query) { rows -> … }`, which hands rows over as they are read.
- `ExternalSources.withPetScanTitles(...)`, the streamed form of `petScanTitles`. PetScan and SPARQL
  sources no longer hold their whole list.
- `BotReport.problems` and `problemsTruncated`, the refusals and failures, at most
  `BotReport.PROBLEM_LIMIT`.

### Changed

- `BotReport` holds counts, not every outcome, and `outcomes` is gone. `BotRunBuilder.onOutcome` sees each
  outcome as it happens.
- PetScan is read as plain text, a ninth of the JSON's size. `ExternalSources.petScanTitles` suspends, and
  `titlesFromPetScan` is gone.
- A chunked upload reads a chunk at a time, so a 2 GB file no longer needs 2 GB of heap.
- Parsing allocates 23.26 bytes per source character, down from 24.72.

## [1.0.3] — 2026-09-03

### Added

- `SparqlClient` takes credentials, such as `SparqlAuth.wcqs(token)` for the Commons Query Service, and
  retries a `429` or `5xx` as `RetryPolicy` says.
- `SparqlClient.COMMONS` and `LINGUA_LIBRE`, beside `WIKIDATA`.
- `ExternalSources.sparql(...)`, a query service as a `PageSource`.
- `ExternalSources.petScan(parameters, ...)`, which takes the query itself rather than a saved PSID.
- `ExternalSources.petScanTitles(...)`, which gives titles rather than pages to edit.
- `BotRunBuilder.readBatch`, the pages read per request, 1 by default.
- `Section.withSubsectionAt(index, subsection)`, where `withSubsection` appends.

### Changed

- Every public type moved into a subpackage named for what it does, so every import changes: `model.Title`
  is `model.title.Title`, and `client.PageService` is `client.service.PageService`.
- Implementation classes left the public packages for `internal` ones.
- `ExternalSources.petScan` and `pagePile` take a `UserAgent`.
- `SparqlClient`'s constructor takes two more parameters, with defaults. Not binary-compatible with 1.0.x.

### Fixed

- `ExternalSources.petScan` and `pagePile` send a `User-Agent`, as Wikimedia's policy asks.
- `''italic''` and `'''bold'''` end with their line, as in MediaWiki, so an unmatched `'''` no longer hides
  the sections after it.

## [1.0.2] — 2026-09-02

### Changed

- `kwikibot` is a native binary, not a bundled JVM, and starts in 94 ms instead of 560 ms.

## [1.0.1] — 2026-09-02

### Changed

- `UserAgent.LIBRARY_VERSION` is a `val`, not a `const val`. Not binary-compatible with 1.0.0.
- Without a jar to read, the version is `dev`.

### Fixed

- A release reports its own version, not the one before it.
- A missing configuration prints a message, not an `IllegalArgumentException` and a stack trace.
- The Homebrew formula downloads the published archives, with their real checksums, and its `kwikibot`
  starts.

## [1.0.0] — 2026-09-02

First release.

### Added

- Pages read singly and in batches, edited with a full `EditOutcome`, moved, deleted, undeleted, protected,
  rolled back, undone, watched and purged, with section edits, `testActions` and `flagged()`.
- Lists, from category members and backlinks to logs and watchlists, as cold `Flow`s with filters for
  namespace, title, subpage depth and set operations.
- `Markup`, a lossless wikitext parser and editor: sections, templates, categories, language links, scoped
  text replacement, unified diffs, ISBNs, signature times and `CosmeticChanges`.
- Wikibase items, properties, lexemes and MediaInfo as a sealed `Entity`, with statements, qualifiers,
  references and data values.
- EventStreams with resumable offsets, SPARQL, XML dumps, PetScan and PagePile.
- `Family`, `SiteMatrix`, `ApiDetector`, `paramInfo`, and `requireRight`, `requireExtension` and
  `requireVersion`.
- Throttling, `maxlag` and rate-limit retries, a disk response cache, and continuation as a `Flow`.
- GeoData, PageImages, TextExtracts, WikibaseClient, Linter, Echo, Thanks, UrlShortener, FlaggedRevs,
  GlobalUsage and ProofreadPage, each refusing when absent.
- `botRun`, a dry run by default, with bounded concurrency, a fail-closed `StopPolicy`, `{{nobots}}`
  exclusion, page sources, filters, `RunLog` and `Progress`.
- `kwikibot-testkit`, with `FakeWiki` and `FakePageService`, so a bot's tests need no network.
- `kwikibot`, a command-line tool that tells whether a problem is the bot or the wiki, in Scoop, Homebrew,
  Debian and RPM packages and archives.

[Unreleased]: https://github.com/fenakhay/kwikibot/compare/v1.4.0...HEAD
[1.4.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.4.0
[1.3.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.3.0
[1.2.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.2.0
[1.1.1]: https://github.com/fenakhay/kwikibot/releases/tag/v1.1.1
[1.1.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.1.0
[1.0.3]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.3
[1.0.2]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.2
[1.0.1]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.1
[1.0.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.0
