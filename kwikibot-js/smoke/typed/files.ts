import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describeError, type JsonValue } from "kwikibot";
import {
  type DeletedFilesOptions,
  FileInfo,
  FileRepository,
  FileService,
  FileVersions,
  GlobalUsage,
  type UploadOptions,
  UploadResult,
} from "kwikibot/client";
import { FakeSite, assertArray, assertDate, assertNone, assertRecord, collect, withWiki } from "./support.js";

const IMAGE_INFO: JsonValue = {
  query: {
    pages: [
      {
        pageid: 1,
        ns: 6,
        title: "File:Fire.jpg",
        imageinfo: [
          {
            url: "https://example.org/Fire.jpg",
            descriptionurl: "https://example.org/wiki/File:Fire.jpg",
            size: 1024,
            width: 40,
            height: 30,
            mime: "image/jpeg",
            mediatype: "BITMAP",
            sha1: "abc",
            timestamp: "2026-08-31T21:43:26Z",
            user: "Alice",
            comment: "first",
          },
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

function typosDoNotCompile(result: UploadResult): boolean {
  // @ts-expect-error a kind of result that does not exist
  return result.kind === "upload";
}
void typosDoNotCompile;

async function readFileFacts(site: FakeSite, files: FileService): Promise<void> {
  site.answers.imageinfo = IMAGE_INFO;
  const [found] = await files.info(["Fire.jpg"], 3);
  assert.ok(found instanceof FileVersions);
  assert.equal(found.title, "File:Fire.jpg");
  const sent = lastSent(site, "prop", "imageinfo");
  assert.equal(sent.iilimit, "3");
  assert.equal(sent.titles, "File:Fire.jpg");

  const [version] = assertArray(found.versions);
  assert.ok(version instanceof FileInfo);
  assert.equal(version.url, "https://example.org/Fire.jpg");
  assert.equal(version.descriptionUrl, "https://example.org/wiki/File:Fire.jpg");
  assert.equal(version.size, 1024);
  assert.equal(version.width, 40);
  assert.equal(version.height, 30);
  assert.equal(version.hasDimensions, true);
  assert.equal(version.mimeType, "image/jpeg");
  assert.equal(version.mediaType, "BITMAP");
  assert.equal(version.sha1, "abc");
  assert.equal(assertDate(version.timestamp).toISOString(), "2026-08-31T21:43:26.000Z");
  assert.equal(version.user, "Alice");
  assert.equal(version.comment, "first");

  assert.equal(assertArray(await files.info(["Fire.jpg"])).length, 1);
  assert.equal((await files.info(["Fire.jpg"], null)).length, 1);

  const latest = await files.latest("File:Fire.jpg");
  assert.ok(latest != null);
  assert.equal(latest.sha1, "abc");

  site.answers.imageinfo = { query: { pages: [{ pageid: 1, ns: 6, title: "File:Song.ogg", imageinfo: [{ url: "https://example.org/Song.ogg", size: 2048 }] }] } };
  const song = await files.latest("Song.ogg");
  assert.ok(song != null);
  assert.equal(song.width, 0);
  assert.equal(song.height, 0);
  assert.equal(song.hasDimensions, false);
  assertNone(song.descriptionUrl);
  assertNone(song.mimeType);
  assertNone(song.mediaType);
  assertNone(song.sha1);
  assertNone(song.timestamp);
  assertNone(song.user);
  assertNone(song.comment);

  site.answers.imageinfo = { query: { pages: [{ ns: 6, title: "File:Gone.jpg", missing: true }] } };
  assertNone(await files.latest("Gone.jpg"));
  assert.deepEqual(assertArray(await files.info(["Gone.jpg"])), []);
}

async function findWhereFilesAreUsed(site: FakeSite, files: FileService): Promise<void> {
  site.answers.imageusage = {
    query: {
      imageusage: [
        { ns: 0, title: "Volcano" },
        { ns: 0, title: "Ash" },
      ],
    },
  };
  assert.deepEqual(await collect(files.usage("Fire.jpg", 1)), ["Volcano"]);
  assert.equal(lastSent(site, "list", "imageusage").iutitle, "File:Fire.jpg");
  assert.deepEqual(await collect(files.usage("Fire.jpg")), ["Volcano", "Ash"]);

  site.answers.globalusage = {
    query: {
      pages: [
        {
          ns: 6,
          title: "File:Fire.jpg",
          globalusage: [
            { wiki: "frwiki", title: "Volcan", url: "https://fr.example.org/wiki/Volcan" },
            { wiki: "dewiki", title: "Vulkan" },
          ],
        },
      ],
    },
  };
  const [french, german] = await collect(files.globalUsage("Fire.jpg"));
  assert.ok(french instanceof GlobalUsage);
  assert.equal(french.wiki, "frwiki");
  assert.equal(french.title, "Volcan");
  assert.equal(french.url, "https://fr.example.org/wiki/Volcan");
  assert.equal(german.title, "Vulkan");
  assertNone(german.url);
  assert.equal((await collect(files.globalUsage("Fire.jpg", 1))).length, 1);

  site.answers.allimages = { query: { allimages: [{ name: "Fire.jpg" }] } };
  assert.equal(files.duplicateLimit, 10);
  assert.deepEqual(assertArray(await files.duplicatesOf("abc")), ["File:Fire.jpg"]);
  assert.equal(lastSent(site, "list", "allimages").ailimit, String(files.duplicateLimit));
  await files.duplicatesOf("abc", 2);
  assert.equal(lastSent(site, "list", "allimages").ailimit, "2");

  site.answers.filearchive = { query: { filearchive: [{ ns: 6, title: "File:Gone.jpg" }] } };
  const options: DeletedFilesOptions = { from: "A", to: "Z", prefix: "G", limit: 2 };
  assert.deepEqual(await collect(files.deletedFiles(options)), ["File:Gone.jpg"]);
  const archive = lastSent(site, "list", "filearchive");
  assert.equal(archive.fafrom, "A");
  assert.equal(archive.fato, "Z");
  assert.equal(archive.faprefix, "G");
  assert.equal(archive.falimit, "2");
  assert.deepEqual(await collect(files.deletedFiles()), ["File:Gone.jpg"]);

  site.answers.filerepoinfo = {
    query: {
      repos: [
        { name: "shared", displayname: "Commons", rootUrl: "https://r.example.org", url: "https://u.example.org", canUpload: true },
        { name: "local", displayname: "This wiki", local: true },
      ],
    },
  };
  const [shared, local] = assertArray(await files.repositories());
  assert.ok(shared instanceof FileRepository);
  assert.equal(shared.name, "shared");
  assert.equal(shared.displayName, "Commons");
  assert.equal(shared.isLocal, false);
  assert.equal(shared.url, "https://u.example.org");
  assert.equal(shared.rootUrl, "https://r.example.org");
  assert.equal(shared.canUpload, true);
  assert.equal(local.name, "local");
  assert.equal(local.isLocal, true);
  assert.equal(local.canUpload, false);
  assertNone(local.url);
  assertNone(local.rootUrl);
}

async function downloadFiles(site: FakeSite, files: FileService, folder: string): Promise<void> {
  site.answers.imageinfo = IMAGE_INFO;
  site.raw = (url) => (url === "https://example.org/Fire.jpg" ? new Response("the bytes") : undefined);

  const target = join(folder, "saved.jpg");
  assert.equal(await files.download("Fire.jpg", target), target);
  assert.equal(await readFile(target, "utf8"), "the bytes");

  const nested = join(folder, "a", "b", "fire.jpg");
  assert.equal(await files.download("File:Fire.jpg", nested), nested);
  assert.equal(await readFile(nested, "utf8"), "the bytes");

  const intoFolder = await files.download("Fire.jpg", folder);
  assert.equal(intoFolder, join(folder, "Fire.jpg"));
  assert.equal(await readFile(intoFolder, "utf8"), "the bytes");

  site.answers.imageinfo = { query: { pages: [{ ns: 6, title: "File:Gone.jpg", missing: true }] } };
  const refused = await files.download("Gone.jpg", join(folder, "gone.jpg")).then(
    () => undefined,
    (reason: unknown) => reason,
  );
  const info = describeError(refused);
  assert.ok(info != null);
  assert.equal(info.kind, "missing-page");
  site.raw = undefined;
}

async function uploadFiles(site: FakeSite, files: FileService, folder: string): Promise<void> {
  const inner = globalThis.fetch;
  const bodies: string[] = [];
  globalThis.fetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    if (init?.body instanceof Uint8Array) bodies.push(new TextDecoder().decode(init.body));
    return inner(input, init);
  };

  const source = join(folder, "upload.bin");
  await writeFile(source, "some bytes");

  site.answers[""] = { upload: { result: "Success", imageinfo: { url: "https://example.org/Fire.jpg", sha1: "abc" } } };
  const options: UploadOptions = { comment: "why", text: "about", ignoreWarnings: true, chunkSize: 1024 };
  const uploaded = await files.upload(source, "Fire.jpg", options);
  assert.ok(uploaded instanceof UploadResult);
  assert.equal(uploaded.kind, "uploaded");
  assert.equal(uploaded.title, "File:Fire.jpg");
  assert.ok(uploaded.info != null);
  assert.equal(uploaded.info.sha1, "abc");
  assert.equal(uploaded.info.url, "https://example.org/Fire.jpg");
  assertNone(uploaded.warnings);
  assertNone(uploaded.fileKey);
  assertNone(uploaded.isDuplicateOnly);
  assertNone(uploaded.code);
  assertNone(uploaded.detail);
  const body = bodies.at(-1);
  assert.ok(body != null);
  for (const part of ["some bytes", "ignorewarnings", "about", "why"]) assert.ok(body.includes(part), `the body lacks ${part}`);

  site.answers[""] = { upload: { result: "Success" } };
  const untold = await files.upload(source, "Fire.jpg");
  assert.equal(untold.kind, "uploaded");
  assertNone(untold.info);

  site.answers[""] = { upload: { result: "Warning", warnings: { duplicate: "Ash.jpg" }, filekey: "k1" } };
  const warned = await files.upload(source, "Fire.jpg", null);
  assert.equal(warned.kind, "warned");
  const warnings = warned.warnings;
  assert.ok(warnings != null);
  assert.deepEqual(assertRecord(warnings, "string"), { duplicate: "Ash.jpg" });
  assert.equal(warned.fileKey, "k1");
  assert.equal(warned.isDuplicateOnly, true);
  assertNone(warned.title);
  assertNone(warned.info);
  assertNone(warned.code);
  assertNone(warned.detail);

  site.answers[""] = { upload: { result: "Warning", warnings: { exists: "Fire.jpg", duplicate: ["Ash.jpg", "Soot.jpg"] } } };
  const overwriting = await files.upload(source, "Fire.jpg");
  assert.equal(overwriting.isDuplicateOnly, false);
  assertNone(overwriting.fileKey);
  assert.ok(overwriting.warnings != null);
  assertRecord(overwriting.warnings, "string");

  site.answers[""] = { error: { code: "fileexists", info: "no" } };
  const refused = await files.publishStashed("k1", "Fire.jpg", "why", "about");
  assert.equal(refused.kind, "refused");
  assert.equal(refused.code, "fileexists");
  assert.equal(refused.detail, "no");
  assertNone(refused.title);
  assertNone(refused.warnings);
  const stashed = bodies.at(-1);
  assert.ok(stashed != null);
  for (const part of ["k1", "why", "about"]) assert.ok(stashed.includes(part), `the body lacks ${part}`);

  site.answers[""] = { upload: { result: "Success", imageinfo: { url: "https://example.org/Fire.jpg" } } };
  assert.equal((await files.publishStashed("k1", "Fire.jpg")).kind, "uploaded");
  assert.equal((await files.publishStashed("k1", "Fire.jpg", undefined, null)).kind, "uploaded");

  assert.equal(files.defaultChunk, 5 * 1024 * 1024);
  let chunks = 0;
  site.answers[""] = (): JsonValue => {
    chunks += 1;
    if (chunks < 3) return { upload: { result: "Continue", offset: chunks * 4, filekey: `k${chunks}` } };
    if (chunks === 3) return { upload: { result: "Success", filekey: "whole" } };
    return { upload: { result: "Success", imageinfo: { url: "https://example.org/Fire.jpg", sha1: "abc" } } };
  };
  bodies.length = 0;
  const inChunks = await files.upload(source, "Fire.jpg", { chunkSize: 4 });
  assert.equal(inChunks.kind, "uploaded");
  assert.equal(chunks, 4);
  assert.ok(bodies[3].includes("whole"));

  await assert.rejects(files.upload(join(folder, "absent.bin"), "Fire.jpg"), Error);
}

const folder = await mkdtemp(join(tmpdir(), "kwikibot-typed-files-"));
try {
  const site = new FakeSite({}, ["GlobalUsage"]);
  await withWiki(site, async (wiki) => {
    assert.ok(wiki.files instanceof FileService);
    await readFileFacts(site, wiki.files);
    await findWhereFilesAreUsed(site, wiki.files);
    await downloadFiles(site, wiki.files, folder);
    await uploadFiles(site, wiki.files, folder);
  });
} finally {
  await rm(folder, { recursive: true, force: true });
}

console.log("files: versions, usage, duplicates, repositories, streamed downloads and every outcome of an upload read as typed");
