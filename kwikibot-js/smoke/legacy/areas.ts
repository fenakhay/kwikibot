import { connect, parse } from "kwikibot";
import { type PageService } from "kwikibot/client";
import { describeError } from "kwikibot/errors";
import { createTemplate } from "kwikibot/wikitext";
import { parseTimestamp } from "kwikibot/titles";
import { pageSourceOfTitles } from "kwikibot/bot";
import { dumpPages } from "kwikibot/dumps";
import { claim } from "kwikibot/wikibase";
import { sparql } from "kwikibot/sparql";
import { eventStreams } from "kwikibot/events";
import { cron } from "kwikibot/fleet";
import { type ProofreadService } from "kwikibot/proofread";
import { createFakeWiki } from "kwikibot/testing";

export const reached: unknown[] = [
  connect,
  parse,
  describeError,
  createTemplate,
  parseTimestamp,
  pageSourceOfTitles,
  dumpPages,
  claim,
  sparql,
  eventStreams,
  cron,
  createFakeWiki,
];
export type Services = [PageService, ProofreadService];
