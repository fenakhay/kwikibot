import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  BotPassword,
  describeError,
  type JsonValue,
  OAuth2,
  type Page,
  type PageEdit,
  PageSource,
  skipPage,
  type Thrown,
  type Wiki,
} from "kwikibot";
import {
  allStopPolicies,
  BOT_CONFIG_DEFAULT_MAXLAG,
  BOT_CONFIG_FILE_NAME,
  BOT_REPORT_PROBLEM_LIMIT,
  BotConfig,
  type BotConfigInit,
  botConfigSearchPath,
  botConfigTemplate,
  BotIdentity,
  BotPolicy,
  BotReport,
  type BotRunOptions,
  CacheSettings,
  type Change,
  COMMON_OPTIONS_USAGE,
  CommonOptions,
  type CommonOptionsInit,
  concatPageSources,
  createBotConfig,
  createCommonOptions,
  createProgress,
  createRunLog,
  EMPTY_PAGE_SOURCE,
  findBotConfig,
  HttpSection,
  LoginSettings,
  OAuthSettings,
  PageContents,
  type PageOutcome,
  type PageOutcomeKind,
  pageSourceFrom,
  pageSourceKinds,
  pageSourceOf,
  pageSourceOfTitles,
  parseBotConfig,
  parseCommonOptions,
  ParsedCommandLine,
  parsePageSource,
  parsePageSources,
  Problem,
  type ProblemKind,
  Progress,
  type ProgressOptions,
  readBotConfig,
  reportTo,
  RetrySettings,
  RunLog,
  type RunLogOptions,
  RunReport,
  RunSettings,
  RunState,
  sharedStop,
  sharedStopOnPage,
  type SharedStopOptions,
  SkipPageError,
  STOP_POLICY_NONE,
  StopPolicy,
  stopPolicyOf,
  stopPolicyOnPage,
  type StopReason,
  ThrottleSettings,
  WikiSelection,
} from "kwikibot/bot";
import { createClient, UserAgent } from "kwikibot/client";
import { ThrownValue } from "kwikibot/errors";
import {
  pagePileSource,
  petScanSavedSource,
  petScanSource,
  petScanTitles,
  sparqlAuthNone,
  sparqlSource,
  type SparqlSourceOptions,
  withPetScanTitles,
} from "kwikibot/sparql";
import { createFakeWiki, FakePageService } from "kwikibot/testing";
import { FakeSite, QUICK, assertArray, assertNone, collect, json, withWiki } from "./support.js";

const LOGIN = new BotPassword("TestBot", "task", "secret");
const CONTACT = "https://example.org/me";

interface Bench {
  site: FakeSite;
  saved: Record<string, string>;
  edits: () => Array<Record<string, string>>;
}

function bench(pages: Record<string, string>): Bench {
  const texts: Record<string, string> = { ...pages };
  const saved: Record<string, string> = {};
  let revision = 9001;
  const read = (params: Record<string, string>): JsonValue => ({
    query: {
      pages: (params.titles ?? "").split("|").map((title): JsonValue => {
        const text = texts[title];
        if (text === undefined) return { ns: 0, title, missing: true };
        const page: { [key: string]: JsonValue } = {
          pageid: 1,
          ns: 0,
          title,
          revisions: [{ revid: revision, timestamp: "2026-08-31T21:43:26Z", slots: { main: { content: text } } }],
        };
        if (text.startsWith("#REDIRECT")) page.redirect = true;
        return page;
      }),
    },
  });
  const site = new FakeSite({
    "info|revisions": read,
    revisions: read,
    search: { query: { search: [{ ns: 0, title: "Volcano" }, { ns: 0, title: "Volcanic ash" }] } },
    embeddedin: { query: { embeddedin: [{ ns: 0, title: "Volcano" }] } },
    edit: (params): JsonValue => {
      const title = params.title ?? "";
      const previous = revision;
      revision += 1;
      texts[title] = params.text ?? "";
      saved[title] = params.text ?? "";
      const made: JsonValue =
        params.createonly === "1"
          ? { result: "Success", pageid: 1, title, new: true, newrevid: revision }
          : { result: "Success", pageid: 1, title, oldrevid: previous, newrevid: revision };
      return { edit: made };
    },
  });
  return { site, saved, edits: () => site.requests.filter((request) => request.action === "edit") };
}

const tephra = (page: Page): PageEdit | null =>
  page.text.includes("ash") ? { text: page.text.replace("ash", "tephra"), summary: "naming it" } : null;

const volcanoes = (): Bench => bench({ Volcano: "ash\n", Etna: "lava\n" });

function titles(...names: string[]): PageSource {
  return pageSourceOfTitles(names);
}

async function take<T>(items: AsyncIterable<T>, count: number): Promise<T[]> {
  const taken: T[] = [];
  for await (const item of items) {
    taken.push(item);
    if (taken.length === count) break;
  }
  return taken;
}

function only<T>(items: T[]): T {
  assert.equal(items.length, 1);
  const item = items[0];
  assert.ok(item !== undefined);
  return item;
}

function outcomeOf(outcomes: PageOutcome[], title: string): PageOutcome {
  const found = outcomes.find((outcome) => outcome.title === title);
  assert.ok(found != null, `no outcome for ${title}`);
  return found;
}

async function run(wiki: Wiki, options: Omit<BotRunOptions, "onOutcome">): Promise<{ report: BotReport; outcomes: PageOutcome[] }> {
  const outcomes: PageOutcome[] = [];
  const report = await wiki.botRun({ ...options, onOutcome: (outcome) => void outcomes.push(outcome) });
  return { report, outcomes };
}

async function aDryRunComputesAndSavesNothing(): Promise<void> {
  const { site, saved } = volcanoes();
  await withWiki(
    site,
    async (wiki) => {
      const outcomes: PageOutcome[] = [];
      const options: BotRunOptions = {
        source: titles("Volcano", "Etna", "Nope"),
        transform: tephra,
        onOutcome: (outcome) => {
          outcomes.push(outcome);
        },
      };
      const report: BotReport = await wiki.botRun(options);

      assert.ok(report instanceof BotReport);
      assert.deepEqual(
        [report.processed, report.pending, report.saved, report.skipped, report.refused, report.failed],
        [3, 1, 0, 2, 0, 0],
      );
      assert.deepEqual([report.stopped, report.clean, report.problemsTruncated, report.notAttempted], [false, true, false, 0]);
      assertNone(report.stopReason);
      assert.deepEqual(assertArray(report.problems), []);
      assert.match(report.toString(), /pending=1/);
      assert.deepEqual(saved, {});

      const volcano = outcomeOf(outcomes, "Volcano");
      const kind: PageOutcomeKind = volcano.kind;
      assert.equal(kind, "pending");
      assert.equal(volcano.edit?.text, "tephra\n");
      assert.equal(volcano.edit?.summary, "naming it");
      assert.equal(volcano.before, "ash\n");
      assert.equal(volcano.baseRevision, 9001);
      assertNone(volcano.revision);
      assertNone(volcano.previousRevision);
      assertNone(volcano.reason);
      assertNone(volcano.refusal);
      assertNone(volcano.error);

      const etna = outcomeOf(outcomes, "Etna");
      assert.equal(etna.kind, "skipped");
      assert.equal(etna.reason, "no change needed");
      assertNone(etna.edit);
      assertNone(etna.before);
      assertNone(etna.baseRevision);
      assert.equal(outcomeOf(outcomes, "Nope").kind, "missing");
    },
    LOGIN,
  );
}

async function aSavingRunSendsTheEditAsItWasSaid(): Promise<void> {
  const { site, saved, edits } = volcanoes();
  await withWiki(
    site,
    async (wiki) => {
      const edit: PageEdit = { text: "new\n", summary: "why", minor: true, tags: ["bot-tag"] };
      const { report, outcomes } = await run(wiki, {
        source: titles("Volcano"),
        dryRun: false,
        stopPolicy: STOP_POLICY_NONE,
        transform: async () => edit,
      });

      assert.equal(report.saved, 1);
      assert.deepEqual(saved, { Volcano: "new\n" });
      const sent = only(edits());
      assert.deepEqual([sent.summary, sent.minor, sent.tags, sent.baserevid], ["why", "1", "bot-tag", "9001"]);

      const outcome = only(outcomes);
      assert.equal(outcome.kind, "saved");
      assert.equal(outcome.revision, 9002);
      assert.equal(typeof outcome.revision, "number");
      assert.equal(outcome.previousRevision, 9001);
      assert.equal(outcome.before, "ash\n");
      assert.equal(outcome.edit?.summary, "why");
      assert.equal(outcome.edit?.minor, true);
      assert.deepEqual(assertArray(outcome.edit?.tags ?? []), ["bot-tag"]);
      assertNone(outcome.edit?.section);
    },
    LOGIN,
  );
}

async function anEditOfNoTextAndASectionAreToldApart(): Promise<void> {
  const { site, edits } = volcanoes();
  await withWiki(
    site,
    async (wiki) => {
      const { outcomes } = await run(wiki, {
        source: titles("Volcano", "Etna"),
        dryRun: false,
        transform: (page) =>
          page.title === "Volcano" ? { text: page.text, summary: "nothing" } : { text: "more", summary: "heading", section: "new" },
      });
      assert.equal(outcomeOf(outcomes, "Volcano").kind, "unchanged");
      const etna = outcomeOf(outcomes, "Etna");
      assert.equal(etna.kind, "saved");
      assert.equal(etna.edit?.section, "new");
      assert.equal(only(edits()).section, "new");
    },
    LOGIN,
  );
}

async function aTransformSkipsWithAReasonOrStopsTheRun(): Promise<void> {
  const { site } = volcanoes();
  await withWiki(
    site,
    async (wiki) => {
      const skipped = await run(wiki, { source: titles("Volcano"), transform: () => skipPage("not today") });
      assert.equal(skipped.report.skipped, 1);
      assert.equal(only(skipped.outcomes).reason, "not today");

      const error = new SkipPageError("later");
      assert.ok(error instanceof Error);
      assert.equal(error.reason, "later");
      assert.equal(error.message, "later");
      const rejected = await run(wiki, { source: titles("Volcano"), transform: () => Promise.reject(error) });
      assert.equal(rejected.report.skipped, 1);
      assert.equal(only(rejected.outcomes).reason, "later");

      assert.throws(() => skipPage("thrown"), (thrown: unknown) => thrown instanceof SkipPageError && thrown.reason === "thrown");

      await assert.rejects(
        wiki.botRun({
          source: titles("Volcano"),
          transform: () => {
            throw new RangeError("it broke");
          },
        }),
        /it broke/,
      );
    },
    LOGIN,
  );
}

async function createMissingMakesAPageThatMustNotExist(): Promise<void> {
  const { site, saved, edits } = volcanoes();
  await withWiki(
    site,
    async (wiki) => {
      const dry = await run(wiki, {
        source: titles("Nope"),
        transform: tephra,
        createMissing: (title) => ({ text: `about ${title}`, summary: "creating" }),
      });
      assert.equal(dry.report.pending, 1);
      const pending = only(dry.outcomes);
      assert.equal(pending.kind, "pending");
      assert.equal(pending.before, "");
      assertNone(pending.baseRevision);
      assert.equal(pending.edit?.createOnly, true);

      const left = await run(wiki, { source: titles("Nope"), transform: tephra, createMissing: () => null });
      assert.equal(only(left.outcomes).kind, "missing");

      const { report, outcomes } = await run(wiki, {
        source: titles("Nope", "Other"),
        dryRun: false,
        transform: tephra,
        createMissing: async (title) => (title === "Nope" ? { text: `about ${title}`, summary: "creating" } : skipPage("not that one")),
      });
      assert.deepEqual([report.saved, report.skipped], [1, 1]);
      assert.deepEqual(saved, { Nope: "about Nope" });
      assert.equal(only(edits()).createonly, "1");
      const created = outcomeOf(outcomes, "Nope");
      assert.equal(created.kind, "saved");
      assert.equal(created.edit?.createOnly, true);
      assertNone(created.previousRevision);
    },
    LOGIN,
  );
}

async function limitsCapARun(): Promise<void> {
  const capped = volcanoes();
  await withWiki(
    capped.site,
    async (wiki) => {
      const report = await wiki.botRun({
        source: titles("Volcano", "Etna", "Nope"),
        transform: tephra,
        limit: 2,
        readBatch: 2,
        readConcurrency: 1,
        writeConcurrency: 1,
      });
      assert.equal(report.processed, 2);
      const reads = capped.site.requests.filter((request) => (request.rvprop ?? "").includes("content"));
      assert.deepEqual(
        reads.map((request) => request.titles),
        ["Volcano|Etna"],
      );
    },
    LOGIN,
  );

  const three = bench({ A: "ash", B: "ash", C: "ash" });
  await withWiki(
    three.site,
    async (wiki) => {
      const { report, outcomes } = await run(wiki, {
        source: titles("A", "B", "C"),
        dryRun: false,
        readConcurrency: 1,
        transform: tephra,
        maxEdits: 1,
      });
      assert.equal(report.saved, 1);
      assert.equal(report.stopped, true);
      const reason: StopReason | null | undefined = report.stopReason;
      assert.equal(reason, "policy");
      assert.equal(report.notAttempted, 2);
      const untried = outcomes.filter((outcome) => outcome.kind === "not-attempted");
      assert.equal(untried.length, 2);
      assert.equal(untried[0]?.reason, "policy");
    },
    LOGIN,
  );
}

async function aStopPolicyStopsASavingRun(): Promise<void> {
  const { site, saved } = volcanoes();
  await withWiki(
    site,
    async (wiki) => {
      const stopped = await wiki.botRun({
        source: titles("Volcano"),
        dryRun: false,
        transform: tephra,
        stopPolicy: stopPolicyOf(() => false),
      });
      assert.deepEqual([stopped.stopped, stopped.stopReason, stopped.processed], [true, "policy", 0]);

      const failing = await wiki.botRun({
        source: titles("Volcano"),
        dryRun: false,
        transform: tephra,
        stopPolicy: stopPolicyOf(() => Promise.reject(new Error("no"))),
      });
      assert.equal(failing.stopReason, "policy-unreachable");
      assert.deepEqual(saved, {});
    },
    LOGIN,
  );
}

async function stopPoliciesCombineAndReadAPage(): Promise<void> {
  assert.ok(STOP_POLICY_NONE instanceof StopPolicy);
  assert.equal(await stopPolicyOf(() => true).mayContinue(), true);
  assert.equal(await stopPolicyOf(() => Promise.resolve(true)).mayContinue(), true);
  assert.equal(await stopPolicyOf(() => false).mayContinue(), false);
  assert.equal(await STOP_POLICY_NONE.mayContinue(), true);
  assert.equal(await STOP_POLICY_NONE.check(), undefined);
  await assert.rejects(stopPolicyOf(() => false).check());

  assert.equal(await allStopPolicies([STOP_POLICY_NONE, stopPolicyOf(() => true)]).mayContinue(), true);
  assert.equal(await allStopPolicies([STOP_POLICY_NONE, stopPolicyOf(() => false)]).mayContinue(), false);

  const { site } = bench({ Stop: "false", Halt: "please stop" });
  await withWiki(
    site,
    async (wiki) => {
      assert.equal(await stopPolicyOnPage(wiki, "Stop").mayContinue(), true);
      assert.equal(await stopPolicyOnPage(wiki, "Halt").mayContinue(), false);
      assert.equal(await wiki.stopPage("Stop").mayContinue(), true);
      assert.equal(site.requests.at(-1)?.titles, "Stop");
      assert.equal(await wiki.stopPage("Absent").mayContinue(), false);
      assert.equal(await wiki.stopPage().mayContinue(), false);
      assert.equal(site.requests.at(-1)?.titles, "User:TestBot/Stop");
      assert.equal(await sharedStopOnPage(wiki, "Stop").mayContinue(), true);
    },
    LOGIN,
  );
}

async function aSharedStopReadsOncePerInterval(): Promise<void> {
  let clock = 0;
  let reads = 0;
  let text: string | null = "false";
  const options: SharedStopOptions = { every: 1000, staleAfter: 5000, now: () => new Date(clock) };
  const shared = sharedStop(() => {
    reads += 1;
    return text;
  }, options);
  assert.equal(await shared.mayContinue(), true);
  clock = 500;
  assert.equal(await shared.mayContinue(), true);
  assert.equal(reads, 1);
  clock = 1000;
  text = "stop, please";
  assert.equal(await shared.mayContinue(), false);
  assert.equal(reads, 2);
  clock = 2000;
  text = null;
  assert.equal(await shared.mayContinue(), false);

  clock = 0;
  let healthy = true;
  const flaky = sharedStop(async () => {
    if (!healthy) throw new Error("the wiki is away");
    return "false";
  }, options);
  assert.equal(await flaky.mayContinue(), true);
  healthy = false;
  clock = 1500;
  assert.equal(await flaky.mayContinue(), true);
  clock = 9000;
  assert.equal(await flaky.mayContinue(), false);

  const neverGood = sharedStop(() => Promise.reject(new Error("no")));
  assert.equal(await neverGood.mayContinue(), false);
}

async function whatTheWikiRefusesOrCannotServeIsAProblem(): Promise<void> {
  const refused = volcanoes();
  refused.site.answers.edit = { error: { code: "protectedpage", info: "It is protected." } };
  await withWiki(
    refused.site,
    async (wiki) => {
      const report = await wiki.botRun({ source: titles("Volcano"), dryRun: false, transform: tephra });
      assert.deepEqual([report.refused, report.clean], [1, false]);
      const problem = only(assertArray(report.problems));
      assert.equal(problem.kind, "refused");
      assert.equal(problem.title, "Volcano");
      assert.equal(problem.refusal?.status, "protected");
      assert.equal(problem.refusal?.detail, "It is protected.");
      assertNone(problem.error);
    },
    LOGIN,
  );

  const broken = volcanoes();
  broken.site.answers["info|revisions"] = { error: { code: "internal_api_error_x", info: "it broke" } };
  await withWiki(
    broken.site,
    async (wiki) => {
      const report = await wiki.botRun({ source: titles("Volcano"), transform: tephra });
      assert.equal(report.failed, 1);
      const problem = only(report.problems);
      assert.equal(problem.kind, "failed");
      assert.ok(problem.error instanceof Error);
      assert.match(problem.error.message, /it broke/);
      assert.equal(describeError(problem.error)?.kind, "api");
      assertNone(problem.refusal);

      const many = Array.from({ length: BOT_REPORT_PROBLEM_LIMIT + 1 }, (_, index) => `Page ${index}`);
      const crowded = await wiki.botRun({ source: pageSourceOfTitles(many), transform: tephra, readBatch: 1 });
      assert.equal(BOT_REPORT_PROBLEM_LIMIT, 100);
      assert.equal(crowded.failed, 101);
      assert.equal(crowded.problems.length, 100);
      assert.equal(crowded.problemsTruncated, true);
    },
    LOGIN,
  );
}

async function anExclusionIsHonouredUnlessToldOtherwise(): Promise<void> {
  const { site } = bench({ Volcano: "{{nobots}} ash" });
  await withWiki(
    site,
    async (wiki) => {
      const excluded = await wiki.botRun({ source: titles("Volcano"), transform: tephra });
      assert.deepEqual([excluded.pending, excluded.skipped], [0, 1]);
      const optedOut = await wiki.botRun({ source: titles("Volcano"), transform: tephra, exclusionPolicy: null });
      assert.equal(optedOut.pending, 1);
      const other = await wiki.botRun({ source: titles("Volcano"), transform: tephra, exclusionPolicy: new BotPolicy("Other") });
      assert.equal(other.skipped, 1);
    },
    LOGIN,
  );
}

async function theCommonFlagsAndAConfigurationSetWhatTheOptionsDoNot(): Promise<void> {
  const { site, saved } = volcanoes();
  const directory = mkdtempSync(join(tmpdir(), "kwikibot-typed-common-"));
  await withWiki(
    site,
    async (wiki) => {
      const capped = await wiki.botRun({
        source: titles("Volcano", "Etna"),
        transform: tephra,
        common: createCommonOptions({ limit: 1 }),
        config: parseBotConfig('[bot]\nname = "B"\ncontact = "c"\n[run]\nreadBatch = 2\n'),
      });
      assert.equal(capped.processed, 1);

      const overridden = await wiki.botRun({
        source: titles("Volcano", "Etna"),
        transform: tephra,
        common: createCommonOptions({ limit: 1 }),
        limit: 2,
      });
      assert.equal(overridden.processed, 2);

      const noted = await wiki.botRun({
        source: titles("Etna"),
        transform: tephra,
        common: createCommonOptions({ state: join(directory, "from-flags") }),
      });
      assert.equal(noted.processed, 1);
      assert.ok(existsSync(join(directory, "from-flags", "finished.tsv")));
      const resumed = await wiki.botRun({
        source: titles("Etna"),
        transform: tephra,
        common: createCommonOptions({ state: join(directory, "from-flags"), resume: true }),
      });
      assert.equal(resumed.processed, 0);

      const saving = await wiki.botRun({ source: titles("Volcano"), transform: tephra, common: createCommonOptions({ save: true }) });
      assert.equal(saving.saved, 1);
      assert.deepEqual(saved, { Volcano: "tephra\n" });
    },
    LOGIN,
  );
  rmSync(directory, { recursive: true, force: true });
}

async function reportToHandsEachOutcomeToEveryHandler(): Promise<void> {
  const { site } = volcanoes();
  await withWiki(
    site,
    async (wiki) => {
      const first: string[] = [];
      const second: PageOutcomeKind[] = [];
      const onOutcome = reportTo([(outcome) => void first.push(outcome.title), (outcome) => void second.push(outcome.kind)]);
      assert.equal(typeof onOutcome, "function");
      await wiki.botRun({ source: titles("Volcano"), transform: tephra, onOutcome });
      assert.deepEqual(first, ["Volcano"]);
      assert.deepEqual(second, ["pending"]);
    },
    LOGIN,
  );
}

async function theSimpleRunReportsInItsOwnTerms(): Promise<void> {
  const replaceAsh = (text: string): string | null => (text.includes("ash") ? text.replace("ash", "tephra") : null);

  const dry = volcanoes();
  await withWiki(
    dry.site,
    async (wiki) => {
      const changes: Change[] = [];
      const report: RunReport = await wiki.run(["Volcano", "Etna", "Nope"], "naming it", replaceAsh, false, (change) => void changes.push(change));
      assert.ok(report instanceof RunReport);
      assert.deepEqual([report.processed, report.pending, report.saved, report.skipped, report.refused, report.failed, report.notAttempted], [3, 1, 0, 2, 0, 0, 0]);
      assertNone(report.stopReason);
      assert.deepEqual(assertArray(report.problems), []);
      const change = only(changes);
      assert.equal(change.title, "Volcano");
      assert.match(change.diff, /^--- old\/Volcano\n\+\+\+ new\/Volcano\n/);
      assert.match(change.diff, /-ash\n\+tephra/);
      assertNone(change.revision);
      assert.deepEqual(dry.saved, {});
    },
    LOGIN,
  );

  const saving = volcanoes();
  await withWiki(
    saving.site,
    async (wiki) => {
      const changes: Change[] = [];
      const report = await wiki.run(["Volcano", "Etna"], "naming it", async (text) => replaceAsh(text), true, (change) => void changes.push(change));
      assert.equal(report.saved, 1);
      assert.deepEqual(saving.saved, { Volcano: "tephra\n" });
      assert.equal(only(saving.edits()).summary, "naming it");
      assert.equal(only(changes).revision, 9002);

      const thenable: PromiseLike<string> = { then: (done, failed) => Promise.resolve("settled\n").then(done, failed) };
      await wiki.run(["Etna"], "adding", () => thenable, true);
      assert.equal(saving.edits().at(-1)?.text, "settled\n");

      await assert.rejects(wiki.run(["Etna"], "throwing", () => {
        throw new RangeError("it broke");
      }), /it broke/);
    },
    LOGIN,
  );

  const capped = bench({ A: "ash", B: "ash", C: "ash" });
  await withWiki(
    capped.site,
    async (wiki) => {
      const report = await wiki.run(["A", "B", "C"], "naming it", replaceAsh, true, null, null, 1);
      assert.equal(report.saved, 1);
      assert.equal(report.stopReason, "policy");
      assert.ok(report.notAttempted >= 1);
    },
    LOGIN,
  );

  const guarded = bench({ Volcano: "ash\n", Stop: "false" });
  await withWiki(
    guarded.site,
    async (wiki) => {
      const halted = await wiki.run(["Volcano"], "naming it", replaceAsh, true, null, "Absent");
      assert.equal(halted.stopReason, "policy");
      assert.deepEqual(guarded.saved, {});
      const allowed = await wiki.run(["Volcano"], "naming it", replaceAsh, true, null, "Stop");
      assert.equal(allowed.saved, 1);
    },
    LOGIN,
  );

  const refused = volcanoes();
  refused.site.answers.edit = { error: { code: "protectedpage", info: "It is protected." } };
  await withWiki(
    refused.site,
    async (wiki) => {
      const report = await wiki.run(["Volcano"], "naming it", replaceAsh, true);
      assert.equal(report.refused, 1);
      const problem: Problem = only(report.problems);
      assert.ok(problem instanceof Problem);
      const kind: ProblemKind = problem.kind;
      assert.equal(kind, "refused");
      assert.equal(problem.title, "Volcano");
      assert.match(problem.detail, /protected/);
    },
    LOGIN,
  );

  const broken = volcanoes();
  broken.site.answers["info|revisions"] = { error: { code: "internal_api_error_x", info: "it broke" } };
  await withWiki(
    broken.site,
    async (wiki) => {
      const report = await wiki.run(["Volcano"], "naming it", replaceAsh);
      assert.equal(report.failed, 1);
      const problem = only(report.problems);
      assert.equal(problem.kind, "failed");
      assert.match(problem.detail, /it broke/);
    },
    LOGIN,
  );
}

const TOML = `maxlag = 2

[bot]
name = "MyBot"
version = "2.0"
contact = "https://example.org/me"

[wiki]
lang = "fr"
family = "wikipedia"

[throttle]
read = "200ms"
write = "5s"

[login]
account = "MyBot"
botName = "task"
passwordEnv = "MY_PASSWORD"

[oauth]
tokenEnv = "MY_TOKEN"
username = "MyBot"
optional = true

[run]
readConcurrency = 8
writeConcurrency = 2
readBatch = 50

[http]
maxConcurrentRequests = 2
maxIdleConnections = 4
timeout = "30s"

[retry]
maxRetries = 1
initialDelay = "2s"
maxDelay = "4s"
relogins = 0
`;

function aConfigurationReadsEverySection(): void {
  const config: BotConfig = parseBotConfig(TOML);
  assert.ok(config instanceof BotConfig);

  assert.ok(config.bot instanceof BotIdentity);
  assert.deepEqual([config.bot.name, config.bot.version, config.bot.contact], ["MyBot", "2.0", "https://example.org/me"]);
  assert.deepEqual([config.wiki.lang, config.wiki.family, config.wiki.scriptPath], ["fr", "wikipedia", "/w"]);
  assertNone(config.wiki.server);
  assert.deepEqual([config.throttle.read, config.throttle.write], ["200ms", "5s"]);
  assert.deepEqual(
    [config.login?.account, config.login?.botName, config.login?.passwordEnv, config.login?.optional],
    ["MyBot", "task", "MY_PASSWORD", false],
  );
  assertNone(config.cache);
  assert.equal(config.maxlag, 2);
  assert.deepEqual([config.oauth?.tokenEnv, config.oauth?.username, config.oauth?.optional], ["MY_TOKEN", "MyBot", true]);
  assert.deepEqual([config.run.readConcurrency, config.run.writeConcurrency, config.run.readBatch], [8, 2, 50]);
  assert.deepEqual([config.http.maxConcurrentRequests, config.http.maxIdleConnections, config.http.timeout], [2, 4, "30s"]);
  assert.deepEqual([config.retry.maxRetries, config.retry.initialDelay, config.retry.maxDelay, config.retry.relogins], [1, "2s", "4s", 0]);

  assert.equal(config.language(), "fr");
  assert.equal(config.family().name, "wikipedia");
  assert.equal(config.endpoint().server, "fr.wikipedia.org");

  const options = config.connectOptions();
  assert.match(options.userAgent?.toString() ?? "", /MyBot\/2\.0/);
  assert.equal(options.throttle?.read, 200);
  assert.equal(options.throttle?.write, 5000);
  assert.equal(options.retry?.maxRetries, 1);
  assert.equal(options.retry?.initialDelay, 2000);
  assert.equal(options.retry?.maxDelay, 4000);
  assert.equal(options.maxlag, 2);
  assert.equal(options.relogins, 0);
  assert.equal(options.http?.maxConcurrentRequests, 2);
  assert.equal(options.http?.maxIdleConnections, 4);
  assert.equal(options.http?.timeout, 30000);
  assertNone(options.login);

  const password = config.credentials((name) => (name === "MY_PASSWORD" ? "secret" : null));
  assert.ok(password instanceof BotPassword);
  assert.deepEqual([password.account, password.botName], ["MyBot", "task"]);

  const token = config.credentials((name) => (name === "MY_TOKEN" ? "token" : null));
  assert.ok(token instanceof BotPassword);
  assert.ok(token instanceof OAuth2);
  assert.equal(token.username, "MyBot");

  assert.throws(() => config.credentials(() => null), /MY_PASSWORD/);
  assertNone(parseBotConfig('[bot]\nname = "B"\ncontact = "c"\n').credentials(() => null));
}

function aConfigurationCanBeMade(): void {
  const init: BotConfigInit = {
    bot: new BotIdentity("Made", "c"),
    wiki: new WikiSelection("de", "wiktionary", "wiki.example.org", "/mw"),
    throttle: new ThrottleSettings("1s", "2s"),
    login: new LoginSettings("Made", "task"),
    cache: new CacheSettings("here", "1h"),
    maxlag: 0,
    oauth: new OAuthSettings(),
    run: new RunSettings(undefined, undefined, 3),
    http: new HttpSection(undefined, undefined, "9s"),
    retry: new RetrySettings(9),
  };
  const config = createBotConfig(init);

  assert.equal(config.bot.version, "1.0");
  assert.equal(config.endpoint().server, "wiki.example.org");
  assert.equal(config.endpoint().scriptPath, "/mw");
  assert.equal(config.throttle.write, "2s");
  assert.equal(config.login?.passwordEnv, "KWIKIBOT_PASSWORD");
  assert.equal(config.login?.optional, false);
  assert.ok(config.cache instanceof CacheSettings);
  assert.deepEqual([config.cache.path, config.cache.ttl], ["here", "1h"]);
  assert.equal(config.maxlag, 0);
  assert.equal(config.oauth?.tokenEnv, "KWIKIBOT_OAUTH_TOKEN");
  assertNone(config.oauth?.username);
  assert.equal(config.oauth?.optional, false);
  assert.equal(config.run.readBatch, 3);
  assertNone(config.run.readConcurrency);
  assertNone(config.run.writeConcurrency);
  assert.equal(config.http.timeout, "9s");
  assert.equal(config.http.maxConcurrentRequests, 3);
  assert.equal(config.retry.maxRetries, 9);
  assertNone(config.retry.initialDelay);
  assertNone(config.retry.maxDelay);

  const plain = createBotConfig({ bot: new BotIdentity("Plain", "c", "3") });
  assert.equal(plain.bot.version, "3");
  assert.equal(plain.wiki.family, "wiktionary");
  assert.equal(plain.wiki.lang, "en");
  assert.equal(plain.throttle.read, "100ms");
  assert.equal(plain.maxlag, BOT_CONFIG_DEFAULT_MAXLAG);
  assert.equal(BOT_CONFIG_DEFAULT_MAXLAG, 5);
  assertNone(plain.login);
  assertNone(plain.oauth);
  assertNone(plain.connectOptions().cache);

  assert.equal(BOT_CONFIG_FILE_NAME, "kwikibot.toml");
  assert.match(botConfigTemplate(), /\[bot\]/);
  assert.equal(parseBotConfig(botConfigTemplate()).bot.name, "MyBot");
}

function aConfigurationOnDiskIsFound(): void {
  const directory = mkdtempSync(join(tmpdir(), "kwikibot-typed-config-"));
  const path = join(directory, "config.toml");
  writeFileSync(path, '[bot]\nname = "OnDisk"\ncontact = "c"\n');
  const onDisk: BotConfig = readBotConfig(path);
  assert.equal(onDisk.bot.name, "OnDisk");
  assert.equal(findBotConfig(path)?.bot.name, "OnDisk");
  assert.throws(() => findBotConfig(`${path}.absent`));

  const cwd = process.cwd();
  const saved = { HOME: process.env.HOME, KWIKIBOT_CONFIG: process.env.KWIKIBOT_CONFIG, XDG_CONFIG_HOME: process.env.XDG_CONFIG_HOME };
  process.chdir(directory);
  process.env.HOME = directory;
  process.env.XDG_CONFIG_HOME = directory;
  delete process.env.KWIKIBOT_CONFIG;
  try {
    const searched = assertArray(botConfigSearchPath());
    assert.equal(searched[0], BOT_CONFIG_FILE_NAME);
    for (const place of searched.slice(1)) assert.ok(place.startsWith(directory), `searched outside the sandbox: ${place}`);
    assertNone(findBotConfig());
    writeFileSync(join(directory, BOT_CONFIG_FILE_NAME), '[bot]\nname = "Here"\ncontact = "c"\n');
    assert.equal(findBotConfig()?.bot.name, "Here");
  } finally {
    process.chdir(cwd);
    for (const [name, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
    rmSync(directory, { recursive: true, force: true });
  }
}

async function aConfigurationOpensItsWikiOnAClient(): Promise<void> {
  const site = new FakeSite();
  const restore = site.install();
  try {
    const config = parseBotConfig('[bot]\nname = "B"\ncontact = "c"\n[wiki]\nserver = "test.example.org"\n');
    const client = createClient(CONTACT, QUICK);
    const wiki = await config.connect(client);
    assert.equal(wiki.siteName, "Test Wiki");
    wiki.close();
    client.close();
  } finally {
    restore();
  }
}

function theCommonFlagsAreReadAndHandedBack(): void {
  const parsed: ParsedCommandLine = parseCommonOptions([
    "--save",
    "--limit=3",
    "--diff-log",
    "d.txt",
    "--skip-log",
    "s.txt",
    "--stop-page",
    "Stop",
    "--read-delay",
    "100ms",
    "--write-delay",
    "2s",
    "--http-connections",
    "5",
    "--contact",
    "me",
    "--state",
    "dir",
    "--resume",
    "--trial",
    "mine",
  ]);
  assert.ok(parsed instanceof ParsedCommandLine);
  assert.deepEqual(assertArray(parsed.rest), ["mine"]);
  const options: CommonOptions = parsed.options;
  assert.ok(options instanceof CommonOptions);
  assert.deepEqual([options.save, options.resume, options.trial], [true, true, true]);
  assert.equal(options.limit, 3);
  assert.deepEqual([options.diffLog, options.skipLog, options.state], ["d.txt", "s.txt", "dir"]);
  assert.equal(options.stopPage, "Stop");
  assert.deepEqual([options.readDelay, options.writeDelay], [100, 2000]);
  assert.equal(options.httpConnections, 5);
  assert.equal(options.contact, "me");

  const bare = parseCommonOptions(["mine", "--", "--save"]);
  assert.deepEqual(bare.rest, ["mine", "--save"]);
  assert.deepEqual([bare.options.save, bare.options.resume, bare.options.trial], [false, false, false]);
  assertNone(bare.options.limit);
  assertNone(bare.options.diffLog);
  assertNone(bare.options.skipLog);
  assertNone(bare.options.stopPage);
  assertNone(bare.options.readDelay);
  assertNone(bare.options.writeDelay);
  assertNone(bare.options.httpConnections);
  assertNone(bare.options.contact);
  assertNone(bare.options.state);

  assert.match(COMMON_OPTIONS_USAGE, /--save/);
  assert.throws(() => parseCommonOptions(["--limit"]), /--limit/);

  const init: CommonOptionsInit = {
    save: true,
    limit: 2,
    diffLog: "d",
    skipLog: "s",
    stopPage: "Stop",
    readDelay: 50,
    writeDelay: 70,
    httpConnections: 7,
    contact: "elsewhere",
    state: "dir",
    resume: true,
    trial: true,
  };
  const made = createCommonOptions(init);
  assert.deepEqual(
    [made.save, made.limit, made.diffLog, made.skipLog, made.stopPage, made.readDelay, made.writeDelay, made.httpConnections, made.contact, made.state, made.resume, made.trial],
    [true, 2, "d", "s", "Stop", 50, 70, 7, "elsewhere", "dir", true, true],
  );
  assert.throws(() => createCommonOptions({ resume: true }));
  assert.throws(() => createCommonOptions({ trial: true }));

  const merged = createCommonOptions({ readDelay: 50, httpConnections: 7, contact: "elsewhere" }).connectOptions({
    maxlag: 1,
    userAgent: new UserAgent("Mine", "1", "here"),
    throttle: { write: 9 },
  });
  assert.equal(merged.maxlag, 1);
  assert.equal(merged.userAgent?.contact, "elsewhere");
  assert.equal(merged.throttle?.read, 50);
  assert.equal(merged.throttle?.write, 9);
  assert.equal(merged.http?.maxConcurrentRequests, 7);
  assert.equal(merged.http?.maxIdleConnections, 7);
  assertNone(createCommonOptions().connectOptions().maxlag);
}

async function theCommonFlagsMakeAStopPolicyAndALog(): Promise<void> {
  const { site } = bench({ Stop: "false", Volcano: "ash\n" });
  const directory = mkdtempSync(join(tmpdir(), "kwikibot-typed-flags-"));
  await withWiki(site, async (wiki) => {
    assert.equal(await createCommonOptions({ stopPage: "Stop" }).stopPolicy(wiki).mayContinue(), true);
    assert.equal(await createCommonOptions({ stopPage: "Absent" }).stopPolicy(wiki).mayContinue(), false);
    assert.equal(await createCommonOptions().stopPolicy(wiki).mayContinue(), true);

    const diffLog = join(directory, "diffs.txt");
    const skipLog = join(directory, "skips.jsonl");
    const log: RunLog = createCommonOptions({ diffLog, skipLog }).runLog(wiki);
    assert.ok(log instanceof RunLog);
    await wiki.botRun({
      source: titles("Volcano", "Absent"),
      transform: tephra,
      onOutcome: (outcome) => log.record(outcome),
    });
    assert.match(readFileSync(diffLog, "utf8"), /-ash/);
    assert.match(readFileSync(skipLog, "utf8"), /"Absent"/);
    createCommonOptions().runLog(wiki);
  });
  rmSync(directory, { recursive: true, force: true });
}

const skippingTransform = (page: Page): PageEdit | null => {
  if (page.title === "Volcano") return { text: "tephra\n", summary: "naming it" };
  if (page.title === "Etna") return skipPage("not this one");
  return null;
};

async function aRunsLogWritesDiffsAndSkips(): Promise<void> {
  const { site } = volcanoes();
  const directory = mkdtempSync(join(tmpdir(), "kwikibot-typed-log-"));
  await withWiki(
    site,
    async (wiki) => {
      const { outcomes } = await run(wiki, { source: titles("Volcano", "Etna", "Nope"), transform: skippingTransform, readConcurrency: 1 });
      let diffs = "";
      let skips = "";
      const options: RunLogOptions = {
        onDiff: (text) => {
          diffs += text;
        },
        onSkip: (text) => {
          skips += text;
        },
        context: 0,
        namespaces: wiki.namespaces,
      };
      const log = createRunLog(options);
      outcomes.forEach((outcome) => log.record(outcome));
      assert.ok(diffs.startsWith("=== diff: Volcano ===\n--- old/Volcano"));
      const records = skips.trim().split("\n");
      assert.equal(records[0], '{"title":"Etna","kind":"skipped","reason":"not this one"}');
      assert.equal(records[1], '{"title":"Nope","kind":"missing","reason":"page does not exist"}');

      const silent = createRunLog();
      outcomes.forEach((outcome) => silent.record(outcome));

      const diffFile = join(directory, "diffs");
      const skipFile = join(directory, "skips");
      const toFiles = createRunLog({ diffFile, skipFile });
      outcomes.forEach((outcome) => toFiles.record(outcome));
      assert.match(readFileSync(diffFile, "utf8"), /-ash/);
      assert.match(readFileSync(skipFile, "utf8"), /not this one/);
    },
    LOGIN,
  );
  rmSync(directory, { recursive: true, force: true });
}

async function aProgressLineSaysHowFarARunHasCome(): Promise<void> {
  const { site } = volcanoes();
  await withWiki(
    site,
    async (wiki) => {
      const { outcomes } = await run(wiki, { source: titles("Volcano", "Etna", "Nope"), transform: skippingTransform, readConcurrency: 1 });
      let written = "";
      let now = 0;
      const options: ProgressOptions = {
        total: 3,
        write: (text) => {
          written += text;
        },
        width: 200,
        now: () => now,
      };
      const progress: Progress = createProgress(options);
      assert.ok(progress instanceof Progress);
      for (const outcome of outcomes) {
        now += 1000;
        progress.record(outcome);
      }
      progress.finish();
      assert.match(written, /3\/3/);
      assert.match(written, /would change 1/);
      assert.match(written, /skipped 2/);
      assert.ok(written.endsWith("\n"));

      let off = "";
      const hidden = createProgress({
        enabled: false,
        write: (text) => {
          off += text;
        },
      });
      hidden.finish();
      assert.equal(off, "");
      createProgress({ enabled: false }).finish();
    },
    LOGIN,
  );
}

async function aRunsStateResumes(): Promise<void> {
  const { site } = volcanoes();
  const directory = mkdtempSync(join(tmpdir(), "kwikibot-typed-state-"));
  await withWiki(
    site,
    async (wiki) => {
      const state = new RunState(directory);
      assert.equal(state.directory, directory);
      assert.equal(await state.finishedCount(), 0);

      const { outcomes } = await run(wiki, {
        source: titles("Volcano", "Etna", "Nope"),
        transform: skippingTransform,
        readConcurrency: 1,
        dryRun: false,
        state,
      });
      assert.deepEqual(
        outcomes.map((outcome) => outcome.kind),
        ["saved", "skipped", "missing"],
      );
      assert.equal(await state.finishedCount(), 3);
      assert.equal(await state.isFinished(wiki, "Volcano"), true);
      assert.equal(await state.isFinished(wiki, "Other"), false);
      assert.equal(await state.pin(wiki, "Volcano"), 9002);
      assertNone(await state.pin(wiki, "Other"));
      assert.match(readFileSync(join(directory, "saves.jsonl"), "utf8"), /naming it/);

      const resumed = await run(wiki, {
        source: titles("Volcano", "Etna", "Nope"),
        transform: skippingTransform,
        dryRun: false,
        state,
        resume: true,
      });
      assert.deepEqual(resumed.outcomes, []);

      const again = await run(wiki, { source: titles("Etna"), transform: skippingTransform });
      assert.equal(await state.record(only(again.outcomes), 5), undefined);
      await state.record(only(again.outcomes));
      state.close();
    },
    LOGIN,
  );
  rmSync(directory, { recursive: true, force: true });
}

async function sourcesNameTheirPages(): Promise<void> {
  const { site } = bench({ Volcano: "ash", Etna: "lava", Redirect: "#REDIRECT [[Etna]]" });
  await withWiki(site, async (wiki) => {
    const source: PageSource = titles("Volcano", "Etna");
    assert.ok(source instanceof PageSource);
    assert.deepEqual(await collect(source.pages(wiki)), ["Volcano", "Etna"]);
    assert.deepEqual(await collect(concatPageSources([titles("Volcano"), titles("Etna")]).pages(wiki)), ["Volcano", "Etna"]);
    assert.deepEqual(await collect(EMPTY_PAGE_SOURCE.pages(wiki)), []);

    assert.deepEqual(await collect(pageSourceOf(wiki.search("x")).pages(wiki)), ["Volcano", "Volcanic ash"]);
    async function* generated(): AsyncGenerator<string> {
      yield "Etna";
    }
    assert.deepEqual(await collect(pageSourceOf(generated()).pages(wiki)), ["Etna"]);
    assert.deepEqual(await collect(pageSourceFrom((opened) => opened.embeddedIn("Infobox")).pages(wiki)), ["Volcano"]);

    let ended = false;
    const endless: AsyncIterable<string> = {
      [Symbol.asyncIterator]: () => ({
        next: async () => ({ value: "Etna", done: false }),
        return: async () => {
          ended = true;
          return { value: undefined, done: true };
        },
      }),
    };
    assert.deepEqual(await take(pageSourceOf(endless).pages(wiki), 1), ["Etna"]);
    assert.equal(ended, true);

    assert.deepEqual(await collect(parsePageSource("page:Volcano").pages(wiki)), ["Volcano"]);
    assert.deepEqual(await collect(parsePageSources(["page:Volcano", "page:Etna"]).pages(wiki)), ["Volcano", "Etna"]);
    assert.deepEqual(await collect(parsePageSource("search:lava").pages(wiki)), ["Volcano", "Volcanic ash"]);
    assert.equal(site.requests.filter((request) => request.list === "search").at(-1)?.srsearch, "lava");
    assert.deepEqual(assertArray(pageSourceKinds()), [
      "page",
      "cat",
      "subcats",
      "catfiles",
      "links",
      "backlinks",
      "transcludes",
      "search",
      "prefix",
      "allpages",
      "special",
      "usercontribs",
      "recentchanges",
      "file",
    ]);
    assert.throws(() => parsePageSource("nope:x"), /available/);

    const all = titles("Volcano", "Etna", "User:Me", "Volcano", "Volcano/sub/deep");
    assert.deepEqual(await collect(all.inNamespaces([2]).pages(wiki)), ["User:Me"]);
    assert.equal((await collect(all.inNamespaces([]).pages(wiki))).length, 5);
    assert.deepEqual(await collect(all.titleMatching(/^Etna$/).pages(wiki)), ["Etna"]);
    assert.deepEqual(await collect(all.titleNotMatching(/V|M/).pages(wiki)), ["Etna"]);
    assert.equal((await collect(all.distinctPages().pages(wiki))).length, 4);
    assert.equal((await collect(all.subpageDepthAtMost(1).pages(wiki))).length, 4);
    assert.deepEqual(await collect(all.intersect(titles("Etna", "Other")).pages(wiki)), ["Etna"]);
    assert.deepEqual(await collect(all.excluding(titles("Volcano", "Volcano/sub/deep", "User:Me")).pages(wiki)), ["Etna"]);
    assert.equal((await collect(all.pages(wiki))).length, 5);

    assert.deepEqual(await take(titles("Etna", "Etna", "Volcano").repeating(1, 10).pages(wiki), 2), ["Etna", "Volcano"]);
    assert.deepEqual(await take(titles("Etna").repeating().pages(wiki), 1), ["Etna"]);

    const named = titles("Volcano", "Etna", "Nope");
    const contents: PageContents = named.withContent(wiki, 2, 2);
    assert.ok(contents instanceof PageContents);
    const read = await collect(contents.pages());
    assert.deepEqual(
      read.map((page) => [page.title, page.text]),
      [
        ["Volcano", "ash"],
        ["Etna", "lava"],
      ],
    );
    const batches = site.requests.filter((request) => (request.rvprop ?? "").includes("content")).map((request) => request.titles);
    assert.deepEqual(batches, ["Volcano|Etna", "Nope"]);

    const pageTitles = async (from: PageContents): Promise<string[]> => (await collect(from.pages())).map((page) => page.title);
    assert.deepEqual(await pageTitles(named.withContent(wiki).textMatching(/ash/)), ["Volcano"]);
    assert.deepEqual(await pageTitles(named.withContent(wiki).textNotMatching(/ash/)), ["Etna"]);
    const withRedirect = titles("Volcano", "Redirect");
    assert.deepEqual(await pageTitles(withRedirect.withContent(wiki).redirects(false)), ["Volcano"]);
    assert.deepEqual(await pageTitles(withRedirect.withContent(wiki).redirects()), ["Redirect"]);
  });
}

async function externalToolsAreReadForPages(): Promise<void> {
  const { site } = volcanoes();
  site.raw = (url) => {
    const { host, pathname } = new URL(url);
    if (host.includes("petscan")) return new Response("Volcano\n\nEtna\n");
    if (host.includes("pagepile")) return json({ pages: ["Etna"] });
    if (host === "q.example.org") return new Response(pathname === "/plain" ? '?title\n"Volcano"\n' : '?page\n"Etna"\n');
    return undefined;
  };
  const toolRequests = (): Array<Record<string, string>> => site.requests.filter((request) => request.action !== "query");
  await withWiki(site, async (wiki) => {
    const query: Record<string, string> = { categories: "Volcanoes" };

    assert.deepEqual(await collect(petScanSource(query, CONTACT).pages(wiki)), ["Volcano", "Etna"]);
    const asked = toolRequests().at(-1);
    assert.equal(asked?.categories, "Volcanoes");
    assert.equal(asked?.format, "plain");

    assert.deepEqual(await collect(petScanSavedSource("42", CONTACT, new UserAgent("Mine", "1", "c")).pages(wiki)), ["Volcano", "Etna"]);
    assert.equal(toolRequests().at(-1)?.psid, "42");

    assert.deepEqual(assertArray(await petScanTitles(query, CONTACT)), ["Volcano", "Etna"]);

    const walked: string[] = [];
    const answer: number = await withPetScanTitles(query, CONTACT, async (names) => {
      walked.push(...(await collect(names)));
      return walked.length;
    });
    assert.equal(answer, 2);
    assert.deepEqual(walked, ["Volcano", "Etna"]);
    const plain: string = await withPetScanTitles(query, CONTACT, () => "ignored the titles");
    assert.equal(plain, "ignored the titles");
    await withPetScanTitles(query, CONTACT, async (names) => {
      assert.deepEqual(await collect(names), ["Volcano", "Etna"]);
      await assert.rejects(collect(names), /only once/);
    });

    assert.deepEqual(await collect(pagePileSource("7", CONTACT).pages(wiki)), ["Etna"]);
    assert.equal(toolRequests().at(-1)?.id, "7");

    const options: SparqlSourceOptions = {
      endpoint: "https://q.example.org/plain",
      auth: sparqlAuthNone(),
      userAgent: new UserAgent("Mine", "1", "c"),
    };
    assert.deepEqual(await collect(sparqlSource("SELECT ?title", CONTACT, options).pages(wiki)), ["Volcano"]);
    assert.deepEqual(
      await collect(sparqlSource("SELECT ?page", CONTACT, { endpoint: "https://q.example.org/named", variable: "page" }).pages(wiki)),
      ["Etna"],
    );
  });
}

function typosDoNotCompile(report: BotReport, outcome: PageOutcome, problem: Problem): void {
  // @ts-expect-error there is no such kind of outcome
  const kind: PageOutcomeKind = "pendng";
  // @ts-expect-error a report never stops for that reason
  const stopped: boolean = report.stopReason === "limitt";
  // @ts-expect-error a saved outcome is spelled "saved"
  const saved: boolean = outcome.kind === "save";
  // @ts-expect-error a problem is refused or failed
  const refused: ProblemKind = "denied";
  // @ts-expect-error a stop check answers with a boolean
  const policy = stopPolicyOf(() => "yes");
  const bad: BotRunOptions = {
    source: EMPTY_PAGE_SOURCE,
    // @ts-expect-error a transform answers with an edit or null, not its text
    transform: () => "just text",
  };
  // @ts-expect-error an edit needs a summary
  const edit: PageEdit = { text: "x" };
  void [kind, stopped, saved, refused, policy, bad, edit, problem];
}
void typosDoNotCompile;

async function whatIsNotAnErrorRejectsWithItself(): Promise<void> {
  const wiki = createFakeWiki({ pages: new FakePageService({ pages: { Volcano: "ash" } }) });
  const plain = { code: 7 };
  for (const value of ["boom", undefined, null, plain]) {
    const thrown = await wiki
      .botRun({
        source: titles("Volcano"),
        transform: () => {
          throw value;
        },
      })
      .then(
        () => assert.fail("the run should have rejected"),
        (failure: unknown) => failure,
      );
    assert.ok(thrown instanceof ThrownValue);
    assert.ok(thrown instanceof Error);
    const kept: Thrown = thrown.value;
    assert.equal(kept, value);
  }
  const rejected = await wiki
    .botRun({ source: titles("Volcano"), transform: () => Promise.reject() })
    .catch((failure: unknown) => failure);
  assert.ok(rejected instanceof ThrownValue);
  assert.equal(rejected.value, undefined);
}

await whatIsNotAnErrorRejectsWithItself();
await aDryRunComputesAndSavesNothing();
await aSavingRunSendsTheEditAsItWasSaid();
await anEditOfNoTextAndASectionAreToldApart();
await aTransformSkipsWithAReasonOrStopsTheRun();
await createMissingMakesAPageThatMustNotExist();
await limitsCapARun();
await aStopPolicyStopsASavingRun();
await stopPoliciesCombineAndReadAPage();
await aSharedStopReadsOncePerInterval();
await whatTheWikiRefusesOrCannotServeIsAProblem();
await anExclusionIsHonouredUnlessToldOtherwise();
await theCommonFlagsAndAConfigurationSetWhatTheOptionsDoNot();
await reportToHandsEachOutcomeToEveryHandler();
await theSimpleRunReportsInItsOwnTerms();
aConfigurationReadsEverySection();
aConfigurationCanBeMade();
aConfigurationOnDiskIsFound();
await aConfigurationOpensItsWikiOnAClient();
theCommonFlagsAreReadAndHandedBack();
await theCommonFlagsMakeAStopPolicyAndALog();
await aRunsLogWritesDiffsAndSkips();
await aProgressLineSaysHowFarARunHasCome();
await aRunsStateResumes();
await sourcesNameTheirPages();
await externalToolsAreReadForPages();

console.log("bot_runs: runs, reports, sources, stop policies, flags and configurations behave as typed");
