import assert from "node:assert/strict";
import { type RetryOptions, UserAgent } from "kwikibot/client";
import {
  EditQualityScore,
  LiftWing,
  liftWing,
  type LiftWingOptions,
  LiftWingResult,
  QualityScore,
  type RevertRiskModel,
  RevertRiskScore,
  Unscorable,
  type UnscorableReason,
  WIKIMEDIA_LIFT_WING,
} from "kwikibot/services";
import { FakeSite, assertArray, assertNone, assertRecord, json, withWiki } from "./support.js";

const CONTACT = "https://example.org/TestBot";
const QUICK_RETRY: RetryOptions = { maxRetries: 1, initialDelay: 1, maxDelay: 1 };

interface Asked {
  url: URL;
  body: Record<string, unknown>;
  authorization: string | null;
}

function revscoring(wiki: string, kind: string, revision: number, prediction: boolean | string, probability: Record<string, number>): Response {
  return json({ [wiki]: { models: { [kind]: { version: "0.5.1" } }, scores: { [String(revision)]: { [kind]: { score: { prediction, probability } } } } } });
}

function answer(url: URL, body: Record<string, unknown>): Response {
  const model = url.pathname.split("/").at(-1)?.replace(":predict", "") ?? "";
  const revision = Number(body.rev_id);
  if (model.startsWith("enwiktionary-")) return json({ httpCode: 404, httpReason: "Not Found" }, 404);
  if (revision === 1383648813 && model.startsWith("revertrisk")) {
    return json({ detail: "Could not make prediction for revision 1383648813 (en). Reason: revision_missing" }, 422);
  }
  if (revision === 1383648813) {
    return json({ error: "The MW API does not have any info related to the rev-id provided as input (1383648813)" }, 400);
  }
  if (revision === 1250000000) {
    return json({ detail: "Could not make prediction for revision 1250000000 (en). Reason: parent_revision_missing" }, 422);
  }
  if (model.startsWith("revertrisk")) {
    return json({ model_name: model, model_version: "3", wiki_db: "enwiki", revision_id: revision, output: { prediction: true, probabilities: { true: 0.926, false: 0.074 } } });
  }
  if (model.endsWith("-goodfaith")) return revscoring("enwiki", "goodfaith", revision, true, { true: 0.996, false: 0.004 });
  if (model.endsWith("-articlequality")) return revscoring("enwiki", "articlequality", revision, "FA", { FA: 0.54, GA: 0.35, B: 0.11 });
  if (model.endsWith("-draftquality")) return revscoring("enwiki", "draftquality", revision, "vandalism", { OK: 0.28, vandalism: 0.62, spam: 0.1 });
  if (model.endsWith("-articletopic")) return json({ enwiki: { models: { articletopic: { version: "1.3.0" } }, scores: {} } });
  return revscoring("enwiki", "damaging", revision, false, { true: 0.246, false: 0.754 });
}

function bodyOf(init?: RequestInit): Record<string, unknown> {
  if (init?.body == null) return {};
  const text = init.body instanceof Uint8Array ? new TextDecoder().decode(init.body) : String(init.body);
  return JSON.parse(text) as Record<string, unknown>;
}

function intercept(): { asked: Asked[]; restore: () => void } {
  const original = globalThis.fetch;
  const asked: Asked[] = [];
  globalThis.fetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const url = new URL(input instanceof Request ? input.url : input.toString());
    const body = bodyOf(init);
    asked.push({ url, body, authorization: new Headers(init?.headers).get("Authorization") });
    return answer(url, body);
  };
  return {
    asked,
    restore: () => {
      globalThis.fetch = original;
    },
  };
}

function scoreOf<T>(result: LiftWingResult<T>): T {
  assert.ok(result instanceof LiftWingResult);
  assertNone(result.unscorable);
  const score = result.score;
  assert.ok(score !== null && score !== undefined);
  return score;
}

function whyOf<T>(result: LiftWingResult<T>): Unscorable {
  assertNone(result.score);
  const why = result.unscorable;
  assert.ok(why instanceof Unscorable);
  return why;
}

async function scored(): Promise<void> {
  const gateway = intercept();
  try {
    assert.equal(WIKIMEDIA_LIFT_WING, "https://api.wikimedia.org/service/lw/inference/v1/models");
    const client: LiftWing = liftWing(CONTACT, { retry: QUICK_RETRY });
    assert.ok(client instanceof LiftWing);

    const result = await client.damaging("enwiki", 1383620073);
    assert.equal(result.revision, 1383620073);
    const damaging = scoreOf(result);
    assert.ok(damaging instanceof EditQualityScore);
    assert.equal(damaging.model, "enwiki-damaging");
    assert.equal(damaging.prediction, false);
    assert.equal(damaging.trueProbability, 0.246);
    assert.equal(damaging.falseProbability, 0.754);
    assert.equal(damaging.modelVersion, "0.5.1");
    assert.equal(gateway.asked[0]?.url.toString(), `${WIKIMEDIA_LIFT_WING}/enwiki-damaging:predict`);
    assert.deepEqual(gateway.asked[0]?.body, { rev_id: 1383620073 });
    assertNone(gateway.asked[0]?.authorization);

    const goodfaith = scoreOf(await client.goodfaith("enwiki", 1383620073));
    assert.equal(goodfaith.model, "enwiki-goodfaith");
    assert.equal(goodfaith.prediction, true);

    const models: RevertRiskModel[] = ["language-agnostic", "multilingual"];
    for (const model of models) {
      const risk = scoreOf(await client.revertRisk("en", 1383620073, model));
      assert.ok(risk instanceof RevertRiskScore);
      assert.equal(risk.model, model);
      assert.equal(risk.wiki, "enwiki");
      assert.equal(risk.prediction, true);
      assert.equal(risk.probability, 0.926);
      assert.equal(risk.modelVersion, "3");
      assert.deepEqual(gateway.asked.at(-1)?.body, { rev_id: 1383620073, lang: "en" });
    }
    await client.revertRisk("en", 1383620073);
    assert.ok(gateway.asked.at(-1)?.url.pathname.endsWith("/revertrisk-language-agnostic:predict"));

    const article = scoreOf(await client.articleQuality("enwiki", 1383620073));
    assert.ok(article instanceof QualityScore);
    assert.equal(article.model, "enwiki-articlequality");
    assert.equal(article.prediction, "FA");
    assert.equal(article.modelVersion, "0.5.1");
    assert.equal(assertRecord(article.probabilities, "number").GA, 0.35);
    const draft = scoreOf(await client.draftQuality("enwiki", 1383620073));
    assert.equal(draft.prediction, "vandalism");

    const missing = whyOf(await client.damaging("enwiki", 1383648813));
    const reason: UnscorableReason = missing.reason;
    assert.equal(reason, "missing");
    assert.equal(missing.model, "enwiki-damaging");
    assert.ok(missing.detail.includes("does not have any info"));
    assert.equal(whyOf(await client.revertRisk("en", 1250000000)).reason, "first-revision");

    const topics = await client.predict("enwiki-articletopic", { rev_id: 1383620073, extended_output: false });
    assert.deepEqual(topics, { enwiki: { models: { articletopic: { version: "1.3.0" } }, scores: {} } });
    assert.deepEqual(gateway.asked.at(-1)?.body, { rev_id: 1383620073, extended_output: false });

    await assert.rejects(client.damaging("enwiktionary", 1383620073), /enwiktionary-damaging/);
    await assert.rejects(client.revertRisk("en", 1383620073, "bogus" as RevertRiskModel), Error);
    client.close();

    const options: LiftWingOptions = {
      baseUrl: "https://lw.example.org",
      retry: QUICK_RETRY,
      userAgent: new UserAgent("Mine", "2.0", CONTACT),
      accessToken: "not-a-real-token",
    };
    const mine = liftWing(CONTACT, options);
    await mine.damaging("enwiki", 1383620073);
    assert.equal(gateway.asked.at(-1)?.url.host, "lw.example.org");
    assert.equal(gateway.asked.at(-1)?.authorization, "Bearer not-a-real-token");
    mine.close();
  } finally {
    gateway.restore();
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
    namespaces: { "0": { id: 0, name: "", case: "first-letter" } },
  },
};

function jsonBodyOf(params: Record<string, string>): Record<string, unknown> {
  const key = Object.keys(params).find((name) => name.startsWith("{"));
  return key === undefined ? {} : (JSON.parse(key) as Record<string, unknown>);
}

async function ofWikiRevisions(): Promise<void> {
  const site = new FakeSite({ siteinfo: WIKIPEDIA_SITE });
  const models: string[] = [];
  site.raw = (url, params) => {
    if (!url.startsWith(WIKIMEDIA_LIFT_WING)) return undefined;
    const target = new URL(url);
    models.push(target.pathname.split("/").at(-1) ?? "");
    return answer(target, jsonBodyOf(params));
  };
  await withWiki(site, async (wiki) => {
    const client = liftWing(CONTACT);
    assert.equal(client.prefixOf(wiki), "enwiki");
    assert.equal(client.languageOf(wiki), "en");
    const revisions = [1383620073, 1383648813, 1383620073];

    const damaging = assertArray(await client.damagingOf(wiki, revisions));
    assert.deepEqual(damaging.map((result) => result.revision), [1383620073, 1383648813]);
    assert.equal(damaging[0]?.score?.trueProbability, 0.246);
    assert.equal(damaging[1]?.unscorable?.reason, "missing");
    assert.equal((await client.goodfaithOf(wiki, revisions))[0]?.score?.prediction, true);
    assert.equal((await client.revertRiskOf(wiki, [1383620073]))[0]?.score?.model, "language-agnostic");
    assert.equal((await client.revertRiskOf(wiki, [1383620073], "multilingual"))[0]?.score?.model, "multilingual");
    assert.equal((await client.articleQualityOf(wiki, revisions))[0]?.score?.prediction, "FA");
    assert.equal((await client.draftQualityOf(wiki, revisions))[0]?.score?.prediction, "vandalism");
    assert.ok(models.includes("enwiki-damaging:predict") && models.includes("revertrisk-multilingual:predict"));
    client.close();
  });

  await withWiki(new FakeSite(), async (wiki) => {
    const client = liftWing(CONTACT);
    assert.throws(() => client.prefixOf(wiki));
    assert.throws(() => client.languageOf(wiki));
    await assert.rejects(client.damagingOf(wiki, [1]), Error);
    client.close();
  });
}

await scored();
await ofWikiRevisions();

console.log("lift_wing: a revision's scores, why one has none, and a wiki's revisions read as typed");
