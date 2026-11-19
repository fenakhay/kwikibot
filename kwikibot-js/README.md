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
`kwikibot/dumps`, `kwikibot/wikibase`, `kwikibot/sparql`, `kwikibot/events`, `kwikibot/services`,
`kwikibot/fleet`, `kwikibot/proofread` and `kwikibot/testing`:

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

A login can also be an owner-only OAuth consumer, `new OAuth2(token)` or, for one that only has
OAuth 1.0a, `new OAuth1(consumerKey, consumerSecret, accessToken, accessSecret)`, which signs every
request and needs no login round trip. Either goes wherever a `BotPassword` does, and none of its secrets
shows in `JSON.stringify`, `util.inspect`, a spread or what a listener hears.
`credentialsFromEnvironment()` reads one from `KWIKIBOT_OAUTH_CONSUMER_KEY`,
`KWIKIBOT_OAUTH_CONSUMER_SECRET`, `KWIKIBOT_OAUTH_ACCESS_TOKEN` and `KWIKIBOT_OAUTH_ACCESS_SECRET`:

```js
import { connect, OAuth1 } from "kwikibot";

const signed = new OAuth1(
  process.env.MYBOT_CONSUMER_KEY,
  process.env.MYBOT_CONSUMER_SECRET,
  process.env.MYBOT_ACCESS_TOKEN,
  process.env.MYBOT_ACCESS_SECRET,
  "MyBot",
);
const wiki = await connect("en.wikipedia.org", contact, signed);
console.log(wiki.user.name);
wiki.close();
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

Wikitext tables read as MediaWiki reads them, and each change gives back the whole page, nothing outside the
change touched:

```js
import { parse } from "kwikibot";
import { createTableBuilder } from "kwikibot/wikitext";

const code = parse(text);
const table = code.tables()[0];
const heights = table.column("Height").map((cell) => cell?.text);
const renamed = table.rows[1].cells[0].withContent("[[Mount Etna]]");
const longer = renamed.tables()[0].withRow(["Fuji", "3,776 m"]);

const written = createTableBuilder().sortable().header(["Name", "Height"]).row(["Etna", "3,357 m"]).build();
```

A file can be uploaded from a URL the wiki fetches itself, which needs `$wgAllowCopyUploads`, a host in
`$wgCopyUploadsDomains` and the `upload_by_url` right, and so works in a browser too. A warning is a result
with a `fileKey` to publish; a wiki that takes no uploads from that URL rejects:

```js
const result = await wiki.files.uploadFromUrl("https://photos.example.org/volcano.jpg", "Volcano.jpg", {
  comment: "a volcano",
  text: "== Summary ==",
});
if (result.kind === "warned" && !result.isDuplicateOnly) await wiki.files.publishStashed(result.fileKey, "Volcano.jpg");
```

An account's preferences read and change as plain objects, `null` putting one back to its default, and
`setPreferences` resolves to the ones the wiki refused. `email` sends a real email to a person, so wikis
rate-limit it; one that cannot be delivered resolves to why rather than rejecting. `serverTime` is the wiki's
clock, and `globalUser` reads a CentralAuth account, `null` when there is none:

```js
const refused = await wiki.users.setPreferences({ "userjs-mybot-last-run": "2026-11-04" });
const sent = await wiki.users.email("Example", "Your bot request", "It has been approved.");
if (sent.kind !== "sent") console.log(sent.kind, sent.detail);
const drift = (await wiki.meta.serverTime()).getTime() - Date.now();
const global = await wiki.extensions.globalUser("Example", { properties: ["groups", "merged"] });
console.log(global?.home, global?.merged.length);
```

Page views come from Wikimedia's pageviews API, for Wikimedia's wikis from July 2015. A page or a period with
no data is an empty array, and `ofPages` counts a wiki's own pages, a few at a time, one request each:

```js
import { connect } from "kwikibot";
import { pageviews } from "kwikibot/services";

const views = pageviews(contact);
const january = new Date("2024-01-01T00:00:00Z");
const end = new Date("2024-01-31T00:00:00Z");

const einstein = await views.perArticle("en.wikipedia", "Albert Einstein", january, end, { agent: "user" });
console.log(einstein.reduce((sum, day) => sum + day.views, 0));

const wikipedia = await connect("en.wikipedia.org", contact);
for (const page of await views.ofPages(wikipedia, ["Mount Etna", "Mount Fuji"], january, end, { granularity: "monthly" })) {
  console.log(page.title, page.counts[0]?.views);
}
for (const top of await views.top("en.wikipedia", january)) console.log(top.rank, top.title);
views.close();
wikipedia.close();
```

Revision scores come from LiftWing, Wikimedia's machine-learning service, which replaced ORES. They are
advisory, a model's guess for a person to check. A revision it cannot score resolves with `unscorable` saying
why, `"missing"`, `"hidden"` or, for revert risk, `"first-revision"`, and a model or wiki it does not have
rejects. An `accessToken` raises the limit from 50,000 requests an hour to 100,000:

```js
import { liftWing } from "kwikibot/services";

const scores = liftWing(contact);
const damaging = await scores.damaging("enwiki", 1383620073);
if ((damaging.score?.trueProbability ?? 0) > 0.9) console.log("worth a look");
const risk = await scores.revertRisk("en", 1383620073, "multilingual");
console.log(risk.score?.probability ?? risk.unscorable?.reason);
scores.close();
```

The package covers the parser (`parse`), titles and signature times, reading and editing pages, files,
category members, search, transclusions and history, Wikibase entities, SPARQL (`sparql`), page views, XML
dumps, bot runs and fleets. Revision ids are numbers and lists are arrays.

It runs in browsers too, through a bundler. From a page on a site other than the wiki's, requests
carry `origin=*`, which MediaWiki answers as it would a logged-out reader, so logging in works on
Node and from a page on the wiki's own site. It logs to the console from `INFO` up, the login
included, and `setLogLevel` changes that.

Licensed under the Mozilla Public License 2.0. Release notes are in the
[CHANGELOG](https://github.com/fenakhay/kwikibot/blob/main/CHANGELOG.md).
