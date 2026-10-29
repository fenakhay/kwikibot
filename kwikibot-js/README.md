# @fenakhay/kwikibot

Read, parse and edit MediaWiki wikis from JavaScript and TypeScript: a wikitext parser that writes
text back byte for byte, a client for any wiki's `api.php`, Wikibase entities, SPARQL, and bot runs
that are dry runs unless told to save. It is [kwikibot](https://github.com/fenakhay/kwikibot), the
Kotlin library, compiled to JavaScript.

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

`contact` is a URL or email address the wiki's operators can reach you at, which Wikimedia's
user-agent policy asks for. `page` resolves to `null` for a page that does not exist. Listings are
async iterables that fetch as the loop asks, so `break` ends the paging.

A bot run takes a transform that returns the page's new text, or `null` to leave the page alone,
and may return a promise of either:

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

The package covers the parser (`parse`), titles and signature times, reading and editing pages,
category members, search, transclusions and history, Wikibase entities, SPARQL (`sparql`) and bot
runs. Revision ids are numbers and lists are arrays.

It runs in browsers too, through a bundler. From a page on a site other than the wiki's, requests
carry `origin=*`, which MediaWiki answers as it would a logged-out reader, so logging in works on
Node and from a page on the wiki's own site. It logs to the console from `INFO` up, the login
included, and the level cannot be set from JavaScript.

MIT licensed. Release notes are in the
[CHANGELOG](https://github.com/fenakhay/kwikibot/blob/main/CHANGELOG.md).
