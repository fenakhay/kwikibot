import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BotPassword, type JsonValue } from "kwikibot";
import { createCommonOptions, pageSourceOfTitles, type RunReport, stopPolicyOf } from "kwikibot/bot";
import {
  type Accounts,
  BotRegistry,
  type BotSpec,
  createAccounts,
  createFleet,
  createFleetWith,
  cron,
  DeclinedError,
  every,
  type Fleet,
  FleetEvent,
  type FleetHostOptions,
  type FleetOptions,
  FleetRun,
  type Question,
  RecordingContext,
  type RecordingOptions,
  type RunContext,
  type RunLevel,
  type RunStatus,
  Schedule,
} from "kwikibot/fleet";
import { endpointOf, FAMILY_WIKTIONARY } from "kwikibot/titles";
import { FakeSite, QUICK, assertArray, assertDate, assertNone, collect, withWiki } from "./support.js";

const LOGIN = new BotPassword("TestBot", "task", "secret");

function wikiWith(pages: Record<string, string>): FakeSite {
  const texts = new Map(Object.entries(pages));
  let revision = 9001;
  return new FakeSite({
    "info|revisions": (params) => {
      const entries: JsonValue[] = (params.titles ?? "").split("|").map((title): JsonValue => {
        const text = texts.get(title);
        if (text === undefined) return { ns: 0, title, missing: true };
        return {
          pageid: 1,
          ns: 0,
          title,
          revisions: [{ revid: revision, timestamp: "2026-08-31T21:43:26Z", slots: { main: { content: text } } }],
        };
      });
      return { query: { pages: entries } };
    },
    edit: (params) => {
      const title = params.title ?? "";
      const previous = revision;
      revision += 1;
      texts.set(title, params.text ?? "");
      return { edit: { result: "Success", pageid: 1, title, oldrevid: previous, newrevid: revision } };
    },
  });
}

function statusOf(fleet: Fleet, bot: string): RunStatus | undefined {
  return fleet.runs().find((run) => run.bot === bot)?.status;
}

function runOf(fleet: Fleet, bot: string): FleetRun {
  const run = fleet.runs().find((candidate) => candidate.bot === bot);
  assert.ok(run instanceof FleetRun, `${bot} has no run`);
  return run;
}

async function schedules(): Promise<void> {
  const minutely: Schedule = every(60_000);
  assert.ok(minutely instanceof Schedule);
  assert.equal(assertDate(minutely.next(new Date(0))).getTime(), 60_000);
  assert.equal(assertDate(cron("0 3 * * *").next(new Date(0))).getTime(), 3 * 3_600_000);
  assert.equal(cron("0 3 * * *", "Europe/Berlin").next(new Date(0)).getTime(), 2 * 3_600_000);
  assert.throws(() => cron("not a schedule"));
}

async function aSimpleFleet(): Promise<void> {
  const site = wikiWith({ Volcano: "ash\n" });
  const restore = site.install();
  const lines: string[] = [];
  const asked: Array<[string, string, string]> = [];
  const options: FleetOptions = {
    server: "test.example.org",
    contact: "https://example.org/typed",
    scriptPath: "/w",
    login: LOGIN,
    accounts: { reviewer: new BotPassword("Reviewer", "task", "secret") },
    stopPage: null,
    onAsk: async (bot, title, summary) => {
      asked.push([bot, title, summary]);
      return title !== "Delete everything?";
    },
    onLine: (line) => {
      lines.push(line);
    },
  };
  let reviewerName: string | undefined;
  const bots: BotSpec[] = [
    {
      id: "reader",
      description: "reads a page",
      run: async (context) => {
        const page = await context.wiki.page("Volcano");
        assert.ok(page != null);
        context.expect(2);
        context.expect(1);
        context.say(`read ${page.title} ${context.arguments.join(",")}`);
        context.stopped("first reason");
        context.stopped("second reason");
        assert.equal(context.save, false);
      },
    },
    {
      id: "reviewer",
      account: "reviewer",
      run: (context) => {
        reviewerName = context.wiki.user.name;
      },
    },
    {
      id: "broken",
      run: async () => {
        throw new Error("it broke");
      },
    },
    {
      id: "synchronously-broken",
      run: () => {
        throw new Error("it broke at once");
      },
    },
    {
      id: "asker",
      run: async (context) => {
        const yes: boolean = await context.ask("Go on?", "yes goes on");
        assert.equal(yes, true);
        assert.equal(await context.ask("Delete everything?"), false);
        throw new DeclinedError("they said no");
      },
    },
    {
      id: "waiter",
      schedule: every(3_600_000),
      run: async (context) => {
        const signal: AbortSignal = context.signal;
        assert.ok(signal instanceof AbortSignal);
        assert.equal(signal.aborted, false);
        await new Promise<void>((resolve) => {
          signal.addEventListener("abort", () => {
            resolve();
          });
        });
        assert.equal(signal.aborted, true);
      },
    },
  ];

  const fleet: Fleet = await createFleet(options, bots);
  try {
    assert.equal(fleet.runs().length, 0);
    assert.throws(() => fleet.start("nobody"));

    const reader: number = fleet.start("reader", ["x", "y"]);
    assert.equal(typeof reader, "number");
    fleet.start("reviewer");
    fleet.start("broken");
    fleet.start("synchronously-broken");
    fleet.start("asker");
    const waiting = fleet.start("waiter", [], false);
    assert.equal(statusOf(fleet, "waiter") === "done", false);
    await new Promise((resolve) => {
      setTimeout(resolve, 20);
    });
    assert.equal(fleet.cancel(waiting), true);
    assert.equal(fleet.cancel(12345), false);

    const idle: Promise<void> = fleet.idle();
    assert.ok(idle instanceof Promise);
    await idle;

    const runs: FleetRun[] = assertArray(fleet.runs());
    assert.equal(runs.length, 6);
    assert.equal(runs.every((run) => run instanceof FleetRun && run.isFinished === true), true);
    assert.deepEqual(runs.map((run) => run.id), [...new Set(runs.map((run) => run.id))]);
    const done = runOf(fleet, "reader");
    assert.equal(done.id, reader);
    assert.equal(done.bot, "reader");
    assert.equal(done.status, "done");
    assert.equal(done.expected, 3);
    assert.equal(done.seen, 0);
    assert.equal(done.saved, 0);
    assert.equal(done.stoppedBecause, "first reason");
    assertNone(done.failure);

    assert.equal(statusOf(fleet, "reviewer"), "done");
    assert.equal(reviewerName, "Reviewer");

    const failed = runOf(fleet, "broken");
    assert.equal(failed.status, "failed");
    assert.equal(failed.failure, "it broke");
    assertNone(failed.stoppedBecause);
    assert.equal(statusOf(fleet, "synchronously-broken"), "failed");

    assert.equal(statusOf(fleet, "asker"), "declined");
    assert.deepEqual(asked, [
      ["asker", "Go on?", "yes goes on"],
      ["asker", "Delete everything?", ""],
    ]);

    assert.equal(statusOf(fleet, "waiter"), "cancelled");
    assert.ok(lines.includes("[reader] read Volcano x,y"), lines.join("|"));
    assert.ok(lines.includes("[broken] failed: it broke"), lines.join("|"));
  } finally {
    fleet.close();
    restore();
  }
  assert.ok(new DeclinedError("no") instanceof Error);
  assert.equal(new DeclinedError("no").message, "no");
}

async function aHostedFleet(): Promise<void> {
  const site = wikiWith({ Volcano: "ash\n", Etna: "lava\n" });
  const restore = site.install();
  const directory = mkdtempSync(join(tmpdir(), "kwikibot-typed-fleet-"));
  const lines: string[] = [];
  const questions: Question[] = [];
  const named: Array<string | null | undefined> = [];
  const accounts: Accounts = createAccounts("https://example.org/typed", QUICK, (account) => {
    named.push(account);
    return account === "nobody" ? null : LOGIN;
  });
  let writable: string | null | undefined;
  let elsewhere = "";
  let report: RunReport | undefined;
  let botReportSaved = -1;
  let limit: number | null | undefined;
  const hosted: FleetHostOptions = {
    accounts,
    endpoint: endpointOf("test.example.org"),
    stop: stopPolicyOf(async () => true),
    directory,
    onQuestion: async (bot, question) => {
      assert.equal(bot, "full");
      questions.push(question);
      return true;
    },
    now: () => new Date(0),
    onLine: (line) => {
      lines.push(line);
    },
    heartbeat: 1,
  };
  const bots: BotSpec[] = [
    {
      id: "full",
      description: "does everything a run can",
      run: async (context: RunContext) => {
        limit = context.options.limit;
        writable = context.directory;
        const level: RunLevel = "saved";
        context.sayAt("saved one", level);
        assert.equal(await context.stopPolicy.mayContinue(), true);
        assert.equal(await context.mayContinue(), true);
        assert.equal(await context.askQuestion({ title: "Go?", summary: "yes", diff: "+x" }), true);
        assert.equal(await context.ask("And on?"), true);
        const answer: number = await context.step("German", async (inner) => {
          inner.sayAt("in it", "error");
          assert.throws(() => {
            // @ts-expect-error a level nobody has heard of fails at runtime too
            inner.sayAt("x", "nope");
          });
          return 41 + 1;
        });
        assert.equal(answer, 42);
        const nested: string = await context.step("Plain", () => "not a promise");
        assert.equal(nested, "not a promise");
        const french = await context.open("fr", "wiktionary");
        elsewhere = french.id;
        const family = await context.openFamily("en", FAMILY_WIKTIONARY);
        assert.equal(typeof family.id, "string");
      },
    },
    {
      id: "editor",
      run: async (context) => {
        report = await context.run(["Volcano", "Etna", "Missing"], "adding more", async (text, title) => {
          return title === "Etna" ? null : `${text}more`;
        });
      },
    },
    {
      id: "capped",
      run: (context) => context.run(["Volcano", "Etna"], "again", (text) => `${text}!`, 1),
    },
    {
      id: "counting",
      run: async (context) => {
        const counted = await context.wiki.botRun({
          source: pageSourceOfTitles(["Volcano"]),
          stopPolicy: context.stopPolicy,
          dryRun: !context.save,
          onOutcome: context.outcomes,
          transform: (page) => ({ text: `${page.text}more`, summary: "why" }),
        });
        botReportSaved = counted.saved;
      },
    },
    { id: "plain", run: () => 3 },
  ];

  const fleet: Fleet = createFleetWith(hosted, bots);
  try {
    const seen: FleetEvent[] = [];
    const walking = (async () => {
      for await (const event of fleet.events()) {
        seen.push(event);
      }
    })();

    const watched: number[] = [];
    const watching = (async () => {
      for await (const runs of fleet.watchRuns()) {
        assertArray(runs);
        watched.push(runs.length);
        if (runs.length === 5 && runs.every((run) => run.isFinished)) break;
      }
    })();

    await new Promise((resolve) => {
      setTimeout(resolve, 5);
    });
    const id: number = fleet.startWith("full", createCommonOptions({ limit: 3 }), ["x"]);
    assert.equal(typeof id, "number");
    fleet.startWith("editor", createCommonOptions({ save: true }));
    fleet.start("capped", [], true);
    fleet.startWith("counting", createCommonOptions({ save: true }));
    fleet.startWith("plain");
    await fleet.idle();
    await watching;
    assert.equal(watched[0], 0);
    await new Promise((resolve) => {
      setTimeout(resolve, 20);
    });

    assert.equal(statusOf(fleet, "full"), "done", String(runOf(fleet, "full").failure));
    assert.equal(limit, 3);
    assert.equal(writable, join(directory, "full", "1"));
    assert.equal(elsewhere.length > 0, true);
    assertNone(named[0]);
    assert.equal(lines[0], "[full] saved one");

    assert.equal(questions.length, 2);
    const shown = questions[0];
    assert.ok(shown != null);
    assert.equal(shown.title, "Go?");
    assert.equal(shown.summary, "yes");
    assert.equal(shown.diff, "+x");
    const plainly = questions[1];
    assert.ok(plainly != null);
    assert.equal(plainly.title, "And on?");
    assertNone(plainly.diff);

    assert.ok(report != null);
    assert.equal(report.saved, 1);
    assert.equal(report.skipped >= 1, true);
    const edited = runOf(fleet, "editor");
    assert.equal(edited.status, "done");
    assert.equal(edited.saved, 1);
    assert.equal(edited.seen >= 2, true);
    assert.strictEqual(runOf(fleet, "capped").saved, 1);
    assert.equal(botReportSaved, 1);
    assert.equal(runOf(fleet, "counting").seen, 1);
    assert.equal(runOf(fleet, "counting").saved, 1);
    assert.equal(statusOf(fleet, "plain"), "done");

    const said = seen.find((event) => event.kind === "said" && event.message === "in it");
    assert.ok(said instanceof FleetEvent);
    assert.equal(said.bot, "full");
    assert.equal(said.run, id);
    assert.equal(said.level, "error");
    assert.deepEqual(assertArray(said.steps ?? []), ["German"]);
    assertNone(said.status);
    const outside = seen.find((event) => event.message === "saved one");
    assert.ok(outside != null);
    assert.equal(outside.level, "saved");
    assert.deepEqual(outside.steps, []);
    const changed = seen.find((event) => event.kind === "changed" && event.status === "done");
    assert.ok(changed != null);
    assertNone(changed.message);
    assertNone(changed.level);
    assertNone(changed.steps);
    assert.equal(typeof changed.run, "number");

    assert.deepEqual(fleet.registry.all.map((bot) => bot.id), ["full", "editor", "capped", "counting", "plain"]);
    assert.ok(fleet.registry instanceof BotRegistry);

    fleet.close();
    await walking;

    const wiki = await accounts.wiki(endpointOf("test.example.org"));
    assert.equal(wiki.user.name, "TestBot");
    const askedBefore = named.length;
    assert.equal((await accounts.wiki(endpointOf("test.example.org"))).id, wiki.id);
    assert.equal(named.length, askedBefore);
    const logins = site.requests.filter((request) => request.action === "login").length;
    const anonymous = await accounts.wiki(endpointOf("test.example.org"), "nobody");
    assert.equal(typeof anonymous.user.isAnonymous, "boolean");
    assert.equal(site.requests.filter((request) => request.action === "login").length, logins);
    assert.deepEqual(named.slice(-1), ["nobody"]);
  } finally {
    fleet.close();
    accounts.close();
    restore();
    rmSync(directory, { recursive: true, force: true });
  }
  createAccounts("https://example.org/typed").close();
}

async function servingFleets(): Promise<void> {
  const site = wikiWith({});
  const restore = site.install();
  const accounts = createAccounts("https://example.org/typed", QUICK, () => LOGIN);
  const limits: Array<number | null | undefined> = [];
  const due: BotSpec = {
    id: "due",
    schedule: every(1),
    run: (context) => {
      limits.push(context.options.limit);
    },
  };
  const unscheduled: BotSpec = { id: "unscheduled", schedule: null, run: () => assert.fail("nobody schedules this") };
  const withFlags = createFleetWith({ accounts, endpoint: endpointOf("test.example.org") }, [due, unscheduled]);
  const serving: Promise<void> = withFlags.serveWith(createCommonOptions({ limit: 2 }));
  assert.ok(serving instanceof Promise);
  const ended = serving.then(
    () => "resolved",
    () => "rejected",
  );
  for (let waits = 0; waits < 200 && limits.length === 0; waits++) {
    await new Promise((resolve) => {
      setTimeout(resolve, 5);
    });
  }
  assert.equal(limits[0], 2);
  withFlags.close();
  assert.equal(await ended, "resolved");

  let plainly = 0;
  const bare = createFleetWith({ accounts, endpoint: endpointOf("test.example.org") }, [
    {
      id: "due",
      schedule: every(1),
      run: () => {
        plainly += 1;
      },
    },
  ]);
  const unset = bare.serve().then(
    () => "resolved",
    () => "rejected",
  );
  for (let waits = 0; waits < 200 && plainly === 0; waits++) {
    await new Promise((resolve) => {
      setTimeout(resolve, 5);
    });
  }
  assert.ok(plainly >= 1);
  bare.close();
  assert.equal(await unset, "resolved");

  accounts.close();
  restore();
}

function registries(): void {
  const one: BotSpec = { id: "one", run: () => undefined };
  const two: BotSpec = { id: "two", run: () => undefined };
  const registry = new BotRegistry([one, two]);
  assert.deepEqual(assertArray(registry.all).map((bot) => bot.id), ["one", "two"]);
  assert.equal(registry.find("two"), two);
  assertNone(registry.find("three"));
  assert.equal(registry.get("one"), one);
  assert.throws(() => registry.get("three"), /one/);
  assert.throws(() => new BotRegistry([one, one]));
}

async function recording(): Promise<void> {
  await withWiki(
    wikiWith({ Volcano: "ash\n" }),
    async (wiki) => {
      const told: RecordingOptions = {
        stopPolicy: stopPolicyOf(() => false),
        options: createCommonOptions({ save: true }),
        arguments: ["x"],
        directory: "here",
        answer: (question) => question.title === "Yes?",
      };
      const recorded = new RecordingContext(wiki, told);
      const context: RunContext = recorded.context;

      context.say("hello");
      context.sayAt("saved", "saved");
      context.expect(2);
      context.expect(1);
      context.stopped("first");
      context.stopped("second");
      assert.equal(await context.ask("Yes?"), true);
      assert.equal(await context.askQuestion({ title: "No?", diff: "+x" }), false);
      assert.equal(await context.mayContinue(), false);
      assert.equal(context.save, true);
      assert.deepEqual(assertArray(context.arguments), ["x"]);
      assert.equal(context.directory, "here");
      await context.step("inside", (inner) => {
        inner.say("in");
      });

      const report = await wiki.botRun({
        source: pageSourceOfTitles(["Volcano"]),
        transform: () => null,
        onOutcome: context.outcomes,
      });
      assert.equal(report.processed, 1);

      assert.deepEqual(assertArray(recorded.said), ["hello", "saved", "in"]);
      assert.deepEqual(assertArray(recorded.asked).map((question) => question.title), ["Yes?", "No?"]);
      assert.equal(recorded.asked[1]?.diff, "+x");
      assert.deepEqual(assertArray(recorded.pages).map((page) => page.kind), ["skipped"]);
      assert.equal(recorded.expected, 3);
      assert.equal(recorded.stoppedBecause, "first");

      const plain = new RecordingContext(wiki);
      assert.equal(await plain.context.ask("Anything?"), false);
      assert.equal(await plain.context.mayContinue(), true);
      assert.equal(plain.context.save, false);
      assertNone(plain.context.directory);
      assertNone(plain.stoppedBecause);
      assert.equal(plain.expected, 0);
      assert.equal((await plain.context.open("en", "wiktionary")).id, wiki.id);
      assert.equal((await plain.context.openFamily("en", FAMILY_WIKTIONARY)).id, wiki.id);
    },
    LOGIN,
  );
}

async function emptyWalks(): Promise<void> {
  const site = wikiWith({});
  const restore = site.install();
  const accounts = createAccounts("https://example.org/typed", QUICK, () => LOGIN);
  const fleet = createFleetWith({ accounts, endpoint: endpointOf("test.example.org") }, [{ id: "quick", run: () => null }]);
  for await (const runs of fleet.watchRuns()) {
    assert.deepEqual(runs, []);
    break;
  }
  fleet.start("quick");
  await fleet.idle();
  for await (const runs of fleet.watchRuns()) {
    assert.equal(runs[0]?.status, "done");
    break;
  }
  fleet.close();
  accounts.close();
  restore();
}

function rejectedFleetShapes(fleet: Fleet, context: RunContext, run: FleetRun, event: FleetEvent): void {
  // @ts-expect-error a level nobody has heard of
  context.sayAt("x", "warning");
  // @ts-expect-error a status nobody has heard of
  const status: RunStatus = "finished";
  // @ts-expect-error a run's status is one of the known ones
  const compared = run.status === "finished";
  // @ts-expect-error an event's kind is one of the known ones
  const kind = event.kind === "spoke";
  // @ts-expect-error a bot has to say what it does
  const nothing: BotSpec = { id: "nothing" };
  // @ts-expect-error a question has a title
  void context.askQuestion({ summary: "what?" });
  // @ts-expect-error run ids are numbers
  fleet.cancel("1");
  void [status, compared, kind, nothing];
}
void rejectedFleetShapes;

async function aRejectionStaysInItsRun(): Promise<void> {
  const broken = { error: { code: "internal_api_error", info: "no" } };
  const site = new FakeSite({ revisions: broken, search: broken });
  const restore = site.install();
  const accounts = createAccounts("https://example.org/typed", QUICK, () => null);
  const bots: BotSpec[] = [
    {
      id: "forgiving",
      run: async (context) => {
        await assert.rejects(context.wiki.page("Broken"));
        await assert.rejects(collect(context.wiki.search("q")));
        await assert.rejects(
          context.step("failing", () => {
            throw new Error("inside a step");
          }),
        );
        context.say("went on");
      },
    },
    { id: "unforgiving", run: (context) => context.wiki.page("Broken") },
    { id: "steady", run: (context) => context.say("still here") },
  ];
  const fleet = createFleetWith({ accounts, endpoint: endpointOf("test.example.org") }, bots);
  try {
    for (const bot of bots) fleet.start(bot.id);
    assert.equal(await fleet.idle(), undefined);
    const statuses = Object.fromEntries(fleet.runs().map((run) => [run.bot, run.status]));
    assert.deepEqual(statuses, { forgiving: "done", unforgiving: "failed", steady: "done" });
  } finally {
    fleet.close();
    restore();
  }
}

await schedules();
registries();
await aRejectionStaysInItsRun();
await aSimpleFleet();
await aHostedFleet();
await servingFleets();
await recording();
await emptyWalks();

console.log("fleet: bots ran, asked, failed alone, were cancelled and served, on fleets of each kind");
