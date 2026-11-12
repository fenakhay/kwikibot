import assert from "node:assert/strict";
import { describeError } from "kwikibot";
import { type RetryOptions, UserAgent } from "kwikibot/client";
import {
  EventStreams,
  eventStreams,
  type EventStreamsOptions,
  WikiEvent,
  WIKIMEDIA_EVENT_STREAMS,
} from "kwikibot/events";
import {
  COMMONS_QUERY_SERVICE,
  LINGUA_LIBRE_QUERY_SERVICE,
  sparql,
  SparqlAuth,
  sparqlAuthCookie,
  sparqlAuthNone,
  sparqlAuthWcqs,
  SparqlClient,
  sparqlClient,
  type SparqlClientOptions,
  SparqlRow,
  SparqlValue,
  WIKIDATA_QUERY_SERVICE,
} from "kwikibot/sparql";
import { assertArray, assertDate, assertNone, collect } from "./support.js";

const CONTACT = "https://example.org/TestBot";
const QUICK_RETRY: RetryOptions = { maxRetries: 0, initialDelay: 1, maxDelay: 1 };

interface Asked {
  url: string;
  headers: Headers;
  body: string;
}

function intercept(answer: (asked: Asked) => Response): { asked: Asked[]; restore: () => void } {
  const original = globalThis.fetch;
  const asked: Asked[] = [];
  globalThis.fetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const url = input instanceof Request ? input.url : input.toString();
    const raw = init?.body;
    const body = raw == null ? "" : raw instanceof Uint8Array ? new TextDecoder().decode(raw) : String(raw);
    const request: Asked = { url, headers: new Headers(init?.headers), body };
    asked.push(request);
    return answer(request);
  };
  return {
    asked,
    restore: () => {
      globalThis.fetch = original;
    },
  };
}

const TSV = '?item\t?label\n<http://www.wikidata.org/entity/Q42>\t"Douglas Adams"@en\n';
const JSON_ROWS = JSON.stringify({
  head: { vars: ["item", "label", "born", "node"] },
  results: {
    bindings: [
      {
        item: { type: "uri", value: "http://www.wikidata.org/entity/Q42" },
        label: { type: "literal", value: "Douglas Adams", "xml:lang": "en" },
        born: { type: "literal", value: "1952-03-11", datatype: "http://www.w3.org/2001/XMLSchema#date" },
        node: { type: "bnode", value: "b0" },
      },
      { item: { type: "uri", value: "http://example.org/not-an-entity" } },
    ],
  },
});

function queryService(asked: Asked): Response {
  const wantsTable = asked.headers.get("Accept") === "text/tab-separated-values";
  return new Response(wantsTable ? TSV : JSON_ROWS, { status: 200 });
}

async function queries(): Promise<void> {
  const service = intercept(queryService);
  try {
    assert.ok(WIKIDATA_QUERY_SERVICE.startsWith("https://"));
    assert.ok(COMMONS_QUERY_SERVICE.includes("commons"));
    assert.ok(LINGUA_LIBRE_QUERY_SERVICE.includes("lingualibre.org"));

    const rows: SparqlRow[] = assertArray(await sparql("SELECT ?item WHERE {}", CONTACT));
    assert.equal(rows.length, 2);
    assert.equal(service.asked[0]?.url, WIKIDATA_QUERY_SERVICE);
    assert.ok(service.asked[0]?.body.includes("query=SELECT"));
    const row = rows[0];
    assert.ok(row instanceof SparqlRow);
    assert.deepEqual(assertArray(row.names).sort(), ["born", "item", "label", "node"]);

    const item = row.get("item");
    assert.ok(item instanceof SparqlValue);
    assert.equal(item.type, "uri");
    assert.equal(item.value, "http://www.wikidata.org/entity/Q42");
    assert.equal(item.entityId, "Q42");
    assertNone(item.language);
    assertNone(item.dataType);

    const label = row.get("label");
    assert.ok(label != null);
    assert.equal(label.type, "literal");
    assert.equal(label.value, "Douglas Adams");
    assert.equal(label.language, "en");
    assertNone(label.entityId);

    assert.equal(row.get("born")?.dataType, "http://www.w3.org/2001/XMLSchema#date");
    assert.equal(row.get("node")?.type, "bnode");
    assertNone(row.get("unbound"));
    assert.deepEqual(rows[1]?.names, ["item"]);
    assertNone(rows[1]?.get("item")?.entityId);

    await sparql("SELECT ?item WHERE {}", CONTACT, COMMONS_QUERY_SERVICE);
    assert.equal(service.asked[1]?.url, COMMONS_QUERY_SERVICE);

    const options: SparqlClientOptions = {
      endpoint: COMMONS_QUERY_SERVICE,
      auth: sparqlAuthWcqs("secret-token"),
      retry: QUICK_RETRY,
      userAgent: new UserAgent("Mine", "2.0", CONTACT),
    };
    const client: SparqlClient = sparqlClient(CONTACT, options);
    assert.ok(client instanceof SparqlClient);
    const selected = await client.select("SELECT ?item WHERE {}");
    assert.equal(selected[0]?.get("item")?.entityId, "Q42");
    const last = service.asked[service.asked.length - 1];
    assert.equal(last?.url, COMMONS_QUERY_SERVICE);
    assert.ok(last?.headers.get("User-Agent")?.includes("Mine/2.0"));
    assert.ok(last?.headers.get("Cookie")?.includes("wcqsOauth=secret-token"));
    assert.deepEqual(assertArray(await client.entityIds("SELECT ?item WHERE {}")), ["Q42"]);
    assert.deepEqual(await client.entityIds("SELECT ?other WHERE {}", "other"), []);
    client.close();

    const plain = sparqlClient(CONTACT);
    const streamed: SparqlRow[] = await collect(plain.selectStreamed("SELECT ?item ?label WHERE {}"));
    assert.equal(streamed.length, 1);
    assert.ok(streamed[0] instanceof SparqlRow);
    assert.equal(streamed[0]?.get("item")?.entityId, "Q42");
    assert.equal(streamed[0]?.get("label")?.value, "Douglas Adams");
    assert.equal(streamed[0]?.get("label")?.language, "en");
    plain.close();

    const none: SparqlAuth = sparqlAuthNone();
    assert.ok(none instanceof SparqlAuth);
    assert.equal(none.kind, "none");
    assertNone(none.name);
    assertNone(none.value);
    const cookie = sparqlAuthCookie("c", "v");
    assert.equal(cookie.kind, "cookie");
    assert.equal(cookie.name, "c");
    assert.equal(cookie.value, "v");
    assert.equal(sparqlAuthWcqs("t").name, "wcqsOauth");
  } finally {
    service.restore();
  }

  const busy = intercept(() => new Response("", { status: 429 }));
  try {
    const client = sparqlClient(CONTACT, { retry: QUICK_RETRY });
    await assert.rejects(client.select("SELECT"), (error) => {
      assert.equal(describeError(error)?.code, "sparqlfailed");
      return true;
    });
    client.close();
  } finally {
    busy.restore();
  }
}

const EDIT = [
  ": keep-alive",
  'id: [{"topic":"a","offset":1}]',
  'data: {"wiki":"enwiktionary","type":"edit","title":"Talk:Lava","namespace":1,"user":"Alice",',
  'data: "comment":"c","timestamp":1788000000,"revision":{"new":9,"old":8},"bot":true,"minor":true}',
  "",
  "",
].join("\n");
const CREATION = ['id: [{"topic":"a","offset":2}]', 'data: {"wiki":"frwiki","type":"new","title":"Lave","namespace":0}', "", ""].join("\n");
const UNREADABLE = ["data: not json", "", 'data: {"wiki":"frwiki"}', "", ""].join("\n");

function streamingBody(chunks: string[], encoder = new TextEncoder()): ReadableStream<Uint8Array> {
  let next = 0;
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      const chunk = chunks[next];
      next += 1;
      if (chunk === undefined) {
        return new Promise<void>(() => undefined);
      }
      controller.enqueue(encoder.encode(chunk));
      return undefined;
    },
  });
}

function liveStream(): Response {
  return new Response(streamingBody([EDIT.slice(0, 40), EDIT.slice(40), UNREADABLE, CREATION]), {
    status: 200,
    headers: { "Content-Type": "text/event-stream" },
  });
}

async function firstOf(events: AsyncIterable<WikiEvent>, count: number): Promise<WikiEvent[]> {
  assert.equal(typeof events[Symbol.asyncIterator], "function");
  const found: WikiEvent[] = [];
  for await (const event of events) {
    found.push(event);
    if (found.length === count) break;
  }
  return found;
}

async function liveFeeds(): Promise<void> {
  const wikimedia = intercept(liveStream);
  try {
    assert.ok(WIKIMEDIA_EVENT_STREAMS.startsWith("https://"));
    const streams: EventStreams = eventStreams(CONTACT, { retry: QUICK_RETRY });
    assert.ok(streams instanceof EventStreams);
    const found = await firstOf(streams.recentChanges(), 2);
    const edit = found[0];
    const creation = found[1];
    assert.ok(edit instanceof WikiEvent && creation instanceof WikiEvent);

    assert.equal(edit.wiki, "enwiktionary");
    assert.equal(edit.type, "edit");
    assert.equal(edit.title, "Talk:Lava");
    assert.equal(edit.namespace, 1);
    assert.equal(edit.user, "Alice");
    assert.equal(edit.comment, "c");
    assert.equal(assertDate(edit.timestamp).getTime(), 1788000000000);
    assert.equal(edit.revisionId, 9);
    assert.equal(edit.previousRevisionId, 8);
    assert.equal(edit.isBot, true);
    assert.equal(edit.isMinor, true);
    assert.equal(edit.offset, '[{"topic":"a","offset":1}]');
    assert.equal(edit.matches("enwiktionary"), true);
    assert.equal(edit.matches("enwiktionary", null), true);
    assert.equal(edit.matches("enwiktionary", [0]), false);
    assert.equal(edit.matches("enwiktionary", [1]), true);
    assert.equal(edit.matches("frwiki"), false);

    assert.equal(creation.title, "Lave");
    assertNone(creation.user);
    assertNone(creation.comment);
    assertNone(creation.timestamp);
    assertNone(creation.revisionId);
    assertNone(creation.previousRevisionId);
    assert.equal(creation.isBot, false);
    assert.equal(creation.isMinor, false);

    const first = wikimedia.asked[0];
    assert.equal(first?.url, `${WIKIMEDIA_EVENT_STREAMS}/v2/stream/recentchange`);
    assert.equal(first?.headers.get("Accept"), "text/event-stream");
    streams.close();

    const options: EventStreamsOptions = {
      baseUrl: "https://streams.example.org",
      retry: QUICK_RETRY,
      userAgent: new UserAgent("Mine", "2", CONTACT),
    };
    const mine = eventStreams(CONTACT, options);
    await firstOf(mine.pageCreations("[offset]"), 1);
    const created = wikimedia.asked[wikimedia.asked.length - 1];
    assert.equal(created?.url, "https://streams.example.org/v2/stream/page-create");
    assert.equal(created?.headers.get("Last-Event-ID"), "[offset]");
    assert.ok(created?.headers.get("User-Agent")?.includes("Mine/2"));

    await firstOf(mine.events("mediawiki.page-move", "2026-01-01T00:00:00Z"), 1);
    const moved = new URL(wikimedia.asked[wikimedia.asked.length - 1]?.url ?? "");
    assert.equal(moved.pathname, "/v2/stream/mediawiki.page-move");
    assert.equal(moved.searchParams.get("since"), "2026-01-01T00:00:00Z");

    await firstOf(mine.recentChanges(null), 1);
    assert.equal(wikimedia.asked[wikimedia.asked.length - 1]?.headers.get("Last-Event-ID"), null);
    mine.close();
  } finally {
    wikimedia.restore();
  }

  const refusing = intercept(() => new Response("no", { status: 403 }));
  try {
    const streams = eventStreams(CONTACT);
    await assert.rejects(firstOf(streams.recentChanges(), 1), (error) => {
      assert.equal(describeError(error)?.kind, "refused");
      return true;
    });
    streams.close();
  } finally {
    refusing.restore();
  }
}

function rejectedStreamShapes(row: SparqlRow, event: WikiEvent): void {
  // @ts-expect-error a value's type is one of the three SPARQL has
  const typo = row.get("x")?.type === "url";
  // @ts-expect-error an auth's kind is one of the known ones
  const kind = sparqlAuthNone().kind === "bearer";
  // @ts-expect-error namespaces are numbers
  const matches = event.matches("frwiki", ["0"]);
  void [typo, kind, matches];
}
void rejectedStreamShapes;

await queries();
await liveFeeds();

console.log("streams: SPARQL rows and live event streams arrived through a stubbed fetch, streamed");
