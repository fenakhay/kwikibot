import assert from "node:assert/strict";
import {
  type ApiValue,
  type Awaitable,
  type ExitStatus,
  type Ignored,
  type Negatable,
  parse,
} from "kwikibot";
import { type PageOutcomeKind, type ProblemKind, type StopReason } from "kwikibot/bot";
import {
  type AbuseFilterOrder,
  type AbuseFilterProperty,
  type AbuseFilterState,
  type BlockFilter,
  type CategoryMemberType,
  type CategorySort,
  type ContributionFilter,
  type DecodedPageKind,
  type EditRefusal,
  type EditStatus,
  type LogDetailsKind,
  type LogLevel,
  logLevel,
  type ModuleGroup,
  type NewPagesOrder,
  type NewPagesReviewed,
  type ParseProperty,
  type ParserName,
  type RecentChangeFilter,
  type RedirectFilter,
  type RevisionPart,
  type SearchScope,
  type SearchSort,
  setLogLevel,
  type TagOperation,
  type TextDirection,
  type TimeOrder,
  type TriageStatus,
  type UploadKind,
  type WatchlistFilter,
  type WatchMode,
} from "kwikibot/client";
import { type DumpCompression, dumpPagesOf, type DumpProblem } from "kwikibot/dumps";
import { type ErrorKind } from "kwikibot/errors";
import { type ChangeType, type EventType } from "kwikibot/events";
import { type FleetEventKind, type RunLevel, type RunStatus } from "kwikibot/fleet";
import { parseProofreadText, type ProofreadQuality } from "kwikibot/proofread";
import { type SparqlAuthKind, type SparqlValueType } from "kwikibot/sparql";
import {
  createNamespaces,
  type FamilyKind,
  type TitleCase,
  type TitleKind,
  type TitleProblem,
} from "kwikibot/titles";
import {
  type DataValueKind,
  type EntityKind,
  type EntitySearchKind,
  type MergeConflict,
  type NewEntityKind,
  type SnakKind,
  type StatementRank,
} from "kwikibot/wikibase";
import { type HexMarker, type NodeKind, type QuoteMark, type TextScopeName } from "kwikibot/wikitext";
import { FakeSite, collect, withWiki } from "./support.js";

function every<T extends string>(table: Record<T, true>): T[] {
  return Object.keys(table).filter((key): key is T => key in table);
}

const ORDERS = every<TimeOrder>({ "newest-first": true, "oldest-first": true });
const WATCH_MODES = every<WatchMode>({ "no-change": true, preferences: true, watch: true, unwatch: true });
const MEMBER_TYPES = every<CategoryMemberType>({ page: true, subcategory: true, file: true });
const CATEGORY_SORTS = every<CategorySort>({ sortkey: true, timestamp: true });
const REDIRECTS = every<RedirectFilter>({ all: true, "only-redirects": true, "no-redirects": true });
const SEARCH_SCOPES = every<SearchScope>({ text: true, title: true, "near-match": true });
const SEARCH_SORTS = every<SearchSort>({
  relevance: true,
  "last-edit-descending": true,
  "last-edit-ascending": true,
  "created-descending": true,
  "created-ascending": true,
  title: true,
});
const FILTER_ORDERS = every<AbuseFilterOrder>({ "lowest-id-first": true, "highest-id-first": true });
const FILTER_PROPERTIES = every<AbuseFilterProperty>({
  id: true,
  description: true,
  pattern: true,
  actions: true,
  hits: true,
  comments: true,
  "last-editor": true,
  "last-edit-time": true,
  flags: true,
  status: true,
  private: true,
  protected: true,
  suppressed: true,
});
const PAGE_ORDERS = every<NewPagesOrder>({
  "newest-first": true,
  "oldest-first": true,
  "newest-review-first": true,
  "oldest-review-first": true,
});
const REVIEWED = every<NewPagesReviewed>({ unreviewed: true, reviewed: true, all: true });
const PROPERTIES = every<ParseProperty>({
  text: true,
  links: true,
  templates: true,
  categories: true,
  images: true,
  "external-links": true,
  "limit-report": true,
  "toc-data": true,
  sections: true,
});
const PARSERS = every<ParserName>({ legacy: true, parsoid: true });
const PARTS = every<RevisionPart>({ content: true, comment: true, user: true });
const OPERATIONS = every<TagOperation>({ create: true, delete: true, activate: true, deactivate: true });
const LEVELS = every<LogLevel>({ trace: true, debug: true, info: true, warn: true, error: true, off: true });
const COMPRESSIONS = every<DumpCompression>({ none: true, gzip: true, bzip2: true });
const SCOPES = every<TextScopeName>({ prose: true, everywhere: true });
const QUALITIES = every<ProofreadQuality>({
  "without-text": true,
  "not-proofread": true,
  problematic: true,
  proofread: true,
  validated: true,
});
const CASES = every<TitleCase>({ "first-letter": true, "case-sensitive": true });
const RANKS = every<StatementRank>({ preferred: true, normal: true, deprecated: true });
const NEW_KINDS = every<NewEntityKind>({ item: true, property: true, lexeme: true, form: true, sense: true, mediainfo: true });
const SEARCH_KINDS = every<EntitySearchKind>({ item: true, property: true, lexeme: true, form: true, sense: true });

const previous = logLevel();
for (const level of LEVELS) {
  setLogLevel(level);
  assert.equal(logLevel(), level);
}
setLogLevel(previous);

const noDump = new TextEncoder().encode("<mediawiki></mediawiki>");
assert.deepEqual(dumpPagesOf(noDump, COMPRESSIONS[0]), []);
for (const scope of SCOPES) assert.equal(parse("a").replaceText(/a/, "b", scope).toString(), "b");
for (const quality of QUALITIES) assert.equal(typeof quality, "string");
assert.equal(parseProofreadText("x").quality ?? "none", "none");
for (const namespaceCase of CASES) {
  assert.ok(createNamespaces([{ id: 100, canonicalName: "Thing", case: namespaceCase }]).get(100)?.case === namespaceCase);
}

const answers = { query: { pages: [], categorymembers: [], allpages: [], search: [], recentchanges: [], logevents: [], allrevisions: [] } };
const site = new FakeSite(
  {
    categorymembers: answers,
    allpages: answers,
    search: answers,
    recentchanges: answers,
    logevents: answers,
    revisions: answers,
    deletedrevisions: answers,
    allrevisions: answers,
    abusefilters: { query: { abusefilters: [] } },
    pagetriagelist: { pagetriagelist: { pages: [] } },
    parse: { parse: { title: "A", pageid: 1 } },
  },
  ["Abuse Filter", "PageTriage"],
);
await withWiki(site, async (wiki) => {
  for (const order of ORDERS) {
    await collect(wiki.logs.events({ order }));
    await collect(wiki.logs.recentChanges({ order }));
    await collect(wiki.revisions.history("A", { order }));
    await collect(wiki.revisions.deletedHistory("A", order));
  }
  for (const type of MEMBER_TYPES) await collect(wiki.lists.categoryMembers("C", { type }));
  for (const sort of CATEGORY_SORTS) await collect(wiki.lists.categoryMembers("C", { sort }));
  for (const redirects of REDIRECTS) await collect(wiki.lists.allPages({ redirects }));
  for (const scope of SEARCH_SCOPES) await collect(wiki.lists.search("q", { scope }));
  for (const sort of SEARCH_SORTS) await collect(wiki.lists.search("q", { sort }));
  for (const order of FILTER_ORDERS) await collect(wiki.extensions.abuseFilters({ order }));
  await collect(wiki.extensions.abuseFilters({ properties: FILTER_PROPERTIES }));
  for (const order of PAGE_ORDERS) await collect(wiki.extensions.newPages({ order }));
  for (const reviewed of REVIEWED) await collect(wiki.extensions.newPages({ reviewed }));
  await wiki.render.resolve("A", PROPERTIES);
  for (const parser of PARSERS) await wiki.render.parse({ page: "A", parser });
  assert.throws(() => {
    // @ts-expect-error not an order, which fails at runtime too
    wiki.revisions.history("A", { order: "sideways" });
  });
  await collect(wiki.revisions.allRevisions({ order: ORDERS[0] }));
  assert.ok(WATCH_MODES.length + PARTS.length + OPERATIONS.length + RANKS.length + NEW_KINDS.length + SEARCH_KINDS.length > 0);
});

const later: Awaitable<number> = Promise.resolve(1);
const flag: ApiValue = ["a", 1, new Date()];
const anything: Ignored = "a one-line arrow function can answer this";
const exit: ExitStatus = 3;
assert.equal(await later, 1);
assert.ok(Array.isArray(flag));
assert.equal(typeof anything, "string");
assert.equal(exit, 3);

function everyResultIsSpelledInItsUnion(): void {
  const statuses: Record<RunStatus, true> = { queued: true, running: true, waiting: true, done: true, failed: true, cancelled: true, declined: true };
  const said: Record<RunLevel, true> = { info: true, saved: true, skipped: true, error: true };
  const events: Record<FleetEventKind, true> = { said: true, changed: true };
  const sparql: Record<SparqlValueType, true> = { uri: true, literal: true, bnode: true };
  const titles: Record<TitleKind, true> = { local: true, interwiki: true, invalid: true };
  const problems: Record<TitleProblem, true> = { empty: true, "illegal-character": true, "relative-path": true, signature: true, "percent-escape": true, "too-long": true };
  const refusals: Record<EditRefusal, true> = { conflict: true, protected: true, "permission-denied": true, "rate-limited": true, filtered: true, captcha: true, "page-changed": true, rejected: true };
  const edits: Record<EditStatus, true> = { ...refusals, saved: true, unchanged: true };
  const stops: Record<StopReason, true> = { policy: true, "policy-unreachable": true, auth: true, "read-only": true, configuration: true, refused: true };
  const outcomes: Record<PageOutcomeKind, true> = { missing: true, skipped: true, unchanged: true, pending: true, saved: true, refused: true, failed: true, "not-attempted": true };
  const runProblems: Record<ProblemKind, true> = { refused: true, failed: true };
  const entities: Record<EntityKind, true> = { item: true, property: true, lexeme: true, form: true, sense: true, mediainfo: true, unknown: true };
  const merges: Record<MergeConflict, true> = { description: true, sitelink: true, statement: true };
  const families: Record<FamilyKind, true> = { wikimedia: true, "single-site": true };
  const decoded: Record<DecodedPageKind, true> = { existing: true, missing: true, invalid: true };
  const snaks: Record<SnakKind, true> = { value: true, somevalue: true, novalue: true };
  const auths: Record<SparqlAuthKind, true> = { none: true, cookie: true };
  const changes: Record<ChangeType, true> = { edit: true, new: true, log: true, categorize: true, external: true };
  const dumps: Record<DumpProblem, true> = { "unexpected-eof": true, "bad-checksum": true, "bad-data": true };
  const values: Record<DataValueKind, true> = { text: true, "entity-ref": true, time: true, quantity: true, "globe-coordinate": true, monolingual: true, unknown: true };
  const states: Record<AbuseFilterState, true> = { enabled: true, deleted: true, private: true, protected: true, suppressed: true };
  const triage: Record<TriageStatus, true> = { unreviewed: true, reviewed: true, patrolled: true, autopatrolled: true };
  const uploads: Record<UploadKind, true> = { uploaded: true, warned: true, refused: true };
  const logs: Record<LogDetailsKind, true> = { none: true, move: true, block: true, upload: true, protect: true, rights: true, patrol: true, unknown: true };
  const directions: Record<TextDirection, true> = { "left-to-right": true, "right-to-left": true };
  const nodes: Record<NodeKind, true> = { text: true, comment: true, template: true, argument: true, "wiki-link": true, "external-link": true, heading: true, "html-entity": true, tag: true };
  const groups: Record<ModuleGroup, true> = { list: true, prop: true, meta: true };
  const hexes: Record<HexMarker, true> = { x: true, X: true };
  const quotes: Record<QuoteMark, true> = { '"': true, "'": true };
  const errors: Record<ErrorKind, true> = {
    timeout: true,
    unreachable: true,
    "server-error": true,
    refused: true,
    "rate-limited": true,
    maxlag: true,
    api: true,
    "not-logged-in": true,
    "login-failed": true,
    "no-credentials": true,
    "missing-right": true,
    "permission-denied": true,
    "account-blocked": true,
    "bad-token": true,
    "missing-page": true,
    "not-redirect": true,
    "unresolvable-redirect": true,
    "bad-title": true,
    "off-wiki": true,
    "unsupported-content-model": true,
    "read-only": true,
    "unknown-site": true,
    "missing-extension": true,
    "version-too-old": true,
    unsupported: true,
    "round-trip": true,
    "dump-format": true,
  };
  const negated: Negatable<"minor"> = "!minor";
  const contribution: ContributionFilter = "!new";
  const block: BlockFilter = "!temp";
  const change: RecentChangeFilter = "!bot";
  const watched: WatchlistFilter = "unread";
  const event: EventType = "page-create";

  const counts = [statuses, said, events, sparql, titles, problems, edits, stops, outcomes, runProblems, entities, merges, families, decoded, snaks, auths, changes, dumps, values, states, triage, uploads, logs, directions, nodes, groups, hexes, quotes, errors].map((table) => Object.keys(table).length);
  assert.ok(counts.every((count) => count >= 2));
  assert.deepEqual([negated, contribution, block, change, watched, event].length, 6);
}
everyResultIsSpelledInItsUnion();

function typosDoNotCompile(): void {
  // @ts-expect-error a typo in a closed set
  const order: TimeOrder = "newest-frist";
  // @ts-expect-error a flag that does not exist
  const contribution: ContributionFilter = "!mainor";
  void [order, contribution];
}
void typosDoNotCompile;

console.log("literals: every union is spelled out and every argument one is accepted at runtime");
