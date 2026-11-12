import { createFleet } from "kwikibot/fleet";

const contact = process.argv[2] ?? "https://github.com/fenakhay/kwikibot";
const lines = [];

const fleet = await createFleet(
  { server: "en.wiktionary.org", contact, onLine: (line) => lines.push(line) },
  [
    {
      id: "reader",
      description: "reads a page",
      run: async (context) => {
        const page = await context.wiki.page("volcano");
        context.say(`read ${page.title}`);
      },
    },
    {
      id: "broken",
      description: "fails",
      run: async () => {
        throw new Error("on purpose");
      },
    },
  ],
);

fleet.start("reader");
fleet.start("broken");
await fleet.idle();

const statuses = fleet.runs().map((run) => `${run.bot}:${run.status}`).sort().join();
if (statuses !== "broken:failed,reader:done") throw new Error(`the runs ended ${statuses}`);
if (!lines.includes("[reader] read volcano")) throw new Error(`the lines were ${lines}`);

console.log("fleet:      ran two bots, one of which failed alone");
fleet.close();
process.exit(0);
