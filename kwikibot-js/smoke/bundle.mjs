import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";

const DUMP =
  "QlpoOTFBWSZTWZ9T9UsAARXf9dAQUQP9nwAEDRA/79/gAAwAAIAgCAACAEAIQAI8AABgABoAAyaAAANAYAAaAAMmgAADQGAAGgADJoAAA0AiimjUyNGKaaaNU80mKPSeiafooaeliCb1UhRWiqeve1oP2etLGYT0/2u9y06PcHt6MoYh5nnpu4vJwZOmePhr38x9oATTY/XU9nfgMPN2ts+OhRoWtniejKda3MhDcy4N7L5P3sbn7aBlaEP8up4OWzDB1upmkcCXzO0wu2/Hs+XVHi/wJhERF7+bGxxZZCCaSNjL59GvfMuSSy8Hwl6Pa9ze72rCQyXGXIwLSIdTAloW3srqaw3ussxCWCY58X46LGL7QnjrzA+4SONzcPuNn48zh6yPJzYue9m59WGHhY1AeI2Ojm37mHkm4JhiI7ZI0O4e2Sg0GHkOENGj2WjKZ0U2EubpbSU7C9df0TEqN44OBDCy/UCwdC8a7kkL21cjeBkmof248Gq6F2hmNgGfraNXWPoMj1NzUTGgxeMS1KBtq1bgbh6+MHubB933bR2DqZHMctQ87LxODi7Uh07nIcaaDgy1LcBgdqxA5r8DL4NxU69aWWRHFuXa9VZfDsIl0xZM2WHnQcQ/xxaNjtKjDR3Sy0XIdLh8rm5/sdhk4aJ9KH4drZfxYbCKpUH5GnUia3/vz8/PO7CB/Qu5IpwoSE+p+qWAQlpoOTFBWSZTWY7VQPUAAChZkNAQQACgBSbr3cAAAgAIIABIaR6UZNAaG0agaU0wAAACJIIBFSEkEwTlh50v5xVaixXv1tOrhmcvrh4xLZsfydw3QC6kAgXhdyRThQkI7VQPUA==";

const directory = mkdtempSync(join(tmpdir(), "kwikibot-bundle-"));
const dump = join(directory, "dump.xml.bz2");
writeFileSync(dump, Buffer.from(DUMP, "base64"));

const here = fileURLToPath(new URL("build/", import.meta.url));
mkdirSync(here, { recursive: true });
const entry = join(here, "bundled-bot.mjs");
writeFileSync(
  entry,
  `import { Worker } from "node:worker_threads";
import { DiskCache } from "kwikibot/client";
import { dumpPages, scanDump } from "kwikibot/dumps";
import { isMainThread } from "node:worker_threads";
console.log("the bot's main ran on " + (isMainThread ? "the main thread" : "a worker"));
const threads = process.getBuiltinModule("worker_threads");
let workers = 0;
threads.Worker = class extends Worker {
  constructor(...args) {
    super(...args);
    workers++;
  }
};
(async () => {
  const pages = [];
  for await (const page of dumpPages(process.argv[2], null, 2)) pages.push([page.title, page.text]);
  let scanned = 0;
  await scanDump(process.argv[2], () => void scanned++, [0], 2);
  await new DiskCache(process.argv[3]).put({ action: "query" }, { query: {} });
  console.log("pages " + JSON.stringify(pages));
  console.log("scanned " + scanned);
  console.log("workers " + workers);
  process.exit(0);
})();
`,
);

async function ran(name, options) {
  const outfile = join(here, `${name}.${options.format === "esm" ? "mjs" : "cjs"}`);
  await build({ entryPoints: [entry], bundle: true, platform: "node", outfile, logLevel: "error", ...options });
  const cacheDirectory = join(directory, `${name}-cache`);
  const result = spawnSync(process.execPath, [outfile, dump, cacheDirectory], {
    encoding: "utf8",
    timeout: 120_000,
    cwd: here,
  });
  if (result.error) throw new Error(`${name}: it did not end: ${result.error.message}`);
  if (result.status !== 0) throw new Error(`${name}: exit ${result.status}: ${result.stderr}`);
  return result.stdout.trim().split("\n");
}

const lineOf = (lines, start) => lines.find((line) => line.startsWith(start));

const external = await ran("external", { format: "cjs", external: ["kwikibot"] });
const commonJs = await ran("commonjs", { format: "cjs" });
const esModule = await ran("esmodule", { format: "esm" });

const pages = lineOf(external, "pages ");
if (!pages.includes("Talk:Volcano")) throw new Error(`the external bundle read ${pages}`);

for (const [name, lines] of Object.entries({ external, commonJs, esModule })) {
  const mains = lines.filter((line) => line.startsWith("the bot's main ran"));
  if (mains.join() !== "the bot's main ran on the main thread") throw new Error(`${name}: ${mains}`);
  if (lineOf(lines, "pages ") !== pages) throw new Error(`${name} read other pages: ${lines}`);
  if (lineOf(lines, "scanned ") !== "scanned 2") throw new Error(`${name}: ${lines}`);
}
if (lineOf(commonJs, "workers ") !== "workers 0") throw new Error(`commonJs: ${commonJs}`);
if (lineOf(esModule, "workers ") !== "workers 0") throw new Error(`esModule: ${esModule}`);
if (lineOf(external, "workers ") === "workers 0") throw new Error(`external used no workers: ${external}`);

console.log("bundle:     a bundled bot reads the same pages on the main thread, and runs its main once");
