import kwikibot from "@fenakhay/kwikibot";

const contact = process.argv[2] ?? "https://github.com/fenakhay/kwikibot";

const endpoint = await kwikibot.detect("https://en.wiktionary.org", contact);
if (endpoint?.apiUrl !== "https://en.wiktionary.org/w/api.php") {
  throw new Error(`detect found ${endpoint?.apiUrl}`);
}
console.log(`api:        ${endpoint.apiUrl}`);

const wiki = await kwikibot.connect(endpoint.server, contact, null, endpoint.scriptPath);
try {
  console.log(`wiki:       ${wiki.id} (${wiki.siteName}, MediaWiki ${wiki.mediaWikiVersion})`);
  console.log(`user:       ${wiki.user.name}${wiki.user.isAnonymous ? " (anonymous)" : ""}`);

  const page = await wiki.page("volcano");
  const templates = page?.wikitext().templates() ?? [];
  if (templates.length === 0) {
    throw new Error("volcano has no templates");
  }
  console.log(`volcano:    revision ${page.revision}, ${templates.length} templates, first {{${templates[0].name}}}`);

  const members = [];
  for await (const title of wiki.categoryMembers("English lemmas")) {
    members.push(title);
    if (members.length === 3) break;
  }
  if (members.length !== 3) {
    throw new Error(`Category:English lemmas listed ${members.length} pages`);
  }
  console.log(`lemmas:     ${members.join(", ")}`);
} finally {
  wiki.close();
}
