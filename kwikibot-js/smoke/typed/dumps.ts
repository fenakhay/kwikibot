import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { gzipSync } from "node:zlib";
import { describeError } from "kwikibot";
import { availableDumpWorkers, DumpPage, dumpPages, dumpPagesOf, scanDump } from "kwikibot/dumps";
import { assertArray, assertDate, assertNone, collect } from "./support.js";

const XML = [
  "<mediawiki>",
  "<page><title>Volcano</title><ns>0</ns><id>1</id><revision><id>2</id>",
  "<timestamp>2026-10-01T12:00:00Z</timestamp><contributor><username>Alice</username></contributor>",
  "<comment>adding lava</comment><text>lava</text></revision></page>",
  "<page><title>Talk:Volcano</title><ns>1</ns><id>3</id><redirect title=\"Volcano\" /><revision><id>4</id>",
  "<text>#REDIRECT [[Volcano]]</text></revision></page>",
  "</mediawiki>",
].join("");

const BZIP2 =
  "QlpoOTFBWSZTWZ9T9UsAARXf9dAQUQP9nwAEDRA/79/gAAwAAIAgCAACAEAIQAI8AABgABoAAyaAAANAYAAaAAMmgAADQGAAGgADJoAAA0AiimjUyNGKaaaNU80mKPSeiafooaeliCb1UhRWiqeve1oP2etLGYT0/2u9y06PcHt6MoYh5nnpu4vJwZOmePhr38x9oATTY/XU9nfgMPN2ts+OhRoWtniejKda3MhDcy4N7L5P3sbn7aBlaEP8up4OWzDB1upmkcCXzO0wu2/Hs+XVHi/wJhERF7+bGxxZZCCaSNjL59GvfMuSSy8Hwl6Pa9ze72rCQyXGXIwLSIdTAloW3srqaw3ussxCWCY58X46LGL7QnjrzA+4SONzcPuNn48zh6yPJzYue9m59WGHhY1AeI2Ojm37mHkm4JhiI7ZI0O4e2Sg0GHkOENGj2WjKZ0U2EubpbSU7C9df0TEqN44OBDCy/UCwdC8a7kkL21cjeBkmof248Gq6F2hmNgGfraNXWPoMj1NzUTGgxeMS1KBtq1bgbh6+MHubB933bR2DqZHMctQ87LxODi7Uh07nIcaaDgy1LcBgdqxA5r8DL4NxU69aWWRHFuXa9VZfDsIl0xZM2WHnQcQ/xxaNjtKjDR3Sy0XIdLh8rm5/sdhk4aJ9KH4drZfxYbCKpUH5GnUia3/vz8/PO7CB/Qu5IpwoSE+p+qWAQlpoOTFBWSZTWY7VQPUAAChZkNAQQACgBSbr3cAAAgAIIABIaR6UZNAaG0agaU0wAAACJIIBFSEkEwTlh50v5xVaixXv1tOrhmcvrh4xLZsfydw3QC6kAgXhdyRThQkI7VQPUA==";

const bytes = new TextEncoder().encode(XML);
const directory = mkdtempSync(join(tmpdir(), "kwikibot-typed-dump-"));

function describe(page: DumpPage): string {
  return `${page.namespace}:${page.title}#${page.pageId}/${page.revisionId}`;
}

try {
  const workers: number = availableDumpWorkers();
  assert.equal(typeof workers, "number");
  assert.ok(Number.isInteger(workers) && workers >= 1);

  const inMemory: DumpPage[] = assertArray(dumpPagesOf(bytes));
  assert.deepEqual(inMemory.map(describe), ["0:Volcano#1/2", "1:Talk:Volcano#3/4"]);
  const first = inMemory[0];
  const second = inMemory[1];
  assert.ok(first != null && second != null);
  assert.ok(first instanceof DumpPage);

  assert.equal(first.text, "lava");
  assert.equal(first.contributor, "Alice");
  assert.equal(first.comment, "adding lava");
  assert.equal(assertDate(first.timestamp).toISOString(), "2026-10-01T12:00:00.000Z");
  assert.equal(first.isRedirect, false);
  assertNone(first.redirectTarget);
  assert.equal(typeof first.pageId, "number");
  assert.equal(typeof first.revisionId, "number");

  assert.equal(second.isRedirect, true);
  assert.equal(second.redirectTarget, "Volcano");
  assertNone(second.timestamp);
  assertNone(second.contributor);
  assertNone(second.comment);

  assert.equal(first.inNamespaces([0, 1]), true);
  assert.equal(first.inNamespaces([1]), false);
  assert.equal(first.inNamespaces([]), true);

  assert.deepEqual(dumpPagesOf(bytes, "none", [1]).map((page) => page.title), ["Talk:Volcano"]);
  assert.deepEqual(dumpPagesOf(bytes, "none", null).map((page) => page.title), ["Volcano", "Talk:Volcano"]);
  assert.deepEqual(dumpPagesOf(gzipSync(bytes), "gzip").map((page) => page.title), ["Volcano", "Talk:Volcano"]);
  const compressed = Buffer.from(BZIP2, "base64");
  assert.deepEqual(dumpPagesOf(compressed, "bzip2").map((page) => page.title), ["Volcano", "Talk:Volcano", "Lava"]);

  const plainFile = join(directory, "dump.xml");
  const gzipFile = join(directory, "dump.xml.gz");
  const bzipFile = join(directory, "dump.xml.bz2");
  writeFileSync(plainFile, bytes);
  writeFileSync(gzipFile, gzipSync(bytes));
  writeFileSync(bzipFile, compressed);

  const fromPlain: DumpPage[] = await collect(dumpPages(plainFile));
  assert.deepEqual(fromPlain.map(describe), ["0:Volcano#1/2", "1:Talk:Volcano#3/4"]);
  assert.ok(fromPlain[0] instanceof DumpPage);

  const fromGzip = await collect(dumpPages(gzipFile, [0], 1));
  assert.deepEqual(fromGzip.map((page) => page.text), ["lava"]);

  const fromBzip = await collect(dumpPages(bzipFile, null, 2));
  assert.deepEqual(fromBzip.map((page) => page.title), ["Volcano", "Talk:Volcano", "Lava"]);

  let leftEarly = "";
  for await (const page of dumpPages(bzipFile, null, 2)) {
    leftEarly = page.title;
    break;
  }
  assert.equal(leftEarly, "Volcano");

  const synchronously: string[] = [];
  const scanning: Promise<void> = scanDump(plainFile, (page) => {
    synchronously.push(page.title);
  });
  assert.ok(scanning instanceof Promise);
  assert.equal(await scanning, undefined);
  assert.deepEqual(synchronously.sort(), ["Talk:Volcano", "Volcano"]);

  const asynchronously: string[] = [];
  await scanDump(
    bzipFile,
    async (page) => {
      await Promise.resolve();
      asynchronously.push(page.title);
    },
    [0],
    2,
  );
  assert.deepEqual(asynchronously.sort(), ["Lava", "Volcano"]);

  await assert.rejects(
    scanDump(plainFile, () => {
      throw new Error("the bot gave up");
    }),
    /the bot gave up/,
  );

  assert.throws(() => dumpPagesOf(bytes, "gzip"));
  assert.throws(() => dumpPagesOf(bytes, "bzip2"));

  await assert.rejects(collect(dumpPages(join(directory, "absent.xml"))));

  const truncated = new Uint8Array(compressed.subarray(0, compressed.length - 40));
  try {
    dumpPagesOf(truncated, "bzip2");
    assert.fail("a damaged dump read");
  } catch (error) {
    const info = describeError(error);
    assert.ok(info != null);
    assert.equal(info.kind, "dump-format");
    assert.ok(info.dumpProblem === "unexpected-eof" || info.dumpProblem === "bad-checksum" || info.dumpProblem === "bad-data");
  }
} finally {
  rmSync(directory, { recursive: true, force: true });
}

function rejectedDumpShapes(): void {
  // @ts-expect-error a compression nobody has heard of
  void dumpPagesOf(bytes, "zip");
  // @ts-expect-error namespaces are numbers
  void dumpPages("dump.xml", ["0"]);
}
void rejectedDumpShapes;

console.log("dumps: read plain, gzip and bzip2 dumps in memory and from files, on worker threads");
