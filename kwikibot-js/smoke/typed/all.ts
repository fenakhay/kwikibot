const PROGRAMS = [
  "pages_edits",
  "listings",
  "revisions_logs",
  "users",
  "files",
  "meta_render",
  "extensions",
  "wikibase",
  "wikitext",
  "titles_model",
  "bot_runs",
  "fixes_messages",
  "fleet",
  "dumps",
  "connection_login",
  "streams",
  "testing",
  "literals",
  "entry_points",
] as const;

for (const name of PROGRAMS) {
  await import(`./${name}.js`);
}

console.log(`typed: ${PROGRAMS.length} programs ran`);
