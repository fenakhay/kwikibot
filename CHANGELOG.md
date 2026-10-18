# Changelog

Notable changes to kwikibot, newest first.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). The public API of every
published module is recorded in `*/api/*.api` and checked on every build, so a breaking change
cannot reach a release without showing up as a diff first.

## [Unreleased]

A value of the wrong JSON type now reads as absent instead of throwing, and the accessors that
read it are public. `Messages` takes its plural rules from CLDR. The build requires every module to
cover 90% of its branches as well as 95% of its lines.

Source- and binary-compatible. Decoding behaves differently in the cases listed under Changed, and
`kwikibot-bot` depends on `net.xyzsd.plurals:cldr-plural-rules`.

### Added

- JSON accessors in `com.fenakhay.kwikibot.protocol.decode`: `string`, `long`, `int`, `double`,
  `boolean`, `flag`, `time`, `obj` and `array` on `JsonObject`, and `strings` and `objects` on
  `JsonArray?`. An absent key, JSON `null` and a value of the wrong type all read as absent.

### Changed

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
- `Messages` reads plural rules from CLDR 48 through `cldr-plural-rules`, a 27 kB library with no
  dependencies of its own. Each language CLDR covers gets its own rule. The hand-written table had
  seventeen, and the rest took English's, Czech, Lithuanian, Maltese, Hebrew and Welsh among them.
  Forms are numbered as MediaWiki numbers them, by the categories the language has, and a variant
  code such as `sr-ec` or `de-formal` takes its language's rule.
- `replaceText`, `containsText` and `textIn` leave an argument's name alone unless the scope
  includes templates. In `{{{colour|colour}}}`, prose reaches only the default.

### Removed

- Decoding of formatversion=1 shapes: content and warning text under `"*"`, warnings keyed by
  module, log parameters under the log type's key, an empty `case-sensitive` flag, and the move
  parameters `new_ns`, `new_title` and `suppressedredirect`. kwikibot asks for formatversion=2.
  The errorformat=bc `error` object is still read.

### Fixed

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

[Unreleased]: https://github.com/fenakhay/kwikibot/compare/v1.3.0...HEAD
[1.3.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.3.0
[1.2.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.2.0
[1.1.1]: https://github.com/fenakhay/kwikibot/releases/tag/v1.1.1
[1.1.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.1.0
[1.0.3]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.3
[1.0.2]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.2
[1.0.1]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.1
[1.0.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.0
