# kwikibot-benchmarks

JMH benchmarks for the wikitext parser, run over real page text rather than over examples chosen
to flatter it. This module is not published, and it does not run as part of `build`.

```bash
./gradlew :kwikibot-benchmarks:quickBenchmark
```

Roughly a minute. Accurate enough to notice that something is wrong, so use it while working.

```bash
./gradlew :kwikibot-benchmarks:mainBenchmark
```

About three minutes, five measured iterations after three warmups. These are the numbers worth
quoting.

## Comparing two runs

`baseline.json` is a committed `mainBenchmark` report, and comparing against it is the point of
the suite:

```bash
./gradlew :kwikibot-benchmarks:compareToBaseline
```

That runs `mainBenchmark`, prints the difference, and fails the build if anything got slower by
more than its error bars. To compare two reports you already have:

```bash
./gradlew :kwikibot-benchmarks:compareFiles -Pbefore=old.json -Pafter=new.json
```

To move the baseline forward, once a change is understood and wanted:

```bash
./gradlew :kwikibot-benchmarks:recordBaseline
```

**A baseline is only comparable to a run from the same machine.** These are laptop numbers, and a
comparison against a baseline recorded somewhere else measures the hardware instead of the code.
If the machine has changed, re-record before comparing.

## What is measured

The corpus is the 230 pages in `roundtrip-pages.json.gz`, the same ones the round-trip test
replays, drawn at random from en.wiktionary (entries and templates), en/fr/de/ja.wikipedia and
Commons. 764,000 characters in all. One operation is a pass over the whole of it, so divide to get
a rate.

`parseLargePages` reads a second corpus, `large-pages.json.gz`: eight pages of a quarter of a
megabyte each, 1.95M characters, recorded by `:kwikibot-tools:wikitextLargePageDump`. The
round-trip corpus skips anything over 80KB and its largest page is 38KB, so nothing in it
exercises the parser at the size a long article reaches. These pages are only ever timed. Checking
that the parser is correct is the other corpus's job.

| Suite | What it times |
|---|---|
| `ParserBenchmark` | Parsing, serializing, and the two together, split so a regression can be attributed to one of them. Short and long pages are timed separately, since a category walk feels the per-page cost and an article feels the per-character one |
| `QueryBenchmark` | Questions asked of a page that is already parsed: templates, links, headings, the section tree, visible text. Parsing happens in setup, so what these time is the query |
| `PathologicalBenchmark` | Input that invites backtracking, including the shape that once took the parser from linear to exponential on a real page. If these ever stop returning rather than merely getting slower, that bug is back |

They are kept apart because they fail in different ways, and one number covering all three would
hide which of them moved.
