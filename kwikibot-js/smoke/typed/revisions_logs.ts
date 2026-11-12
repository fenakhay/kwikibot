import assert from "node:assert/strict";
import { describeError, type JsonValue, Revision } from "kwikibot";
import {
  type AllRevisionsOptions,
  type HistoryOptions,
  LogDetails,
  LogEvent,
  type LogEventsOptions,
  LogService,
  Protection,
  RecentChange,
  type RecentChangesOptions,
  type RevisionDeleteOptions,
  RevisionService,
  type WatchlistChangesOptions,
} from "kwikibot/client";
import { FakeSite, assertArray, assertDate, assertNone, assertRecord, collect, withWiki } from "./support.js";

const AUGUST = new Date("2026-08-01T00:00:00Z");
const SEPTEMBER = new Date("2026-09-01T00:00:00Z");
const AUGUST_TEXT = "2026-08-01T00:00:00Z";
const SEPTEMBER_TEXT = "2026-09-01T00:00:00Z";

const HISTORY: JsonValue = {
  query: {
    pages: [
      {
        pageid: 1,
        ns: 0,
        title: "Volcano",
        revisions: [
          { revid: 9001, parentid: 9000, user: "Alice", timestamp: "2026-08-31T21:43:26Z", comment: "adding ash", size: 120, minor: true, bot: true, sha1: "abc", tags: ["mobile edit"] },
          { revid: 9000, parentid: 0, user: "Bob", timestamp: "2026-08-30T08:00:00Z", comment: "created", size: 80, tags: [] },
        ],
      },
    ],
  },
};

function lastSent(site: FakeSite, key: string, value: string): Record<string, string> {
  const found = site.requests.filter((params) => params[key] === value).at(-1);
  assert.ok(found != null, `nothing was sent with ${key}=${value}`);
  return found;
}

function entry(type: string, action: string, params: JsonValue): JsonValue {
  return { logid: 3, type, action, ns: 0, title: "Volcano", pageid: 4, user: "Alice", timestamp: "2026-08-31T21:43:26Z", comment: "why", tags: ["bot"], params };
}

function typosDoNotCompile(revisions: RevisionService, logs: LogService): void {
  // @ts-expect-error a misspelt order
  revisions.history("Volcano", { order: "newest-frist" });
  // @ts-expect-error a misspelt order
  revisions.allRevisions({ order: "oldest" });
  // @ts-expect-error a misspelt order
  revisions.deletedHistory("Volcano", "older");
  // @ts-expect-error a part of a revision that does not exist
  revisions.revisionDelete("Volcano", [1], { hide: ["summary"] });
  // @ts-expect-error a misspelt order
  logs.events({ order: "newest-frist" });
  // @ts-expect-error a misspelt kind of change
  logs.recentChanges({ types: ["edti"] });
  // @ts-expect-error a misspelt kind of change
  logs.watchlistChanges({ types: ["nwe"] });
}
void typosDoNotCompile;

async function walkHistory(site: FakeSite, revisions: RevisionService): Promise<void> {
  site.answers.revisions = HISTORY;
  const options: HistoryOptions = { order: "oldest-first", start: AUGUST, end: SEPTEMBER, user: "Alice", excludeUser: "Bob", limit: 2 };
  const [newest, first] = await collect(revisions.history("Volcano", options));
  assert.ok(newest instanceof Revision);
  assert.equal(newest.id, 9001);
  assert.equal(newest.parentId, 9000);
  assert.equal(assertDate(newest.timestamp).toISOString(), "2026-08-31T21:43:26.000Z");
  assert.equal(newest.user, "Alice");
  assert.equal(newest.comment, "adding ash");
  assert.equal(newest.size, 120);
  assert.equal(newest.minor, true);
  assert.equal(newest.bot, true);
  assert.equal(newest.sha1, "abc");
  assert.deepEqual(assertArray(newest.tags), ["mobile edit"]);
  assert.equal(newest.isUserHidden, false);
  assert.equal(newest.isCommentHidden, false);

  assert.equal(first.id, 9000);
  assertNone(first.parentId);
  assertNone(first.sha1);
  assert.equal(first.minor, false);
  assert.equal(first.bot, false);
  assert.deepEqual(assertArray(first.tags), []);

  const sent = lastSent(site, "prop", "revisions");
  assert.equal(sent.titles, "Volcano");
  assert.equal(sent.rvdir, "newer");
  assert.equal(sent.rvstart, AUGUST_TEXT);
  assert.equal(sent.rvend, SEPTEMBER_TEXT);
  assert.equal(sent.rvuser, "Alice");
  assert.equal(sent.rvexcludeuser, "Bob");
  assert.equal(sent.rvlimit, "2");

  const limited = await collect(revisions.history("Volcano", { limit: 1 }));
  assert.deepEqual(limited.map((revision) => revision.id), [9001]);

  const nothingAsked = await collect(revisions.history("Volcano", { order: null, start: undefined, end: null, user: null, excludeUser: null, limit: null }));
  assert.equal(nothingAsked.length, 2);
  assert.equal(lastSent(site, "prop", "revisions").rvdir, "older");
  assert.equal((await collect(revisions.history("Volcano", null))).length, 2);
  assert.equal((await collect(revisions.history("Volcano"))).length, 2);

  // @ts-expect-error an order the type refuses, which the library refuses too
  assert.throws(() => revisions.history("Volcano", { order: "older" }), (thrown: unknown) => thrown instanceof Error && thrown.message.includes("'newest-first', 'oldest-first'"));
  assert.throws(() => revisions.history("Volcano", { start: new Date("nonsense") }), Error);

  site.answers.revisions = { query: { pages: [{ pageid: 1, ns: 0, title: "Volcano", revisions: [{ revid: 5, timestamp: "2026-08-30T08:00:00Z", userhidden: true, commenthidden: true, sha1hidden: true }] }] } };
  const [hidden] = await collect(revisions.history("Volcano"));
  assert.equal(hidden.isUserHidden, true);
  assert.equal(hidden.isCommentHidden, true);
  assertNone(hidden.user);
  assertNone(hidden.comment);
  assertNone(hidden.sha1);
}

async function readOldText(site: FakeSite, revisions: RevisionService): Promise<void> {
  site.answers.revisions = {
    query: {
      pages: [
        {
          pageid: 1,
          ns: 0,
          title: "Volcano",
          revisions: [
            { revid: 2, timestamp: "2026-08-31T21:43:26Z", slots: { main: { content: "two" } } },
            { revid: 1, timestamp: "2026-08-30T21:43:26Z", slots: { main: { content: "#REDIRECT [[Ash]]" } } },
          ],
        },
      ],
    },
  };
  const pages = await revisions.contentsAt([1, 3, 2, 1]);
  assertArray(pages);
  assert.deepEqual(pages.map((page) => page.revision), [1, 2]);
  assert.deepEqual(pages.map((page) => page.title), ["Volcano", "Volcano"]);
  assert.equal(pages[0].redirectTarget, "Ash");
  assert.equal(lastSent(site, "prop", "revisions").revids, "1|3|2");

  const two = await revisions.contentAt(2);
  assert.ok(two != null);
  assert.equal(two.text, "two");
  assertNone(await revisions.contentAt(3));

  site.answers.revisions = HISTORY;
  const found = await revisions.byId([9000, 9001, 9000, 1]);
  assertArray(found);
  assert.deepEqual(found.map((revision) => revision.id), [9000, 9001]);
  assert.equal(lastSent(site, "prop", "revisions").revids, "9000|9001|1");
  assert.deepEqual(await revisions.byId([]), []);
  assert.deepEqual(await revisions.contentsAt([]), []);

  site.answers.compare = { compare: { body: "<tr>changed</tr>" } };
  const diff = await revisions.compare(1, 2);
  assert.equal(diff, "<tr>changed</tr>");
  const sent = lastSent(site, "action", "compare");
  assert.equal(sent.fromrev, "1");
  assert.equal(sent.torev, "2");
}

async function walkTheWholeWiki(site: FakeSite, revisions: RevisionService): Promise<void> {
  const rows = (module: string): JsonValue => ({
    query: { [module]: [{ pageid: 1, ns: 0, title: "Volcano", revisions: [{ revid: 5, timestamp: "2026-08-30T08:00:00Z", sha1: "abc" }] }] },
  });
  site.answers.allrevisions = rows("allrevisions");
  site.answers.alldeletedrevisions = rows("alldeletedrevisions");
  site.answers.deletedrevisions = { query: { pages: [{ ns: 0, title: "Volcano", deletedrevisions: [{ revid: 5, timestamp: "2026-08-30T08:00:00Z", user: "Alice" }] }] } };

  const options: AllRevisionsOptions = { namespaces: [0, 2], user: "Alice", order: "oldest-first", start: AUGUST, end: SEPTEMBER, limit: 3 };
  const [live] = await collect(revisions.allRevisions(options));
  assert.equal(live.id, 5);
  assert.equal(live.sha1, "abc");
  assert.equal(live.isUserHidden, true);
  assert.equal(live.isCommentHidden, true);
  const all = lastSent(site, "list", "allrevisions");
  assert.equal(all.arvnamespace, "0|2");
  assert.equal(all.arvuser, "Alice");
  assert.equal(all.arvdir, "newer");
  assert.equal(all.arvstart, AUGUST_TEXT);
  assert.equal(all.arvend, SEPTEMBER_TEXT);
  assert.equal(all.arvlimit, "3");
  assert.equal((await collect(revisions.allRevisions())).length, 1);

  const [deleted] = await collect(revisions.allDeletedRevisions(options));
  assert.equal(deleted.id, 5);
  const gone = lastSent(site, "list", "alldeletedrevisions");
  assert.equal(gone.adrnamespace, "0|2");
  assert.equal(gone.adruser, "Alice");
  assert.equal(gone.adrdir, "newer");
  assert.equal(gone.adrlimit, "3");
  assert.equal((await collect(revisions.allDeletedRevisions())).length, 1);

  const [restorable] = await collect(revisions.deletedHistory("Volcano", "oldest-first", 2));
  assert.equal(restorable.user, "Alice");
  const sent = lastSent(site, "prop", "deletedrevisions");
  assert.equal(sent.titles, "Volcano");
  assert.equal(sent.drvdir, "newer");
  assert.equal(sent.drvlimit, "2");
  assert.equal((await collect(revisions.deletedHistory("Volcano", null, null))).length, 1);
  assert.equal(lastSent(site, "prop", "deletedrevisions").drvdir, "older");
}

async function hideARevision(site: FakeSite, revisions: RevisionService): Promise<void> {
  site.answers.revisiondelete = { revisiondelete: { status: "Success" } };
  const options: RevisionDeleteOptions = { hide: ["content", "user"], show: ["comment"], reason: "copyright", suppress: true };
  assert.equal(await revisions.revisionDelete("Volcano", [1, 2], options), undefined);
  const sent = lastSent(site, "action", "revisiondelete");
  assert.equal(sent.target, "Volcano");
  assert.equal(sent.ids, "1|2");
  assert.equal(sent.hide, "content|user");
  assert.equal(sent.show, "comment");
  assert.equal(sent.reason, "copyright");
  assert.equal(sent.suppress, "yes");

  await assert.rejects(revisions.revisionDelete("Volcano", [1]), Error);
  await assert.rejects(revisions.revisionDelete("Volcano", [], { hide: ["content"] }), Error);
  await assert.rejects(revisions.revisionDelete("Volcano", [1], { hide: ["user"], show: ["user"] }), Error);
  // @ts-expect-error a part the type refuses, which the library refuses too
  await assert.rejects(revisions.revisionDelete("Volcano", [1], { hide: ["summary"] }), (thrown: unknown) => thrown instanceof Error && thrown.message.includes("'content', 'comment', 'user'"));

  site.answers.revisiondelete = { error: { code: "permissiondenied", info: "You may not." } };
  const refused = await revisions.revisionDelete("Volcano", [1], { hide: ["content"] }).then(
    () => undefined,
    (reason: unknown) => reason,
  );
  const info = describeError(refused);
  assert.ok(info != null);
  assert.equal(typeof info.message, "string");
}

async function followTheLogs(site: FakeSite, logs: LogService): Promise<void> {
  const answer = (...events: JsonValue[]): void => {
    site.answers.logevents = { query: { logevents: events } };
  };

  answer(entry("delete", "delete", {}));
  const options: LogEventsOptions = { type: "delete", user: "Alice", page: "Volcano", namespaces: [0, 2], order: "oldest-first", start: AUGUST, end: SEPTEMBER, limit: 5 };
  const [deletion] = await collect(logs.events(options));
  assert.ok(deletion instanceof LogEvent);
  assert.equal(deletion.id, 3);
  assert.equal(deletion.type, "delete");
  assert.equal(deletion.action, "delete");
  assert.equal(deletion.page, "Volcano");
  assert.equal(deletion.pageId, 4);
  assert.equal(deletion.user, "Alice");
  assert.equal(deletion.comment, "why");
  assert.equal(assertDate(deletion.timestamp).toISOString(), "2026-08-31T21:43:26.000Z");
  assert.deepEqual(assertArray(deletion.tags), ["bot"]);
  assert.equal(deletion.isSuppressed, false);
  assert.ok(deletion.details instanceof LogDetails);
  assert.equal(deletion.details.kind, "none");
  const sent = lastSent(site, "list", "logevents");
  assert.equal(sent.letype, "delete");
  assert.equal(sent.leuser, "Alice");
  assert.equal(sent.letitle, "Volcano");
  assert.equal(sent.lenamespace, "0|2");
  assert.equal(sent.ledir, "newer");
  assert.equal(sent.lestart, AUGUST_TEXT);
  assert.equal(sent.leend, SEPTEMBER_TEXT);
  assert.equal(sent.lelimit, "5");

  answer(entry("move", "move_redir", {}));
  await collect(logs.events({ type: "move", action: "move/move_redir" }));
  const byAction = lastSent(site, "list", "logevents");
  assert.equal(byAction.leaction, "move/move_redir");
  assertNone(byAction.letype);

  answer({ logid: 3, type: "delete", action: "delete", timestamp: "2026-08-31T21:43:26Z" });
  const [hidden] = await collect(logs.events());
  assertNone(hidden.page);
  assertNone(hidden.pageId);
  assertNone(hidden.user);
  assertNone(hidden.comment);
  assert.equal(hidden.isSuppressed, true);
  assert.deepEqual(assertArray(hidden.tags), []);

  let asked = 0;
  site.answers.logevents = (): JsonValue => {
    asked += 1;
    return asked === 1
      ? { continue: { lecontinue: "20260831|2", continue: "-||" }, query: { logevents: [entry("delete", "delete", {})] } }
      : { query: { logevents: [entry("delete", "restore", {})] } };
  };
  const paged = await collect(logs.events());
  assert.deepEqual(paged.map((event) => event.action), ["delete", "restore"]);
  assert.equal(asked, 2);
  assert.equal(lastSent(site, "list", "logevents").lecontinue, "20260831|2");

  answer(entry("move", "move", { target_ns: 10, target_title: "Template:Ash", suppressredirect: true }), entry("move", "move", { target_ns: 0, target_title: "Ash" }));
  const [moved, redirected] = await collect(logs.events());
  assert.equal(moved.details.kind, "move");
  assert.equal(moved.details.target, "Template:Ash");
  assert.equal(moved.details.suppressedRedirect, true);
  assertNone(moved.details.flags);
  assertNone(moved.details.sha1);
  assertNone(moved.details.revision);
  assert.equal(redirected.details.target, "Ash");
  assert.equal(redirected.details.suppressedRedirect, false);

  answer(
    entry("block", "block", { duration: "1 week", expiry: "2026-09-07T00:00:00Z", flags: ["nocreate"], sitewide: false }),
    entry("block", "block", { duration: "infinity", flags: [], sitewide: true }),
    entry("block", "unblock", {}),
  );
  const [blocked, forever, unblocked] = await collect(logs.events());
  assert.equal(blocked.details.kind, "block");
  assert.equal(blocked.details.duration, "1 week");
  assert.equal(blocked.details.isPartial, true);
  assert.equal(blocked.details.isInfinite, false);
  assert.equal(assertDate(blocked.details.expiry).toISOString(), "2026-09-07T00:00:00.000Z");
  assert.deepEqual(assertArray(blocked.details.flags ?? []), ["nocreate"]);
  assertNone(blocked.details.target);
  assert.equal(forever.details.isInfinite, true);
  assert.equal(forever.details.isPartial, false);
  assertNone(forever.details.expiry);
  assert.equal(unblocked.details.kind, "block");
  assertNone(unblocked.details.duration);
  assertNone(unblocked.details.expiry);
  assert.equal(unblocked.details.isInfinite, true);

  answer(
    entry("protect", "protect", {
      description: "[edit=sysop]",
      details: [
        { type: "edit", level: "sysop", expiry: "infinity" },
        { type: "move", level: "autoconfirmed", expiry: "2026-09-07T00:00:00Z", cascade: true },
      ],
    }),
  );
  const [protect] = await collect(logs.events());
  assert.equal(protect.details.kind, "protect");
  assert.equal(protect.details.description, "[edit=sysop]");
  const protections = protect.details.protections;
  assert.ok(protections != null);
  assertArray(protections);
  const [edit, move] = protections;
  assert.ok(edit instanceof Protection);
  assert.equal(edit.action, "edit");
  assert.equal(edit.level, "sysop");
  assertNone(edit.expiry);
  assert.equal(edit.cascading, false);
  assert.equal(edit.isActiveAt(SEPTEMBER), true);
  assert.equal(move.action, "move");
  assert.equal(move.level, "autoconfirmed");
  assert.equal(move.cascading, true);
  assert.equal(assertDate(move.expiry).toISOString(), "2026-09-07T00:00:00.000Z");
  assert.equal(move.isActiveAt(SEPTEMBER), true);
  assert.equal(move.isActiveAt(new Date("2026-10-01T00:00:00Z")), false);

  answer(
    entry("upload", "upload", { img_sha1: "abc" }),
    entry("rights", "rights", { oldgroups: ["bot"], newgroups: ["sysop"] }),
    entry("patrol", "patrol", { curid: 9, previd: 8, auto: true }),
    entry("patrol", "patrol", { curid: 9, auto: false }),
    entry("thanks", "thank", { who: "Bob", count: 3 }),
  );
  const [upload, rights, patrol, firstPatrol, thanks] = await collect(logs.events());
  assert.equal(upload.details.kind, "upload");
  assert.equal(upload.details.sha1, "abc");
  assert.equal(rights.details.kind, "rights");
  assert.deepEqual(assertArray(rights.details.added ?? []), ["sysop"]);
  assert.deepEqual(assertArray(rights.details.removed ?? []), ["bot"]);
  assert.equal(patrol.details.kind, "patrol");
  assert.equal(patrol.details.revision, 9);
  assert.equal(patrol.details.previous, 8);
  assert.equal(patrol.details.automatic, true);
  assert.equal(firstPatrol.details.automatic, false);
  assertNone(firstPatrol.details.previous);
  assert.equal(thanks.details.kind, "unknown");
  const fields = thanks.details.fields;
  assert.ok(fields != null);
  assert.deepEqual(assertRecord(fields, "string"), { who: "Bob", count: "3" });
  assertNone(upload.details.fields);
}

async function watchTheStream(site: FakeSite, logs: LogService): Promise<void> {
  site.answers.recentchanges = {
    query: {
      recentchanges: [
        { rcid: 1, type: "edit", ns: 0, title: "Volcano", pageid: 4, user: "Alice", timestamp: "2026-08-31T21:43:26Z", comment: "ash", revid: 9, old_revid: 8, oldlen: 10, newlen: 25, minor: true, bot: true, tags: ["t"] },
        { rcid: 2, type: "log", logid: 3, logtype: "move", logaction: "move", ns: 0, title: "Volcano", user: "Bob", timestamp: "2026-08-31T21:43:27Z", comment: "moved", patrolled: true, logparams: { target_ns: 0, target_title: "Ash" } },
        { rcid: 3, type: "new", ns: 0, title: "Ash", pageid: 5, user: "Carol", timestamp: "2026-08-31T21:43:28Z", comment: "new", revid: 10, old_revid: 0, oldlen: 0, newlen: 7, new: true, patrolled: false },
        { rcid: 4, type: "external", ns: 0, title: "Ash", pageid: 5, timestamp: "2026-08-31T21:43:29Z", revid: 10, old_revid: 10, oldlen: 30, newlen: 10 },
        { rcid: 5, type: "categorize", ns: 14, title: "Category:Ash", pageid: 6, timestamp: "2026-08-31T21:43:30Z" },
      ],
    },
  };
  const options: RecentChangesOptions = { namespaces: [0], types: ["edit", "log"], user: "Alice", show: ["minor", "!bot"], tag: "t", topOnly: true, order: "oldest-first", start: AUGUST, end: SEPTEMBER, limit: 5 };
  const [edit, log, created, external, categorized] = await collect(logs.recentChanges(options));

  assert.ok(edit instanceof RecentChange);
  assert.equal(edit.id, 1);
  assert.equal(edit.type, "edit");
  assert.equal(edit.page, "Volcano");
  assert.equal(edit.pageId, 4);
  assert.equal(edit.user, "Alice");
  assert.equal(edit.comment, "ash");
  assert.equal(assertDate(edit.timestamp).toISOString(), "2026-08-31T21:43:26.000Z");
  assert.equal(edit.revision, 9);
  assert.equal(edit.previousRevision, 8);
  assert.equal(edit.sizeChange, 15);
  assert.equal(edit.minor, true);
  assert.equal(edit.bot, true);
  assert.equal(edit.isNew, false);
  assert.equal(edit.isPatrolled, false);
  assert.equal(edit.isLogEntry, false);
  assertNone(edit.patrolled);
  assertNone(edit.logEvent);
  assert.deepEqual(assertArray(edit.tags), ["t"]);

  assert.equal(log.type, "log");
  assert.equal(log.isLogEntry, true);
  assert.equal(log.isPatrolled, true);
  assert.equal(log.patrolled, true);
  assertNone(log.revision);
  assertNone(log.previousRevision);
  assert.equal(log.sizeChange, 0);
  assertNone(log.pageId);
  assert.ok(log.logEvent != null);
  assert.equal(log.logEvent.type, "move");
  assert.equal(log.logEvent.details.kind, "move");
  assert.equal(log.logEvent.details.target, "Ash");

  assert.equal(created.type, "new");
  assert.equal(created.isNew, true);
  assert.equal(created.patrolled, false);
  assert.equal(created.isPatrolled, false);
  assert.equal(created.sizeChange, 7);
  assertNone(created.previousRevision);

  assert.equal(external.type, "external");
  assertNone(external.user);
  assertNone(external.comment);
  assert.equal(external.sizeChange, -20);
  assert.equal(categorized.type, "categorize");

  const sent = lastSent(site, "list", "recentchanges");
  assert.equal(sent.rcnamespace, "0");
  assert.equal(sent.rctype, "edit|log");
  assert.equal(sent.rcuser, "Alice");
  assert.equal(sent.rcshow, "minor|!bot");
  assert.equal(sent.rctag, "t");
  assert.equal(sent.rctoponly, "1");
  assert.equal(sent.rcdir, "newer");
  assert.equal(sent.rcstart, AUGUST_TEXT);
  assert.equal(sent.rcend, SEPTEMBER_TEXT);
  assert.equal(sent.rclimit, "5");

  await collect(logs.recentChanges({ types: ["external", "categorize", "new"] }));
  assert.equal(lastSent(site, "list", "recentchanges").rctype, "external|categorize|new");

  site.answers.recentchanges = { query: { recentchanges: [] } };
  assert.deepEqual(await collect(logs.recentChanges()), []);
  const unconstrained = lastSent(site, "list", "recentchanges");
  assert.equal(unconstrained.rcdir, "older");
  assertNone(unconstrained.rcshow);
  assertNone(unconstrained.rctoponly);
  assertNone(unconstrained.rcnamespace);

  site.answers.watchlist = { query: { watchlist: [{ rcid: 1, type: "new", ns: 0, title: "Volcano", timestamp: "2026-08-31T21:43:26Z", new: true }] } };
  const watchOptions: WatchlistChangesOptions = { namespaces: [2], types: ["new"], show: ["!minor"], allRevisions: true, order: "oldest-first", start: AUGUST, end: SEPTEMBER, limit: 1 };
  const [watched] = await collect(logs.watchlistChanges(watchOptions));
  assert.equal(watched.type, "new");
  assert.equal(watched.isNew, true);
  const watching = lastSent(site, "list", "watchlist");
  assert.equal(watching.wlnamespace, "2");
  assert.equal(watching.wltype, "new");
  assert.equal(watching.wlshow, "!minor");
  assert.equal(watching.wlallrev, "1");
  assert.equal(watching.wldir, "newer");
  assert.equal(watching.wlstart, AUGUST_TEXT);
  assert.equal(watching.wlend, SEPTEMBER_TEXT);
  assert.equal(watching.wllimit, "1");
  assert.equal((await collect(logs.watchlistChanges())).length, 1);
  assert.equal(lastSent(site, "list", "watchlist").wldir, "older");

  // @ts-expect-error an order the type refuses, which the library refuses too
  assert.throws(() => logs.events({ order: "older" }), Error);
  // @ts-expect-error an order the type refuses, which the library refuses too
  assert.throws(() => logs.recentChanges({ order: "older" }), Error);
  // @ts-expect-error an order the type refuses, which the library refuses too
  assert.throws(() => logs.watchlistChanges({ order: "older" }), Error);
}

const site = new FakeSite();
await withWiki(site, async (wiki) => {
  assert.ok(wiki.revisions instanceof RevisionService);
  assert.ok(wiki.logs instanceof LogService);
  await walkHistory(site, wiki.revisions);
  await readOldText(site, wiki.revisions);
  await walkTheWholeWiki(site, wiki.revisions);
  await hideARevision(site, wiki.revisions);
  await followTheLogs(site, wiki.logs);
  await watchTheStream(site, wiki.logs);
});

console.log("revisions and logs: history, old text, diffs, revision deletion, every kind of log entry and the change streams read as typed");
