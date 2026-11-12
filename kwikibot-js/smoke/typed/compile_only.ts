import { botMain, type BotStart, type ExitStatus } from "kwikibot";
import { NonInteractive, pageSourceOfTitles } from "kwikibot/bot";
import { type BotSpec, every, fleetMain, fleetMainWith } from "kwikibot/fleet";

const args = process.argv.slice(2);

async function tidyVolcanoes(start: BotStart): Promise<ExitStatus> {
  const titles: string[] = start.arguments.length > 0 ? start.arguments : ["Volcano"];
  const report = await start.wiki.botRun({
    source: pageSourceOfTitles(titles),
    dryRun: !start.options.save,
    stopPolicy: start.options.stopPolicy(start.wiki),
    transform: (page) => ({ text: page.text.trim(), summary: `tidying, as ${start.config.bot.name}` }),
  });
  return report.failed > 0 ? 1 : 0;
}

function aBot(): void {
  botMain(args, tidyVolcanoes);
}

function aReader(): void {
  botMain(
    args,
    async (start) => {
      const page = await start.wiki.page("Volcano");
      console.log(page?.text);
    },
    true,
  );
}

function aSynchronousBot(): void {
  botMain(args, (start) => (start.options.save ? 1 : 0));
}

const BOTS: BotSpec[] = [
  {
    id: "tidier",
    description: "tidies pages",
    schedule: every(3_600_000),
    run: async (context) => {
      const report = await context.run(["Volcano"], "tidying", (text) => text.trim());
      context.say(`saved ${report.saved}`);
    },
  },
];

function aFleet(): void {
  fleetMain(args, BOTS);
}

function aFleetThatNeverAsks(): void {
  fleetMainWith(args, BOTS, new NonInteractive(false));
}

void [aBot, aReader, aSynchronousBot, aFleet, aFleetThatNeverAsks];
