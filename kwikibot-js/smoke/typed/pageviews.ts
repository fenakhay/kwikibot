import assert from "node:assert/strict";
import { type RetryOptions, UserAgent } from "kwikibot/client";
import {
  PagePageviews,
  type PageviewAccess,
  type PageviewAgent,
  PageviewCount,
  type PageviewGranularity,
  Pageviews,
  pageviews,
  type PageviewsOptions,
  type PageviewsQuery,
  TopPage,
  type TopPagesOptions,
  WIKIMEDIA_PAGEVIEWS,
} from "kwikibot/services";
import { FakeSite, assertArray, assertDate, assertNone, json, withWiki } from "./support.js";

const CONTACT = "https://example.org/TestBot";
const QUICK_RETRY: RetryOptions = { maxRetries: 1, initialDelay: 1, maxDelay: 1 };
const JANUARY = new Date("2024-01-01T00:00:00Z");
const THIRD = new Date("2024-01-03T00:00:00Z");

const NO_DATA = {
  detail: "The date(s) you used are valid, but we either do not have data for those date(s), or the project you asked for is not loaded yet.",
  status: 404,
  title: "Not Found",
};

function countsOf(article: string, access: PageviewAccess, agent: PageviewAgent, granularity: PageviewGranularity): Response {
  return json({
    items: [
      { project: "en.wikipedia", article, granularity, timestamp: "2024010100", access, agent, views: 29632 },
      { project: "en.wikipedia", article, granularity, timestamp: "2024010200", access, agent, views: 24807 },
    ],
  });
}

function answer(url: URL): Response {
  const parts = url.pathname.split("/");
  const at = parts.findIndex((part) => part === "per-article" || part === "aggregate" || part === "top");
  const kind = parts[at];
  const field = (offset: number): string => decodeURIComponent(parts[at + offset] ?? "");
  if (kind === "top") {
    return json({ items: [{ project: field(1), access: field(2), articles: [{ article: "Main_Page", views: 4817450, rank: 1 }] }] });
  }
  if (kind === "per-article" && field(4) === "Nobody_reads_this") return json(NO_DATA, 404);
  if (kind === "per-article") {
    return countsOf(field(4), field(2) as PageviewAccess, field(3) as PageviewAgent, field(5) as PageviewGranularity);
  }
  return countsOf("", field(2) as PageviewAccess, field(3) as PageviewAgent, field(4) as PageviewGranularity);
}

function intercept(): { asked: URL[]; restore: () => void } {
  const original = globalThis.fetch;
  const asked: URL[] = [];
  globalThis.fetch = async (input: string | URL | Request): Promise<Response> => {
    const url = new URL(input instanceof Request ? input.url : input.toString());
    asked.push(url);
    return answer(url);
  };
  return {
    asked,
    restore: () => {
      globalThis.fetch = original;
    },
  };
}

function readCount(count: PageviewCount | undefined): PageviewCount {
  assert.ok(count instanceof PageviewCount);
  assertDate(count.timestamp);
  assert.equal(typeof count.views, "number");
  assert.equal(typeof count.project, "string");
  return count;
}

async function counted(): Promise<void> {
  const wikimedia = intercept();
  try {
    assert.equal(WIKIMEDIA_PAGEVIEWS, "https://wikimedia.org/api/rest_v1/metrics/pageviews");
    const client: Pageviews = pageviews(CONTACT, { retry: QUICK_RETRY });
    assert.ok(client instanceof Pageviews);
    assert.equal(client.firstDay.toISOString(), "2015-07-01T00:00:00.000Z");

    const days = assertArray(await client.perArticle("en.wikipedia", "Albert Einstein", JANUARY, THIRD));
    assert.equal(days.length, 2);
    const first = readCount(days[0]);
    assert.equal(first.timestamp.toISOString(), "2024-01-01T00:00:00.000Z");
    assert.equal(first.views, 29632);
    assert.equal(first.project, "en.wikipedia");
    assert.equal(first.access, "all-access");
    assert.equal(first.agent, "user");
    assert.equal(first.granularity, "daily");
    assert.equal(first.title, "Albert Einstein");
    assert.equal(
      wikimedia.asked[0]?.toString(),
      `${WIKIMEDIA_PAGEVIEWS}/per-article/en.wikipedia/all-access/user/Albert_Einstein/daily/2024010100/2024010300`,
    );

    const query: PageviewsQuery = { granularity: "monthly", access: "mobile-web", agent: "spider" };
    const months = await client.perArticle("en.wikipedia", "AC/DC", JANUARY, THIRD, query);
    assert.equal(readCount(months[0]).granularity, "monthly");
    assert.equal(months[0]?.title, "AC/DC");
    assert.ok(wikimedia.asked.at(-1)?.pathname.includes("/mobile-web/spider/AC%2FDC/monthly/"));

    assert.deepEqual(await client.perArticle("en.wikipedia", "Nobody reads this", JANUARY, THIRD), []);

    const hours = await client.aggregate("en.wikipedia", JANUARY, THIRD, { granularity: "hourly", access: "desktop", agent: "automated" });
    const hour = readCount(hours[0]);
    assertNone(hour.title);
    assert.equal(hour.access, "desktop");
    assert.equal(hour.agent, "automated");
    assert.equal(hour.granularity, "hourly");
    await client.aggregate("en.wikipedia", JANUARY, THIRD, null);
    assert.ok(wikimedia.asked.at(-1)?.pathname.endsWith("/aggregate/en.wikipedia/all-access/user/daily/2024010100/2024010300"));

    const ranking: TopPagesOptions = { access: "mobile-app", granularity: "monthly" };
    const top = assertArray(await client.top("en.wikipedia", JANUARY, ranking));
    const main = top[0];
    assert.ok(main instanceof TopPage);
    assert.equal(main.title, "Main Page");
    assert.equal(main.views, 4817450);
    assert.equal(main.rank, 1);
    assert.ok(wikimedia.asked.at(-1)?.pathname.endsWith("/top/en.wikipedia/mobile-app/2024/01/all-days"));
    await client.top("en.wikipedia", JANUARY);
    assert.ok(wikimedia.asked.at(-1)?.pathname.endsWith("/top/en.wikipedia/all-access/2024/01/01"));

    assert.equal(client.projectOf("en.wikipedia.org"), "en.wikipedia");
    assert.equal(client.projectOf("www.wikidata.org"), "wikidata");
    assert.throws(() => client.projectOf("wiki.example.org"));
    client.close();

    const options: PageviewsOptions = {
      baseUrl: "https://aqs.example.org",
      retry: QUICK_RETRY,
      userAgent: new UserAgent("Mine", "2.0", CONTACT),
    };
    const mine = pageviews(CONTACT, options);
    await assert.rejects(mine.aggregate("en.wikipedia", JANUARY, THIRD, { agent: "bogus" as PageviewAgent }), Error);
    await mine.aggregate("en.wikipedia", JANUARY, THIRD, { agent: "spider" });
    assert.equal(wikimedia.asked.at(-1)?.host, "aqs.example.org");
    mine.close();
  } finally {
    wikimedia.restore();
  }
}

const WIKIPEDIA_SITE = {
  query: {
    general: {
      wikiid: "enwiki",
      sitename: "Wikipedia",
      lang: "en",
      server: "//en.wikipedia.org",
      articlepath: "/wiki/$1",
      mainpage: "Main Page",
      generator: "MediaWiki 1.47.0",
    },
    namespaces: {
      "0": { id: 0, name: "", case: "first-letter" },
      "1": { id: 1, name: "Talk", canonical: "Talk", case: "first-letter" },
    },
  },
};

async function ofWikiPages(): Promise<void> {
  const site = new FakeSite({ siteinfo: WIKIPEDIA_SITE });
  const asked: string[] = [];
  site.raw = (url) => {
    if (!url.startsWith(WIKIMEDIA_PAGEVIEWS)) return undefined;
    asked.push(url);
    return answer(new URL(url));
  };
  await withWiki(site, async (wiki) => {
    const client = pageviews(CONTACT);
    const pages = assertArray(await client.ofPages(wiki, ["Talk:AC/DC", "Nobody reads this", "Talk:AC/DC"], JANUARY, THIRD, { agent: "user" }));
    assert.deepEqual(pages.map((page) => page.title), ["Talk:AC/DC", "Nobody reads this"]);
    const talk = pages[0];
    assert.ok(talk instanceof PagePageviews);
    assert.equal(assertArray(talk.counts).length, 2);
    assert.equal(talk.counts[0]?.title, "Talk:AC/DC");
    assert.deepEqual(pages[1]?.counts, []);
    assert.ok(asked.every((url) => url.includes("/per-article/en.wikipedia/")));
    client.close();
  });

  await withWiki(new FakeSite(), async (wiki) => {
    const client = pageviews(CONTACT);
    await assert.rejects(client.ofPages(wiki, ["Volcano"], JANUARY, THIRD), Error);
    client.close();
  });
}

await counted();
await ofWikiPages();

console.log("pageviews: a page's views, a project's, the most viewed pages and a wiki's own pages read as typed");
