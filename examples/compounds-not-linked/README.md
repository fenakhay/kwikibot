# compounds-not-linked-bot

A working Wiktionary bot, kept here as the proof that kwikibot supports real work rather than
only its own tests.

It adds the derived terms listed at
[`Wiktionary:Todo/compounds not linked to from components`](https://en.wiktionary.org/wiki/Wiktionary:Todo/compounds_not_linked_to_from_components)
to the entries of their components: put each term list into the right section of the right
entry, at the right heading level, in [WT:EL](https://en.wiktionary.org/wiki/Wiktionary:Entry_layout)
order, without disturbing anything else.

```bash
compounds --todo todo.wikitext --contact https://en.wiktionary.org/wiki/User:MyBot --limit 5
compounds --todo todo.wikitext --contact … --save --diff-log logs/diffs.log --skip-log logs/skips.jsonl
```

Dry run unless `--save` is given. Credentials come from `KWIKIBOT_ACCOUNT`, `KWIKIBOT_BOT_NAME`
and `KWIKIBOT_PASSWORD` rather than from options, so a bot password is not left in shell history
or shown in the process list. Without all three it runs anonymously, which is enough for a dry
run.

## What it is here to show

The library carries the weight. The bot is 1,213 lines including comments, and none of them are
about reading pages, pacing requests, retrying, detecting an edit conflict, honouring an emergency
stop, or writing diffs and skip logs. Those are library concerns. What is left is the part that is
actually about Wiktionary entries.

The front end belongs to the bot. kwikibot ships no command base class to subclass, because how a
program starts is the program's own business. `Compounds.kt` reads its own arguments and assembles
the run from the pieces the library does supply: `botRun`, `BotPolicy` for `{{nobots}}`,
`StopPolicy` for the emergency stop, and `RunLog`, `Progress` and `reportTo` for the reporting.
About forty lines in all.

Every rule has its expected output committed as a fixture and replayed offline: each task parsed
from excerpts of the real list page, and 42 transform cases covering every placement rule, every
documented refusal, and the bot's own sample entries, compared on the exact wikitext, byte for
byte. A rule that changes shows up as a failing test rather than as an edit on the wiki.

It also holds up on pages nobody chose for it. Run against ten live entries taken from the todo
list, it reached a defensible verdict on all ten, including the refusals
(`ambiguous_pos:Adjective,Noun`) and the no-ops on entries already carrying their derived terms.

## Layout

| File | What it does |
|---|---|
| `TodoList.kt` | Reads the list page: both layouts, the off-wiki gate, merging repeated targets |
| `EntryLayout.kt` | Where things go: parts of speech, WT:EL heading order, trailing furniture |
| `Container.kt` | Term lists however they are written, and the sort `Module:columns` applies |
| `DerivedTerms.kt` | The transform: merge into an existing list, or create the section |
| `Summaries.kt` | Edit summaries that name the todo page and admit to reformatting |
| `Main.kt` | The command line, and the wiring between the four |

## What it will not touch

Each one a test:

| Refusal | Meaning |
|---|---|
| `no_lang_section` | no `==English==` section |
| `no_pos_section` | nothing to attach a subsection to |
| `ambiguous_pos:Noun,Verb` | several parts of speech, and which sense the term belongs to is a human call |
| `unrecognized_container` | the existing list uses a form the bot cannot safely extend |
| `container_lang_mismatch:fr` | the existing `{{col}}` is for another language |
| `multiple_derived_sections` | malformed entry with two derived terms headings |
| `keepfirst` / `keeplast` | rows pinned out of the sort; the order cannot be reproduced |
