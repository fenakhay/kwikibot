import assert from "node:assert/strict";
import { BotPassword, describeError, Revision } from "kwikibot";
import {
  type AllUsersOptions,
  BlockInfo,
  type BlockOptions,
  BlockRecord,
  type BlocksOptions,
  type ChangeRightsOptions,
  Contribution,
  type ContributionsOptions,
  UserInfo,
  UserService,
} from "kwikibot/client";
import { FakeSite, assertArray, assertDate, assertNone, assertRecord, collect, withWiki } from "./support.js";

const AUGUST = new Date("2026-08-01T00:00:00Z");
const SEPTEMBER = new Date("2026-09-01T00:00:00Z");
const AUGUST_TEXT = "2026-08-01T00:00:00Z";
const SEPTEMBER_TEXT = "2026-09-01T00:00:00Z";

function lastSent(site: FakeSite, key: string, value: string): Record<string, string> {
  const found = site.requests.filter((params) => params[key] === value).at(-1);
  assert.ok(found != null, `nothing was sent with ${key}=${value}`);
  return found;
}

function typosDoNotCompile(users: UserService): void {
  // @ts-expect-error a filter that does not exist
  users.contributions("Alice", { show: ["nwe"] });
  // @ts-expect-error a filter cannot be negated twice
  users.contributions("Alice", { show: ["!!minor"] });
  // @ts-expect-error a filter that does not exist
  users.blocks({ show: ["!tmp"] });
  // @ts-expect-error a block ends at a date, not at a duration
  users.block("Alice", "spam", { expiry: "1 week" });
}
void typosDoNotCompile;

async function lookUpAccounts(site: FakeSite, users: UserService): Promise<void> {
  site.answers.users = {
    query: {
      users: [
        {
          userid: 7,
          name: "Alice",
          groups: ["*", "sysop"],
          rights: ["edit"],
          editcount: 12,
          registration: "2020-01-01T00:00:00Z",
          gender: "female",
          emailable: true,
          blockid: 5,
          blockedby: "Bob",
          blockreason: "spam",
          blockedtimestamp: "2026-08-01T00:00:00Z",
          blockexpiry: "2026-09-01T00:00:00Z",
          blockpartial: true,
        },
        { name: "Nobody", missing: true },
        { name: "192.0.2.1", invalid: true },
        { userid: 8, name: "~2026-1", groups: ["temp"] },
        { userid: 9, name: "Carol", groups: ["*"], rights: [], editcount: 0, gender: "unknown", blockedby: "Bob", blockexpiry: "infinity" },
      ],
    },
  };
  const table = await users.info(["alice", "Nobody", "192.0.2.1", "~2026-1", "Carol"]);
  assertRecord(table);
  assert.deepEqual(Object.keys(table), ["alice", "Nobody", "192.0.2.1", "~2026-1", "Carol"]);
  assert.equal(lastSent(site, "list", "users").ususers, "alice|Nobody|192.0.2.1|~2026-1|Carol");

  const alice = table.alice;
  assert.ok(alice instanceof UserInfo);
  assert.equal(alice.name, "Alice");
  assert.equal(alice.id, 7);
  assert.equal(alice.editCount, 12);
  assert.equal(alice.gender, "female");
  assert.equal(alice.emailable, true);
  assert.deepEqual(assertArray(alice.groups), ["*", "sysop"]);
  assert.deepEqual(assertArray(alice.rights), ["edit"]);
  assert.equal(assertDate(alice.registration).toISOString(), "2020-01-01T00:00:00.000Z");
  assert.equal(alice.hasRight("edit"), true);
  assert.equal(alice.hasRight("block"), false);
  assert.equal(alice.inGroup("sysop"), true);
  assert.equal(alice.inGroup("bot"), false);
  assert.equal(alice.isBlocked, true);
  assert.equal(alice.isRegistered, true);
  assert.equal(alice.isAnonymous, false);
  assert.equal(alice.isTemporary, false);
  assert.equal(alice.isMissing, false);

  const block = alice.block;
  assert.ok(block instanceof BlockInfo);
  assert.equal(block.id, 5);
  assert.equal(block.by, "Bob");
  assert.equal(block.reason, "spam");
  assert.equal(block.isPartial, true);
  assert.equal(block.isInfinite, false);
  assert.equal(assertDate(block.since).toISOString(), "2026-08-01T00:00:00.000Z");
  assert.equal(assertDate(block.expiry).toISOString(), "2026-09-01T00:00:00.000Z");

  const nobody = table.Nobody;
  assert.equal(nobody.isMissing, true);
  assert.equal(nobody.isRegistered, false);
  assert.equal(nobody.isBlocked, false);
  assert.equal(nobody.editCount, 0);
  assert.equal(nobody.emailable, false);
  assert.deepEqual(assertArray(nobody.groups), []);
  assert.deepEqual(assertArray(nobody.rights), []);
  assertNone(nobody.id);
  assertNone(nobody.block);
  assertNone(nobody.registration);
  assertNone(nobody.gender);

  const address = table["192.0.2.1"];
  assert.equal(address.isAnonymous, true);
  assert.equal(address.isRegistered, false);
  assertNone(address.id);

  const temporary = table["~2026-1"];
  assert.equal(temporary.isTemporary, true);
  assert.equal(temporary.isRegistered, false);

  const carol = table.Carol;
  assertNone(carol.gender);
  assert.equal(carol.isBlocked, true);
  assert.ok(carol.block != null);
  assert.equal(carol.block.by, "Bob");
  assert.equal(carol.block.isInfinite, true);
  assert.equal(carol.block.isPartial, false);
  assertNone(carol.block.expiry);
  assertNone(carol.block.id);
  assertNone(carol.block.reason);
  assertNone(carol.block.since);
}

async function readEdits(site: FakeSite, users: UserService): Promise<void> {
  site.answers.usercontribs = {
    query: {
      usercontribs: [
        { userid: 7, user: "Alice", pageid: 4, revid: 9, parentid: 8, ns: 0, title: "Volcano", timestamp: "2026-08-31T21:43:26Z", new: true, top: true, size: 30, sizediff: 30, comment: "c", tags: [] },
        { userid: 7, user: "Alice", revid: 7, parentid: 0, ns: 0, title: "Ash", timestamp: "2026-08-30T21:43:26Z", size: 10, sizediff: -5, comment: "d", tags: ["t"] },
      ],
    },
  };
  const options: ContributionsOptions = { namespaces: [0], start: AUGUST, end: SEPTEMBER, show: ["new", "!minor"], tag: "t", limit: 2 };
  const [created, later] = await collect(users.contributions("Alice", options));
  assert.ok(created instanceof Contribution);
  assert.equal(created.page, "Volcano");
  assert.equal(created.pageId, 4);
  assert.equal(created.isNew, true);
  assert.equal(created.isTop, true);
  assert.equal(created.sizeChange, 30);
  assert.ok(created.revision instanceof Revision);
  assert.equal(created.revision.id, 9);
  assert.equal(created.revision.parentId, 8);
  assert.equal(created.revision.user, "Alice");
  assert.equal(assertDate(created.revision.timestamp).toISOString(), "2026-08-31T21:43:26.000Z");

  assert.equal(later.page, "Ash");
  assertNone(later.pageId);
  assert.equal(later.isNew, false);
  assert.equal(later.isTop, false);
  assert.equal(later.sizeChange, -5);
  assertNone(later.revision.parentId);
  assert.deepEqual(assertArray(later.revision.tags), ["t"]);

  const sent = lastSent(site, "list", "usercontribs");
  assert.equal(sent.ucuser, "Alice");
  assert.equal(sent.ucnamespace, "0");
  assert.equal(sent.ucstart, AUGUST_TEXT);
  assert.equal(sent.ucend, SEPTEMBER_TEXT);
  assert.equal(sent.ucshow, "new|!minor");
  assert.equal(sent.uctag, "t");
  assert.equal(sent.uclimit, "2");

  assert.equal((await collect(users.contributions("Alice", { limit: 1 }))).length, 1);
  assert.equal((await collect(users.contributions("Alice"))).length, 2);
  assert.equal((await collect(users.contributions("Alice", null))).length, 2);
}

async function blockAndUnblock(site: FakeSite, users: UserService): Promise<void> {
  site.answers.block = { block: { user: "Alice" } };
  assert.equal(await users.block("Alice", "spam"), undefined);
  const plain = lastSent(site, "action", "block");
  assert.equal(plain.user, "Alice");
  assert.equal(plain.reason, "spam");
  assert.equal(plain.expiry, "infinity");
  assert.equal(plain.anononly, "0");
  assert.equal(plain.nocreate, "1");
  assert.equal(plain.autoblock, "1");

  const options: BlockOptions = { expiry: SEPTEMBER, anonymousOnly: true, preventAccountCreation: false, autoBlock: false };
  await users.block("192.0.2.1", "spam", options);
  const told = lastSent(site, "action", "block");
  assert.equal(told.expiry, SEPTEMBER_TEXT);
  assert.equal(told.anononly, "1");
  assertNone(told.nocreate);
  assertNone(told.autoblock);

  site.answers.block = { error: { code: "permissiondenied", info: "You may not block." } };
  const refused = await users.block("Alice", "spam").then(
    () => undefined,
    (reason: unknown) => reason,
  );
  assert.ok(refused instanceof Error);
  const info = describeError(refused);
  assert.ok(info != null);
  assert.ok(info.message.length > 0);

  site.answers.unblock = { unblock: { user: "Alice" } };
  assert.equal(await users.unblock("Alice", "done"), undefined);
  assert.equal(lastSent(site, "action", "unblock").user, "Alice");
  assert.equal(lastSent(site, "action", "unblock").reason, "done");
  await users.unblock("Alice");
  assert.equal(lastSent(site, "action", "unblock").reason, "");

  site.answers.blocks = {
    query: {
      blocks: [
        { id: 5, user: "192.0.2.0/24", by: "Bob", timestamp: "2026-08-01T00:00:00Z", expiry: "infinity", reason: "spam", automatic: true },
        { id: 6, user: "Dave", by: "Bob", timestamp: "2026-08-02T00:00:00Z", expiry: "2026-09-02T00:00:00Z", reason: "vandalism", partial: true },
        { id: 7, timestamp: "2026-08-03T00:00:00Z", expiry: "infinity" },
      ],
    },
  };
  const options2: BlocksOptions = { users: ["Alice", "Bob"], show: ["!temp"], start: AUGUST, end: SEPTEMBER, limit: 3 };
  const [range, account, hidden] = await collect(users.blocks(options2));
  assert.ok(range instanceof BlockRecord);
  assert.equal(range.target, "192.0.2.0/24");
  assert.equal(range.isAutomatic, true);
  assert.equal(range.isRange, true);
  assert.ok(range.info instanceof BlockInfo);
  assert.equal(range.info.id, 5);
  assert.equal(range.info.by, "Bob");
  assert.equal(range.info.reason, "spam");
  assert.equal(range.info.isInfinite, true);
  assertNone(range.info.expiry);
  assert.equal(account.target, "Dave");
  assert.equal(account.isAutomatic, false);
  assert.equal(account.isRange, false);
  assert.equal(account.info.isPartial, true);
  assert.equal(assertDate(account.info.expiry).toISOString(), "2026-09-02T00:00:00.000Z");
  assertNone(hidden.target);
  assert.equal(hidden.isRange, false);
  assertNone(hidden.info.by);
  assertNone(hidden.info.reason);
  const sent = lastSent(site, "list", "blocks");
  assert.equal(sent.bkusers, "Alice|Bob");
  assert.equal(sent.bkshow, "!temp");
  assert.equal(sent.bkstart, AUGUST_TEXT);
  assert.equal(sent.bkend, SEPTEMBER_TEXT);
  assert.equal(sent.bklimit, "3");
  assert.equal((await collect(users.blocks())).length, 3);
}

async function manageAccounts(site: FakeSite, users: UserService): Promise<void> {
  site.answers.allusers = { query: { allusers: [{ userid: 7, name: "Alice", editcount: 3 }] } };
  const options: AllUsersOptions = { from: "A", to: "B", prefix: "Al", group: ["sysop", "bot"], withEditsOnly: true, excludeTemporary: true, limit: 1 };
  const [found] = await collect(users.allUsers(options));
  assert.ok(found instanceof UserInfo);
  assert.equal(found.name, "Alice");
  assert.equal(found.editCount, 3);
  const sent = lastSent(site, "list", "allusers");
  assert.equal(sent.aufrom, "A");
  assert.equal(sent.auto, "B");
  assert.equal(sent.auprefix, "Al");
  assert.equal(sent.augroup, "sysop|bot");
  assert.equal(sent.auwitheditsonly, "1");
  assert.equal(sent.auexcludetemp, "1");
  assert.equal(sent.aulimit, "1");
  assert.equal((await collect(users.allUsers())).length, 1);

  site.answers.userrights = { userrights: { user: "Alice" } };
  const rights: ChangeRightsOptions = { add: ["bot"], remove: ["sysop"], reason: "approved", expiry: "1 month" };
  assert.equal(await users.changeRights("Alice", rights), undefined);
  const told = lastSent(site, "action", "userrights");
  assert.equal(told.user, "Alice");
  assert.equal(told.add, "bot");
  assert.equal(told.remove, "sysop");
  assert.equal(told.reason, "approved");
  assert.equal(told.expiry, "1 month");

  await assert.rejects(users.changeRights("Alice"), Error);
  await assert.rejects(users.changeRights("Alice", { add: [] }), Error);
  await assert.rejects(users.changeRights("Alice", { add: ["bot"], remove: ["bot"] }), Error);
}

const anonymousSite = new FakeSite();
await withWiki(anonymousSite, async (wiki) => {
  assert.ok(wiki.users instanceof UserService);
  const anonymous = await wiki.users.current();
  assert.equal(anonymous.name, "192.0.2.1");
  assert.equal(anonymous.isAnonymous, true);
  assert.equal(anonymous.isRegistered, false);
  assertNone(anonymous.id);
  assert.equal(anonymous.editCount, 0);
});

const loggedInSite = new FakeSite();
await withWiki(
  loggedInSite,
  async (wiki) => {
    const current = await wiki.users.current();
    assert.equal(current.name, "TestBot");
    assert.equal(current.id, 7);
    assert.equal(current.isAnonymous, false);
    assert.deepEqual(assertArray(current.rights), ["bot", "edit"]);
    assert.equal(current.inGroup("bot"), true);
  },
  new BotPassword("TestBot", "task", "secret"),
);

const site = new FakeSite();
await withWiki(site, async (wiki) => {
  await lookUpAccounts(site, wiki.users);
  await readEdits(site, wiki.users);
  await blockAndUnblock(site, wiki.users);
  await manageAccounts(site, wiki.users);
});

console.log("users: account tables, the session's account, contributions, blocks, listings and rights read as typed");
