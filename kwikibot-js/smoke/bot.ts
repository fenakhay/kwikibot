import { ApiValue, Awaitable, botMain, describeError, JsonValue, Wiki } from "kwikibot";
import { BotReport, pageSourceOfTitles, STOP_POLICY_NONE, stopPolicyOf } from "kwikibot/bot";
import { BotSpec, FleetOptions, RunContext } from "kwikibot/fleet";
import { createFakeWiki, FakePageService } from "kwikibot/testing";

const pages: FakePageService = new FakePageService({ pages: { Volcano: "ash" } });
const wiki: Wiki = createFakeWiki({ pages });

const report: BotReport = await wiki.botRun({
  source: pageSourceOfTitles(["Volcano", "Etna"]),
  transform: (page) => ({ text: `${page.text} and lava`, summary: "adding lava" }),
  dryRun: false,
  maxEdits: 5,
  stopPolicy: STOP_POLICY_NONE,
});

const saved: number = report.saved;
const text: string | null | undefined = pages.text("Volcano");
if (saved !== 1 || text !== "ash and lava") throw new Error(`saved ${saved}, and the page says ${text}`);

if (describeError(new Error("x")) === undefined) throw new Error("describeError gave nothing");

console.log("typescript: a typed bot ran on a fake wiki");

function acceptedShapes(wiki: Wiki, context: RunContext): void {
  const later: Awaitable<number> = Promise.resolve(1);
  const flag: ApiValue = ["a", 1, new Date()];
  const signal: AbortSignal = context.signal;
  const stepped: Promise<number> = context.step("counting", async () => 1);
  const token: Promise<string> = wiki.tokens.withFreshToken((value) => value);
  const answer: Promise<Record<string, JsonValue>> = wiki.call({ action: "query", titles: ["A"], flag: true });
  const bot: BotSpec = { id: "quiet", run: async () => {} };
  const oneLiner: BotSpec = { id: "short", run: (c) => c.run(["A"], "s", (text) => text) };
  botMain([], async () => {});
  botMain([], () => 3);
  const policy = stopPolicyOf(async () => true);
  void [later, flag, signal, stepped, token, answer, bot, oneLiner, policy];
}

function rejectedShapes(wiki: Wiki, context: RunContext): void {
  void wiki.botRun({
    source: pageSourceOfTitles(["Volcano"]),
    // @ts-expect-error a transform answers with an edit or null, not a number
    transform: () => 1,
  });
  // @ts-expect-error a run's transform answers with text or null, not an object
  void context.run(["Volcano"], "naming it", () => ({ text: "lava" }));
  // @ts-expect-error onAsk answers with a boolean, not a string
  const onAsk: FleetOptions["onAsk"] = () => "yes";
  // @ts-expect-error a parameter of wiki.call is not an object
  void wiki.call({ action: "query", nested: { a: 1 } });
  void onAsk;
}

void acceptedShapes;
void rejectedShapes;
