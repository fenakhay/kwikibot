# Comparison with other MediaWiki libraries

This page sets kwikibot beside [pywikibot](https://www.mediawiki.org/wiki/Manual:Pywikibot) and
[mwclient](https://github.com/mwclient/mwclient) in Python, [mwn](https://github.com/siddharthvp/mwn)
and [m3api](https://gitlab.wikimedia.org/repos/m3api/m3api) in JavaScript, and three wikitext parsers:
[mwparserfromhell](https://github.com/earwig/mwparserfromhell),
[wikiparser-node](https://github.com/bhsd-harry/wikiparser-node) and
[wtf_wikipedia](https://github.com/spencermountain/wtf_wikipedia).

Every claim about another library comes from its own documentation and source, read on 30 October
2026, and describes the release named in the table's header. A correction is welcome as an issue or
a pull request.

## Features

✓ means built in, – means not built in, and anything else says how far it goes.

### Clients and bot frameworks

| | kwikibot | pywikibot 11.9.0 | mwclient 0.11.0 | mwn 3.0.3 | m3api 1.1.0 |
|---|---|---|---|---|---|
| Language | Kotlin | Python | Python | TypeScript | JavaScript |
| Runs on | JVM 21+, macOS, Linux, Windows; Node 26+ and browsers | Python 3.9+ | Python 3 | Node 14+ | Node 18.2+, browsers |
| Reads and edits pages | ✓ | ✓ | ✓ | ✓ | As raw API requests |
| Wikitext parser | ✓ | Through mwparserfromhell | – | Flat lists, no tree | – |
| Dry run by default | ✓ | Opt-in, `-simulate` | – | – | – |
| Stop page | Opt-in, `stopPolicy` or `--stop-page` | – | – | Opt-in, `enableEmergencyShutoff` | – |
| `{{bots}}` and `{{nobots}}` | Opt-in, `exclusionPolicy` | ✓, on every save | – | Opt-in regex, `edit()` only | – |
| `maxlag` by default | 5 | 30 on reads, 5 on writes | Only on `index.php` | 5 | – |
| Retries | Lag, `readonly`, 429, 5xx, database errors | Lag, `readonly`, `ratelimited`, 5xx | Lag, 5xx, database errors | Lag, `readonly`, `badtoken`, 429, 5xx | `Retry-After`, lag, `readonly`, `badtoken` |
| Wikibase | ✓ | ✓ | – | As raw API requests | As raw API requests |
| SPARQL | ✓ | ✓ | – | ✓ | – |
| EventStreams | ✓ | ✓ | – | – | – |
| XML dumps | JVM only | ✓ | – | – | – |
| Fake wiki for tests | `kwikibot-testkit` | – | – | – | – |

### Wikitext parsers

| | kwikibot | mwparserfromhell 0.7.2 | wikiparser-node 1.48.1 | wtf_wikipedia 10.4.2 |
|---|---|---|---|---|
| Language | Kotlin | Python, with a tokenizer in C | TypeScript | JavaScript |
| Runs on | JVM 21+, macOS, Linux, Windows; Node 26+ and browsers | Python 3.9+ | Node 20.19+, 22.13+ or 24.11+; a browser bundle | Node 12+, browsers |
| A parse gives | A tree | A tree | A tree | Sections, sentences, templates, infoboxes |
| Unchanged text written back exactly | ✓ | Tested, not promised | ✓, less NUL, DEL and CR before a line break | – |
| Edits written back | ✓ | ✓ | ✓ | – |
| Talks to a wiki | ✓ | – | Fetches its parser configuration | Reads pages |

A kwikibot run saves only with `dryRun = false` or `--save`, and retries up to 5 times, waiting as
long as `Retry-After` asks; [Running on Wikimedia wikis](../README.md#running-on-wikimedia-wikis)
lists its other defaults. kwikibot runs on Node and in browsers through its npm package, which covers
parsing, reading and editing pages, listings, Wikibase entities, SPARQL and bot runs. See
[From JavaScript and TypeScript](../README.md#from-javascript-and-typescript).

## Performance

Both the pages and the way they are timed are kwikibot's own choice, and other pages or machines
give other numbers. Time your own pages before choosing on a small difference. How each number was
taken is under [How the numbers were measured](#how-the-numbers-were-measured).

| | Version | Runtime |
|---|---|---|
| kwikibot | `main` at `1b2ff37` | Oracle GraalVM for JDK 25; Kotlin/Native on macOS; Node 26.12.0 |
| mwparserfromhell | 0.7.2 | CPython 3.13, C tokenizer |
| wikiparser-node | 1.48.1 | Node 26.12.0 |
| wtf_wikipedia | 10.4.2 | Node 26.12.0 |
| mwn | 3.0.3 | Node 26.12.0 |
| m3api | 1.1.0 | Node 26.12.0 |

All on an Apple M3 Pro (11 cores, 36 GB) running macOS, on 30 October 2026, one library at a time.

The pages are kwikibot's test corpora: the 230 pages its round-trip test replays, drawn from
en.wiktionary, four Wikipedias and Commons, 764,000 characters in all, and eight pages of a quarter
of a megabyte each, 1.95 million characters. kwikibot, mwparserfromhell and wikiparser-node each
wrote all 238 pages back exactly.

### Parsing

Milliseconds for one pass over a corpus, lower is better. Relative is against the fastest.

| Parser | Runs on | 230 pages | Relative | 8 large pages | Relative |
|---|---|---|---|---|---|
| kwikibot | JVM | 10.3 | 1.0× | 22.3 | 1.0× |
| kwikibot | macOS, native | 22.3 | 2.2× | 50.0 | 2.2× |
| kwikibot | Node | 38.5 | 3.7× | 79.2 | 3.6× |
| wtf_wikipedia | Node | 77.6 | 7.5× | 120.9 | 5.4× |
| mwparserfromhell | Python | 230 | 22× | 467 | 21× |
| wikiparser-node | Node | 557 | 54× | 761 | 34× |

An average page of the 230 takes kwikibot 0.04 ms on the JVM and 0.17 ms on Node, wtf_wikipedia
0.34 ms, mwparserfromhell 1.0 ms and wikiparser-node 2.4 ms. Each row times a runtime as well as a
parser, and a project picks the two together. wtf_wikipedia also splits text into sentences and reads
infoboxes, which the others leave to the caller.

### Parsing and writing back

The same passes, writing each page back out after parsing it. wtf_wikipedia does not write
wikitext. Lower is better.

| Parser | Runs on | 230 pages | 8 large pages |
|---|---|---|---|
| kwikibot | JVM | 11.7 | 23.9 |
| kwikibot | macOS, native | 24.9 | 55.1 |
| kwikibot | Node | 40.3 | 80.3 |
| mwparserfromhell | Python | 240 | 485 |
| wikiparser-node | Node | 595 | 737 |

### Memory

What one process holds once it has parsed all 238 pages and kept every result. Lower is better.

| Parser | Runs on | Held | Per character parsed |
|---|---|---|---|
| kwikibot | JVM | 20 MB | 8 bytes |
| kwikibot | macOS, native | 43 MB | 16 bytes |
| kwikibot | Node | 43 MB | 17 bytes |
| wtf_wikipedia | Node | 28 MB | 11 bytes |
| mwparserfromhell | Python | 52 MB | 20 bytes |
| wikiparser-node | Node | 299 MB | 116 bytes |

wtf_wikipedia holds less than the parsers that keep every byte because it keeps only what it
extracted from the page.

### Start-up and size

A fresh process loads the library and, for a parser, parses one en.wiktionary page of 982
characters. Lower is better.

| Library | Runs on | Load | First parse | Code loaded | Installed, with dependencies |
|---|---|---|---|---|---|
| kwikibot | JVM | – | 21 ms | – | 2.3 MB in 3 jars |
| kwikibot | macOS, native | – | 0.3 ms | – | 1.9 MB, one executable |
| kwikibot | Node | 34 ms | 5.6 ms | 3.6 MB in 25 files | 9.5 MB, 3 packages |
| wtf_wikipedia | Node | 6.7 ms | 5.6 ms | 0.32 MB in 2 files | 1.8 MB, 7 packages |
| wikiparser-node | Node | 8.8 ms | 36 ms | 0.87 MB in 98 files | 15.0 MB, 121 packages |
| mwn | Node | 45 ms | – | 1.0 MB in 101 files | 6.5 MB, 45 packages |
| m3api | Node | 38 ms | – | 1.2 MB in 125 files | 4.8 MB, 7 packages |
| mwparserfromhell | Python | 27 ms | 0.4 ms | – | 0.8 MB, no dependencies |

The JVM loads kwikibot's classes as the first parse reaches them, so there the first parse includes
loading them, and a native executable has nothing to load. wikiparser-node loads most of its code at
the first parse. kwikibot's npm package loads every module at once, its HTTP client included, so
against wtf_wikipedia it makes up its 27 ms of extra start-up only after about 160 pages of the
average size above.

The runtime itself takes time to start: an empty program took 99 ms on Node here, 23 ms on Python,
58 ms on the JVM and 8 ms natively. A JVM process that parsed the page took 40 ms longer than an
empty one, and a native one took no measurable extra time.

## How the numbers were measured

kwikibot on the JVM and natively is timed by its own suite, `kwikibot-benchmarks`: the mean of five
one-second iterations after three warm-ups. That suite does not write the large pages back, so a
separate run with the same harness and settings timed parsing them and writing them back, and both
large-page columns for the JVM and native come from it. Parsing the large pages took 8% longer on the
JVM and 10% longer natively in that run than in the suite's own, so two runs on this machine can
differ by that much.

The other parsers, and kwikibot's npm package, are timed by a script that runs each parser in a
process of its own and takes the median of 15 passes over the 230 pages and 10 over the large ones,
after 5 and 3 warm-up passes. It ran twice, interleaving the parsers, and the tables give the mean of
the two rounds, which agreed within 10%.

Memory is what is live after forced collections, less what was live before parsing: on the JVM the
heap after three `System.gc()` calls, natively the object size Kotlin/Native's collector reports, on
Node the heap after two collections, and on Python what `tracemalloc` counts. The JVM and native
figures are the median of five runs.

Start-up figures are the median of 21 fresh processes. Load is what `require` or `import` takes once
the runtime has started. On the JVM the parser needs `kwikibot-wikitext`, `kotlin-stdlib` and
JetBrains' `annotations`, and the native executable holds the parser, Kotlin's runtime and the file
reading this test needed. How much code the JVM and Python load was not measured.
