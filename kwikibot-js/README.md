<h1>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fenakhay/kwikibot/main/brand/logo-light.svg">
    <img alt="kwikibot" src="https://raw.githubusercontent.com/fenakhay/kwikibot/main/brand/logo-dark.svg" height="48">
  </picture>
</h1>

Read, parse and edit MediaWiki wikis from JavaScript and TypeScript: a wikitext parser that writes
text back byte for byte, a client for any wiki's `api.php`, Wikibase entities, SPARQL, and bot runs
that are dry runs unless told to save. It is [kwikibot](https://github.com/fenakhay/kwikibot), the
Kotlin library, compiled to JavaScript.

```bash
npm install kwikibot
```

Up to 1.4.0 this package was called `@fenakhay/kwikibot`. To move, run `npm uninstall @fenakhay/kwikibot`, then
`npm install kwikibot`, and import from `"kwikibot"`.

The package is CommonJS with TypeScript definitions, and needs Node 22.3 or newer. Its everyday names, such
as `connect`, `Wiki`, `parse` and `botMain`, come from `"kwikibot"`; the rest come from the area they belong
to: `kwikibot/client`, `kwikibot/errors`, `kwikibot/wikitext`, `kwikibot/titles`, `kwikibot/bot`,
`kwikibot/dumps`, `kwikibot/wikibase`, `kwikibot/sparql`, `kwikibot/events`, `kwikibot/fleet`,
`kwikibot/proofread` and `kwikibot/testing`:

```js
import { connect } from "kwikibot";

const contact = "https://en.wiktionary.org/wiki/User:MyBot";
const wiki = await connect("en.wiktionary.org", contact);

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

`contact` is a URL or email address the wiki's operators can reach you at, which Wikimedia's
user-agent policy asks for. `page` resolves to `null` for a page that does not exist. Listings are
async iterables that fetch as the loop asks, so `break` ends the paging.

A bot run takes a transform that returns the page's new text, or `null` to leave the page alone,
and may return a promise of either:

```js
import { BotPassword, connect, parse } from "kwikibot";

const login = new BotPassword("MyBot", "task", process.env.MYBOT_PASSWORD);
const bot = await connect("en.wikipedia.org", contact, login);

const report = await bot.run(
  ["Mount Etna", "Mount Vesuvius"],
  "adding [[Category:Volcanoes]]",
  (text) => {
    const code = parse(text);
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

Pass a `stopPage` as `run`'s sixth argument, a page that has to say `false` for the run to save, so it
can be stopped from the wiki. A saving run without one logs a warning. `maxEdits`, the seventh, caps what it
saves.

XML dumps read as async iterables, with bzip2 decoded on worker threads so the event loop stays free.
A browser has no files, so `dumpPagesOf` reads a small dump from bytes, on one thread. A bundled bot reads
the same pages on the main thread instead, since a worker can only be started from the package's own file:
leave `kwikibot` out of the bundle, such as with esbuild's `--external:kwikibot`, to keep the worker threads.

```js
import { dumpPages } from "kwikibot/dumps";

for await (const page of dumpPages("enwiktionary-latest-pages-articles.xml.bz2", [0])) {
  if (page.text.includes("{{col|")) console.log(page.title);
}
```

Many bots can run in one process, one lane each, sharing logins and a stop page. A bot that fails stays
failed alone, and a schedule can run a bot through `serve`. JavaScript has one thread, so lanes take turns
at each `await`:

```js
import { createFleet, cron } from "kwikibot/fleet";

const fleet = await createFleet(
  { server: "en.wiktionary.org", contact, login, stopPage: "User:MyBot/Stop", onLine: console.log },
  [
    {
      id: "tidier",
      description: "takes {{old}} off a page",
      schedule: cron("0 3 * * *", "Europe/Berlin"),
      run: (context) => context.run(["Volcano"], "removing {{old}}", (text) => text.replace("{{old}}", ""), 500),
    },
  ],
);
fleet.start("tidier", [], true);
await fleet.idle();
fleet.close();
```

`fleetMain` is the same thing as a command-line program, with `list`, `run <bot>`, `run --all` and
`serve`.

`wiki.botRun` is a run with every setting there is, and `botMain` starts one from a command line
and `kwikibot.toml`:

```js
import { skipPage } from "kwikibot";
import { parsePageSource } from "kwikibot/bot";

const report = await wiki.botRun({
  source: parsePageSource("cat:Volcanoes").titleNotMatching(/\//),
  stopPolicy: wiki.stopPage(),
  dryRun: false,
  maxEdits: 100,
  readBatch: 50,
  transform: (page) => {
    if (page.isRedirect) skipPage("a redirect");
    return { text: page.text + "\n[[Category:Mountains]]", summary: "adding a category" };
  },
  onOutcome: (outcome) => console.log(outcome.title, outcome.kind),
});
```

To test a bot without a wiki, `createFakeWiki` makes one in memory. It is a `Wiki`, so `botRun` and the rest
work on it, and its `FakePageService` reads back what was saved. What the fake does not implement rejects
saying so, such as `lists`, rather than answering with nothing; `globalThis.fetch` stubbed, in Node or in a
browser, answers for the whole of a real `connect`, and a `MockTransport` for what `wiki.call` sends.
`new RecordingContext(wiki)` stands in for a fleet, and `onWarning: failOnDeprecation()`
fails a test whose request draws a deprecation.

```js
import { pageSourceOfTitles } from "kwikibot/bot";
import { createFakeWiki, FakePageService } from "kwikibot/testing";

const pages = new FakePageService({ pages: { Volcano: "ash" } });
const wiki = createFakeWiki({ pages });
await wiki.botRun({
  source: pageSourceOfTitles(["Volcano"]),
  dryRun: false,
  transform: (page) => ({ text: page.text + "!", summary: "louder" }),
});
console.assert(pages.text("Volcano") === "ash!");
```

The package covers the parser (`parse`), titles and signature times, reading and editing pages,
category members, search, transclusions and history, Wikibase entities, SPARQL (`sparql`), XML dumps, bot runs
and fleets. Revision ids are numbers and lists are arrays.

It runs in browsers too, through a bundler. From a page on a site other than the wiki's, requests
carry `origin=*`, which MediaWiki answers as it would a logged-out reader, so logging in works on
Node and from a page on the wiki's own site. It logs to the console from `INFO` up, the login
included, and `setLogLevel` changes that.

Licensed under the Mozilla Public License 2.0. Release notes are in the
[CHANGELOG](https://github.com/fenakhay/kwikibot/blob/main/CHANGELOG.md).
