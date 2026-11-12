import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { dumpPages, scanDump } from "kwikibot/dumps";

const directory = mkdtempSync(join(tmpdir(), "kwikibot-dump-"));
const file = join(directory, "dump.xml.bz2");
writeFileSync(file, Buffer.from("QlpoOTFBWSZTWZ9T9UsAARXf9dAQUQP9nwAEDRA/79/gAAwAAIAgCAACAEAIQAI8AABgABoAAyaAAANAYAAaAAMmgAADQGAAGgADJoAAA0AiimjUyNGKaaaNU80mKPSeiafooaeliCb1UhRWiqeve1oP2etLGYT0/2u9y06PcHt6MoYh5nnpu4vJwZOmePhr38x9oATTY/XU9nfgMPN2ts+OhRoWtniejKda3MhDcy4N7L5P3sbn7aBlaEP8up4OWzDB1upmkcCXzO0wu2/Hs+XVHi/wJhERF7+bGxxZZCCaSNjL59GvfMuSSy8Hwl6Pa9ze72rCQyXGXIwLSIdTAloW3srqaw3ussxCWCY58X46LGL7QnjrzA+4SONzcPuNn48zh6yPJzYue9m59WGHhY1AeI2Ojm37mHkm4JhiI7ZI0O4e2Sg0GHkOENGj2WjKZ0U2EubpbSU7C9df0TEqN44OBDCy/UCwdC8a7kkL21cjeBkmof248Gq6F2hmNgGfraNXWPoMj1NzUTGgxeMS1KBtq1bgbh6+MHubB933bR2DqZHMctQ87LxODi7Uh07nIcaaDgy1LcBgdqxA5r8DL4NxU69aWWRHFuXa9VZfDsIl0xZM2WHnQcQ/xxaNjtKjDR3Sy0XIdLh8rm5/sdhk4aJ9KH4drZfxYbCKpUH5GnUia3/vz8/PO7CB/Qu5IpwoSE+p+qWAQlpoOTFBWSZTWY7VQPUAAChZkNAQQACgBSbr3cAAAgAIIABIaR6UZNAaG0agaU0wAAACJIIBFSEkEwTlh50v5xVaixXv1tOrhmcvrh4xLZsfydw3QC6kAgXhdyRThQkI7VQPUA==", "base64"));

const titles = [];
for await (const page of dumpPages(file, null, 2)) titles.push(page.title);
if (titles.join() !== "Volcano,Talk:Volcano,Lava") throw new Error(`dumpPages read ${titles}`);

let scanned = 0;
await scanDump(file, () => void scanned++, [0], 2);
if (scanned !== 2) throw new Error(`scanDump read ${scanned} pages`);

console.log("dump:       read on worker threads");
process.exit(0);
