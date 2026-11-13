import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const MAKE_DUMP = String.raw`
import bz2, sys
head = '<mediawiki xmlns="http://www.mediawiki.org/xml/export-0.11/"><siteinfo><sitename>t</sitename><namespaces><namespace key="0" case="first-letter"/></namespaces></siteinfo>'
pages = "".join(
    f'<page><title>P{i}</title><ns>0</ns><id>{i + 1}</id><revision><id>{i + 1}</id><timestamp>2024-01-01T00:00:00Z</timestamp>'
    f'<contributor><username>a</username><id>1</id></contributor><text bytes="5">hello {i} {"lorem ipsum " * 50}</text></revision></page>'
    for i in range(20000)
)
open(sys.argv[1], "wb").write(bz2.compress((head + pages + "</mediawiki>").encode(), 1))
`;

const directory = mkdtempSync(join(tmpdir(), "kwikibot-lifetime-"));
const dump = join(directory, "dump.xml.bz2");
const made = spawnSync("python3", ["-c", MAKE_DUMP, dump], { encoding: "utf8" });
if (made.status !== 0) throw new Error(`could not make a dump of many blocks: ${made.error ?? made.stderr}`);

const programs = {
  "a read in progress holds Node open until it ends": `
    let pages = 0;
    for await (const page of kwikibot.dumpPages(process.argv[2], null, 2)) pages++;
    console.log(pages);
  `,
  "a read in progress holds Node open across a pause of the reader": `
    let pages = 0;
    for await (const page of kwikibot.dumpPages(process.argv[2], null, 2)) {
      if (++pages % 5000 === 1) await new Promise((resolve) => setTimeout(resolve, 50));
    }
    console.log(pages);
  `,
  "an abandoned read does not hold Node open": `
    const walk = kwikibot.dumpPages(process.argv[2], null, 2)[Symbol.asyncIterator]();
    const first = await walk.next();
    console.log(first.value.title);
  `,
  "a read never begun does not hold Node open": `
    kwikibot.dumpPages(process.argv[2], null, 2)[Symbol.asyncIterator]();
    console.log("begun");
  `,
};

const expected = {
  "a read in progress holds Node open until it ends": "20000",
  "a read in progress holds Node open across a pause of the reader": "20000",
  "an abandoned read does not hold Node open": "P0",
  "a read never begun does not hold Node open": "begun",
};

const here = fileURLToPath(new URL("build/", import.meta.url));
mkdirSync(here, { recursive: true });
for (const [name, program] of Object.entries(programs)) {
  const file = join(here, `lifetime-${Object.keys(programs).indexOf(name)}.mjs`);
  writeFileSync(file, `import * as kwikibot from "kwikibot/dumps";\n${program}`);
  const result = spawnSync(process.execPath, [file, dump], { encoding: "utf8", timeout: 120_000 });
  if (result.error) throw new Error(`${name}: it did not exit: ${result.error.message}`);
  if (result.status !== 0) throw new Error(`${name}: exit ${result.status}: ${result.stderr}`);
  if (result.stdout.trim() !== expected[name]) throw new Error(`${name}: it said ${result.stdout}`);
}

console.log("lifetime:   workers hold Node open only while a read needs them");
