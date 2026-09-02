# Changelog

Notable changes to kwikibot, newest first.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). The public API of every
published module is recorded in `*/api/*.api` and checked on every build, so a breaking change
cannot reach a release without showing up as a diff first.

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

[1.0.3]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.3
[1.0.2]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.2
[1.0.1]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.1
[1.0.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.0
