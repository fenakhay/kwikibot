# Changelog

Notable changes to kwikibot, newest first.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). The public API of every
published module is recorded in `*/api/*.api` and checked on every build, so a breaking change
cannot reach a release without showing up as a diff first.

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

[1.0.1]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.1
[1.0.0]: https://github.com/fenakhay/kwikibot/releases/tag/v1.0.0
