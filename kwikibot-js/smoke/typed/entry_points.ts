import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { botMain, type BotStart, Wiki } from "kwikibot";
import { BotConfig, CommonOptions, NonInteractive } from "kwikibot/bot";
import { type BotSpec, every, fleetMain, fleetMainWith } from "kwikibot/fleet";
import { FakeSite, assertArray } from "./support.js";

const site = new FakeSite();
const restoreFetch = site.install();
const folder = mkdtempSync(join(tmpdir(), "kwikibot-entry-"));
const startedIn = process.cwd();
const realExit = process.exit;

process.chdir(folder);
writeFileSync(
  "kwikibot.toml",
  `[bot]
name = "EntryBot"
contact = "https://example.org/typed"

[wiki]
server = "test.example.org"

[throttle]
read = "0s"
write = "0s"
`,
);

async function exitOf(start: () => void): Promise<number> {
  return new Promise((resolve) => {
    Object.defineProperty(process, "exit", {
      configurable: true,
      value: (code?: number) => {
        resolve(code ?? 0);
      },
    });
    start();
  });
}

let started: BotStart | undefined;
const status = await exitOf(() => {
  botMain(
    ["--limit=2", "mine"],
    async (start) => {
      started = start;
      return 7;
    },
    true,
  );
});
assert.equal(status, 7);
assert.ok(started != null);
assert.ok(started.wiki instanceof Wiki);
assert.equal(started.wiki.siteName, "Test Wiki");
assert.ok(started.options instanceof CommonOptions);
assert.equal(started.options.limit, 2);
assert.deepEqual(assertArray(started.arguments), ["mine"]);
assert.ok(started.config instanceof BotConfig);
assert.equal(started.config.bot.name, "EntryBot");

const nothingSaid = await exitOf(() => {
  botMain([], async () => {}, true);
});
assert.equal(nothingSaid, 0);

const ran: string[] = [];
const bots: BotSpec[] = [
  {
    id: "tidier",
    description: "tidies pages",
    schedule: every(3_600_000),
    run: async (context) => {
      ran.push(context.arguments.join(" "));
    },
  },
];

assert.equal(
  await exitOf(() => {
    fleetMain(["list"], bots);
  }),
  0,
);
assert.equal(
  await exitOf(() => {
    fleetMainWith(["run", "tidier", "first"], bots, new NonInteractive(false));
  }),
  0,
);
assert.deepEqual(ran, ["first"]);

Object.defineProperty(process, "exit", { configurable: true, value: realExit });
process.chdir(startedIn);
restoreFetch();

console.log("entry points: botMain and fleetMain started programs and exited with their status");
