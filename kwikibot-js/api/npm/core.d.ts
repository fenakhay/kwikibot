type Nullable<T> = T | null | undefined
declare function KtSingleton<T>(): T & (abstract new() => any);
/**
 * How many threads `dumpPages` and `scanDump` decode on unless told.
 */
export declare function availableDumpWorkers(): number;
/**
 * The pages of the dump file at [path], in the dump's order, as an async iterable. Plain XML, `.gz` or
 * `.bz2`, going by the name. Node only.
 * 
 * bzip2 is decoded on [workers] worker threads, one per processor unless told otherwise, so reading a dump
 * leaves the event loop free. Stopping the loop early, with `break`, stops the reading and the threads.
 * 
 * @param path - the dump.
 * @param namespaces - the namespaces wanted, or every one when `null`.
 * @param workers - how many threads to decode on.
 */
export declare function dumpPages(path: string, namespaces?: Nullable<Array<number>>, workers?: Nullable<number>): AsyncIterable<DumpPage>;
/**
 * The pages of a dump already in memory, which is the way to read one in a browser: on one thread, and only
 * for a dump small enough to hold.
 * 
 * @param bytes - the dump.
 * @param compression - `"none"`, `"gzip"` or `"bzip2"`.
 * @param namespaces - the namespaces wanted, or every one when `null`.
 */
export declare function dumpPagesOf(bytes: Uint8Array, compression?: DumpCompression, namespaces?: Nullable<Array<number>>): Array<DumpPage>;
/**
 * Calls [block] with every page of the dump file at [path], which may return a promise. The fastest way
 * through a dump when the order does not matter, as pages come in no particular order. Rejects with the first
 * failure. Node only.
 * 
 * @param path - the dump.
 * @param block - what to do with each page.
 * @param namespaces - the namespaces wanted, or every one when `null`.
 * @param workers - how many threads to decode on.
 */
export declare function scanDump(path: string, block: (page: DumpPage) => Awaitable<Ignored>, namespaces?: Nullable<Array<number>>, workers?: Nullable<number>): Promise<void>;
/**
 * One page of an XML dump, with the one revision the dump carries.
 */
export declare class DumpPage {
    private constructor();
    /**
     * Whether this page is in one of [namespaces]. Every namespace matches an empty array.
     */
    inNamespaces(namespaces: Array<number>): boolean;
    /**
     * The full title, with its namespace prefix.
     */
    get title(): string;
    /**
     * The namespace number.
     */
    get namespace(): number;
    /**
     * The wiki's id for the page.
     */
    get pageId(): number;
    /**
     * The revision this text came from.
     */
    get revisionId(): number;
    /**
     * The wikitext, as of that revision.
     */
    get text(): string;
    /**
     * When the revision was made, if the dump says.
     */
    get timestamp(): Nullable<Date>;
    /**
     * Who made it, absent where the dump withheld it.
     */
    get contributor(): Nullable<string>;
    /**
     * The edit summary.
     */
    get comment(): Nullable<string>;
    /**
     * Whether the page is a redirect.
     */
    get isRedirect(): boolean;
    /**
     * The page a redirect points to, or `null` for a page that is not one.
     */
    get redirectTarget(): Nullable<string>;
}
export declare namespace DumpPage {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => DumpPage;
    }
}
/**
 * A schedule firing every [milliseconds].
 */
export declare function every(milliseconds: number): Schedule;
/**
 * A schedule on a five-field cron expression, as [zone] tells the time, such as `"Europe/Berlin"`. A firing
 * during a run waits its turn, and firings missed while nothing was running are not made up.
 */
export declare function cron(expression: string, zone?: string): Schedule;
/**
 * Opens a fleet of [bots] on one wiki. Bots on one account share one login and one budget.
 * 
 * @param options - the wiki, and how to reach it.
 * @param bots - the bots.
 */
export declare function createFleet(options: FleetOptions, bots: Array<BotSpec>): Promise<Fleet>;
/**
 * Starts a program of many bots from its command line, in Node: `list`, `run <bot>`, `run --all` or `serve`,
 * with `kwikibot.toml` and the common flags. Exits with the status: 0 only if every run finished.
 * 
 * @param args - the command line, without Node's own two entries.
 * @param bots - the bots.
 */
export declare function fleetMain(args: Array<string>, bots: Array<BotSpec>): void;
/**
 * `fleetMain`, with [prompt] answering a run's question in place of the terminal.
 */
export declare function fleetMainWith(args: Array<string>, bots: Array<BotSpec>, prompt: Prompt): void;
/**
 * When `serve` runs a bot.
 */
export declare class Schedule {
    private constructor();
    /**
     * The first firing after [after].
     */
    next(after: Date): Date;
}
export declare namespace Schedule {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Schedule;
    }
}
/**
 * A bot a fleet can run.
 */
export declare interface BotSpec {
    /**
     * Stable and lowercase. It names the bot in everything a fleet reports.
     */
    readonly id: string;
    /**
     * One sentence on what it does.
     */
    readonly description?: Nullable<string>;
    /**
     * Which account it edits as, by its name in [FleetOptions.accounts], or the default one.
     */
    readonly account?: Nullable<string>;
    /**
     * When `serve` runs it.
     */
    readonly schedule?: Nullable<Schedule>;
    /**
     * Does the work, and may return a promise. A rejection fails this run only, and a [DeclinedError] says
     * somebody answered no.
     */
    readonly run: (context: RunContext) => Awaitable<Ignored>;
}
/**
 * What a fleet is opened with.
 */
export declare interface FleetOptions {
    /**
     * The wiki's server, such as `"en.wiktionary.org"`.
     */
    readonly server: string;
    /**
     * A URL or email address the wiki's operators can reach you at.
     */
    readonly contact: string;
    /**
     * Where `api.php` is under the server, `"/w"` unless given.
     */
    readonly scriptPath?: Nullable<string>;
    /**
     * The default account. In Node, without one, it is read from `KWIKIBOT_` in the environment.
     */
    readonly login?: Nullable<BotPassword>;
    /**
     * Other accounts by name. In Node, a name not here is read from `KWIKIBOT_<NAME>_` in the environment. A
     * browser has no environment, so there it has to be here.
     */
    readonly accounts?: Nullable<Record<string, BotPassword>>;
    /**
     * A page of the wiki that has to say `false` for bots to save, read once a minute for all of them.
     */
    readonly stopPage?: Nullable<string>;
    /**
     * Answers a run's question, and may return a promise. Without it, the answer is no.
     */
    readonly onAsk?: Nullable<(bot: string, title: string, summary: string) => Awaitable<boolean>>;
    /**
     * Takes each line the bots say, prefixed by the bot's id.
     */
    readonly onLine?: Nullable<(line: string) => void>;
}
/**
 * Thrown by a bot whose question was answered no, which ends its run as declined rather than failed.
 */
export declare class DeclinedError extends Error {
    constructor(message: string);
}
export declare namespace DeclinedError {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => DeclinedError;
    }
}
/**
 * What a run has of its fleet, and how it talks back.
 */
export declare class RunContext {
    private constructor();
    /**
     * Another wiki, as the same account, by language and family name, such as `"en"`, `"wiktionary"`.
     */
    open(language: string, family: string): Promise<Wiki>;
    /**
     * Says one line about what the run is doing.
     */
    say(message: string): void;
    /**
     * Says how much more work there is.
     */
    expect(items: number): void;
    /**
     * Says the run stopped before doing all it set out to. The first reason given is the one kept.
     */
    stopped(reason: string): void;
    /**
     * Runs [work] as a named phase of this run.
     */
    step<T>(name: string, work: (context: RunContext) => Awaitable<T>): Promise<T>;
    /**
     * Parks this lane and asks a person, while other bots keep going.
     */
    ask(title: string, summary?: string): Promise<boolean>;
    /**
     * Parks this lane and asks a person [question], which can show a diff, while other bots keep going.
     */
    askQuestion(question: Question): Promise<boolean>;
    /**
     * Says one line about what the run is doing, with how much it matters: `"info"`, `"saved"`, `"skipped"`
     * or `"error"`.
     */
    sayAt(message: string, level: RunLevel): void;
    /**
     * Another wiki, as the same account, in [language] of [family], which may be one of your own.
     */
    openFamily(language: string, family: Family): Promise<Wiki>;
    /**
     * Whether the run may go on. A stop page that cannot be read answers `false`.
     */
    mayContinue(): Promise<boolean>;
    /**
     * Runs over [titles], as `Wiki.run` does, under this fleet's stop page and counted against this run.
     * 
     * @param titles - the pages.
     * @param summary - the edit summary.
     * @param transform - takes a page's text and title, and returns its new text, or `null` to leave it alone.
     * @param maxEdits - how many edits to save at most.
     */
    run(titles: Array<string>, summary: string, transform: (text: string, title: string) => Awaitable<Nullable<string>>, maxEdits?: Nullable<number>): Promise<RunReport>;
    /**
     * Aborted when the run is cancelled, for handing to `fetch` and the like.
     */
    get signal(): AbortSignal;
    /**
     * The wiki this bot's account edits, shared with every bot on the account.
     */
    get wiki(): Wiki;
    /**
     * What the run was started with, after the flags.
     */
    get arguments(): Array<string>;
    /**
     * Whether the run was told to save.
     */
    get save(): boolean;
    /**
     * Where this run may write files, or `null` where there is no filesystem.
     */
    get directory(): Nullable<string>;
    /**
     * The fleet's stop page, to give a run as its `stopPolicy`.
     */
    get stopPolicy(): StopPolicy;
    /**
     * The common flags this run was started with.
     */
    get options(): CommonOptions;
    /**
     * Hand this to a run's `onOutcome`, which counts each page and keeps the heartbeat going.
     */
    get outcomes(): (outcome: PageOutcome) => void;
}
export declare namespace RunContext {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RunContext;
    }
}
/**
 * One run, as a fleet sees it.
 */
export declare class FleetRun {
    private constructor();
    /**
     * What the fleet calls it.
     */
    get id(): number;
    /**
     * Whose it is.
     */
    get bot(): string;
    /**
     * `"queued"`, `"running"`, `"waiting"`, `"done"`, `"failed"`, `"cancelled"` or `"declined"`.
     */
    get status(): RunStatus;
    /**
     * How many items it said it had to do.
     */
    get expected(): number;
    /**
     * How many pages it has handled.
     */
    get seen(): number;
    /**
     * How many of them it saved.
     */
    get saved(): number;
    /**
     * Why it stopped before doing everything, if it did.
     */
    get stoppedBecause(): Nullable<string>;
    /**
     * What went wrong, if something did.
     */
    get failure(): Nullable<string>;
    /**
     * Whether the run is over, however it ended.
     */
    get isFinished(): boolean;
}
export declare namespace FleetRun {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => FleetRun;
    }
}
/**
 * Something a fleet did: a run said a line, or changed status.
 */
export declare class FleetEvent {
    private constructor();
    /**
     * Which run it is about.
     */
    get run(): number;
    /**
     * Whose run that is.
     */
    get bot(): string;
    /**
     * What the run said, or `null` for a change of status.
     */
    get message(): Nullable<string>;
    /**
     * What the run's status became, or `null` for something said.
     */
    get status(): Nullable<RunStatus>;
    /**
     * `"said"` for a line, `"changed"` for a change of status.
     */
    get kind(): FleetEventKind;
    /**
     * The phases the run was in when it said this, outermost first, or `null` for a change of status.
     */
    get steps(): Nullable<Array<string>>;
    /**
     * How much what the run said matters, `"info"`, `"saved"`, `"skipped"` or `"error"`, or `null` for a
     * change of status.
     */
    get level(): Nullable<RunLevel>;
}
export declare namespace FleetEvent {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => FleetEvent;
    }
}
/**
 * Many bots in one process, one lane each: bots run at the same time as each other, and one at a time within
 * a bot. What goes wrong in a run stays in that run. JavaScript has one thread, so lanes take turns at each
 * `await`, and a transform that blocks holds them all.
 */
export declare class Fleet {
    private constructor();
    /**
     * Queues a run of the bot called [id] in its lane.
     * 
     * @param id - which bot.
     * @param arguments - what to hand it.
     * @param save - whether it may save. A dry run unless told.
     * @returns what to call the run.
     */
    start(id: string, arguments?: Array<string>, save?: boolean): number;
    /**
     * Stops a run, and says whether there was such a run still to stop.
     */
    cancel(run: number): boolean;
    /**
     * Every run asked for, oldest first.
     */
    runs(): Array<FleetRun>;
    /**
     * What is happening, as it happens. A walk sees only what follows its start.
     */
    events(): AsyncIterable<FleetEvent>;
    /**
     * Resolves once every run asked for so far has ended.
     */
    idle(): Promise<void>;
    /**
     * Runs each bot that has a schedule when it comes round, until the fleet is closed.
     */
    serve(): Promise<void>;
    /**
     * Stops every run and closes every account.
     */
    close(): void;
    /**
     * Queues a run of the bot called [id] in its lane, with the common flags and the rest of its command
     * line.
     * 
     * @returns what to call the run.
     */
    startWith(id: string, options?: Nullable<CommonOptions>, arguments?: Array<string>): number;
    /**
     * Every run, each time one changes, starting with how they are now.
     */
    watchRuns(): AsyncIterable<Array<FleetRun>>;
    /**
     * Runs each bot that has a schedule when it comes round, with the common flags, until the fleet is
     * closed.
     */
    serveWith(options: CommonOptions): Promise<void>;
    /**
     * The bots this fleet runs.
     */
    get registry(): BotRegistry;
}
export declare namespace Fleet {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Fleet;
    }
}
/**
 * Wikidata's query service.
 */
export declare const WIKIDATA_QUERY_SERVICE: string;
/**
 * The rows a SPARQL `SELECT` [query] gives at [endpoint], Wikidata's query service unless another is named,
 * asked as the tool whose operator [contact] reaches.
 */
export declare function sparql(query: string, contact: string, endpoint?: string): Promise<Array<SparqlRow>>;
/**
 * One row of a SPARQL result.
 */
export declare class SparqlRow {
    private constructor();
    /**
     * What variable [name], without its `?`, is bound to, or `null` when it is unbound in this row.
     */
    get(name: string): Nullable<SparqlValue>;
    /**
     * The variables bound in this row.
     */
    get names(): Array<string>;
}
export declare namespace SparqlRow {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SparqlRow;
    }
}
/**
 * What a variable is bound to.
 */
export declare class SparqlValue {
    private constructor();
    /**
     * `"uri"`, `"literal"` or `"bnode"`.
     */
    get type(): SparqlValueType;
    /**
     * The URI, the literal's text, or the blank node's label.
     */
    get value(): string;
    /**
     * The literal's language, or `null`.
     */
    get language(): Nullable<string>;
    /**
     * The literal's data type, or `null`.
     */
    get dataType(): Nullable<string>;
    /**
     * The Wikibase entity id when the value is one's URI, such as `"Q42"`, or `null`.
     */
    get entityId(): Nullable<string>;
}
export declare namespace SparqlValue {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SparqlValue;
    }
}
/**
 * Reads [text] as a page title the way MediaWiki does, with the namespaces every MediaWiki has, under their
 * English names. `Wiki.parseTitle` reads one with a wiki's own namespaces.
 */
export declare function parseTitle(text: string): Title;
/**
 * The times of the signatures in [text], in the order they appear, read with the formats of [language] in
 * UTC. `Wiki.signatureTimes` reads them in a wiki's own time zone and with its own month names.
 */
export declare function signatureTimes(text: string, language?: string): Array<SignatureTime>;
/**
 * A page title, read the way MediaWiki reads one.
 */
export declare class Title {
    private constructor();
    /**
     * The wikitext of a link to this page, showing [text] when one is given, or `null` unless it is local.
     * [forced] writes the leading colon a category or file needs to be linked to rather than used.
     */
    render(text?: Nullable<string>, forced?: boolean): Nullable<string>;
    /**
     * The wikitext of a link showing only the part of the title before a comma or bracket, as the pipe trick
     * does, or `null` unless it is local.
     */
    renderPiped(): Nullable<string>;
    /**
     * The title in full, its fragment included: namespace prefix and text, or the interwiki prefix and the
     * rest, or as given when it is invalid.
     */
    toString(): string;
    /**
     * `"local"` for a page of this wiki, `"interwiki"` for one on another, `"invalid"` for no page at all.
     */
    get kind(): TitleKind;
    /**
     * The namespace number, or `null` unless the title is local.
     */
    get namespace(): Nullable<number>;
    /**
     * The title without its namespace prefix, normalised, or `null` unless it is local.
     */
    get text(): Nullable<string>;
    /**
     * The interwiki prefix, or `null` unless the title is an interwiki one.
     */
    get interwiki(): Nullable<string>;
    /**
     * What follows `#`, or `null`.
     */
    get fragment(): Nullable<string>;
    /**
     * Why the title is invalid, such as `"illegal-character"`, or `null` when it is valid.
     */
    get invalidReason(): Nullable<TitleProblem>;
    /**
     * The text after an interwiki prefix, which is the other wiki's title, or `null` unless interwiki.
     */
    get rest(): Nullable<string>;
    /**
     * The text as it was given, before any normalising, or `null` unless the title is invalid.
     */
    get raw(): Nullable<string>;
}
export declare namespace Title {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Title;
    }
}
/**
 * The time of one signature, and where it is in the text.
 */
export declare class SignatureTime {
    private constructor();
    /**
     * When it was signed.
     */
    get date(): Date;
    /**
     * The timestamp as written.
     */
    get text(): string;
    /**
     * Where the timestamp starts in the text, counted in UTF-16 code units as JavaScript counts them.
     */
    get start(): number;
    /**
     * Where the timestamp ends, just past its last code unit.
     */
    get end(): number;
    /**
     * The language whose format matched.
     */
    get language(): string;
}
export declare namespace SignatureTime {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SignatureTime;
    }
}
/**
 * Opens the wiki at [server], such as `"en.wiktionary.org"`, and reads its site information.
 * 
 * [contact] is a URL or email address the wiki's operators can reach you at, which Wikimedia's user-agent
 * policy asks for. With a [login], the wiki is opened logged in. [scriptPath] is where `api.php` is under the
 * server, `"/w"` as on Wikimedia's wikis unless given.
 * 
 * In a browser, an anonymous request to a wiki on another origin sends `origin=*`, which MediaWiki answers
 * for any page. A logged-in one needs the wiki to allow the page's origin.
 */
export declare function connect(server: string, contact: string, login?: Nullable<BotPassword>, scriptPath?: string): Promise<Wiki>;
/**
 * Finds where the API of the wiki at [url], any of its pages, is, from what the page advertises in its head.
 * `null` when it advertises none, or when the page cannot be fetched.
 */
export declare function detect(url: string, contact: string): Promise<Nullable<Endpoint>>;
/**
 * A bot password from Special:BotPasswords, to log in with.
 */
export declare class BotPassword {
    constructor(account: string, botName: string, password: string);
    /**
     * The account the bot password belongs to.
     */
    get account(): string;
    /**
     * The bot password's name.
     */
    get botName(): string;
}
export declare namespace BotPassword {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => BotPassword;
    }
}
/**
 * Where a wiki's API is.
 */
export declare class Endpoint {
    private constructor();
    /**
     * The URL of the page [title], as it appears in a browser.
     */
    articleUrl(title: string): string;
    /**
     * The URL of `api.php`.
     */
    get apiUrl(): string;
    /**
     * The host the wiki is on, such as `"en.wiktionary.org"`.
     */
    get server(): string;
    /**
     * Where `api.php` is under the server, such as `"/w"`, or `""` when it is at the root.
     */
    get scriptPath(): string;
    /**
     * Whether it speaks HTTPS.
     */
    get secure(): boolean;
    /**
     * The URL of `index.php`, for permalinks and links a person follows.
     */
    get indexUrl(): string;
}
export declare namespace Endpoint {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Endpoint;
    }
}
/**
 * An open wiki. Close it when done, which ends what it is still fetching.
 */
export declare class Wiki {
    private constructor();
    /**
     * The full title [raw] names on this wiki, normalised and with its prefix, as a page of the namespace
     * numbered [defaultNamespace] when it has no prefix of its own. Throws when it is not a title, or names a
     * page on another wiki.
     */
    ref(raw: string, defaultNamespace?: Nullable<number>): string;
    /**
     * Reads [text] as a title with this wiki's namespaces, as a page of the namespace numbered
     * [defaultNamespace] when it has no prefix of its own.
     */
    parseTitleIn(text: string, defaultNamespace: number): Title;
    /**
     * Sends [params] to `api.php` and answers with the response as plain JSON, for the actions this library
     * does not model. Paced, retried and sent as this wiki's session is.
     * 
     * [params] has to name an `action`. A string goes as it is, a number as its digits, a date as a
     * timestamp, an array joined with `|`, and `true` as `1`; `false`, `null` and `undefined` leave the
     * parameter out, as MediaWiki reads a flag by its presence.
     * 
     * An error from the wiki is an answer here: it comes back as the response's `error`, for the caller to
     * read, rather than as a rejection. A write has to carry a token from [tokens].
     */
    call(params: Record<string, ApiValue>, options?: Nullable<CallOptions>): Promise<Record<string, JsonValue>>;
    /**
     * Sends a read [params] as [call] does and answers with each batch of the response, following `continue`
     * as the walk asks. An error from the wiki rejects the walk: a query that fails halfway is not a short
     * result.
     * 
     * @param params - what to send, as [call] takes it.
     * @param maxBatches - a ceiling on the round trips; none unless given.
     * @param list - the `list=` module, to walk the entries it lists in place of whole batches.
     * @param pages - whether to walk the page entries of a `prop=` query in place of whole batches. A page can
     * arrive in several batches, each with different properties.
     */
    callPaged(params: Record<string, ApiValue>, maxBatches?: Nullable<number>, list?: Nullable<string>, pages?: boolean): AsyncIterable<Record<string, JsonValue>>;
    /**
     * How this wiki's wikitext parses: its extension tags, URL schemes and File namespace names. Its `parse`
     * reads a page as this wiki reads it.
     */
    parseOptions(): Promise<ParseOptions>;
    /**
     * How this wiki names pages: its namespace names, which namespaces keep case, its parser functions and
     * variables. Tells templates apart as this wiki does.
     */
    titleRules(): Promise<TitleRules>;
    /**
     * The account the wiki sees now, asked afresh.
     */
    whoami(): Promise<User>;
    /**
     * Reads [text] as a title with this wiki's namespaces.
     */
    parseTitle(text: string): Title;
    /**
     * The current text of [title], or `null` when there is no such page.
     */
    page(title: string): Promise<Nullable<Page>>;
    /**
     * The current text of each of [titles] that exists, in as few requests as the wiki allows.
     */
    pages(titles: Array<string>): Promise<Array<Page>>;
    /**
     * Saves [text] to [title] with [summary], and says how the wiki answered: a refusal is a result, not an
     * error.
     * 
     * [baseRevision] is the revision the text was computed from, a page's `revision`. When the page has
     * changed since, the wiki merges the two edits or, when it cannot, refuses this one as a conflict instead
     * of overwriting the other. A logged-in bot's edits are marked as a bot's.
     */
    edit(title: string, text: string, summary: string, baseRevision?: Nullable<number>, minor?: boolean): Promise<EditResult>;
    /**
     * The titles in [category], which needs no `Category:` prefix.
     */
    categoryMembers(category: string): AsyncIterable<string>;
    /**
     * The titles of the pages a search for [query] finds, best match first.
     */
    search(query: string): AsyncIterable<string>;
    /**
     * The titles of the pages that transclude [template], which needs no `Template:` prefix.
     */
    embeddedIn(template: string): AsyncIterable<string>;
    /**
     * The revisions of [title], newest first.
     */
    history(title: string): AsyncIterable<Revision>;
    /**
     * The times of the signatures in [text], read in this wiki's time zone and with its month names.
     */
    signatureTimes(text: string): Promise<Array<SignatureTime>>;
    /**
     * The Wikibase entity [id], such as `"Q42"`, `"P31"` or `"L7"`, or `null` when there is none.
     */
    entity(id: string): Promise<Nullable<Entity>>;
    /**
     * Every Wikimedia wiki, as the SiteMatrix extension on this wiki lists them.
     */
    siteMatrix(): Promise<SiteMatrix>;
    /**
     * How this wiki's signatures are written, with its month names and in its time zone.
     */
    signatures(): Promise<Signatures>;
    /**
     * The month names this wiki writes, lowercased, with their numbers.
     */
    monthNames(): Promise<Record<string, number>>;
    /**
     * How this wiki titles its pages about a calendar day, or `null` for a language with no format.
     */
    dayTitleFormat(): Promise<Nullable<DayFormat>>;
    /**
     * The names [template] can be called by on this wiki: its own and every redirect's, to give to
     * `Wikitext.templatesNamed`.
     */
    templateNames(template: string): Promise<Array<string>>;
    /**
     * Whether the account now holds [right], asked afresh.
     */
    hasRight(right: string): Promise<boolean>;
    /**
     * Rejects unless the account now holds [right].
     */
    requireRight(right: string): Promise<void>;
    /**
     * Throws unless the account was in [group] when the wiki was opened.
     */
    requireGroup(group: string): void;
    /**
     * Whether the wiki has [extension], named as MediaWiki reports it.
     */
    hasExtension(extension: string): boolean;
    /**
     * Throws unless the wiki has [extension], named as MediaWiki reports it.
     */
    requireExtension(extension: string): void;
    /**
     * Whether the wiki runs MediaWiki [version], such as `"1.44.0"`, or newer. A version it cannot read is
     * older.
     */
    hasVersion(version: string): boolean;
    /**
     * Throws unless the wiki runs MediaWiki [version], such as `"1.44.0"`, or newer.
     */
    requireVersion(version: string): void;
    /**
     * Runs a bot over [titles]: reads each page, gives its text and title to [transform], and saves the text
     * that comes back with [summary]. The transform returns `null` to leave a page alone, and may return a
     * promise of either.
     * 
     * A dry run, which only computes each edit, unless [save] is `true`. [onChange] is called with each
     * change as the run computes it, or saves it when it saves. A page the wiki refuses, or cannot serve, is
     * a problem in the report; a transform that throws, or answers with anything else, stops the run, which
     * rejects with that error.
     * 
     * [stopPage] names a page of the wiki that has to say `false` for the run to save, so that it can be
     * stopped from the wiki. [maxEdits] caps how many edits it saves.
     */
    run(titles: Array<string>, summary: string, transform: (text: string, title: string) => Awaitable<Nullable<string>>, save?: boolean, onChange?: Nullable<(change: Change) => void>, stopPage?: Nullable<string>, maxEdits?: Nullable<number>): Promise<RunReport>;
    /**
     * Runs a bot as [options] describes, with every setting a run has, and answers with the whole report:
     * reads each page of its `source`, gives it to its `transform`, and saves the edit that comes back. A dry
     * run unless told otherwise. Honours `{{nobots}}` and `{{bots}}` as this account unless told otherwise.
     * 
     * A page the wiki refuses, or cannot serve, is a problem in the report. A transform that throws anything
     * but what `skipPage` throws stops the run, which rejects with that error.
     */
    botRun(options: BotRunOptions): Promise<BotReport>;
    /**
     * A stop policy on page [title] of this wiki, by convention `User:<account>/Stop`, which is the default.
     * The page has to exist and say `false` for the bot to run, so an administrator stops it by editing the
     * page, and a bot whose page was never made does not start.
     */
    stopPage(title?: Nullable<string>): StopPolicy;
    /**
     * Ends what this wiki is still fetching and releases its connections.
     */
    close(): void;
    /**
     * The wiki's database name, such as `"enwiktionary"`.
     */
    get id(): string;
    /**
     * The wiki's name, such as `"Wiktionary"`.
     */
    get siteName(): string;
    /**
     * The MediaWiki version the wiki runs, such as `"1.48.0-wmf.1"`.
     */
    get mediaWikiVersion(): string;
    /**
     * Everything the wiki can be asked to list.
     */
    get lists(): ListService;
    /**
     * The history of pages.
     */
    get revisions(): RevisionService;
    /**
     * What has happened on the wiki: its logs and its recent changes.
     */
    get logs(): LogService;
    /**
     * The people editing the wiki.
     */
    get users(): UserService;
    /**
     * Reading and writing pages, everything an edit can say included. The name `pages` is the older way to
     * read many pages at once.
     */
    get pagesService(): PageService;
    /**
     * Files: what is known about them, where they are used, and how to add one.
     */
    get files(): FileService;
    /**
     * What the wiki says about itself beyond its site information: its messages, magic words and counters.
     */
    get meta(): MetaService;
    /**
     * The wiki's own view of a page: what it renders, and what it resolves to.
     */
    get render(): RenderService;
    /**
     * What the wiki can do only because an extension is installed.
     */
    get extensions(): ExtensionService;
    /**
     * Wikisource's scanned pages. Needs the ProofreadPage extension.
     */
    get proofread(): ProofreadService;
    /**
     * What the wiki says its own API accepts.
     */
    get paramInfo(): ParamInfo;
    /**
     * The tokens writes need, for actions this library does not model, sent through [call].
     */
    get tokens(): TokenStore;
    /**
     * The Wikibase repository this wiki is: reading, searching and writing entities and statements.
     */
    get entities(): EntityService;
    /**
     * What the wiki said about itself when it was opened.
     */
    get info(): SiteInfo;
    /**
     * Who the wiki saw when it was opened, with what [user] leaves out.
     */
    get identity(): Identity;
    /**
     * The wiki's namespaces.
     */
    get namespaces(): Namespaces;
    /**
     * The wiki's interwiki prefixes.
     */
    get interwiki(): Interwiki;
    /**
     * Where the wiki's API is.
     */
    get endpoint(): Endpoint;
    /**
     * Reads what [call] and [callPaged] answer into this package's own values.
     */
    get decoder(): ResponseDecoder;
    /**
     * The account the wiki saw when it was opened.
     */
    get user(): User;
}
export declare namespace Wiki {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Wiki;
    }
}
/**
 * A page's current text and the revision it is.
 */
export declare class Page {
    private constructor();
    /**
     * The text, parsed.
     */
    wikitext(): Wikitext;
    /**
     * The page's title, prefix included.
     */
    get title(): string;
    /**
     * The text.
     */
    get text(): string;
    /**
     * The revision this text is, to pass back to `edit` as its base revision.
     */
    get revision(): number;
    /**
     * When the revision was saved, or `null` when the wiki did not say.
     */
    get timestamp(): Nullable<Date>;
    /**
     * Whether the page is a redirect.
     */
    get isRedirect(): boolean;
    /**
     * Where the page redirects, which may be on another wiki, or `null` when it is not a redirect.
     */
    get redirectTarget(): Nullable<string>;
    /**
     * What kind of content the text is, such as `"wikitext"`, `"json"` or `"Scribunto"`.
     */
    get contentModel(): string;
    /**
     * The wiki's own id for the page, or `null` when the wiki did not say.
     */
    get pageId(): Nullable<number>;
}
export declare namespace Page {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Page;
    }
}
/**
 * How the wiki answered an edit.
 */
export declare class EditResult {
    private constructor();
    /**
     * `"saved"`, `"unchanged"`, or why the wiki refused: `"conflict"`, `"protected"`, `"permission-denied"`,
     * `"rate-limited"`, `"filtered"`, `"captcha"`, `"page-changed"` or `"rejected"`.
     */
    get status(): EditStatus;
    /**
     * The revision saved, or the current one when nothing changed and the wiki said which; `null` when
     * refused.
     */
    get revision(): Nullable<number>;
    /**
     * The revision the save replaced, or `null`.
     */
    get previousRevision(): Nullable<number>;
    /**
     * What the wiki said when it refused, or `null`.
     */
    get detail(): Nullable<string>;
    /**
     * Whether trying again, after reloading or after a wait, could succeed. `false` unless refused.
     */
    get isRetryable(): boolean;
    /**
     * On a `"conflict"`, the revision the page is on now, when the wiki said.
     */
    get currentRevision(): Nullable<number>;
    /**
     * On a `"protected"`, the protection level that blocked the edit, when the wiki named one.
     */
    get protectionLevel(): Nullable<string>;
    /**
     * On a `"protected"`, whether the protection comes from a page transcluding this one.
     */
    get isCascadingProtection(): Nullable<boolean>;
    /**
     * On a `"filtered"`, the filter name or blacklist entry, when the wiki named one.
     */
    get filter(): Nullable<string>;
    /**
     * On a `"rate-limited"`, how long the wiki asked to wait, in milliseconds, when it said.
     */
    get retryAfter(): Nullable<number>;
    /**
     * On a `"captcha"`, the captcha the wiki wants solved, when it said.
     */
    get captchaId(): Nullable<string>;
    /**
     * On a `"page-changed"`, whether the page was deleted, where `false` covers both a page never created and
     * one a `createOnly` found existing.
     */
    get wasDeleted(): Nullable<boolean>;
    /**
     * On a `"rejected"`, the wiki's code for a refusal this library does not model.
     */
    get code(): Nullable<string>;
}
export declare namespace EditResult {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => EditResult;
    }
}
/**
 * One revision of a page.
 */
export declare class Revision {
    private constructor();
    /**
     * The revision's id.
     */
    get id(): number;
    /**
     * The revision before it, or `null` for a page's first.
     */
    get parentId(): Nullable<number>;
    /**
     * When it was saved.
     */
    get timestamp(): Date;
    /**
     * Who saved it, or `null` when that is hidden.
     */
    get user(): Nullable<string>;
    /**
     * Its edit summary, or `null` when that is hidden.
     */
    get comment(): Nullable<string>;
    /**
     * The page's size after it, in bytes.
     */
    get size(): number;
    /**
     * Whether it was marked minor.
     */
    get minor(): boolean;
    /**
     * Whether it was marked as a bot's.
     */
    get bot(): boolean;
    /**
     * The change tags on it.
     */
    get tags(): Array<string>;
    /**
     * The hash of its content, which tells two revisions apart without reading them, or `null`.
     */
    get sha1(): Nullable<string>;
    /**
     * Whether who saved it is hidden.
     */
    get isUserHidden(): boolean;
    /**
     * Whether its edit summary is hidden.
     */
    get isCommentHidden(): boolean;
}
export declare namespace Revision {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Revision;
    }
}
/**
 * An account, or an anonymous user.
 */
export declare class User {
    private constructor();
    /**
     * The account name, or the address an anonymous user edits from.
     */
    get name(): string;
    /**
     * The groups the account is in, `"*"` included.
     */
    get groups(): Array<string>;
    /**
     * The rights the account has.
     */
    get rights(): Array<string>;
    /**
     * Whether the user is not logged in.
     */
    get isAnonymous(): boolean;
}
export declare namespace User {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => User;
    }
}
/**
 * The prose a reader sees: not templates, link targets, comments or nowiki.
 */
export declare const TEXT_SCOPE_PROSE: TextScopeOptions;
/**
 * Every piece of text, wherever it sits.
 */
export declare const TEXT_SCOPE_EVERYWHERE: TextScopeOptions;
/**
 * Parses [text] as MediaWiki does on Wikimedia's wikis.
 * 
 * Parsing touches no network, and the result writes back as exactly [text] until something in it is changed.
 */
export declare function parse(text: string): Wikitext;
/**
 * Parsed wikitext. Every change returns a new one and leaves this one as it was.
 */
export declare class Wikitext {
    private constructor();
    /**
     * The templates, outermost first, or only those whose name is [name] when one is given.
     */
    templates(name?: Nullable<string>): Array<Template>;
    /**
     * The links to other pages of the wiki, in the order written.
     */
    links(): Array<Link>;
    /**
     * The sections, as a tree under the lead, which is everything before the first heading.
     */
    outline(): Section;
    /**
     * The names of the categories the page is in, without the namespace prefix.
     */
    categories(): Array<string>;
    /**
     * This text in category [name] as well, sorted under [sortKey] when one is given.
     */
    addCategory(name: string, sortKey?: Nullable<string>): Wikitext;
    /**
     * This text out of category [name].
     */
    removeCategory(name: string): Wikitext;
    /**
     * This text with each template named [name] replaced by what [transform] makes of it.
     */
    mapTemplates(name: string, transform: (template: Template) => Template): Wikitext;
    /**
     * This text with [old], one of its templates, replaced by [replacement].
     */
    replaceTemplate(old: Template, replacement: Template): Wikitext;
    /**
     * This text with [old], one of its sections, replaced by [replacement].
     */
    replaceSection(old: Section, replacement: Section): Wikitext;
    /**
     * This text with every match of [pattern] replaced by [replacement], which is taken as it is: `$1` in it
     * is a dollar sign and a one.
     * 
     * [scope] is `"prose"`, the text a reader sees, which leaves templates, link targets, comments and nowiki
     * alone; or `"everywhere"`. The pattern's `i` and `m` flags apply; `s` and `y` have no equivalent here
     * and are refused.
     */
    replaceText(pattern: RegExp, replacement: string, scope?: TextScopeName): Wikitext;
    /**
     * This text with every match of [pattern] replaced by [replacement], taken as it is, in the regions
     * [scope] opens up. The pattern's `i` and `m` flags apply; `s` and `y` are refused.
     */
    replaceTextIn(pattern: RegExp, replacement: string, scope: TextScopeOptions): Wikitext;
    /**
     * This text with every match of [pattern] replaced by what [replacement] makes of it, in the regions
     * [scope] opens up, the prose a reader sees unless given. The pattern's `i` and `m` flags apply; `s` and
     * `y` are refused.
     */
    replaceTextWith(pattern: RegExp, replacement: (match: TextMatch) => string, scope?: Nullable<TextScopeOptions>): Wikitext;
    /**
     * Whether any text in the regions [scope] opens up, the prose unless given, matches [pattern].
     */
    containsText(pattern: RegExp, scope?: Nullable<TextScopeOptions>): boolean;
    /**
     * The text in the regions [scope] opens up, the prose unless given, node by node.
     */
    textIn(scope?: Nullable<TextScopeOptions>): Array<string>;
    /**
     * Every node, those nested inside templates, links and tags included.
     */
    allNodes(): Array<Node>;
    /**
     * The templates that transclude one of [names] under [rules], nested ones included, Wikimedia's rules
     * unless given. Each name is read as a page, so `l`, `Template:l` and `{{subst:l}}` all name
     * `Template:L`.
     */
    templatesNamed(names: Array<string>, rules?: Nullable<TitleRules>): Array<Template>;
    /**
     * The headings that open sections, not those inside a template parameter, a link or a `<ref>`.
     */
    headings(): Array<Heading>;
    /**
     * The tags, nested ones included, or only those called [name] when one is given.
     */
    tags(name?: Nullable<string>): Array<Tag>;
    /**
     * The comments, nested ones included.
     */
    comments(): Array<Comment>;
    /**
     * This text with every occurrence of [target], nested ones included, replaced by [replacement].
     */
    replace(target: Node, replacement: Node): Wikitext;
    /**
     * This text without [target].
     */
    remove(target: Node): Wikitext;
    /**
     * This text with every template replaced by what [transform] makes of it.
     */
    mapAllTemplates(transform: (template: Template) => Template): Wikitext;
    /**
     * This text with every template transcluding one of [names] under [rules] replaced by [transform]'s.
     */
    mapTemplatesNamed(names: Array<string>, transform: (template: Template) => Template, rules?: Nullable<TitleRules>): Wikitext;
    /**
     * This text with every node rewritten by [transform], those inside it first. It does not go into a tag
     * MediaWiki does not parse, nor `<code>`.
     */
    mapNodes(transform: (node: Node) => Node): Wikitext;
    /**
     * This text followed by [other].
     */
    plus(other: Wikitext): Wikitext;
    /**
     * Where each piece of this text sits in what it writes back as. Ask with what a query here returned.
     */
    ranges(): SourceRanges;
    /**
     * The links filing the page in a category, or naming one, under [prefixes], `"Category"` unless given.
     */
    categoryLinks(prefixes?: Nullable<Array<string>>): Array<Link>;
    /**
     * The names of the page's categories under [prefixes], without the prefix or a sort key.
     */
    categoryNamesIn(prefixes: Array<string>): Array<string>;
    /**
     * The language links, `[[fr:Foo]]` and friends, among the language [codes] this wiki recognises.
     */
    languageLinks(codes: Array<string>): Array<Link>;
    /**
     * This text in category [name] as well, written with the namespace name [prefix] the wiki uses.
     */
    addCategoryAs(name: string, prefix: string, sortKey?: Nullable<string>): Wikitext;
    /**
     * This text out of category [name], which it names with the namespace name [prefix].
     */
    removeCategoryAs(name: string, prefix: string): Wikitext;
    /**
     * This text with category [from] replaced by [to], keeping the sort key.
     */
    changeCategory(from: string, to: string, prefix?: string): Wikitext;
    /**
     * This text with the language links among [codes] sorted, by [order] when given and else by code. Only
     * the links move.
     */
    sortLanguageLinks(codes: Array<string>, order?: Nullable<(a: string, b: string) => number>): Wikitext;
    /**
     * This text with [old] replaced by [replacement], read back as a wiki with [options] reads it.
     */
    replaceSectionWith(old: Section, replacement: Section, options: ParseOptions): Wikitext;
    /**
     * Whether [other] is the same wikitext, node for node.
     */
    equals(other: Nullable<any>): boolean;
    /**
     * A hash that agrees with [equals].
     */
    hashCode(): number;
    /**
     * The wikitext, byte for byte as it was parsed apart from what was changed.
     */
    toString(): string;
    /**
     * The visible text, with markup removed: what a reader sees. For comparing content, never for writing
     * back.
     */
    get text(): string;
    /**
     * The top-level nodes, in the order written.
     */
    get nodes(): Array<Node>;
}
export declare namespace Wikitext {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Wikitext;
    }
}
/**
 * A template, or a parser function, and the parameters it was given.
 */
export declare class Template {
    private constructor();
    /**
     * The value of parameter [name] as visible text, trimmed, or `null` when it is not given.
     */
    get(name: string): Nullable<string>;
    /**
     * The value of parameter [name] as written, or `null` when it is not given.
     */
    getRaw(name: string): Nullable<string>;
    /**
     * Whether parameter [name] is given.
     */
    has(name: string): boolean;
    /**
     * This template with parameter [name] set to [value], in place when it was given and last when not.
     */
    withParameter(name: string, value: string): Template;
    /**
     * This template without parameter [name].
     */
    withoutParameter(name: string): Template;
    /**
     * The parameter [name], or `null` when it is not given.
     */
    parameter(name: string): Nullable<Parameter>;
    /**
     * Whether this is a parser function or a variable under [rules].
     */
    isParserFunctionIn(rules: TitleRules): boolean;
    /**
     * The parser function or variable this calls, under [rules].
     */
    functionNameIn(rules: TitleRules): Nullable<string>;
    /**
     * What a parser function is given after its colon, under [rules].
     */
    functionArgumentIn(rules: TitleRules): Nullable<string>;
    /**
     * The page this transcludes, normalised under [rules], or `null` when it transcludes none.
     */
    keyIn(rules: TitleRules): Nullable<string>;
    /**
     * The template as wikitext.
     */
    toString(): string;
    /**
     * The name as MediaWiki reads it: comments removed and whitespace trimmed, the first letter as written.
     */
    get name(): string;
    /**
     * The name normalised the way MediaWiki resolves it, for comparing: `{{foo_bar}}` and `{{Template:Foo
     * bar}}` are both `"Foo bar"`, and a template from another namespace keeps its prefix, `":Foo"` for the
     * main one. `null` for a parser function or a variable.
     */
    get key(): Nullable<string>;
    /**
     * The parameters, in the order written.
     */
    get parameters(): Array<Parameter>;
    /**
     * The name as written, comments and whitespace included.
     */
    get rawName(): string;
    /**
     * The name as wikitext, which may itself contain markup.
     */
    get nameMarkup(): Wikitext;
    /**
     * Whether this is a parser function or a variable, such as `{{#if:...}}` or `{{PAGENAME}}`.
     */
    get isParserFunction(): boolean;
    /**
     * The parser function or variable this calls, such as `#switch` or `PAGENAME`; `null` for a template.
     */
    get functionName(): Nullable<string>;
    /**
     * What a parser function is given after its colon, such as `sa-decl` in `{{#invoke:sa-decl|show}}`.
     */
    get functionArgument(): Nullable<string>;
    /**
     * This template as a node, to give to `Wikitext.replace` and the like.
     */
    get node(): Node;
}
export declare namespace Template {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Template;
    }
}
/**
 * One parameter of a template.
 */
export declare class Parameter {
    private constructor();
    /**
     * The parameter as wikitext.
     */
    toString(): string;
    /**
     * The name as MediaWiki reads it, or for a positional parameter its number.
     */
    get name(): string;
    /**
     * Whether the name was written, as in `name=value`, rather than implied by position.
     */
    get named(): boolean;
    /**
     * The value as visible text, trimmed.
     */
    get value(): string;
    /**
     * The value as written, whitespace, comments and markup included.
     */
    get rawValue(): string;
    /**
     * The name as written, or `null` for a positional parameter, which has none written.
     */
    get rawName(): Nullable<string>;
    /**
     * The name as wikitext, which for a positional parameter is its number.
     */
    get nameMarkup(): Wikitext;
    /**
     * The value as wikitext.
     */
    get valueMarkup(): Wikitext;
}
export declare namespace Parameter {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Parameter;
    }
}
/**
 * A link to another page of the wiki, `[[target|text]]`.
 */
export declare class Link {
    private constructor();
    /**
     * The link as wikitext.
     */
    toString(): string;
    /**
     * The page linked to, as plain text.
     */
    get target(): string;
    /**
     * What is shown in place of the target when the link is piped, as visible text, or `null`.
     */
    get text(): Nullable<string>;
    /**
     * The page linked to, as wikitext.
     */
    get targetMarkup(): Wikitext;
    /**
     * What is shown in place of the target, as wikitext, or `null`.
     */
    get textMarkup(): Nullable<Wikitext>;
    /**
     * This link as a node, to give to `Wikitext.replace` and the like.
     */
    get node(): Node;
}
export declare namespace Link {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Link;
    }
}
/**
 * A section: its heading, what is directly under it, and the sections nested under it.
 */
export declare class Section {
    private constructor();
    /**
     * The first section under this one, at any depth, headed [title], and at [level] when one is given.
     */
    find(title: string, level?: Nullable<number>): Nullable<Section>;
    /**
     * This section with what is directly under its heading replaced by [content].
     */
    withContent(content: Wikitext): Section;
    /**
     * This section and every section nested inside it, depth first.
     */
    all(): Array<Section>;
    /**
     * The sections directly under this one headed [title].
     */
    children(title: string): Array<Section>;
    /**
     * This section with [subsection] added at the end.
     */
    withSubsection(subsection: Section): Section;
    /**
     * This section with [subsection] added at [index] among its direct children. Throws outside them.
     */
    withSubsectionAt(index: number, subsection: Section): Section;
    /**
     * This section with [target] replaced by [replacement] wherever it is nested.
     */
    replace(target: Section, replacement: Section): Section;
    /**
     * The section as wikitext, heading and subsections included.
     */
    toString(): string;
    /**
     * The heading text, trimmed, or `null` for the lead.
     */
    get title(): Nullable<string>;
    /**
     * The heading level, from 1 to 6, or 0 for the lead.
     */
    get level(): number;
    /**
     * What is directly under the heading, before any subsection.
     */
    get content(): Wikitext;
    /**
     * The sections nested directly under this one.
     */
    get subsections(): Array<Section>;
    /**
     * The heading that opened this section, or `null` for the lead.
     */
    get heading(): Nullable<Heading>;
    /**
     * The nodes directly under the heading, before any subsection.
     */
    get nodes(): Array<Node>;
}
export declare namespace Section {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Section;
    }
}
/**
 * The file name a configuration is looked for under.
 */
export declare const BOT_CONFIG_FILE_NAME: string;
/**
 * The `maxlag` Wikimedia asks well-behaved bots to send.
 */
export declare const BOT_CONFIG_DEFAULT_MAXLAG: number;
/**
 * Makes a configuration from [init].
 */
export declare function createBotConfig(init: BotConfigInit): BotConfig;
/**
 * Reads a configuration from TOML [text]. An unknown key is logged as a warning and otherwise ignored, so a
 * file written for a newer version still runs.
 */
export declare function parseBotConfig(text: string): BotConfig;
/**
 * Reads a configuration from the file at [path]. Node only.
 */
export declare function readBotConfig(path: string): BotConfig;
/**
 * Reads the configuration at [explicit], throwing when it cannot, or when none is given finds it along
 * `botConfigSearchPath`, or `null` if there is none. Node only.
 */
export declare function findBotConfig(explicit?: Nullable<string>): Nullable<BotConfig>;
/**
 * Where a configuration is looked for, in order: the working directory, the home directory,
 * `KWIKIBOT_CONFIG`, the user configuration directory, and on Windows `%APPDATA%\kwikibot`.
 */
export declare function botConfigSearchPath(): Array<string>;
/**
 * A configuration file to start from, whose login section names an environment variable.
 */
export declare function botConfigTemplate(): string;
/**
 * How the bot identifies itself. Required by the Wikimedia user-agent policy.
 */
export declare class BotIdentity {
    constructor(name: string, contact: string, version?: string);
    /**
     * The bot's name, which goes in the user agent.
     */
    get name(): string;
    /**
     * A URL or address an operator can be reached at.
     */
    get contact(): string;
    /**
     * Its version, so an operator can tell two runs apart; `"1.0"` unless given.
     */
    get version(): string;
}
export declare namespace BotIdentity {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => BotIdentity;
    }
}
/**
 * Which wiki to work on unless a command says otherwise.
 */
export declare class WikiSelection {
    constructor(lang?: string, family?: string, server?: Nullable<string>, scriptPath?: string);
    /**
     * The language code of the wiki to work on; `"en"` unless given.
     */
    get lang(): string;
    /**
     * Its project family; `"wiktionary"` unless given.
     */
    get family(): string;
    /**
     * The host of a wiki outside Wikimedia's families, which [lang] and [family] then do not find.
     */
    get server(): Nullable<string>;
    /**
     * Where [server] keeps `api.php`; `"/w"` unless given.
     */
    get scriptPath(): string;
}
export declare namespace WikiSelection {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => WikiSelection;
    }
}
/**
 * How fast requests may go out.
 */
export declare class ThrottleSettings {
    constructor(read?: string, write?: string);
    /**
     * Between reads, as a Kotlin duration such as `"100ms"`, which is the default.
     */
    get read(): string;
    /**
     * Between writes, `"10s"` unless given, the conventional bot pace on Wikimedia wikis.
     */
    get write(): string;
}
export declare namespace ThrottleSettings {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ThrottleSettings;
    }
}
/**
 * Where the account name lives, and where its password does not.
 */
export declare class LoginSettings {
    constructor(account: string, botName: string, passwordEnv?: string, optional?: boolean);
    /**
     * The account name, without the bot-password suffix.
     */
    get account(): string;
    /**
     * The bot password's own name.
     */
    get botName(): string;
    /**
     * The name of an environment variable holding the bot password; `"KWIKIBOT_PASSWORD"` unless given.
     */
    get passwordEnv(): string;
    /**
     * Whether a run may go on anonymously when [passwordEnv] is not set; not unless given.
     */
    get optional(): boolean;
}
export declare namespace LoginSettings {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => LoginSettings;
    }
}
/**
 * An OAuth 2.0 login: an owner-only consumer's access token, which needs no login round trip.
 */
export declare class OAuthSettings {
    constructor(tokenEnv?: string, username?: Nullable<string>, optional?: boolean);
    /**
     * The name of an environment variable holding the access token; `"KWIKIBOT_OAUTH_TOKEN"` unless given.
     */
    get tokenEnv(): string;
    /**
     * The account the token belongs to, for the user agent and the logs.
     */
    get username(): Nullable<string>;
    /**
     * Whether a run may go on without it when [tokenEnv] is not set; not unless given.
     */
    get optional(): boolean;
}
export declare namespace OAuthSettings {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => OAuthSettings;
    }
}
/**
 * How a run reads and writes. Anything left out keeps the run's own default.
 */
export declare class RunSettings {
    constructor(readConcurrency?: Nullable<number>, writeConcurrency?: Nullable<number>, readBatch?: Nullable<number>);
    /**
     * How many pages, or batches, a run works on at once.
     */
    get readConcurrency(): Nullable<number>;
    /**
     * How many edits it has in flight at once.
     */
    get writeConcurrency(): Nullable<number>;
    /**
     * How many pages it reads per request.
     */
    get readBatch(): Nullable<number>;
}
export declare namespace RunSettings {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RunSettings;
    }
}
/**
 * How many requests the client has in flight, and how long a request may take.
 */
export declare class HttpSection {
    constructor(maxConcurrentRequests?: number, maxIdleConnections?: number, timeout?: string);
    /**
     * How many requests may be in flight at once, to every wiki together; 3 unless given.
     */
    get maxConcurrentRequests(): number;
    /**
     * How many idle connections are kept for reuse.
     */
    get maxIdleConnections(): number;
    /**
     * How long connecting, a request and a silent socket may each take; `"60s"` unless given.
     */
    get timeout(): string;
}
export declare namespace HttpSection {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => HttpSection;
    }
}
/**
 * How failed requests and lost sessions are retried. Anything left out keeps the client's default.
 */
export declare class RetrySettings {
    constructor(maxRetries?: Nullable<number>, initialDelay?: Nullable<string>, maxDelay?: Nullable<string>, relogins?: number);
    /**
     * How many times a request may be retried before its failure is raised.
     */
    get maxRetries(): Nullable<number>;
    /**
     * The wait before the first retry, such as `"1s"`. It doubles each time.
     */
    get initialDelay(): Nullable<string>;
    /**
     * The longest any wait may be.
     */
    get maxDelay(): Nullable<string>;
    /**
     * How many times in ten minutes a lost session is logged back into; 0 for never.
     */
    get relogins(): number;
}
export declare namespace RetrySettings {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RetrySettings;
    }
}
/**
 * Where read responses are remembered.
 */
export declare class CacheSettings {
    constructor(path?: string, ttl?: string);
    /**
     * Where on disk to keep them; `"apicache"` unless given.
     */
    get path(): string;
    /**
     * How long an entry stays usable, as a Kotlin duration; `"12h"` unless given.
     */
    get ttl(): string;
}
export declare namespace CacheSettings {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => CacheSettings;
    }
}
/**
 * A bot's configuration, as a TOML file says it, with no credential in it: the file names an environment
 * variable to read each from, so it can be committed. `parseBotConfig` and `findBotConfig` read one, and
 * `createBotConfig` makes one.
 */
export declare class BotConfig {
    private constructor();
    /**
     * What to open a client with, as this configuration describes it, for `createClient` and `connectWith`.
     * The login is left to [credentials]. With a `[cache]`, it needs a filesystem, which a browser does not
     * have.
     */
    connectOptions(): ConnectOptions;
    /**
     * The login this configuration describes, or `null` for none: an `OAuth2` when its token is set, else a
     * `BotPassword`.
     * 
     * @param environment - reads a variable, the process environment unless given.
     * @throws IllegalStateException when a login is configured and its variable is not set, and the login is
     * not marked `optional`.
     */
    credentials(environment?: Nullable<(name: string) => Nullable<string>>): Nullable<BotPassword>;
    /**
     * The family named in the file, or a failure when the file names no family there is.
     */
    family(): Family;
    /**
     * The language code named in the file.
     */
    language(): string;
    /**
     * Where the wiki's API is.
     */
    endpoint(): Endpoint;
    /**
     * Opens the wiki the file names on [client], which [connectOptions] and [credentials] make the one the
     * file describes. Closing it leaves the client open.
     */
    connect(client: WikiClient): Promise<Wiki>;
    /**
     * How the bot identifies itself.
     */
    get bot(): BotIdentity;
    /**
     * Which wiki to work on unless a command says otherwise.
     */
    get wiki(): WikiSelection;
    /**
     * How fast requests may go out.
     */
    get throttle(): ThrottleSettings;
    /**
     * A bot-password login, or `null`.
     */
    get login(): Nullable<LoginSettings>;
    /**
     * Where read responses are remembered, or `null` when they are not.
     */
    get cache(): Nullable<CacheSettings>;
    /**
     * The replication lag in seconds above which a wiki should defer requests; 0 sends no `maxlag`.
     */
    get maxlag(): number;
    /**
     * An OAuth 2.0 login, or `null`. Used in place of [login] when both are given and its token is set.
     */
    get oauth(): Nullable<OAuthSettings>;
    /**
     * How a bot run reads and writes.
     */
    get run(): RunSettings;
    /**
     * How many connections the client keeps to the wiki, and how long a request may take.
     */
    get http(): HttpSection;
    /**
     * How failed requests and lost sessions are retried.
     */
    get retry(): RetrySettings;
}
export declare namespace BotConfig {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => BotConfig;
    }
}
/**
 * What a configuration is made of, as `createBotConfig` takes it.
 */
export declare interface BotConfigInit {
    /**
     * How the bot identifies itself.
     */
    readonly bot: BotIdentity;
    /**
     * Which wiki to work on unless a command says otherwise.
     */
    readonly wiki?: Nullable<WikiSelection>;
    /**
     * How fast requests may go out.
     */
    readonly throttle?: Nullable<ThrottleSettings>;
    /**
     * A bot-password login.
     */
    readonly login?: Nullable<LoginSettings>;
    /**
     * Where read responses are remembered; nowhere unless given.
     */
    readonly cache?: Nullable<CacheSettings>;
    /**
     * The replication lag above which a wiki should defer requests, in seconds; 5 unless given.
     */
    readonly maxlag?: Nullable<number>;
    /**
     * An OAuth 2.0 login.
     */
    readonly oauth?: Nullable<OAuthSettings>;
    /**
     * How a bot run reads and writes.
     */
    readonly run?: Nullable<RunSettings>;
    /**
     * How many connections the client keeps to the wiki, and how long a request may take.
     */
    readonly http?: Nullable<HttpSection>;
    /**
     * How failed requests and lost sessions are retried.
     */
    readonly retry?: Nullable<RetrySettings>;
}
/**
 * The largest number of refusals and failures a report keeps in its `problems`.
 */
export declare const BOT_REPORT_PROBLEM_LIMIT: number;
/**
 * Leaves the page a transform is working on alone, with a reason the outcome records. Does not return.
 */
export declare function skipPage(reason: string): never;
/**
 * A function reporting each outcome to every one of [handlers], in order, as `onOutcome` takes one.
 */
export declare function reportTo(handlers: Array<(outcome: PageOutcome) => void>): (outcome: PageOutcome) => void;
/**
 * The edit a bot decided to make to one page.
 */
export declare interface PageEdit {
    /**
     * The new text: of the whole page, or of [section] when one is named.
     */
    readonly text: string;
    /**
     * The edit summary, which is what a watchlist reader sees.
     */
    readonly summary: string;
    /**
     * Whether to mark it minor; not unless given.
     */
    readonly minor?: Nullable<boolean>;
    /**
     * Change tags to mark the edit with, each one the wiki has defined for manual use.
     */
    readonly tags?: Nullable<Array<string>>;
    /**
     * The section [text] replaces, by the index `RenderService.sections` reports, or `"new"` to add one with
     * the summary as its heading. The whole page unless given.
     */
    readonly section?: Nullable<string>;
    /**
     * Whether the page must not exist yet, so the edit is refused rather than overwriting one created in the
     * meantime. Set on every edit `createMissing` makes.
     */
    readonly createOnly?: Nullable<boolean>;
}
/**
 * Everything a run can be told, as `Wiki.botRun` takes it.
 */
export declare interface BotRunOptions {
    /**
     * The pages to work through.
     */
    readonly source: PageSource;
    /**
     * Computes the edit for one page, or returns `null` to leave it alone, and may return a promise of
     * either. `skipPage` leaves it alone with a reason, which the outcome records. Throwing anything else
     * stops the run, which rejects with that error.
     */
    readonly transform: (page: Page) => Awaitable<Nullable<PageEdit>>;
    /**
     * Computes the page to create where the source names one that does not exist, or returns `null` to leave
     * it missing, and may return a promise of either. A missing page is reported as `"missing"` unless given.
     */
    readonly createMissing?: Nullable<(title: string) => Awaitable<Nullable<PageEdit>>>;
    /**
     * Whether to compute edits without sending them; `true` unless given.
     */
    readonly dryRun?: Nullable<boolean>;
    /**
     * How many pages, or batches of [readBatch] pages, to work on at once; 4 unless given.
     */
    readonly readConcurrency?: Nullable<number>;
    /**
     * How many pages to read per request; 1 unless given.
     */
    readonly readBatch?: Nullable<number>;
    /**
     * How many edits to have in flight at once; 1 unless given.
     */
    readonly writeConcurrency?: Nullable<number>;
    /**
     * Stops after this many pages, read from the source only as far as that.
     */
    readonly limit?: Nullable<number>;
    /**
     * How many edits to save at most. The run stops with `"policy"` on reaching it. A save the wiki refuses
     * still counts.
     */
    readonly maxEdits?: Nullable<number>;
    /**
     * Checked before every save and before a saving run starts. Unless given, the wiki cannot stop the run,
     * and one that saves logs a warning saying so.
     */
    readonly stopPolicy?: Nullable<StopPolicy>;
    /**
     * Whether to honour `{{nobots}}` and `{{bots}}`, and as which bot. As this account unless given; `null`
     * opts out.
     */
    readonly exclusionPolicy?: Nullable<BotPolicy>;
    /**
     * Called as each page finishes, and two calls never overlap. What it returns is not waited for: the run
     * goes on at once, so a promise it returns runs beside the run. Throwing stops the run.
     */
    readonly onOutcome?: Nullable<(outcome: PageOutcome) => void>;
    /**
     * Where the run records each outcome as it happens, so it can be resumed.
     */
    readonly state?: Nullable<RunState>;
    /**
     * Whether to carry on from where a run into the same [state] stopped; not unless given.
     */
    readonly resume?: Nullable<boolean>;
    /**
     * Flags that set whether the run saves, how far it goes, its stop page and its state. What this object
     * sets itself comes after them, and wins.
     */
    readonly common?: Nullable<CommonOptions>;
    /**
     * A configuration whose `[run]` section sets the run's concurrency and batch. What this object sets
     * itself comes after it, and wins.
     */
    readonly config?: Nullable<BotConfig>;
}
/**
 * What a run did. A caller that wants every page takes `onOutcome`.
 */
export declare class BotReport {
    private constructor();
    /**
     * The counts as a line of text.
     */
    toString(): string;
    /**
     * How many pages were handled.
     */
    get processed(): number;
    /**
     * How many edits were saved.
     */
    get saved(): number;
    /**
     * How many edits were computed but not sent, which is every edit in a dry run.
     */
    get pending(): number;
    /**
     * How many were left alone, whether deliberately or because they did not exist.
     */
    get skipped(): number;
    /**
     * How many the wiki refused.
     */
    get refused(): number;
    /**
     * How many failed for a reason that was not about the page.
     */
    get failed(): number;
    /**
     * The refusals and failures, up to `BOT_REPORT_PROBLEM_LIMIT` of them.
     */
    get problems(): Array<PageOutcome>;
    /**
     * Whether there were more refusals and failures than [problems] holds.
     */
    get problemsTruncated(): boolean;
    /**
     * Whether the run ended early.
     */
    get stopped(): boolean;
    /**
     * How many pages taken from the source were not worked on because the run had stopped.
     */
    get notAttempted(): number;
    /**
     * Why the run stopped, or `null` if it did not: `"policy"`, `"policy-unreachable"`, `"auth"`,
     * `"read-only"`, `"configuration"` or `"refused"`.
     */
    get stopReason(): Nullable<StopReason>;
    /**
     * Whether every page was handled without a refusal or a failure.
     */
    get clean(): boolean;
}
export declare namespace BotReport {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => BotReport;
    }
}
/**
 * What became of one page in a run.
 */
export declare class PageOutcome {
    private constructor();
    /**
     * `"missing"`, `"skipped"`, `"unchanged"`, `"pending"` for an edit a dry run computed, `"saved"`,
     * `"refused"`, `"failed"`, or `"not-attempted"` for a page in hand when the run stopped.
     */
    get kind(): PageOutcomeKind;
    /**
     * The page's title, prefix included.
     */
    get title(): string;
    /**
     * On a `"skipped"`, why. On a `"not-attempted"`, why the run stopped, as `BotReport.stopReason` spells
     * it.
     */
    get reason(): Nullable<string>;
    /**
     * On a `"pending"` or a `"saved"`, the edit.
     */
    get edit(): Nullable<PageEdit>;
    /**
     * On a `"pending"` or a `"saved"`, the text as it stood. Empty for a page not yet created.
     */
    get before(): Nullable<string>;
    /**
     * On a `"pending"`, the revision the edit was computed from, `null` for a page not yet created.
     */
    get baseRevision(): Nullable<number>;
    /**
     * On a `"saved"`, the revision the edit produced.
     */
    get revision(): Nullable<number>;
    /**
     * On a `"saved"`, the revision it replaced, `null` for a page the edit created.
     */
    get previousRevision(): Nullable<number>;
    /**
     * On a `"refused"`, what the wiki said, which tells a conflict from a filter.
     */
    get refusal(): Nullable<EditResult>;
    /**
     * On a `"failed"`, what went wrong, which `describeError` reads.
     */
    get error(): Nullable<Error>;
}
export declare namespace PageOutcome {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PageOutcome;
    }
}
/**
 * Thrown by `skipPage` to abandon one page with a reason.
 */
export declare class SkipPageError extends Error {
    constructor(reason: string);
    /**
     * Why the page was left alone, which the outcome records.
     */
    get reason(): string;
}
export declare namespace SkipPageError {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SkipPageError;
    }
}
/**
 * The flags, one per line, for a bot to print in its own help.
 */
export declare const COMMON_OPTIONS_USAGE: string;
/**
 * Makes the common flags from [init].
 * 
 * @throws IllegalArgumentException when `resume` has no `state`, or `trial` has no `save` and `limit`.
 */
export declare function createCommonOptions(init?: Nullable<CommonOptionsInit>): CommonOptions;
/**
 * Reads the common flags out of [args], and hands back everything else. A flag's value may follow it or be
 * joined to it with `=`, and everything after `--` is handed back untouched.
 * 
 * @throws IllegalArgumentException naming the flag, when one is missing its value or has a bad one.
 */
export declare function parseCommonOptions(args: Array<string>): ParsedCommandLine;
/**
 * Starts a bot, in Node: reads the common flags and `kwikibot.toml`, logs in, checks the account, runs
 * [block] with the wiki, and exits with the status [block] answers, a number or a promise of one, or 0 for
 * neither. Returns at once.
 * 
 * With `--save`, [block] runs only if the account is in the bot group, or `--trial` says it is saving the
 * trial edits a wiki asks for first. When the bot cannot start, the reason is one line on standard error and
 * the exit status is 1, or 2 for a bad flag. A failure [block] lets through is printed and also ends with
 * status 1.
 * 
 * @param args - the command line, without Node's own two entries.
 * @param block - the bot.
 * @param anonymous - whether the bot may run without logging in, when `kwikibot.toml` names no login. A bot
 * that saves still may not.
 */
export declare function botMain(args: Array<string>, block: (start: BotStart) => Awaitable<ExitStatus>, anonymous?: boolean): void;
/**
 * The flags most bots take, as a TypeScript author sets them.
 */
export declare interface CommonOptionsInit {
    /**
     * Whether to save edits. A run is a dry run without it.
     */
    readonly save?: Nullable<boolean>;
    /**
     * How many pages to work through at most.
     */
    readonly limit?: Nullable<number>;
    /**
     * Where to write diffs.
     */
    readonly diffLog?: Nullable<string>;
    /**
     * Where to write the pages left alone.
     */
    readonly skipLog?: Nullable<string>;
    /**
     * The page that stops the bot when it says anything but `false`.
     */
    readonly stopPage?: Nullable<string>;
    /**
     * The least time between reads, in milliseconds.
     */
    readonly readDelay?: Nullable<number>;
    /**
     * The least time between writes, in milliseconds.
     */
    readonly writeDelay?: Nullable<number>;
    /**
     * How many requests may be in flight at once.
     */
    readonly httpConnections?: Nullable<number>;
    /**
     * Where an operator can be reached, for the user agent.
     */
    readonly contact?: Nullable<string>;
    /**
     * Where the run records what it has done.
     */
    readonly state?: Nullable<string>;
    /**
     * Whether to carry on from where a run into [state] stopped. Needs [state].
     */
    readonly resume?: Nullable<boolean>;
    /**
     * Whether to save as an account outside the bot group, for a trial. Needs [save] and [limit].
     */
    readonly trial?: Nullable<boolean>;
}
/**
 * The flags most bots take: whether to save, how far to go, where to log and which page stops the bot. Give
 * them to a run as its `common`.
 */
export declare class CommonOptions {
    private constructor();
    /**
     * [base] with what these flags change about a client: its pace, its connections and its contact. A
     * contact is applied to [base]'s `userAgent`; with none there, give [contact] to `connectWith`.
     */
    connectOptions(base?: Nullable<ConnectOptions>): ConnectOptions;
    /**
     * The stop policy these flags ask for: the page `--stop-page` names, or none. One told to save with none
     * logs a warning, since it cannot be stopped from the wiki.
     */
    stopPolicy(wiki: Wiki): StopPolicy;
    /**
     * A log writing diffs to [diffLog] and skips to [skipLog], added to whatever is already in them.
     */
    runLog(wiki: Wiki): RunLog;
    /**
     * Whether to save edits, `--save`.
     */
    get save(): boolean;
    /**
     * How many pages to work through at most, `--limit`.
     */
    get limit(): Nullable<number>;
    /**
     * Where to write diffs, `--diff-log`.
     */
    get diffLog(): Nullable<string>;
    /**
     * Where to write the pages left alone, `--skip-log`.
     */
    get skipLog(): Nullable<string>;
    /**
     * The page that stops the bot when it says anything but `false`, `--stop-page`.
     */
    get stopPage(): Nullable<string>;
    /**
     * The least time between reads in milliseconds, `--read-delay`.
     */
    get readDelay(): Nullable<number>;
    /**
     * The least time between writes in milliseconds, `--write-delay`.
     */
    get writeDelay(): Nullable<number>;
    /**
     * How many requests may be in flight at once, `--http-connections`.
     */
    get httpConnections(): Nullable<number>;
    /**
     * Where an operator can be reached, for the user agent, `--contact`.
     */
    get contact(): Nullable<string>;
    /**
     * Where the run records what it has done, `--state`.
     */
    get state(): Nullable<string>;
    /**
     * Whether to carry on from where a run into [state] stopped, `--resume`.
     */
    get resume(): boolean;
    /**
     * Whether to save as an account outside the bot group, for a trial, `--trial`.
     */
    get trial(): boolean;
}
export declare namespace CommonOptions {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => CommonOptions;
    }
}
/**
 * The common flags read off a command line, and what they left.
 */
export declare class ParsedCommandLine {
    private constructor();
    /**
     * The common flags.
     */
    get options(): CommonOptions;
    /**
     * Everything else, in the order it came, for the bot to read itself.
     */
    get rest(): Array<string>;
}
export declare namespace ParsedCommandLine {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ParsedCommandLine;
    }
}
/**
 * What `botMain` hands to a bot.
 */
export declare class BotStart {
    private constructor();
    /**
     * The wiki `kwikibot.toml` names, opened as its account.
     */
    get wiki(): Wiki;
    /**
     * The common flags.
     */
    get options(): CommonOptions;
    /**
     * What the common flags did not take, in order, for the bot to read itself.
     */
    get arguments(): Array<string>;
    /**
     * The configuration the bot started from.
     */
    get config(): BotConfig;
}
export declare namespace BotStart {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => BotStart;
    }
}
/**
 * Opens the wiki at [server] as `connect` does, with every setting [options] gives. [contact] is a URL or
 * email address the wiki's operators can reach you at.
 */
export declare function connectWith(server: string, contact: string, options?: Nullable<ConnectOptions>): Promise<Wiki>;
/**
 * Opens the wiki of [family] in [language], such as `"en"` and `FAMILY_WIKTIONARY`, with every setting
 * [options] gives. [language] is ignored by a family with one wiki.
 */
export declare function connectFamily(language: string, family: Family, contact: string, options?: Nullable<ConnectOptions>): Promise<Wiki>;
/**
 * Makes a client whose wikis share one set of connections and one throttle, so that a pause one wiki asks for
 * holds back requests to the others. Its `scriptPath` and `secure` are what its wikis open with unless told
 * otherwise. Close it when done.
 */
export declare function createClient(contact: string, options?: Nullable<ConnectOptions>): WikiClient;
/**
 * Opens wikis that share one set of connections and one throttle.
 */
export declare class WikiClient {
    private constructor();
    /**
     * Opens the wiki at [server]. Closing it leaves the client and its other wikis open.
     */
    wiki(server: string): Promise<Wiki>;
    /**
     * Opens the wiki of [family] in [language]. Closing it leaves the client and its other wikis open.
     */
    wikiOf(language: string, family: Family): Promise<Wiki>;
    /**
     * Releases the connections of every wiki opened here.
     */
    close(): void;
}
export declare namespace WikiClient {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => WikiClient;
    }
}
/**
 * An `onWarning` for tests against a real wiki, which fails any request a wiki answers with a deprecation
 * warning, so a test catches what needs replacing before the release that removes it. Every other warning
 * goes to [others], or is logged once when none is given.
 */
export declare function failOnDeprecation(others?: Nullable<(warning: ApiWarning) => void>): (warning: ApiWarning) => void;
/**
 * How fast requests may go out, in milliseconds between the starts of two.
 */
export declare interface ThrottleOptions {
    /**
     * The least time between two reads; 100 unless given.
     */
    readonly read?: Nullable<number>;
    /**
     * The least time between two writes; 10000 unless given.
     */
    readonly write?: Nullable<number>;
}
/**
 * How failed requests are retried.
 */
export declare interface RetryOptions {
    /**
     * How many times a request may be retried before the failure is raised; 5 unless given.
     */
    readonly maxRetries?: Nullable<number>;
    /**
     * The wait before the first retry, in milliseconds; 1000 unless given.
     */
    readonly initialDelay?: Nullable<number>;
    /**
     * The ceiling each doubling of the wait is capped at, in milliseconds; 120000 unless given.
     */
    readonly maxDelay?: Nullable<number>;
}
/**
 * How a client's connections are managed.
 */
export declare interface HttpOptions {
    /**
     * How many requests may be in flight at once, to every wiki the client opens; 3 unless given.
     */
    readonly maxConcurrentRequests?: Nullable<number>;
    /**
     * How many idle connections are kept open for reuse; 32 unless given. fetch manages its own.
     */
    readonly maxIdleConnections?: Nullable<number>;
    /**
     * How long a request may take before giving up, in milliseconds; 60000 unless given.
     */
    readonly timeout?: Nullable<number>;
}
/**
 * Hears what a client does on a bot's behalf. Every function is optional. One that throws fails the request.
 */
export declare interface ListenerOptions {
    /**
     * Hears each answer the wiki sent, with the request's parameters. Not one served from a cache.
     */
    readonly onResponse?: Nullable<(params: Record<string, string>, response: Record<string, JsonValue>) => void>;
    /**
     * Hears each retry: its parameters, which retry it is, the wait in milliseconds, and why.
     */
    readonly onRetry?: Nullable<(params: Record<string, string>, attempt: number, wait: number, reason: string) => void>;
    /**
     * Hears a wiki asking the whole client to hold back, for this many milliseconds.
     */
    readonly onPenalty?: Nullable<(wait: number) => void>;
    /**
     * Hears a dropped session being restored by logging in again.
     */
    readonly onRelogin?: Nullable<(identity: Identity) => void>;
}
/**
 * Remembers responses so a repeated read does not repeat the request. `DiskCache` is one; any object with
 * these two functions is another. Each may return a promise.
 */
export declare interface ResponseCache {
    /**
     * The stored response for the request with [params], or `null` when there is none.
     */
    get(params: Record<string, string>): Awaitable<Nullable<Record<string, JsonValue>>>;
    /**
     * Stores [response] for the request with [params].
     */
    put(params: Record<string, string>, response: Record<string, JsonValue>): Awaitable<Ignored>;
}
/**
 * A response cache on disk, one file per request, under the directory [root], for developing a bot rather
 * than running one. An entry older than [ttl] milliseconds, 12 hours unless given, is ignored and deleted.
 * Writes and anything carrying a credential are never stored.
 * 
 * Works in Node only: in a browser, which has no filesystem, making one throws.
 */
export declare class DiskCache {
    constructor(root: string, ttl?: Nullable<number>);
    /**
     * The stored response for the request with [params], or `null` when there is none or it has expired.
     */
    get(params: Record<string, string>): Promise<Nullable<Record<string, JsonValue>>>;
    /**
     * Stores [response] for the request with [params], unless it is one that is never stored.
     */
    put(params: Record<string, string>, response: Record<string, JsonValue>): Promise<void>;
    /**
     * Removes every entry.
     */
    clear(): void;
    /**
     * The directory the entries go under.
     */
    get root(): string;
}
export declare namespace DiskCache {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => DiskCache;
    }
}
/**
 * Everything a wiki can be opened with beyond its server.
 */
export declare interface ConnectOptions {
    /**
     * Who to log in as, a `BotPassword` or an `OAuth2`. Anonymous unless given.
     */
    readonly login?: Nullable<BotPassword>;
    /**
     * Where `api.php` is under the server, `"/w"` unless given.
     */
    readonly scriptPath?: Nullable<string>;
    /**
     * Whether to speak HTTPS, `true` unless given. Off only for a local test wiki.
     */
    readonly secure?: Nullable<boolean>;
    /**
     * Identifies the bot to operators; the library's name with the contact unless given.
     */
    readonly userAgent?: Nullable<UserAgent>;
    /**
     * How fast requests may go out.
     */
    readonly throttle?: Nullable<ThrottleOptions>;
    /**
     * How failed requests are retried.
     */
    readonly retry?: Nullable<RetryOptions>;
    /**
     * The replication lag in seconds above which a wiki should defer requests; 5 unless given, as Wikimedia
     * asks. `null` leaves the parameter out, which only makes sense for a self-hosted wiki.
     */
    readonly maxlag?: Nullable<number>;
    /**
     * Where read responses are remembered, such as a `DiskCache`. Nowhere unless given.
     */
    readonly cache?: Nullable<ResponseCache>;
    /**
     * Hears each warning a wiki attaches to an answer. Unless given, each distinct one is logged once.
     */
    readonly onWarning?: Nullable<(warning: ApiWarning) => void>;
    /**
     * How many times in ten minutes a dropped session is restored by logging in again; 3 unless given.
     */
    readonly relogins?: Nullable<number>;
    /**
     * How the client's connections are managed.
     */
    readonly http?: Nullable<HttpOptions>;
    /**
     * Hears what the client does on the bot's behalf.
     */
    readonly listener?: Nullable<ListenerOptions>;
}
/**
 * A warning a wiki attached to an answer.
 */
export declare class ApiWarning {
    private constructor();
    /**
     * The warning as a line of text.
     */
    toString(): string;
    /**
     * The module that raised it.
     */
    get module(): string;
    /**
     * What it says.
     */
    get text(): string;
    /**
     * The wiki's code for it, or `null` when it gave none.
     */
    get code(): Nullable<string>;
    /**
     * Whether it says something the request used is deprecated.
     */
    get isDeprecation(): boolean;
}
export declare namespace ApiWarning {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ApiWarning;
    }
}
/**
 * Trailing whitespace at the end of lines, outside what MediaWiki does not parse.
 */
export declare const COSMETIC_TRAILING_WHITESPACE: CosmeticPass;
/**
 * Three or more blank lines in a row, reduced to two.
 */
export declare const COSMETIC_EXTRA_BLANK_LINES: CosmeticPass;
/**
 * `<b>` and `<i>` written as the wiki markup that means the same thing.
 */
export declare const COSMETIC_HTML_EMPHASIS: CosmeticPass;
/**
 * Entities decoded, apart from those that mean something as markup and `&nbsp;`.
 */
export declare const COSMETIC_HTML_ENTITIES: CosmeticPass;
/**
 * A link whose display text is its own target: `[[foo|foo]]` becomes `[[foo]]`.
 */
export declare const COSMETIC_REDUNDANT_LINK_TEXT: CosmeticPass;
/**
 * `<br>` written as `<br />`.
 */
export declare const COSMETIC_SELF_CLOSING_BR: CosmeticPass;
/**
 * Sections with a heading and nothing under them.
 */
export declare const COSMETIC_EMPTY_SECTIONS: CosmeticPass;
/**
 * The passes with no project-specific behaviour: whitespace, blank lines, and redundant link text.
 */
export declare const COSMETIC_SAFE: CosmeticChanges;
/**
 * A pass that is [transform], which returns the page unchanged when it finds nothing.
 */
export declare function cosmeticPass(transform: (code: Wikitext) => Wikitext): CosmeticPass;
/**
 * Rewrites the ISBNs in the page's prose: with [toIsbn13], a valid ten-digit one becomes thirteen digits,
 * written the same way. One whose check digit does not add up is left exactly as written.
 */
export declare function cosmeticReformatIsbns(toIsbn13?: boolean): CosmeticPass;
/**
 * A tidier applying [passes], in order.
 */
export declare function cosmeticChanges(passes: Array<CosmeticPass>): CosmeticChanges;
/**
 * Reads an ISBN, or `null` when it is the wrong length or its check digit does not add up.
 */
export declare function parseIsbn(raw: string): Nullable<Isbn>;
/**
 * Whether [raw] is a well-formed ISBN, check digit included.
 */
export declare function isValidIsbn(raw: string): boolean;
/**
 * One tidying pass over a page. Each is separate and named so a bot can choose, since what counts as tidy
 * differs between projects.
 */
export declare class CosmeticPass {
    private constructor();
    /**
     * The page after this pass.
     */
    apply(code: Wikitext): Wikitext;
}
export declare namespace CosmeticPass {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => CosmeticPass;
    }
}
/**
 * Tidying passes over a page. None is on by default: many wikis prohibit cosmetic-only edits.
 */
export declare class CosmeticChanges {
    private constructor();
    /**
     * The page with every pass applied, in order.
     */
    apply(code: Wikitext): Wikitext;
    /**
     * The text with every pass applied, parsed the way a wiki with [options] reads it, when they are given.
     */
    applyText(wikitext: string, options?: Nullable<ParseOptions>): string;
    /**
     * Whether any pass would change the text, parsed the way a wiki with [options] reads it.
     */
    wouldChange(wikitext: string, options?: Nullable<ParseOptions>): boolean;
}
export declare namespace CosmeticChanges {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => CosmeticChanges;
    }
}
/**
 * An ISBN whose check digit adds up.
 */
export declare class Isbn {
    private constructor();
    /**
     * This ISBN as thirteen digits.
     */
    toIsbn13(): Isbn;
    /**
     * The digits.
     */
    toString(): string;
    /**
     * The digits, without hyphens or spaces, with any check character in upper case.
     */
    get normalised(): string;
    /**
     * Whether this is the thirteen-digit form.
     */
    get isIsbn13(): boolean;
}
export declare namespace Isbn {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Isbn;
    }
}
/**
 * The languages a day title can be built for.
 */
export declare function dayTitleLanguages(): Array<string>;
/**
 * Adds or replaces [language]'s format for day titles. It holds for the whole process.
 */
export declare function registerDayFormat(language: string, format: DayFormat): void;
/**
 * The format registered for [language], or `null`.
 */
export declare function dayFormat(language: string): Nullable<DayFormat>;
/**
 * The title of the page for a calendar day, or `null` for a language with no format. Throws when it is not a
 * real day.
 */
export declare function dayTitle(month: number, day: number, language?: string): Nullable<string>;
/**
 * The day [title] names, or `null` when it is not a day page in [language].
 */
export declare function parseDayTitle(title: string, language?: string): Nullable<MonthDay>;
/**
 * The days [month] has, counting 29 for February, since 29 February has a page.
 */
export declare function daysInMonth(month: number): number;
/**
 * How one language titles its pages about a calendar day.
 */
export declare class DayFormat {
    constructor(months: Array<string>, pattern: string, firstOrdinal?: Nullable<string>);
    /**
     * The month names in this language, January first.
     */
    get months(): Array<string>;
    /**
     * `$day` and `$month` are replaced; `$ordinal` is the day with its suffix, if any.
     */
    get pattern(): string;
    /**
     * The suffix on the first of the month, where a language uses one, such as `"er"` in French.
     */
    get firstOrdinal(): Nullable<string>;
}
export declare namespace DayFormat {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => DayFormat;
    }
}
/**
 * A calendar day, without a year.
 */
export declare class MonthDay {
    private constructor();
    /**
     * The month, from 1 to 12.
     */
    get month(): number;
    /**
     * The day of the month.
     */
    get day(): number;
}
export declare namespace MonthDay {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => MonthDay;
    }
}
/**
 * How many requests a client has in flight at once unless told, the most Wikimedia asks for.
 */
export declare const DEFAULT_CONCURRENT_REQUESTS: number;
/**
 * How many idle connections a client keeps for reuse unless told.
 */
export declare const DEFAULT_CONNECTIONS: number;
/**
 * How long a request may take unless told, in milliseconds.
 */
export declare const DEFAULT_TIMEOUT: number;
/**
 * How many times in ten minutes a dropped session is restored by logging in again unless told.
 */
export declare const DEFAULT_RELOGINS: number;
/**
 * The least time between two reads unless told, in milliseconds.
 */
export declare const DEFAULT_READ_DELAY: number;
/**
 * The least time between two writes unless told, in milliseconds.
 */
export declare const DEFAULT_WRITE_DELAY: number;
/**
 * How long a `DiskCache` keeps an entry unless told, in milliseconds.
 */
export declare const DISK_CACHE_DEFAULT_TTL: number;
/**
 * The longest URL a read is sent in before it goes as a POST instead.
 */
export declare const MAX_GET_LENGTH: number;
/**
 * The module of the advice a wiki attaches to every deprecation, which warnings leave out.
 */
export declare const DEPRECATION_HELP: string;
/**
 * The protection level only administrators may edit through.
 */
export declare const PROTECTION_SYSOP: string;
/**
 * The protection level autoconfirmed accounts may edit through.
 */
export declare const PROTECTION_AUTOCONFIRMED: string;
/**
 * Wikitext, as `Page.contentModel` spells it.
 */
export declare const CONTENT_MODEL_WIKITEXT: string;
/**
 * JSON, as `Page.contentModel` spells it.
 */
export declare const CONTENT_MODEL_JSON: string;
/**
 * CSS, as `Page.contentModel` spells it.
 */
export declare const CONTENT_MODEL_CSS: string;
/**
 * JavaScript, as `Page.contentModel` spells it.
 */
export declare const CONTENT_MODEL_JAVASCRIPT: string;
/**
 * A Lua module, as `Page.contentModel` spells it.
 */
export declare const CONTENT_MODEL_SCRIBUNTO: string;
/**
 * TemplateStyles' CSS, as `Page.contentModel` spells it.
 */
export declare const CONTENT_MODEL_SANITIZED_CSS: string;
/**
 * Reads one entity as `wbgetentities` serialises it.
 */
export declare function decodeEntity(json: Record<string, JsonValue>): Entity;
/**
 * Reads a whole `wbgetentities` response, by entity id. Entries that are not entities are left out.
 */
export declare function decodeEntities(response: Record<string, JsonValue>): Record<string, Entity>;
/**
 * The entity a `wbgetentities` entry redirects to, or `null` when it is not a redirect.
 */
export declare function entityRedirectTarget(json: Record<string, JsonValue>): Nullable<string>;
/**
 * Reads one statement as Wikibase serialises it.
 */
export declare function decodeClaim(json: Record<string, JsonValue>): Claim;
/**
 * Reads one snak as Wikibase serialises it.
 */
export declare function decodeSnak(json: Record<string, JsonValue>): Snak;
/**
 * Reads one value as Wikibase serialises it, `{ type, value }`.
 */
export declare function decodeDataValue(json: Record<string, JsonValue>): DataValue;
/**
 * [statements], by property, as a `claims` block of `wbeditentity`'s data.
 */
export declare function encodeClaims(statements: Record<string, Array<Claim>>): Record<string, JsonValue>;
/**
 * [values], by language, as a `labels` or `descriptions` block of `wbeditentity`'s data.
 */
export declare function encodeLanguageValues(values: Record<string, LanguageValue>): Record<string, JsonValue>;
/**
 * [values], by language, as an `aliases` block of `wbeditentity`'s data.
 */
export declare function encodeAliases(values: Record<string, Array<LanguageValue>>): Record<string, JsonValue>;
/**
 * [links], by wiki database name, as a `sitelinks` block of `wbeditentity`'s data.
 */
export declare function encodeSiteLinks(links: Record<string, EntitySiteLink>): Record<string, JsonValue>;
/**
 * [claim] as Wikibase serialises one statement.
 */
export declare function encodeClaim(claim: Claim): Record<string, JsonValue>;
/**
 * [snak] as Wikibase serialises one.
 */
export declare function encodeSnak(snak: Snak): Record<string, JsonValue>;
/**
 * [value] as Wikibase serialises one, `{ type, value }`.
 */
export declare function encodeDataValue(value: DataValue): Record<string, JsonValue>;
/**
 * What `wbgetentities` accepts in one request.
 */
export declare const ENTITY_BATCH_SIZE: number;
/**
 * How many hits `wiki.entities.search` asks for unless told otherwise.
 */
export declare const ENTITY_SEARCH_LIMIT: number;
/**
 * What kind of entity [id] is from its prefix, as `Entity.type` says it.
 */
export declare function entityKind(id: string): EntityKind;
/**
 * The parts of an entity an edit sets. Everything is optional and what is left out is left alone, because
 * `wbeditentity` merges, unless [clear] says otherwise.
 */
export declare interface EntityEditOptions {
    /**
     * Labels to set, by language code.
     */
    readonly labels?: Nullable<Record<string, string>>;
    /**
     * Descriptions to set, by language code.
     */
    readonly descriptions?: Nullable<Record<string, string>>;
    /**
     * Aliases to set, by language code. Replaces the language entirely.
     */
    readonly aliases?: Nullable<Record<string, Array<string>>>;
    /**
     * Sitelinks to set, by wiki database name.
     */
    readonly siteLinks?: Nullable<Record<string, EntitySiteLink>>;
    /**
     * Statements to add or replace, by property.
     */
    readonly statements?: Nullable<Record<string, Array<Claim>>>;
    /**
     * The edit summary. Wikibase prepends its own to it.
     */
    readonly summary?: Nullable<string>;
    /**
     * Whether to flag the edit as a bot's; `true` unless given.
     */
    readonly bot?: Nullable<boolean>;
    /**
     * The revision the edit was computed from, so the repository can detect a conflict.
     */
    readonly baseRevision?: Nullable<number>;
    /**
     * Replace the entity with what this edit sets, deleting everything else on it. `false` unless given.
     */
    readonly clear?: Nullable<boolean>;
}
/**
 * Reading and writing a Wikibase repository's entities. Reached as `wiki.entities`.
 */
export declare class EntityService {
    private constructor();
    /**
     * The entity [id], such as `"Q42"`, or `null` when there is none.
     */
    entity(id: string): Promise<Nullable<Entity>>;
    /**
     * The entities [ids] names, by id, in as few requests as the wiki allows. An id that does not exist is
     * left out.
     */
    entities(ids: Array<string>): Promise<Record<string, Entity>>;
    /**
     * The entities about [titles] on the wiki with database name [site], each with the title asked for. A
     * title no entity is about is left out.
     */
    forPages(site: string, titles: Array<string>): Promise<Array<EntityOfPage>>;
    /**
     * Searches entities by label and alias in [language]. [kind] is `"item"`, `"property"`, `"lexeme"`,
     * `"form"` or `"sense"`; the wiki does not search `"mediainfo"`, and asking for it or `"unknown"`
     * rejects.
     */
    search(query: string, language?: string, kind?: EntitySearchKind, limit?: number): Promise<Array<EntityMatch>>;
    /**
     * Applies [options] to the existing entity [id], and answers with it as saved.
     */
    edit(id: string, options: EntityEditOptions): Promise<Entity>;
    /**
     * Creates an entity of [kind] with what [options] sets, and answers with it. [kind] is `"item"`,
     * `"property"`, `"lexeme"`, `"form"`, `"sense"` or `"mediainfo"`.
     */
    create(kind: NewEntityKind, options: EntityEditOptions): Promise<Entity>;
    /**
     * Adds [statement] to the entity [target], or replaces the one whose id it carries, and answers with it
     * as saved. [baseRevision] is the revision it was computed from, so the repository can detect a conflict.
     */
    setStatement(target: string, statement: Claim, summary?: string, baseRevision?: Nullable<number>): Promise<Claim>;
    /**
     * Removes the statements with the ids [statementIds].
     */
    removeStatements(statementIds: Array<string>, summary?: string, baseRevision?: Nullable<number>): Promise<void>;
    /**
     * Merges the item [from] into the item [into], leaving the first as a redirect. [ignoreConflicts] says
     * which conflicting parts to overwrite rather than refuse on: `"description"`, `"sitelink"`,
     * `"statement"`.
     */
    mergeItems(from: string, into: string, ignoreConflicts?: Nullable<Array<MergeConflict>>, summary?: string): Promise<void>;
    /**
     * Merges the lexeme [from] into the lexeme [into].
     */
    mergeLexemes(from: string, into: string, summary?: string): Promise<void>;
    /**
     * Points the empty entity [from] at the entity [to] as a redirect.
     */
    redirect(from: string, to: string): Promise<void>;
    /**
     * Parses [values] the way Wikibase would, without saving anything. [dataType] is the Wikibase type name:
     * `"time"`, `"globe-coordinate"`, `"quantity"`.
     */
    parseValues(dataType: string, values: Array<string>): Promise<Array<DataValue>>;
}
export declare namespace EntityService {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => EntityService;
    }
}
/**
 * One entity found for a page, with the title it was asked for.
 */
export declare class EntityOfPage {
    private constructor();
    /**
     * The title as it was asked for.
     */
    get title(): string;
    /**
     * The entity about it.
     */
    get entity(): Entity;
}
export declare namespace EntityOfPage {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => EntityOfPage;
    }
}
/**
 * One hit from an entity search: enough to choose between candidates.
 */
export declare class EntityMatch {
    private constructor();
    /**
     * The entity found.
     */
    get id(): string;
    /**
     * Its label in the language searched, or `null`.
     */
    get label(): Nullable<string>;
    /**
     * Its description, which is what usually separates two candidates, or `null`.
     */
    get description(): Nullable<string>;
}
export declare namespace EntityMatch {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => EntityMatch;
    }
}
/**
 * The pages of a PagePile, a saved, fixed list.
 * 
 * @param id - the pile's id.
 * @param contact - a URL or email address the tool's operators can reach you at.
 * @param agent - who to say is asking, in place of the library's name with the contact.
 */
export declare function pagePileSource(id: string, contact: string, agent?: Nullable<UserAgent>): PageSource;
/**
 * The pages a SPARQL `SELECT` [query] names. It has to select page titles rather than entity URIs.
 * 
 * @param query - the `SELECT` to run.
 * @param contact - a URL or email address the service's operators can reach you at.
 * @param options - where to ask, and what to read.
 */
export declare function sparqlSource(query: string, contact: string, options?: Nullable<SparqlSourceOptions>): PageSource;
/**
 * Where a SPARQL source asks, and what it reads.
 */
export declare interface SparqlSourceOptions {
    /**
     * Which query service to ask; Wikidata's unless given.
     */
    readonly endpoint?: Nullable<string>;
    /**
     * The result variable holding the title; `"title"` unless given.
     */
    readonly variable?: Nullable<string>;
    /**
     * Credentials, for a service that needs them.
     */
    readonly auth?: Nullable<SparqlAuth>;
    /**
     * Who to say is asking, in place of the library's name with the contact.
     */
    readonly userAgent?: Nullable<UserAgent>;
}
/**
 * Encyclopedias.
 */
export declare const FAMILY_WIKIPEDIA: Family;
/**
 * Dictionaries, where a title is a word rather than a subject.
 */
export declare const FAMILY_WIKTIONARY: Family;
/**
 * Transcribed source texts.
 */
export declare const FAMILY_WIKISOURCE: Family;
/**
 * Collections of quotations.
 */
export declare const FAMILY_WIKIQUOTE: Family;
/**
 * Textbooks and manuals.
 */
export declare const FAMILY_WIKIBOOKS: Family;
/**
 * News reporting.
 */
export declare const FAMILY_WIKINEWS: Family;
/**
 * Learning materials.
 */
export declare const FAMILY_WIKIVERSITY: Family;
/**
 * Travel guides.
 */
export declare const FAMILY_WIKIVOYAGE: Family;
/**
 * The shared media repository every other Wikimedia wiki reads files from.
 */
export declare const FAMILY_COMMONS: Family;
/**
 * The structured-data repository, whose pages are entities.
 */
export declare const FAMILY_WIKIDATA: Family;
/**
 * Coordination across the projects, including bot policy.
 */
export declare const FAMILY_META: Family;
/**
 * MediaWiki's own documentation, including the API reference.
 */
export declare const FAMILY_MEDIAWIKI: Family;
/**
 * The write-capable test wiki, where live write tests belong.
 */
export declare const FAMILY_TEST: Family;
/**
 * The family called [name], or `null` when it is not one of the known projects.
 */
export declare function familyNamed(name: string): Nullable<Family>;
/**
 * A third-party wiki, named by its host and the path MediaWiki is installed under.
 */
export declare function customFamily(name: string, server: string, scriptPath?: string): Family;
/**
 * The family of the wiki at [endpoint], as `detect` finds one, under [name] or else the server's.
 */
export declare function familyAt(endpoint: Endpoint, name?: Nullable<string>): Family;
/**
 * The family of one wiki in the site matrix.
 */
export declare function familyOf(wiki: MatrixWiki): Family;
/**
 * Where a wiki's API is, when its layout is already known. [secure] is off only for a local test wiki.
 */
export declare function endpointOf(server: string, scriptPath?: string, secure?: boolean): Endpoint;
/**
 * Where the API of a Wikimedia project's wiki is, such as `"en"` and `"wiktionary.org"`.
 */
export declare function wikimediaEndpoint(code: string, domain: string): Endpoint;
/**
 * Reads where the API is out of the HTML of a page at [pageUrl], which `detect` does after fetching it.
 * `null` when the page advertises none.
 */
export declare function endpointFromHtml(html: string, pageUrl: string): Nullable<Endpoint>;
/**
 * A group of wikis that share a domain pattern, such as the Wiktionaries.
 */
export declare class Family {
    private constructor();
    /**
     * Where the API of this family's wiki in [language] is.
     */
    endpoint(language: string): Endpoint;
    /**
     * `"wikimedia"`, one wiki per language under [domain], or `"single-site"`, one wiki at [server].
     */
    get kind(): FamilyKind;
    /**
     * The family's name, such as `"wiktionary"`.
     */
    get name(): string;
    /**
     * The domain a `"wikimedia"` family's wikis sit under, such as `"wiktionary.org"`, or `null`.
     */
    get domain(): Nullable<string>;
    /**
     * The host of a `"single-site"` family, or `null`.
     */
    get server(): Nullable<string>;
    /**
     * Where a `"single-site"` family has MediaWiki under its host, or `null`.
     */
    get scriptPath(): Nullable<string>;
}
export declare namespace Family {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Family;
    }
}
/**
 * How many unchanged lines `unifiedDiff` shows around each change unless told.
 */
export declare const DIFF_DEFAULT_CONTEXT: number;
/**
 * Runs of spaces inside a line reduced to one. Prose only: runs of spaces lay out tables and lists.
 */
export declare const FIX_EXTRA_SPACES: Fix;
/**
 * A range of years written with a hyphen given an en dash.
 */
export declare const FIX_DATE_RANGES: Fix;
/**
 * Exactly three dots given the ellipsis character.
 */
export declare const FIX_ELLIPSIS: Fix;
/**
 * The language every chain of `Messages` ends at.
 */
export declare const MESSAGES_DEFAULT_LANGUAGE: string;
/**
 * A unified diff of one page edit, for review before anything is saved, or `""` when nothing changes.
 * 
 * @param before - the text as it stands.
 * @param after - the text as it would be.
 * @param title - the page, for the header.
 * @param context - how many unchanged lines to show around each change.
 */
export declare function unifiedDiff(before: string, after: string, title: string, context?: number): string;
/**
 * Makes a fix.
 * 
 * @param name - how the fix is named on a command line.
 * @param description - what it does, shown when the fixes are listed.
 * @param replacements - the substitutions it makes, applied in order.
 * @param scope - which text they may touch: prose unless given, so a fix cannot rewrite a template parameter or
 * a link's target by accident.
 */
export declare function createFix(name: string, description: string, replacements: Array<Replacement>, scope?: Nullable<TextScopeOptions>): Fix;
/**
 * Every fix this library ships, plus any that were registered, by name.
 */
export declare function allFixes(): Record<string, Fix>;
/**
 * The registered fix called [name], or `null`.
 */
export declare function fixNamed(name: string): Nullable<Fix>;
/**
 * Adds [fix] to the registry, replacing one of the same name.
 */
export declare function registerFix(fix: Fix): void;
/**
 * The registered fixes [names] calls for, in that order.
 * 
 * @throws IllegalArgumentException for one there is not, naming those there are.
 */
export declare function fixesNamed(names: Array<string>): Array<Fix>;
/**
 * Which languages `Messages` tries when one has no message, unless told otherwise.
 */
export declare function messageFallbacks(): Record<string, Array<string>>;
/**
 * Messages from bundles already parsed.
 * 
 * @param bundles - each language's messages, by key.
 * @param fallbacks - which languages to try when one has no message; `messageFallbacks()` unless given.
 */
export declare function createMessages(bundles: Record<string, Record<string, string>>, fallbacks?: Nullable<Record<string, Array<string>>>): Messages;
/**
 * Messages from JSON bundles, one per language. The format is MediaWiki's own, a flat object of key to
 * message with `@metadata` ignored, so a bundle can be taken from translatewiki unchanged.
 */
export declare function messagesFromJson(bundles: Record<string, string>): Messages;
/**
 * One search and replace within a fix.
 */
export declare interface Replacement {
    /**
     * What to look for. Its `i` and `m` flags apply; `s` and `y` are refused.
     */
    readonly pattern: RegExp;
    /**
     * What to put in its place, taken as it is: `$1` in it is a dollar sign and a one.
     */
    readonly replacement: string;
}
/**
 * A named bundle of replacements.
 */
export declare class Fix {
    private constructor();
    /**
     * [wikitext] with this fix applied, parsed as a wiki with [options] parses it, when given.
     */
    apply(wikitext: string, options?: Nullable<ParseOptions>): string;
    /**
     * The page, already parsed, with this fix applied.
     */
    applyParsed(code: Wikitext): Wikitext;
    /**
     * Whether this fix would change [wikitext], parsed as a wiki with [options] parses it, when given.
     */
    wouldChange(wikitext: string, options?: Nullable<ParseOptions>): boolean;
    /**
     * How the fix is named on a command line.
     */
    get name(): string;
    /**
     * What it does, shown when the fixes are listed.
     */
    get description(): string;
    /**
     * The substitutions it makes, applied in order.
     */
    get replacements(): Array<Replacement>;
    /**
     * Which text the replacements may touch.
     */
    get scope(): TextScopeOptions;
}
export declare namespace Fix {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Fix;
    }
}
/**
 * Edit summaries and other text in the language of the wiki being edited. Bundles are keyed by language and
 * support `$1` placeholders and `{{PLURAL:}}`, by the language's CLDR rule. A key missing in the language
 * falls back through its chain and then to English; one missing everywhere comes back as the key itself.
 */
export declare class Messages {
    private constructor();
    /**
     * The message for [key] in [language], with `$1`, `$2`… replaced by [values] in order.
     */
    get(key: string, language: string, values?: Array<MessageValue>): string;
    /**
     * Whether [key] exists in [language] or anything it falls back to.
     */
    has(key: string, language: string): boolean;
    /**
     * The languages tried for [language], in order, ending in English.
     */
    chain(language: string): Array<string>;
    /**
     * The languages this holds messages for.
     */
    get languages(): Array<string>;
}
export declare namespace Messages {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Messages;
    }
}
/**
 * Removes links to one page, keeping the words that were linked, as before a page is deleted. `[[volcano]]`
 * becomes `volcano` and `[[volcano|volcanoes]]` becomes `volcanoes`, at any depth.
 */
export declare class Unlinker {
    constructor(capitalLinks?: boolean);
    /**
     * [wikitext] with every link to [target] replaced by the words it was showing.
     */
    unlink(wikitext: string, target: Title): string;
    /**
     * The page, already parsed, with every link to [target] replaced by the words it was showing.
     */
    unlinkParsed(code: Wikitext, target: Title): Wikitext;
    /**
     * Whether [wikitext] links to [target].
     */
    linksTo(wikitext: string, target: Title): boolean;
    /**
     * Whether the page, already parsed, links to [target].
     */
    linksToParsed(code: Wikitext, target: Title): boolean;
}
export declare namespace Unlinker {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Unlinker;
    }
}
/**
 * Makes the wikis a fleet edits through.
 * 
 * @param contact - a URL or email address the wiki's operators can reach you at.
 * @param options - the transport settings every account shares. Its `login` is the default account, and its
 * `scriptPath` and `secure` are ignored, since each wiki comes with an `Endpoint`.
 * @param credentials - gives an account's login by name, or `null` for the default account, and `null` for an
 * anonymous session. Unless given, in Node, an account not otherwise known is read from the environment,
 * under `KWIKIBOT_` and `KWIKIBOT_<NAME>_`.
 */
export declare function createAccounts(contact: string, options?: Nullable<ConnectOptions>, credentials?: Nullable<(account: Nullable<string>) => Nullable<BotPassword>>): Accounts;
/**
 * Opens a fleet of [bots], with everything a fleet can be told. Unlike `createFleet`, it shares [options]'s
 * `accounts` with whatever else uses them.
 */
export declare function createFleetWith(options: FleetHostOptions, bots: Array<BotSpec>): Fleet;
/**
 * The bots a fleet runs, by id. Throws when two of them have the same id.
 */
export declare class BotRegistry {
    constructor(bots: Array<BotSpec>);
    /**
     * The bot called [id], or `null`.
     */
    find(id: string): Nullable<BotSpec>;
    /**
     * The bot called [id], or a failure naming those there are.
     */
    get(id: string): BotSpec;
    /**
     * Every bot, in the order given.
     */
    get all(): Array<BotSpec>;
}
export declare namespace BotRegistry {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => BotRegistry;
    }
}
/**
 * Something a run stopped to ask a person.
 */
export declare interface Question {
    /**
     * The question itself.
     */
    readonly title: string;
    /**
     * What saying yes does, and what saying no does.
     */
    readonly summary?: Nullable<string>;
    /**
     * What is about to change, as a unified diff, when there is one to show.
     */
    readonly diff?: Nullable<string>;
}
/**
 * The wikis a fleet edits through, one session per account and wiki, so bots on one account share one login
 * and one budget. A wrong password shows up when a session opens, so it fails the bot pointed at it, not the
 * fleet. Close it when done.
 */
export declare class Accounts {
    private constructor();
    /**
     * The wiki at [endpoint], as [account], or the default one, logging in if this is the first time it is
     * wanted. Closing the accounts closes it.
     */
    wiki(endpoint: Endpoint, account?: Nullable<string>): Promise<Wiki>;
    /**
     * Closes every session.
     */
    close(): void;
}
export declare namespace Accounts {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Accounts;
    }
}
/**
 * What a fleet is opened with by `createFleetWith`, which is everything a fleet can be told.
 */
export declare interface FleetHostOptions {
    /**
     * Where the bots' wikis come from. Closing the fleet leaves them open.
     */
    readonly accounts: Accounts;
    /**
     * Which wiki they edit.
     */
    readonly endpoint: Endpoint;
    /**
     * What every run answers to; nothing unless given.
     */
    readonly stop?: Nullable<StopPolicy>;
    /**
     * Where runs may write files, each under `<bot>/<run>`. Nowhere unless given. Node only.
     */
    readonly directory?: Nullable<string>;
    /**
     * Who answers a run's question, and may return a promise. Without it, the answer is no.
     */
    readonly onQuestion?: Nullable<(bot: string, question: Question) => Awaitable<boolean>>;
    /**
     * What the time is, for schedules; the system's clock unless given.
     */
    readonly now?: Nullable<() => Date>;
    /**
     * Takes each line the bots say, prefixed by the bot's id.
     */
    readonly onLine?: Nullable<(line: string) => void>;
    /**
     * How often a run that hands pages to `outcomes` says how far it has got, in milliseconds; 30 seconds
     * unless given.
     */
    readonly heartbeat?: Nullable<number>;
}
/**
 * How a recording context behaves.
 */
export declare interface RecordingOptions {
    /**
     * What the bot answers to; nothing unless given.
     */
    readonly stopPolicy?: Nullable<StopPolicy>;
    /**
     * The common flags to give it.
     */
    readonly options?: Nullable<CommonOptions>;
    /**
     * The rest of its command line.
     */
    readonly arguments?: Nullable<Array<string>>;
    /**
     * Where it may write files.
     */
    readonly directory?: Nullable<string>;
    /**
     * What to say to a question; no unless given.
     */
    readonly answer?: Nullable<(question: Question) => boolean>;
}
/**
 * A `RunContext` for testing a bot with no fleet behind it: it writes down what the bot says, and answers as
 * told. `open` gives back another handle on the same wiki, and `step` runs in the same context.
 */
export declare class RecordingContext {
    constructor(wiki: Wiki, options?: Nullable<RecordingOptions>);
    /**
     * What to hand the bot.
     */
    get context(): RunContext;
    /**
     * Everything said, in order.
     */
    get said(): Array<string>;
    /**
     * Everything asked, in order.
     */
    get asked(): Array<Question>;
    /**
     * Every page handed to `outcomes`, in order.
     */
    get pages(): Array<PageOutcome>;
    /**
     * The total of what was passed to `expect`.
     */
    get expected(): number;
    /**
     * The first reason passed to `stopped`.
     */
    get stoppedBecause(): Nullable<string>;
}
export declare namespace RecordingContext {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RecordingContext;
    }
}
/**
 * The name this library goes by in a user agent.
 */
export declare const LIBRARY_NAME: string;
/**
 * Where to read about this library, for a user agent that has nothing better to point at.
 */
export declare const LIBRARY_URL: string;
/**
 * The login the environment holds under [prefix]: an `OAuth2` from `<prefix>_OAUTH_TOKEN`, or a `BotPassword`
 * from `<prefix>_ACCOUNT`, `<prefix>_BOT_NAME` and `<prefix>_PASSWORD`. `null` when neither is set, which a
 * bot that must log in can check for, and always in a browser, which has no environment.
 * 
 * [environment] reads a variable, the process environment unless given. Throws when a bot password is set
 * only in part, which is a mistake rather than a choice to run anonymously.
 */
export declare function credentialsFromEnvironment(prefix?: string, environment?: Nullable<(name: string) => Nullable<string>>): Nullable<BotPassword>;
/**
 * This library's version.
 */
export declare function libraryVersion(): string;
/**
 * What this runs on, such as Node's version, as a bug report says it.
 */
export declare function libraryRuntime(): string;
/**
 * The operating system this runs on, as a bug report says it.
 */
export declare function libraryPlatform(): string;
/**
 * The runtime and operating system this runs on, and the library's version, one line each, for a bug report.
 */
export declare function versionReport(): string;
/**
 * How much the library says on the console: `"trace"`, `"debug"`, `"info"`, `"warn"`, `"error"` or `"off"`.
 * `"info"` until set, and it holds for every wiki in the process.
 */
export declare function setLogLevel(level: LogLevel): void;
/**
 * How much the library says on the console, as `setLogLevel` spells it.
 */
export declare function logLevel(): LogLevel;
/**
 * An OAuth 2.0 owner-only access token, to log in with. It is a kind of `BotPassword`, so it goes wherever
 * one does: `connect`, `connectWith`, a fleet's `login` and `accounts`.
 * 
 * It is sent as a bearer header on every request, with no login round trip. Its [account] is its [username],
 * or `""` when none was given, and its [botName] is `""`.
 */
export declare class OAuth2 extends BotPassword.$metadata$.constructor {
    constructor(accessToken: string, username?: Nullable<string>);
    /**
     * The account the token belongs to, which keeps the answers it is given apart in a cache.
     */
    get username(): Nullable<string>;
}
export declare namespace OAuth2 {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => OAuth2;
    }
}
/**
 * The `User-Agent` a bot sends: Wikimedia's policy asks for a name and a way to reach its operator.
 */
export declare class UserAgent {
    constructor(tool: string, version: string, contact: string);
    /**
     * The header's value.
     */
    toString(): string;
    /**
     * The bot or tool name, such as `"FenaBot"`.
     */
    get tool(): string;
    /**
     * The tool's version, such as `"1.0"`.
     */
    get version(): string;
    /**
     * A URL or email address an operator can be reached at.
     */
    get contact(): string;
    /**
     * The header's value, in the form Wikimedia's policy asks for.
     */
    get headerValue(): string;
}
export declare namespace UserAgent {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => UserAgent;
    }
}
/**
 * Virtual: file pages addressed as media.
 */
export declare const NAMESPACE_MEDIA: number;
/**
 * Virtual: generated pages such as `Special:RecentChanges`.
 */
export declare const NAMESPACE_SPECIAL: number;
/**
 * Article space, and the only namespace whose titles carry no prefix.
 */
export declare const NAMESPACE_MAIN: number;
/**
 * Discussion of article-space pages.
 */
export declare const NAMESPACE_TALK: number;
/**
 * Personal pages, and where a bot's own subpages belong.
 */
export declare const NAMESPACE_USER: number;
/**
 * Where a message left for a person arrives.
 */
export declare const NAMESPACE_USER_TALK: number;
/**
 * The wiki about itself: policy, process, and the pages a bot is approved on.
 */
export declare const NAMESPACE_PROJECT: number;
/**
 * Discussion of the wiki's own pages.
 */
export declare const NAMESPACE_PROJECT_TALK: number;
/**
 * File description pages.
 */
export declare const NAMESPACE_FILE: number;
/**
 * Discussion of file description pages.
 */
export declare const NAMESPACE_FILE_TALK: number;
/**
 * Interface messages, editable only by administrators.
 */
export declare const NAMESPACE_MEDIAWIKI: number;
/**
 * Discussion of interface messages.
 */
export declare const NAMESPACE_MEDIAWIKI_TALK: number;
/**
 * Templates.
 */
export declare const NAMESPACE_TEMPLATE: number;
/**
 * Discussion of templates.
 */
export declare const NAMESPACE_TEMPLATE_TALK: number;
/**
 * Documentation written for readers and editors.
 */
export declare const NAMESPACE_HELP: number;
/**
 * Discussion of help pages.
 */
export declare const NAMESPACE_HELP_TALK: number;
/**
 * Category description pages.
 */
export declare const NAMESPACE_CATEGORY: number;
/**
 * Discussion of category description pages.
 */
export declare const NAMESPACE_CATEGORY_TALK: number;
/**
 * The hard limit MediaWiki places on a page title, in UTF-8 bytes.
 */
export declare const TITLE_MAX_BYTES: number;
/**
 * The namespaces every MediaWiki has, under their English names.
 */
export declare function canonicalNamespaces(): Namespaces;
/**
 * A wiki's namespaces, as [namespaces] describes them, for reading titles before it has been opened.
 */
export declare function createNamespaces(namespaces: Array<NamespaceOptions>): Namespaces;
/**
 * A wiki's interwiki prefixes; [selfPrefixes] point back at the wiki itself, and resolve to a local page.
 */
export declare function createInterwiki(prefixes: Array<string>, selfPrefixes?: Array<string>): Interwiki;
/**
 * Reads [text] as a page title the way MediaWiki does, against what [options] gives.
 */
export declare function parseTitleWith(text: string, options?: Nullable<TitleOptions>): Title;
/**
 * Reads a MediaWiki timestamp, ISO 8601 or the compact `20260831214326`. Throws when it is neither.
 */
export declare function parseTimestamp(raw: string): Date;
/**
 * Reads a MediaWiki timestamp as `parseTimestamp` does, or `null` when it is neither form.
 */
export declare function parseTimestampOrNull(raw: string): Nullable<Date>;
/**
 * Whether [raw] is one of MediaWiki's spellings of an expiry that never arrives, such as `"infinity"`.
 */
export declare function isNeverTimestamp(raw: string): boolean;
/**
 * Writes [date] as the API expects it, ISO 8601 to whole seconds.
 */
export declare function formatTimestamp(date: Date): string;
/**
 * Writes [date] in the compact form, `20260831214326`.
 */
export declare function formatTimestampCompact(date: Date): string;
/**
 * Reads an expiry as the API spells one, or `null` for one of its spellings of "never".
 * 
 * @throws IllegalArgumentException if [raw] is neither.
 */
export declare function parseExpiry(raw: string): Nullable<Date>;
/**
 * Compares two MediaWiki versions as numbers, and a suffix loses to its absence: negative when [first] is
 * older than [second], positive when newer, 0 when the same. `1.10` is newer than `1.9`, and `1.44.0-wmf.3`
 * older than `1.44.0`.
 */
export declare function compareMediaWikiVersions(first: string, second: string): number;
/**
 * A namespace by its number, which is what identifies it on every wiki.
 */
export declare class Namespace {
    constructor(id: number);
    /**
     * The number with a marker, `ns:14`.
     */
    toString(): string;
    /**
     * The number.
     */
    get id(): number;
    /**
     * Whether this is a talk namespace, which are the odd-numbered ones.
     */
    get isTalk(): boolean;
    /**
     * Whether it has no pages of its own in the database: `Media` and `Special`.
     */
    get isVirtual(): boolean;
    /**
     * The number of its talk namespace, this one's when it is one, or `null` when it is virtual.
     */
    get talkSpace(): Nullable<number>;
    /**
     * The number of its subject namespace, this one's when it is one, or `null` when it is virtual.
     */
    get subjectSpace(): Nullable<number>;
}
export declare namespace Namespace {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Namespace;
    }
}
/**
 * One namespace to give to `createNamespaces`.
 */
export declare interface NamespaceOptions {
    /**
     * Its number.
     */
    readonly id: number;
    /**
     * The English canonical name, such as `"Category"`; absent for the main namespace.
     */
    readonly canonicalName?: Nullable<string>;
    /**
     * The wiki's own name for it, such as `"Kategoria"`.
     */
    readonly localName?: Nullable<string>;
    /**
     * Other accepted prefixes, such as `"WP"` for `"Wikipedia"`.
     */
    readonly aliases?: Nullable<Array<string>>;
    /**
     * How the first letter of a title here is treated: `"first-letter"`, the default, or `"case-sensitive"`.
     */
    readonly case?: Nullable<TitleCase>;
    /**
     * Whether a slash in a title makes a subpage here; `false` unless given.
     */
    readonly subpages?: Nullable<boolean>;
}
/**
 * What a title is read against.
 */
export declare interface TitleOptions {
    /**
     * The wiki's namespaces; those every MediaWiki has, in English, unless given.
     */
    readonly namespaces?: Nullable<Namespaces>;
    /**
     * The wiki's interwiki prefixes; none unless given.
     */
    readonly interwiki?: Nullable<Interwiki>;
    /**
     * The number of the namespace a title with no prefix is in; the main one unless given.
     */
    readonly defaultNamespace?: Nullable<number>;
}
/**
 * A link to a page on another wiki, such as `[[fr:Volcan]]`, to build rather than to parse.
 */
export declare class SiteLink {
    constructor(prefix: string, title: string, text?: Nullable<string>);
    /**
     * The wikitext of this link. [forced] writes the leading colon that makes it a link in the sentence
     * rather than an interlanguage link filed at the bottom of the page.
     */
    render(forced?: boolean): string;
    /**
     * The wikitext of this link, unforced.
     */
    toString(): string;
    /**
     * The interwiki prefix, such as `"fr"`, `"w"` or `"commons"`.
     */
    get prefix(): string;
    /**
     * The title on that wiki, with its own namespace prefix when it has one.
     */
    get title(): string;
    /**
     * The text to show, or `null` to show the target.
     */
    get text(): Nullable<string>;
}
export declare namespace SiteLink {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SiteLink;
    }
}
/**
 * Plain text as a node.
 */
export declare function createText(text: string): Node;
/**
 * A comment holding [contents].
 */
export declare function createComment(contents: string, closed?: boolean): Comment;
/**
 * A template's argument called [name], with what to use when it is not supplied.
 */
export declare function createArgument(name: Wikitext, defaultValue?: Nullable<Wikitext>): Argument;
/**
 * A link to the page [target], showing [text] when it is given.
 */
export declare function createLink(target: Wikitext, text?: Nullable<Wikitext>): Link;
/**
 * A link off the wiki to [url], labelled [title]; [brackets] is off for a bare URL in running text.
 */
export declare function createExternalLink(url: Wikitext, title?: Nullable<Wikitext>, brackets?: boolean, separator?: string): ExternalLink;
/**
 * A heading with [level] equals signs on each side.
 */
export declare function createHeading(title: Wikitext, level: number): Heading;
/**
 * An entity with the body [value]; [hexChar] is the `x` or `X` of a hexadecimal one.
 */
export declare function createHtmlEntity(value: string, numeric?: boolean, hexChar?: Nullable<HexMarker>): HtmlEntity;
/**
 * An attribute called [name]; [quote] is the quote character, and the rest the whitespace around it.
 */
export declare function createAttribute(name: Wikitext, value?: Nullable<Wikitext>, quote?: Nullable<QuoteMark>, padFirst?: string, padBeforeEq?: string, padAfterEq?: string): Attribute;
/**
 * A tag called [name].
 */
export declare function createTag(name: string, options?: Nullable<TagOptions>): Tag;
/**
 * What a tag is made with beyond its name.
 */
export declare interface TagOptions {
    /**
     * What sits between the opening and closing tags.
     */
    readonly contents?: Nullable<Wikitext>;
    /**
     * Its attributes.
     */
    readonly attributes?: Nullable<Array<Attribute>>;
    /**
     * Whether it is written as `<br />` rather than as a pair.
     */
    readonly selfClosing?: Nullable<boolean>;
    /**
     * The markup to write in place of HTML: `'''` for bold, `*` for a list item.
     */
    readonly wikiMarkup?: Nullable<string>;
    /**
     * The whitespace before the closing `>`.
     */
    readonly padding?: Nullable<string>;
    /**
     * Whether the closing tag is absent.
     */
    readonly implicitClose?: Nullable<boolean>;
    /**
     * The closing tag to write when it differs from `</name>`.
     */
    readonly closing?: Nullable<string>;
    /**
     * Whether [contents] is the body as written rather than parsed.
     */
    readonly verbatim?: Nullable<boolean>;
}
/**
 * A source that names nothing.
 */
export declare const EMPTY_PAGE_SOURCE: PageSource;
/**
 * A source naming [titles], resolved against the wiki when it runs.
 */
export declare function pageSourceOfTitles(titles: Array<string>): PageSource;
/**
 * A source naming the titles [titles] gives, such as a listing of a wiki or a generator. It may be walked
 * once for each time the source is, so one that can be walked only once makes a source that can be used only
 * once.
 */
export declare function pageSourceOf(titles: AsyncIterable<string>): PageSource;
/**
 * A source naming what [pages] gives for a wiki, for one worked out from the wiki it runs on.
 */
export declare function pageSourceFrom(pages: (wiki: Wiki) => AsyncIterable<string>): PageSource;
/**
 * Every one of [sources] in turn, in the order given.
 */
export declare function concatPageSources(sources: Array<PageSource>): PageSource;
/**
 * The source a command line names, as `kind:argument`: `cat:English lemmas`, `page:volcano`,
 * `transcludes:Template:col`. `pageSourceKinds` lists the kinds, and a `file:` one needs a filesystem.
 * 
 * @throws IllegalArgumentException when the kind is not one of them, listing them.
 */
export declare function parsePageSource(spec: string): PageSource;
/**
 * Every source in a list of [specs], in order.
 */
export declare function parsePageSources(specs: Array<string>): PageSource;
/**
 * The kinds `parsePageSource` understands.
 */
export declare function pageSourceKinds(): Array<string>;
/**
 * Where a bot run gets its pages: a recipe that names them once it has a wiki. Each narrowing makes a new
 * source and leaves this one as it was. None of them fetches a page's text.
 */
export declare class PageSource {
    private constructor();
    /**
     * The titles this source names on [wiki], prefix included, fetched as the walk asks.
     */
    pages(wiki: Wiki): AsyncIterable<string>;
    /**
     * Repeats this source, naming only what it has not named before, for a bot that watches a wiki. The walk
     * never ends on its own.
     * 
     * @param every - how long to wait between rounds, in milliseconds; a minute unless given.
     * @param remember - how many titles to keep in the seen set; 5000 unless given.
     */
    repeating(every?: Nullable<number>, remember?: Nullable<number>): PageSource;
    /**
     * Only pages in the namespaces numbered [namespaces]. Every namespace passes when it is empty.
     */
    inNamespaces(namespaces: Array<number>): PageSource;
    /**
     * Only pages whose title without its prefix matches [pattern].
     */
    titleMatching(pattern: RegExp): PageSource;
    /**
     * Only pages whose title without its prefix does not match [pattern].
     */
    titleNotMatching(pattern: RegExp): PageSource;
    /**
     * Drops pages already named. The set of those grows with the walk.
     */
    distinctPages(): PageSource;
    /**
     * Only pages no deeper than [maxDepth] slashes below their base page; `0` keeps base pages only.
     */
    subpageDepthAtMost(maxDepth: number): PageSource;
    /**
     * Only pages [other] also names. [other] is walked in full first, so put the smaller one second.
     */
    intersect(other: PageSource): PageSource;
    /**
     * Only pages [other] does not name. [other] is walked in full first.
     */
    excluding(other: PageSource): PageSource;
    /**
     * The text of the pages of [wiki] this source names, fetched in batches as the walk asks.
     * 
     * @param wiki - the wiki whose pages are read.
     * @param batch - how many pages one request names; as many as the account may ask for unless given.
     * @param concurrency - how many batches are fetched at once; 1 unless given. Pages still come out in order.
     */
    withContent(wiki: Wiki, batch?: Nullable<number>, concurrency?: number): PageContents;
}
export declare namespace PageSource {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PageSource;
    }
}
/**
 * Pages with their text. Each narrowing makes a new stream and leaves this one as it was.
 */
export declare class PageContents {
    private constructor();
    /**
     * The pages, fetched as the walk asks.
     */
    pages(): AsyncIterable<Page>;
    /**
     * Only pages whose text matches [pattern].
     */
    textMatching(pattern: RegExp): PageContents;
    /**
     * Only pages whose text does not match [pattern].
     */
    textNotMatching(pattern: RegExp): PageContents;
    /**
     * Only pages that are redirects, or with [keep] `false`, only pages that are not.
     */
    redirects(keep?: boolean): PageContents;
}
export declare namespace PageContents {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PageContents;
    }
}
/**
 * What en.wikipedia and en.wiktionary parse with. Its lists are Wikimedia's own.
 */
export declare function defaultParseOptions(): ParseOptions;
/**
 * Parse options for a wiki that is not open, with what [options] gives in place of Wikimedia's.
 */
export declare function createParseOptions(options: ParseOptionsInit): ParseOptions;
/**
 * Names recognised in `{{...}}`, those in [caseSensitive] compared exactly, the others ignoring case.
 */
export declare function createMagicWords(caseSensitive: Array<string>, caseInsensitive: Array<string>): MagicWords;
/**
 * The rules every MediaWiki starts from. Its tables are the core ones.
 */
export declare function defaultTitleRules(): TitleRules;
/**
 * Title rules for a wiki that is not open, with what [options] gives in place of the core ones.
 */
export declare function createTitleRules(options: TitleRulesInit): TitleRules;
/**
 * MediaWiki's own ids for the core parser functions, whatever a wiki calls them.
 */
export declare function coreFunctionIds(): Array<string>;
/**
 * What a wiki's configuration changes about how its wikitext parses. What is left out is Wikimedia's.
 */
export declare interface ParseOptionsInit {
    /**
     * The tags whose bodies the preprocessor steps over, without their brackets.
     */
    readonly extensionTags?: Nullable<Array<string>>;
    /**
     * The extension tags whose body is itself wikitext, such as `ref`.
     */
    readonly parsedTags?: Nullable<Array<string>>;
    /**
     * The extension tags whose body has only its templates expanded, such as `inputbox`.
     */
    readonly preprocessedTags?: Nullable<Array<string>>;
    /**
     * The HTML elements the wiki allows.
     */
    readonly htmlTags?: Nullable<Array<string>>;
    /**
     * The URL schemes that make a link.
     */
    readonly protocols?: Nullable<Array<string>>;
    /**
     * Whether `-{ }-` is markup.
     */
    readonly languageConversion?: Nullable<boolean>;
    /**
     * The names of the File namespace.
     */
    readonly fileNamespaces?: Nullable<Array<string>>;
}
/**
 * How a wiki names pages. What is left out is what every MediaWiki has.
 */
export declare interface TitleRulesInit {
    /**
     * Every namespace name and alias, with the namespace's number.
     */
    readonly namespaces?: Nullable<Record<string, number>>;
    /**
     * The name each namespace, by number, is written with in a key.
     */
    readonly canonicalNames?: Nullable<Record<string, string>>;
    /**
     * The namespaces whose titles keep their first letter as written.
     */
    readonly caseSensitive?: Nullable<Array<number>>;
    /**
     * The parser functions written without a `#`.
     */
    readonly functions?: Nullable<MagicWords>;
    /**
     * The variables, such as `PAGENAME`.
     */
    readonly variables?: Nullable<MagicWords>;
    /**
     * The prefixes that save a template's expansion instead of the template, such as `subst`.
     */
    readonly substitutions?: Nullable<Array<string>>;
    /**
     * The other prefixes that change how a template is transcluded, such as `msg`.
     */
    readonly modifiers?: Nullable<Array<string>>;
}
/**
 * The pages a saved PetScan query returns.
 * 
 * @param id - the PSID of the saved query.
 * @param contact - a URL or email address the tool's operators can reach you at.
 * @param agent - who to say is asking, in place of the library's name with the contact.
 */
export declare function petScanSavedSource(id: string, contact: string, agent?: Nullable<UserAgent>): PageSource;
/**
 * The pages a PetScan query returns, given the query itself.
 * 
 * @param query - PetScan's own parameters; `format` and `doit` are supplied.
 * @param contact - a URL or email address the tool's operators can reach you at.
 * @param agent - who to say is asking, in place of the library's name with the contact.
 */
export declare function petScanSource(query: Record<string, string>, contact: string, agent?: Nullable<UserAgent>): PageSource;
/**
 * The titles a PetScan query returns, as written, without resolving them against a wiki.
 * 
 * @param query - PetScan's own parameters; `format` and `doit` are supplied.
 * @param contact - a URL or email address the tool's operators can reach you at.
 * @param agent - who to say is asking, in place of the library's name with the contact.
 */
export declare function petScanTitles(query: Record<string, string>, contact: string, agent?: Nullable<UserAgent>): Promise<Array<string>>;
/**
 * Gives [use] the titles a PetScan query returns, one at a time, spooled to a temporary file as they arrive
 * rather than held in memory, and answers with what it answers. The titles can be walked once, and a walk
 * begun after [use] has settled finds none.
 * 
 * @param T - what [use] answers with.
 * @param query - PetScan's own parameters; `format` and `doit` are supplied.
 * @param contact - a URL or email address the tool's operators can reach you at.
 * @param use - what to do with the titles, which may return a promise.
 * @param agent - who to say is asking, in place of the library's name with the contact.
 */
export declare function withPetScanTitles<T>(query: Record<string, string>, contact: string, use: (titles: AsyncIterable<string>) => Awaitable<T>, agent?: Nullable<UserAgent>): Promise<T>;
/**
 * A prompt that asks through [handlers].
 */
export declare function promptFrom(handlers: PromptHandlers): Prompt;
/**
 * One choice offered to a person.
 */
export declare interface Choice {
    /**
     * The single character that selects it, matched case-insensitively.
     */
    readonly key: string;
    /**
     * What it does, shown in the prompt.
     */
    readonly label: string;
    /**
     * What selecting it yields.
     */
    readonly value: string;
}
/**
 * Asking a person something, and what to do when no person is present. `TerminalPrompt` reads the terminal,
 * `NonInteractive` answers with a configured default, `ScriptedPrompt` has the answers written down, and
 * `promptFrom` makes one of your own.
 */
export declare class Prompt {
    private constructor();
    /**
     * Asks a yes-or-no question, whose answer is [fallback] when a person gives none.
     */
    confirm(question: string, fallback?: boolean): Promise<boolean>;
    /**
     * Asks for one of [choices], and answers with its value.
     */
    choose(question: string, choices: Array<Choice>): Promise<string>;
    /**
     * Asks for a line of text, whose answer is [fallback] when a person gives none.
     */
    text(question: string, fallback?: string): Promise<string>;
    /**
     * Whether there is a person to ask.
     */
    get isInteractive(): boolean;
}
export declare namespace Prompt {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Prompt;
    }
}
/**
 * A prompt with no person behind it: every question is answered with the configured default and counted, so a
 * bot can say in its report how many decisions it made on the operator's behalf. Of a list of choices, it
 * takes the first.
 */
export declare class NonInteractive extends Prompt.$metadata$.constructor {
    constructor(answer?: boolean, textAnswer?: string);
    /**
     * How many questions were answered without anybody being asked.
     */
    get asked(): number;
}
export declare namespace NonInteractive {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => NonInteractive;
    }
}
/**
 * A prompt that reads the terminal. If standard input reaches its end, the default is taken.
 * 
 */
export declare class TerminalPrompt extends Prompt.$metadata$.constructor {
    /**
     * @param output - what the question is written with; standard output unless given.
     * @param input - gives the next line typed, or `null` once input has ended, and may return a promise; standard
     * input unless given, which needs Node.
     */
    constructor(output?: Nullable<(text: string) => void>, input?: Nullable<() => Awaitable<Nullable<string>>>);
}
export declare namespace TerminalPrompt {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TerminalPrompt;
    }
}
/**
 * A prompt with the answers written down in advance, for tests and for a run that knows what it wants. An
 * empty answer is the Enter key. Running out of answers falls back to [fallback], a `NonInteractive` unless
 * given.
 */
export declare class ScriptedPrompt extends Prompt.$metadata$.constructor {
    constructor(answers: Array<string>, fallback?: Nullable<Prompt>);
}
export declare namespace ScriptedPrompt {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ScriptedPrompt;
    }
}
/**
 * A prompt written in JavaScript. Each function may return a promise.
 */
export declare interface PromptHandlers {
    /**
     * Whether there is a person to ask; `true` unless given.
     */
    readonly isInteractive?: Nullable<boolean>;
    /**
     * Asks a yes-or-no question.
     */
    readonly confirm: (question: string, fallback: boolean) => Awaitable<boolean>;
    /**
     * Asks for one of [choices], and answers with its value.
     */
    readonly choose: (question: string, choices: Array<Choice>) => Awaitable<string>;
    /**
     * Asks for a line of text.
     */
    readonly text: (question: string, fallback: string) => Awaitable<string>;
}
/**
 * The quality named by the [level] ProofreadPage stores, as `PageQuality` spells it, or `null` when no
 * quality has that number.
 */
export declare function proofreadQualityOf(level: number): Nullable<ProofreadQuality>;
/**
 * Reads the wikitext of a `Page:` page. A page not in the usual shape comes back as all body, and writing
 * that back gives the page as it was.
 */
export declare function parseProofreadText(wikitext: string): ProofreadText;
/**
 * Wikisource's scanned pages, reached as `wiki.proofread`. Every method rejects without the ProofreadPage
 * extension rather than answering with nothing.
 */
export declare class ProofreadService {
    private constructor();
    /**
     * The proofreading quality of each of [titles] that has one.
     */
    quality(titles: Array<string>): Promise<Array<PageQuality>>;
    /**
     * The index the scanned page [title] belongs to, which is the book it is part of, or `null`.
     */
    indexOf(title: string): Promise<Nullable<string>>;
    /**
     * The titles of the scanned pages of [index], in page order.
     */
    pagesOf(index: string): Promise<Array<string>>;
}
export declare namespace ProofreadService {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ProofreadService;
    }
}
/**
 * How far one scanned page has got through proofreading.
 */
export declare class PageQuality {
    private constructor();
    /**
     * The page.
     */
    get title(): string;
    /**
     * `"without-text"`, `"not-proofread"`, `"problematic"`, `"proofread"` or `"validated"`.
     */
    get quality(): ProofreadQuality;
    /**
     * The number ProofreadPage stores, from 0 to 4, in the order of progress.
     */
    get level(): number;
}
export declare namespace PageQuality {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PageQuality;
    }
}
/**
 * A page in the `Page:` namespace, split into the parts a bot edits separately.
 * 
 */
export declare class ProofreadText {
    /**
     * @param header - everything inside the opening `<noinclude>`, after the quality marker.
     * @param body - the transcribed text, which is what a reader of the book sees.
     * @param footer - everything inside the closing `<noinclude>`.
     * @param quality - `"without-text"`, `"not-proofread"`, `"problematic"`, `"proofread"` or `"validated"`, absent
     * where the wiki did not say.
     * @param proofreader - who last set the quality.
     */
    constructor(header: string, body: string, footer: string, quality?: Nullable<ProofreadQuality>, proofreader?: Nullable<string>);
    /**
     * This page with a different body and everything else untouched.
     */
    withBody(body: string): ProofreadText;
    /**
     * The wikitext. A page read and not changed comes back exactly as it was.
     */
    serialize(): string;
    /**
     * Everything inside the opening `<noinclude>`, after the quality marker.
     */
    get header(): string;
    /**
     * The transcribed text, which is what a reader of the book sees.
     */
    get body(): string;
    /**
     * Everything inside the closing `<noinclude>`.
     */
    get footer(): string;
    /**
     * How far the page has been proofread, as the constructor takes it, or `null`.
     */
    get quality(): Nullable<ProofreadQuality>;
    /**
     * The number ProofreadPage stores for the quality, or `null`.
     */
    get qualityLevel(): Nullable<number>;
    /**
     * Who last set the quality. A bot should not rewrite it: it is a claim about a person.
     */
    get proofreader(): Nullable<string>;
}
export declare namespace ProofreadText {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ProofreadText;
    }
}
/**
 * The error in [response], an answer `Wiki.call` gave, as `describeError` reads one, or `null` when it holds
 * none.
 */
export declare function responseError(response: Record<string, JsonValue>): Nullable<ErrorInfo>;
/**
 * [response], an answer `Wiki.call` gave, or a throw of the error in it, for a caller that wants one.
 */
export declare function throwOnResponseError(response: Record<string, JsonValue>): Record<string, JsonValue>;
/**
 * Reads what `Wiki.call` and `Wiki.callPaged` answer into this package's own values, for a query this library
 * does not model but whose entries it can read. Each entry is one element of what the wiki listed, as plain
 * JSON. Titles are read with the wiki's namespaces.
 */
export declare class ResponseDecoder {
    private constructor();
    /**
     * One entry of a `query` result's `pages`.
     */
    decodePage(page: Record<string, JsonValue>): DecodedPage;
    /**
     * Every revision a `query` result's page entry carries with its content, newest as the wiki listed it.
     * 
     * @param page - the entry.
     * @param isRedirect - whether a text is a redirect; none is unless given.
     */
    decodeContents(page: Record<string, JsonValue>, isRedirect?: Nullable<(text: string) => boolean>): Array<Page>;
    /**
     * One entry of a page's `revisions`.
     */
    decodeRevision(revision: Record<string, JsonValue>): Revision;
    /**
     * The title a page entry names, as this wiki writes it, or `null` when it names none.
     */
    titleOf(page: Record<string, JsonValue>): Nullable<string>;
    /**
     * One entry of `list=logevents`.
     */
    decodeLogEvent(entry: Record<string, JsonValue>): LogEvent;
    /**
     * One entry of `list=recentchanges`.
     */
    decodeRecentChange(entry: Record<string, JsonValue>): RecentChange;
    /**
     * One entry of `list=usercontribs`, or `null` when what it says of the page is hidden.
     */
    decodeContribution(entry: Record<string, JsonValue>): Nullable<Contribution>;
    /**
     * One entry of `list=blocks`.
     */
    decodeBlockRecord(entry: Record<string, JsonValue>): BlockRecord;
    /**
     * One entry of `list=users`.
     */
    decodeUser(entry: Record<string, JsonValue>): UserInfo;
    /**
     * The `userinfo` of `meta=userinfo`.
     */
    decodeCurrentUser(entry: Record<string, JsonValue>): UserInfo;
}
export declare namespace ResponseDecoder {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ResponseDecoder;
    }
}
/**
 * One entry of a `query` result's `pages`.
 */
export declare class DecodedPage {
    private constructor();
    /**
     * `"existing"`, `"missing"`, or `"invalid"` for a title the wiki refused.
     */
    get kind(): DecodedPageKind;
    /**
     * The page's title, or on an `"invalid"`, the title as it was sent.
     */
    get title(): string;
    /**
     * On an `"invalid"`, why the wiki refused it.
     */
    get reason(): Nullable<string>;
    /**
     * On an `"existing"`, what kind of content it holds.
     */
    get contentModel(): Nullable<string>;
    /**
     * On an `"existing"`, whether it redirects elsewhere.
     */
    get isRedirect(): Nullable<boolean>;
    /**
     * On an `"existing"`, its newest revision, when the query asked for revisions.
     */
    get latestRevision(): Nullable<Revision>;
    /**
     * On an `"existing"`, its text, when the query asked for content.
     */
    get content(): Nullable<Page>;
}
export declare namespace DecodedPage {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => DecodedPage;
    }
}
/**
 * Makes a log writing where [options] says, and nowhere unless it says.
 */
export declare function createRunLog(options?: Nullable<RunLogOptions>): RunLog;
/**
 * Makes a progress display as [options] says.
 */
export declare function createProgress(options?: Nullable<ProgressOptions>): Progress;
/**
 * What a run has done, kept on disk as it happens, so a run that stops halfway can carry on where it left
 * off. Three files in [directory], `finished.tsv`, `pins.tsv` and `saves.jsonl`, each flushed as each page
 * finishes. Set on a run with `state`, and read back by setting `resume`. Pages are keyed by title, so one
 * directory belongs to one wiki.
 * 
 * Works in Node only: in a browser, which has no filesystem, making one throws.
 */
export declare class RunState {
    constructor(directory: string);
    /**
     * How many pages earlier runs into this directory finished.
     */
    finishedCount(): Promise<number>;
    /**
     * Whether a run into this directory already finished page [title] of [wiki].
     */
    isFinished(wiki: Wiki, title: string): Promise<boolean>;
    /**
     * The revision the outcome of page [title] of [wiki] was last decided on, or `null` if it never was.
     */
    pin(wiki: Wiki, title: string): Promise<Nullable<number>>;
    /**
     * Records [outcome], decided on [revision] of the page. Saved edits pin the revision they made. A run
     * does this itself; this is for a bot with a loop of its own.
     */
    record(outcome: PageOutcome, revision?: Nullable<number>): Promise<void>;
    /**
     * Closes the files, once the run that records into them is over.
     */
    close(): void;
    /**
     * Where the files are kept. Created if it does not exist.
     */
    get directory(): string;
}
export declare namespace RunState {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RunState;
    }
}
/**
 * Where a run's log goes. Each may be a file, a function or neither, which writes none of that record.
 */
export declare interface RunLogOptions {
    /**
     * The file to add unified diffs to, for edits computed in a dry run and edits saved. Node only.
     */
    readonly diffFile?: Nullable<string>;
    /**
     * The file to add skip records to, one JSON object per line. Node only.
     */
    readonly skipFile?: Nullable<string>;
    /**
     * Takes the diffs as they are written, in place of [diffFile].
     */
    readonly onDiff?: Nullable<(text: string) => void>;
    /**
     * Takes the skip records as they are written, in place of [skipFile].
     */
    readonly onSkip?: Nullable<(text: string) => void>;
    /**
     * How many unchanged lines to show either side of a change in a diff; 3 unless given.
     */
    readonly context?: Nullable<number>;
    /**
     * The wiki's namespaces, for naming pages the way it does; the canonical English ones unless given.
     */
    readonly namespaces?: Nullable<Namespaces>;
}
/**
 * Writes what a run did as it happens: the diffs, to see what the bot changed or would change, and the skips,
 * to see what it left alone and why. Flushed as they are written, so a run that is interrupted still leaves a
 * usable record.
 */
export declare class RunLog {
    private constructor();
    /**
     * Writes what [outcome] calls for: a diff for an edit, a record for a page left alone.
     */
    record(outcome: PageOutcome): void;
}
export declare namespace RunLog {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RunLog;
    }
}
/**
 * What a progress display shows, and where.
 */
export declare interface ProgressOptions {
    /**
     * How many pages the run will see, when known, for a bar and the time left.
     */
    readonly total?: Nullable<number>;
    /**
     * Takes the line as it is drawn; standard error, or the console in a browser, unless given.
     */
    readonly write?: Nullable<(text: string) => void>;
    /**
     * Whether to draw anything; `true` unless given. Off for a run whose output is not a terminal.
     */
    readonly enabled?: Nullable<boolean>;
    /**
     * The widest the line may be: the `COLUMNS` the shell reports, or 100, unless given.
     */
    readonly width?: Nullable<number>;
    /**
     * What the time is, in milliseconds, for the rate and the redraws; the system's clock unless given.
     */
    readonly now?: Nullable<() => number>;
}
/**
 * A one-line progress display, rewritten in place, on standard error so a run's diffs can be piped somewhere
 * while it stays on the terminal. Redrawn at most five times a second. Hand each outcome to [record], and
 * call [finish] when the run is over.
 */
export declare class Progress {
    private constructor();
    /**
     * Counts [outcome], and redraws the line if it has not been drawn for a moment.
     */
    record(outcome: PageOutcome): void;
    /**
     * Writes the final line and moves to the next, so later output starts cleanly.
     */
    finish(): void;
}
export declare namespace Progress {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Progress;
    }
}
/**
 * Signatures tried with [formats], in order.
 */
export declare function signaturesOf(formats: Array<SignatureFormat>): Signatures;
/**
 * The format shipped for [language], which is English's for a language not shipped; they read as UTC.
 */
export declare function signaturesFor(language: string): Signatures;
/**
 * Every signature format the library ships.
 */
export declare function allSignatures(): Signatures;
/**
 * One wiki's signature format, for [language]. [pattern] has to name the groups `hour`, `minute`, `day`,
 * `month` and `year`; [months] are the names lowercased, with their numbers; [zone] is where the times are,
 * such as `"Europe/Berlin"`.
 */
export declare function signatureFormat(language: string, pattern: RegExp, months: Record<string, number>, zone?: string): SignatureFormat;
/**
 * Finds the timestamps that `~~~~` leaves behind, which is the only record of when a discussion last had a
 * reply. `wiki.signatures()` gives them as a wiki writes them; these functions give the ones the library
 * ships.
 */
export declare class Signatures {
    private constructor();
    /**
     * Every signature time in [wikitext], in the order they appear.
     */
    findAll(wikitext: string): Array<SignatureTime>;
    /**
     * The most recent signature time, or `null` when the text has none.
     */
    latest(wikitext: string): Nullable<SignatureTime>;
    /**
     * The earliest signature time, which is when a discussion started, or `null`.
     */
    earliest(wikitext: string): Nullable<SignatureTime>;
    /**
     * These formats and [other]'s.
     */
    plus(other: Signatures): Signatures;
    /**
     * The patterns these formats match.
     */
    patterns(): Array<RegExp>;
    /**
     * The formats tried, in order.
     */
    get formats(): Array<SignatureFormat>;
}
export declare namespace Signatures {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Signatures;
    }
}
/**
 * One wiki's signature format.
 */
export declare class SignatureFormat {
    private constructor();
    /**
     * Every timestamp in [wikitext] this format matches. Comments are left out.
     */
    findAll(wikitext: string): Array<SignatureTime>;
    /**
     * The pattern this format matches.
     */
    get pattern(): RegExp;
}
export declare namespace SignatureFormat {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SignatureFormat;
    }
}
/**
 * Reads a recorded `action=sitematrix` response.
 */
export declare function decodeSiteMatrix(response: Record<string, JsonValue>): SiteMatrix;
/**
 * Every Wikimedia wiki, from `wiki.siteMatrix()`.
 */
export declare class SiteMatrix {
    private constructor();
    /**
     * The wikis of one [project], such as `"wiktionary"`.
     */
    of(project: string): Array<MatrixWiki>;
    /**
     * The wiki of [project] in [language], or `null` when there is none.
     */
    get(language: string, project: string): Nullable<MatrixWiki>;
    /**
     * The wiki with the database name [id], or `null`.
     */
    byId(id: string): Nullable<MatrixWiki>;
    /**
     * The wikis a bot can actually edit: not closed, not private.
     */
    open(): Array<MatrixWiki>;
    /**
     * The language codes [project] exists in.
     */
    languagesOf(project: string): Array<string>;
    /**
     * Every wiki the matrix lists, in its order.
     */
    get wikis(): Array<MatrixWiki>;
}
export declare namespace SiteMatrix {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SiteMatrix;
    }
}
/**
 * One wiki in the Wikimedia site matrix.
 */
export declare class MatrixWiki {
    private constructor();
    /**
     * The wiki's database name.
     */
    get id(): string;
    /**
     * Its canonical URL.
     */
    get url(): string;
    /**
     * The language code, or `null` for a wiki with no language of its own.
     */
    get language(): Nullable<string>;
    /**
     * The project, such as `"wiktionary"`, `"wikipedia"` or `"commons"`.
     */
    get project(): Nullable<string>;
    /**
     * Whether it is closed to editing but still readable.
     */
    get isClosed(): boolean;
    /**
     * Whether it is private, so that a bot without an account cannot read it.
     */
    get isPrivate(): boolean;
    /**
     * Where its API is, assuming Wikimedia's layout.
     */
    get endpoint(): Endpoint;
}
export declare namespace MatrixWiki {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => MatrixWiki;
    }
}
/**
 * That [property] has [value], of [dataType] when it is known.
 */
export declare function valueSnak(property: string, value: DataValue, dataType?: Nullable<string>): Snak;
/**
 * That [property] deliberately has no value.
 */
export declare function noValueSnak(property: string): Snak;
/**
 * That [property] has a value which is not known.
 */
export declare function someValueSnak(property: string): Snak;
/**
 * A statement [mainSnak] makes, not saved yet: give it to `wiki.entities.setStatement`, or an edit's
 * `statements`.
 */
export declare function claim(mainSnak: Snak, options?: Nullable<ClaimOptions>): Claim;
/**
 * One property-value assertion: that [property] has a value, that it has none, or that it has one not known.
 */
export declare class Snak {
    private constructor();
    /**
     * The snak as Wikibase writes it.
     */
    toJSON(): Record<string, JsonValue>;
    /**
     * `"value"`, `"somevalue"` for an unknown value, or `"novalue"` for a stated absence.
     */
    get kind(): SnakKind;
    /**
     * The property it is about, such as `"P31"`.
     */
    get property(): string;
    /**
     * What the property is asserted to be, or `null` unless a `"value"`.
     */
    get value(): Nullable<DataValue>;
    /**
     * The property's declared data type, on a `"value"` when the response carried it.
     */
    get dataType(): Nullable<string>;
}
export declare namespace Snak {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Snak;
    }
}
/**
 * A source cited for a statement.
 */
export declare class Reference {
    constructor(snaks: Array<Snak>);
    /**
     * The snaks for [property] in this reference.
     */
    get(property: string): Array<Snak>;
    /**
     * The snaks that make up the citation, such as a URL and a retrieval date.
     */
    get snaks(): Array<Snak>;
}
export declare namespace Reference {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Reference;
    }
}
/**
 * What a statement says beyond its main snak.
 */
export declare interface ClaimOptions {
    /**
     * Wikibase's id for the statement, which makes saving it replace the one with that id. In `Claim.copy`,
     * `null` here, or on [qualifiers] or [references], clears it, and leaving it out keeps it.
     */
    readonly id?: Nullable<string>;
    /**
     * `"preferred"`, `"normal"` or `"deprecated"`; `"normal"` for a new statement unless given.
     */
    readonly rank?: Nullable<StatementRank>;
    /**
     * Conditions on the assertion: a point in time, a place.
     */
    readonly qualifiers?: Nullable<Array<Snak>>;
    /**
     * The sources cited for it.
     */
    readonly references?: Nullable<Array<Reference>>;
}
/**
 * No stop check. Only for dry runs and for wikis you own.
 */
export declare const STOP_POLICY_NONE: StopPolicy;
/**
 * A policy that asks [check], which may return a promise. Only `true` lets the bot go on; anything else, a
 * rejection or a throw stops it.
 */
export declare function stopPolicyOf(check: () => Awaitable<boolean>): StopPolicy;
/**
 * A policy letting a bot go on only while every one of [policies] does. The first to say stop, or to fail,
 * decides.
 */
export declare function allStopPolicies(policies: Array<StopPolicy>): StopPolicy;
/**
 * A policy that stops when page [title] of [wiki] says anything other than `false`. Read fresh every time,
 * never from a response cache.
 */
export declare function stopPolicyOnPage(wiki: Wiki, title: string): StopPolicy;
/**
 * One stop page for many bots: [read] gives the page's text, or `null` when there is no such page, and may
 * return a promise. It is called at most once per `every`, and every bot asks what it found. The text has to
 * say `false` for bots to run. A failed read keeps the last good answer until it is stale; with no good
 * answer yet, the bots stop.
 */
export declare function sharedStop(read: () => Awaitable<Nullable<string>>, options?: Nullable<SharedStopOptions>): StopPolicy;
/**
 * One stop page for many bots: page [title] of [wiki], read fresh at most once a minute.
 */
export declare function sharedStopOnPage(wiki: Wiki, title: string): StopPolicy;
/**
 * The check that decides whether a bot may keep writing. A check that cannot be performed stops the run: a
 * bot that keeps editing because its emergency stop was unreachable is what this prevents.
 */
export declare class StopPolicy {
    private constructor();
    /**
     * Whether the bot may continue. Rejecting is treated as "stop".
     */
    mayContinue(): Promise<boolean>;
    /**
     * Rejects if the policy already says stop or could not be read, for work before a run that a stopped bot
     * should not do either.
     */
    check(): Promise<void>;
}
export declare namespace StopPolicy {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => StopPolicy;
    }
}
/**
 * How a shared stop reads.
 */
export declare interface SharedStopOptions {
    /**
     * How often to look, in milliseconds; a minute unless given.
     */
    readonly every?: Nullable<number>;
    /**
     * How long an answer stays good once it can no longer be refreshed, in milliseconds; 5 minutes.
     */
    readonly staleAfter?: Nullable<number>;
    /**
     * What the time is, so a test can say rather than wait; the system's clock unless given.
     */
    readonly now?: Nullable<() => Date>;
}
/**
 * Wikimedia Commons' query service, which needs a token: see `sparqlAuthWcqs`.
 */
export declare const COMMONS_QUERY_SERVICE: string;
/**
 * Lingua Libre's own query service.
 */
export declare const LINGUA_LIBRE_QUERY_SERVICE: string;
/**
 * Wikimedia's public event stream, which needs no credentials.
 */
export declare const WIKIMEDIA_EVENT_STREAMS: string;
/**
 * No credentials, which is what a public endpoint wants.
 */
export declare function sparqlAuthNone(): SparqlAuth;
/**
 * A cookie called [name] holding [value], which is how Wikimedia's query services carry an OAuth token.
 */
export declare function sparqlAuthCookie(name: string, value: string): SparqlAuth;
/**
 * The Commons Query Service's cookie, holding the OAuth [token].
 */
export declare function sparqlAuthWcqs(token: string): SparqlAuth;
/**
 * Makes a client for a query service, asking as the tool whose operator [contact] reaches. A query service is
 * a server of its own, with its own limits and no session, so it is separate from any wiki. Close it when
 * done.
 */
export declare function sparqlClient(contact: string, options?: Nullable<SparqlClientOptions>): SparqlClient;
/**
 * Makes a client for the live feed of changes across Wikimedia's wikis, asking as the tool whose operator
 * [contact] reaches. Close it when done.
 */
export declare function eventStreams(contact: string, options?: Nullable<EventStreamsOptions>): EventStreams;
/**
 * How a query service is told who is asking.
 */
export declare class SparqlAuth {
    private constructor();
    /**
     * `"none"`, what a public endpoint wants, or `"cookie"`.
     */
    get kind(): SparqlAuthKind;
    /**
     * The cookie's name, or `null` unless a `"cookie"`.
     */
    get name(): Nullable<string>;
    /**
     * The token the cookie holds, or `null` unless a `"cookie"`.
     */
    get value(): Nullable<string>;
}
export declare namespace SparqlAuth {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SparqlAuth;
    }
}
/**
 * What a query service client is made with.
 */
export declare interface SparqlClientOptions {
    /**
     * The query service, Wikidata's unless given.
     */
    readonly endpoint?: Nullable<string>;
    /**
     * How the service is told who is asking; nothing unless given.
     */
    readonly auth?: Nullable<SparqlAuth>;
    /**
     * How often to ask again, and how long to wait, while the service says it is busy.
     */
    readonly retry?: Nullable<RetryOptions>;
    /**
     * Who to say is asking; the library's name with the contact unless given.
     */
    readonly userAgent?: Nullable<UserAgent>;
}
/**
 * Runs SPARQL queries against a query service.
 */
export declare class SparqlClient {
    private constructor();
    /**
     * The rows a `SELECT` [query] gives. Rejects with what the service said when it refuses the query.
     */
    select(query: string): Promise<Array<SparqlRow>>;
    /**
     * The entity ids, such as `"Q42"`, the [variable] of a `SELECT` [query] is bound to.
     */
    entityIds(query: string, variable?: string): Promise<Array<string>>;
    /**
     * The rows a `SELECT` [query] gives, one at a time, read from a table of tab-separated values kept until
     * the walk ends rather than parsed whole. In a browser and in Node it is kept in memory.
     */
    selectStreamed(query: string): AsyncIterable<SparqlRow>;
    /**
     * Ends what is still being fetched and releases the connections.
     */
    close(): void;
}
export declare namespace SparqlClient {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SparqlClient;
    }
}
/**
 * What an event stream client is made with.
 */
export declare interface EventStreamsOptions {
    /**
     * Where the streams are served, Wikimedia's unless given.
     */
    readonly baseUrl?: Nullable<string>;
    /**
     * How often to try again, and how long to wait, when a connection fails.
     */
    readonly retry?: Nullable<RetryOptions>;
    /**
     * Who to say is asking; the library's name with the contact unless given.
     */
    readonly userAgent?: Nullable<UserAgent>;
}
/**
 * The live feed of changes across Wikimedia's wikis, over server-sent events.
 * 
 * A walk of one of these does not end on its own: leave it with `break` or `return` to stop it. The server
 * closes every connection after about fifteen minutes, so the walk reconnects from the last event it read,
 * and an event around a reconnect can arrive twice. A connection that keeps failing rejects the walk once the
 * retries are spent, and a refusal does at once.
 * 
 * Node reads the stream as it arrives. In a browser this goes through `fetch`, and what the stream's server
 * allows a page's origin decides whether it can be read.
 */
export declare class EventStreams {
    private constructor();
    /**
     * Recent changes, as they happen. [since] is where to resume from: a previous event's `offset`, or an ISO
     * 8601 timestamp. Without it, from now.
     */
    recentChanges(since?: Nullable<string>): AsyncIterable<WikiEvent>;
    /**
     * Page creations, as they happen, from [since] as `recentChanges` reads it.
     */
    pageCreations(since?: Nullable<string>): AsyncIterable<WikiEvent>;
    /**
     * The events of the stream called [stream], from [since] as `recentChanges` reads it.
     */
    events(stream: string, since?: Nullable<string>): AsyncIterable<WikiEvent>;
    /**
     * Ends every walk and releases the connections.
     */
    close(): void;
}
export declare namespace EventStreams {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => EventStreams;
    }
}
/**
 * One change, as an event stream reports it. Every wiki is on a stream at once, so filter on [wiki].
 */
export declare class WikiEvent {
    private constructor();
    /**
     * Whether the event is from [wiki] and in one of [namespaces], any namespace when none are given.
     */
    matches(wiki: string, namespaces?: Nullable<Array<number>>): boolean;
    /**
     * The database name of the wiki it happened on, such as `"enwiktionary"`.
     */
    get wiki(): string;
    /**
     * `"edit"`, `"new"`, `"log"` or `"categorize"` on recent changes; what its stream calls it, or `""`, on
     * another.
     */
    get type(): EventType;
    /**
     * The full title, with its namespace prefix.
     */
    get title(): string;
    /**
     * The namespace number.
     */
    get namespace(): number;
    /**
     * Who made the change, or `null` where the stream withheld it.
     */
    get user(): Nullable<string>;
    /**
     * The edit summary or log reason.
     */
    get comment(): Nullable<string>;
    /**
     * When it happened, as the stream reported it.
     */
    get timestamp(): Nullable<Date>;
    /**
     * The revision the change produced, or `null` on a log event.
     */
    get revisionId(): Nullable<number>;
    /**
     * The revision it was made from, or `null` on a page creation.
     */
    get previousRevisionId(): Nullable<number>;
    /**
     * Whether it was flagged a bot's.
     */
    get isBot(): boolean;
    /**
     * Whether the editor marked it minor.
     */
    get isMinor(): boolean;
    /**
     * Where in the stream this event was, to resume from.
     */
    get offset(): Nullable<string>;
}
export declare namespace WikiEvent {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => WikiEvent;
    }
}
/**
 * The revision every page of a `FakePageService` is at before anything edits it.
 */
export declare const FAKE_INITIAL_REVISION: number;
/**
 * A wiki that exists only in memory, for testing a bot with no wiki: the facade `Wiki`, so `wiki.botRun`,
 * `wiki.page`, `wiki.pagesService` and the rest work on it. Pages are real. Anything not handed in is not
 * implemented, and rejects saying so, since a fake that answered with an empty list would make a test pass
 * while the bot did nothing: `files`, `extensions` and `proofread` always, and those of
 * [FakeWikiOptions.services] unless given. A listing rejects as it is walked; what answers at once, such as
 * `extensions.has`, throws.
 */
export declare function createFakeWiki(options?: Nullable<FakeWikiOptions>): Wiki;
/**
 * What a fake wiki says instead of applying an edit.
 */
export declare interface FakeRefusal {
    /**
     * Why, as `EditResult.status` spells it: `"conflict"`, `"protected"`, `"permission-denied"`,
     * `"rate-limited"`, `"filtered"`, `"captcha"`, `"page-changed"` or `"rejected"`.
     */
    readonly status: EditRefusal;
    /**
     * What the wiki says.
     */
    readonly detail?: Nullable<string>;
    /**
     * On a `"conflict"`, the revision the page is on now.
     */
    readonly currentRevision?: Nullable<number>;
    /**
     * On a `"protected"`, the protection level that blocked the edit.
     */
    readonly protectionLevel?: Nullable<string>;
    /**
     * On a `"protected"`, whether the protection comes from a page transcluding this one.
     */
    readonly isCascadingProtection?: Nullable<boolean>;
    /**
     * On a `"filtered"`, the filter name or blacklist entry.
     */
    readonly filter?: Nullable<string>;
    /**
     * On a `"rate-limited"`, how long the wiki asks to wait, in milliseconds.
     */
    readonly retryAfter?: Nullable<number>;
    /**
     * On a `"captcha"`, the captcha the wiki wants solved.
     */
    readonly captchaId?: Nullable<string>;
    /**
     * On a `"page-changed"`, whether the page was deleted.
     */
    readonly wasDeleted?: Nullable<boolean>;
    /**
     * On a `"rejected"`, the wiki's code for it.
     */
    readonly code?: Nullable<string>;
}
/**
 * The error every edit of a fake wiki fails with, as a wiki would say it in its answer.
 */
export declare interface FakeFailure {
    /**
     * The wiki's code, which decides the error, such as `"readonly"`, `"blocked"` or `"internal_api_error"`.
     */
    readonly code: string;
    /**
     * What the wiki says.
     */
    readonly info?: Nullable<string>;
}
/**
 * What a fake wiki's pages start as, and how they answer.
 */
export declare interface FakePageServiceOptions {
    /**
     * The pages it starts with, by title, prefix included. None unless given.
     */
    readonly pages?: Nullable<Record<string, string>>;
    /**
     * The database name of the wiki its pages belong to; `"testwiki"` unless given.
     */
    readonly wiki?: Nullable<string>;
    /**
     * A refusal to give instead of applying an edit to the page [title], or `null` to apply it.
     */
    readonly refuse?: Nullable<(title: string) => Nullable<FakeRefusal>>;
    /**
     * The error to fail every edit with, for testing a bot's failure path.
     */
    readonly failWith?: Nullable<() => FakeFailure>;
    /**
     * What expanding wikitext makes of it, with the page it is expanded on. The text itself unless given.
     */
    readonly expand?: Nullable<(wikitext: string, title: Nullable<string>) => string>;
}
/**
 * A wiki's pages held in memory, for testing a bot with no wiki. Give it to `createFakeWiki`. Edits are
 * applied to the text it holds and recorded, so a test can check both what the bot decided and what the page
 * ended up saying. A stale `baseRevision` is a conflict, `createOnly` on a page that exists and `noCreate` on
 * one that does not are refused. It does not edit numbered sections, and an edit of one rejects saying so.
 */
export declare class FakePageService {
    constructor(options?: Nullable<FakePageServiceOptions>);
    /**
     * The current text of the page [title], or `null` if it does not exist.
     */
    text(title: string): Nullable<string>;
    /**
     * The revision the page [title] is at, or `null` if it does not exist.
     */
    revision(title: string): Nullable<number>;
    /**
     * Whether the page [title] is on the fake watchlist.
     */
    isWatched(title: string): boolean;
    /**
     * Every edit that was applied, in order. Each read gives those so far, and does not change after.
     */
    get edits(): Array<FakeEdit>;
}
export declare namespace FakePageService {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => FakePageService;
    }
}
/**
 * One edit a `FakePageService` applied, with everything the bot said of it.
 */
export declare class FakeEdit {
    private constructor();
    /**
     * The page edited, prefix included in English.
     */
    get title(): string;
    /**
     * The new text, or `null` for an edit that only added some.
     */
    get text(): Nullable<string>;
    /**
     * The edit summary.
     */
    get summary(): string;
    /**
     * The text put before the page's.
     */
    get prependText(): Nullable<string>;
    /**
     * The text put after the page's.
     */
    get appendText(): Nullable<string>;
    /**
     * Whether it was marked minor.
     */
    get minor(): boolean;
    /**
     * Whether it was marked as a bot's.
     */
    get bot(): boolean;
    /**
     * The change tags on it.
     */
    get tags(): Array<string>;
    /**
     * The section it replaces, or `"new"`, or `null` for the whole page.
     */
    get section(): Nullable<string>;
    /**
     * The heading of a new section.
     */
    get sectionTitle(): Nullable<string>;
    /**
     * The revision the text was computed from.
     */
    get baseRevision(): Nullable<number>;
    /**
     * Whether the page had to not exist yet.
     */
    get createOnly(): boolean;
    /**
     * Whether the page had to exist already.
     */
    get noCreate(): boolean;
    /**
     * What it does to the watchlist, as `EditOptions` spells it.
     */
    get watchlist(): WatchMode;
    /**
     * How long the page is watched for.
     */
    get watchlistExpiry(): Nullable<string>;
}
export declare namespace FakeEdit {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => FakeEdit;
    }
}
/**
 * A request a `MockTransport` was asked to send.
 */
export declare class MockRequest {
    private constructor();
    /**
     * Its parameters.
     */
    get params(): Record<string, string>;
    /**
     * The `action` it names.
     */
    get action(): string;
    /**
     * Whether it is a write.
     */
    get isWrite(): boolean;
    /**
     * Whether its answer may be served from a cache.
     */
    get cacheable(): boolean;
}
export declare namespace MockRequest {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => MockRequest;
    }
}
/**
 * A transport that answers from [handler] instead of a wiki, for what `wiki.call`, `wiki.callPaged`,
 * `wiki.tokens` and `wiki.paramInfo` send on a fake wiki. [handler] sees each request's parameters and
 * answers as a wiki would, in plain JSON or a promise of it. Throwing from it fails the call.
 */
export declare class MockTransport {
    constructor(handler: (params: Record<string, string>) => Awaitable<Record<string, JsonValue>>, endpoint?: Nullable<Endpoint>);
    /**
     * Every request it was asked to send, in order.
     */
    get requests(): Array<MockRequest>;
}
export declare namespace MockTransport {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => MockTransport;
    }
}
/**
 * Who a fake wiki says is logged in.
 */
export declare interface FakeIdentity {
    /**
     * The account name.
     */
    readonly name: string;
    /**
     * The account's id; 1 unless given, and 0 means anonymous.
     */
    readonly id?: Nullable<number>;
    /**
     * The groups it is in; none unless given.
     */
    readonly groups?: Nullable<Array<string>>;
    /**
     * The rights it has; none unless given.
     */
    readonly rights?: Nullable<Array<string>>;
}
/**
 * What a fake wiki is made of.
 */
export declare interface FakeWikiOptions {
    /**
     * Its pages, to read back what was saved; an empty `FakePageService` unless given.
     */
    readonly pages?: Nullable<FakePageService>;
    /**
     * Its database name; `"testwiki"` unless given.
     */
    readonly id?: Nullable<string>;
    /**
     * The namespaces titles are parsed against; the canonical English ones unless given.
     */
    readonly namespaces?: Nullable<Namespaces>;
    /**
     * Who it says is logged in; a bot called `TestBot` unless given.
     */
    readonly identity?: Nullable<FakeIdentity>;
    /**
     * What `wiki.call`, `wiki.callPaged`, `wiki.tokens` and `wiki.paramInfo` talk to. Unless given, one that
     * refuses, so a test cannot reach the network by accident.
     */
    readonly transport?: Nullable<MockTransport>;
    /**
     * A wiki to take `lists`, `revisions`, `users`, `logs`, `render` and `meta` from, such as one opened with
     * `globalThis.fetch` stubbed. Unless given, using one of them rejects, saying the fake does not implement
     * it.
     */
    readonly services?: Nullable<Wiki>;
}
/**
 * What a failure from the wiki or a dump says, or `null` when [error] is something else, a mistaken argument
 * included: a JavaScript program cannot tell this library's errors apart any other way.
 */
export declare function describeError(error: unknown): Nullable<ErrorInfo>;
/**
 * One of this library's errors. [kind] says which, and so which of the other fields are set.
 */
export declare class ErrorInfo {
    private constructor();
    /**
     * `"timeout"`, `"unreachable"`, `"server-error"`, `"refused"`, `"rate-limited"` or `"maxlag"` when the
     * request produced no usable response; `"api"` for an error from the wiki with no more specific meaning;
     * `"not-logged-in"`, `"login-failed"`, `"no-credentials"`, `"missing-right"`, `"permission-denied"`,
     * `"account-blocked"` or `"bad-token"` when the account may not; `"missing-page"`, `"not-redirect"`,
     * `"unresolvable-redirect"`, `"bad-title"`, `"off-wiki"` or `"unsupported-content-model"` when the page
     * will not do; `"read-only"`; `"unknown-site"`, `"missing-extension"`, `"version-too-old"` or
     * `"unsupported"` when the wiki or the setup is not up to it; `"round-trip"` from `parseExact`; or
     * `"dump-format"` from reading a dump, with [dumpProblem] saying what is wrong with it.
     */
    get kind(): ErrorKind;
    /**
     * What went wrong, for a person.
     */
    get message(): string;
    /**
     * Whether trying the identical request again could succeed.
     */
    get isTransient(): boolean;
    /**
     * The endpoint, on a `"timeout"`, `"unreachable"`, `"server-error"` or `"refused"`.
     */
    get url(): Nullable<string>;
    /**
     * The HTTP status, on a `"server-error"` or `"refused"`.
     */
    get status(): Nullable<number>;
    /**
     * What the wiki or server said, on a `"refused"`, `"permission-denied"`, `"account-blocked"`,
     * `"unresolvable-redirect"` or `"read-only"`, or as its reason on a `"login-failed"`.
     */
    get detail(): Nullable<string>;
    /**
     * How long to wait in milliseconds, on a `"rate-limited"`, when the server said.
     */
    get retryAfter(): Nullable<number>;
    /**
     * How far behind the replica was in milliseconds, on a `"maxlag"`.
     */
    get lag(): Nullable<number>;
    /**
     * Which replica it was, on a `"maxlag"`, when the wiki named it.
     */
    get host(): Nullable<string>;
    /**
     * The wiki's machine-readable code, on an `"api"`.
     */
    get code(): Nullable<string>;
    /**
     * MediaWiki's own text for it, on an `"api"`.
     */
    get info(): Nullable<string>;
    /**
     * The API module that raised it, on an `"api"`, when the wiki said.
     */
    get module(): Nullable<string>;
    /**
     * The action that needed an account, on a `"not-logged-in"`.
     */
    get action(): Nullable<string>;
    /**
     * The wiki nothing was configured for, on a `"no-credentials"`.
     */
    get wiki(): Nullable<string>;
    /**
     * The right the account would need, on a `"missing-right"`.
     */
    get right(): Nullable<string>;
    /**
     * Which token was rejected, on a `"bad-token"`: `"csrf"`, `"rollback"`, `"userrights"`.
     */
    get tokenType(): Nullable<string>;
    /**
     * The title it is about, on a kind about a page, as written in the error.
     */
    get title(): Nullable<string>;
    /**
     * Why the title cannot name a page, on a `"bad-title"`, as `Title.invalidReason` says it.
     */
    get titleProblem(): Nullable<TitleProblem>;
    /**
     * What the page actually holds, on an `"unsupported-content-model"`.
     */
    get contentModel(): Nullable<string>;
    /**
     * The language code asked for, on an `"unknown-site"`.
     */
    get language(): Nullable<string>;
    /**
     * The family asked for, on an `"unknown-site"`.
     */
    get family(): Nullable<string>;
    /**
     * The extension the feature needs, on a `"missing-extension"`.
     */
    get extension(): Nullable<string>;
    /**
     * The lowest MediaWiki version that carries the feature, on a `"version-too-old"`.
     */
    get requiredVersion(): Nullable<string>;
    /**
     * What the wiki runs, on a `"version-too-old"`.
     */
    get actualVersion(): Nullable<string>;
    /**
     * The module and parameter, such as `parse prop`, on an `"unsupported"`.
     */
    get parameter(): Nullable<string>;
    /**
     * What the library would have sent, most preferred first, on an `"unsupported"`.
     */
    get alternatives(): Nullable<Array<string>>;
    /**
     * What is wrong with the dump on a `"dump-format"`: `"unexpected-eof"`, `"bad-checksum"` or `"bad-data"`.
     */
    get dumpProblem(): Nullable<DumpProblem>;
    /**
     * The first offset where the text written back would differ, on a `"round-trip"`.
     */
    get offset(): Nullable<number>;
}
export declare namespace ErrorInfo {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ErrorInfo;
    }
}
/**
 * The pseudo-language Wikibase stores a label under when it applies to every language: `"mul"`.
 */
export declare const MULTIPLE_LANGUAGES: string;
/**
 * A [DataValue] time's precision: to a billion years.
 */
export declare const TIME_PRECISION_BILLION_YEARS: number;
/**
 * A [DataValue] time's precision: to a century.
 */
export declare const TIME_PRECISION_CENTURY: number;
/**
 * A [DataValue] time's precision: to a decade.
 */
export declare const TIME_PRECISION_DECADE: number;
/**
 * A [DataValue] time's precision: to a year, the commonest historical one.
 */
export declare const TIME_PRECISION_YEAR: number;
/**
 * A [DataValue] time's precision: to a month.
 */
export declare const TIME_PRECISION_MONTH: number;
/**
 * A [DataValue] time's precision: to a day, the finest Wikibase records.
 */
export declare const TIME_PRECISION_DAY: number;
/**
 * What Wikibase writes as the unit of a quantity with none: `"1"`.
 */
export declare const UNITLESS: string;
/**
 * A plain string value: an identifier, a URL, a formatter pattern.
 */
export declare function textValue(value: string): DataValue;
/**
 * A value pointing at the entity [id], such as `"Q42"`.
 */
export declare function entityValue(id: string): DataValue;
/**
 * A point in time. [time] is as Wikibase writes it, such as `"+1952-00-00T00:00:00Z"`; [precision] is one of
 * the `TIME_PRECISION_` numbers, and [calendarModel] the calendar as an entity URI.
 */
export declare function timeValue(time: string, precision: number, calendarModel: string, before?: number, after?: number, timezone?: number): DataValue;
/**
 * An amount, kept as text so its precision survives, with a unit as an entity URI and a tolerance.
 */
export declare function quantityValue(amount: string, unit?: string, upperBound?: Nullable<string>, lowerBound?: Nullable<string>): DataValue;
/**
 * A point on [globe], an entity URI, with its [precision] in degrees when it has one.
 */
export declare function coordinateValue(latitude: number, longitude: number, precision: Nullable<number>, globe: string): DataValue;
/**
 * Text in a stated language: a name, a title, a motto.
 */
export declare function monolingualValue(text: string, language: string): DataValue;
/**
 * A value of the type Wikibase calls [type], which this library does not model, holding [value].
 */
export declare function unknownValue(type: string, value: JsonValue): DataValue;
/**
 * A value a statement can carry. [kind] says which it is, and so which fields are set; a value of a type this
 * library does not model is kept whole as an `"unknown"`, and written back unchanged.
 */
export declare class DataValue {
    private constructor();
    /**
     * The value as Wikibase writes it, `{ type, value }`.
     */
    toJSON(): Record<string, JsonValue>;
    /**
     * `"text"`, `"entity-ref"`, `"time"`, `"quantity"`, `"globe-coordinate"`, `"monolingual"` or `"unknown"`.
     */
    get kind(): DataValueKind;
    /**
     * A `"text"`'s string.
     */
    get value(): Nullable<string>;
    /**
     * An `"entity-ref"`'s entity.
     */
    get id(): Nullable<string>;
    /**
     * A `"time"`'s timestamp, as Wikibase writes it with a sign and a padded year.
     */
    get time(): Nullable<string>;
    /**
     * A `"time"`'s precision, one of the `TIME_PRECISION_` numbers, or a `"globe-coordinate"`'s in degrees.
     */
    get precision(): Nullable<number>;
    /**
     * A `"time"`'s calendar, as an entity URI.
     */
    get calendarModel(): Nullable<string>;
    /**
     * How many units earlier a `"time"` might be.
     */
    get before(): Nullable<number>;
    /**
     * How many units later a `"time"` might be.
     */
    get after(): Nullable<number>;
    /**
     * The offset in minutes a `"time"` was stated in.
     */
    get timezone(): Nullable<number>;
    /**
     * A `"quantity"`'s number as written, kept as text so its precision survives.
     */
    get amount(): Nullable<string>;
    /**
     * A `"quantity"`'s unit as an entity URI, or `UNITLESS`.
     */
    get unit(): Nullable<string>;
    /**
     * The top of a `"quantity"`'s stated tolerance.
     */
    get upperBound(): Nullable<string>;
    /**
     * The bottom of a `"quantity"`'s stated tolerance.
     */
    get lowerBound(): Nullable<string>;
    /**
     * A `"globe-coordinate"`'s degrees north, negative for south.
     */
    get latitude(): Nullable<number>;
    /**
     * A `"globe-coordinate"`'s degrees east, negative for west.
     */
    get longitude(): Nullable<number>;
    /**
     * A `"globe-coordinate"`'s globe, as an entity URI.
     */
    get globe(): Nullable<string>;
    /**
     * A `"monolingual"`'s text.
     */
    get text(): Nullable<string>;
    /**
     * A `"monolingual"`'s language, as a Wikimedia language code.
     */
    get language(): Nullable<string>;
    /**
     * The type Wikibase named for an `"unknown"`.
     */
    get type(): Nullable<string>;
    /**
     * An `"unknown"`'s value as it arrived.
     */
    get json(): Nullable<JsonValue>;
}
export declare namespace DataValue {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => DataValue;
    }
}
/**
 * A parameter of a template. [showKey] says whether it is written as `name=value` rather than by position,
 * its [name] then being the number it has.
 */
export declare function createParameter(name: Wikitext, value: Wikitext, showKey: boolean): Parameter;
/**
 * A template called [name] with [parameters], which can go on with `withParameter`.
 */
export declare function createTemplate(name: Wikitext, parameters?: Array<Parameter>): Template;
/**
 * A section with [heading], `null` for a lead, the [nodes] directly under it and [subsections].
 */
export declare function createSection(heading: Nullable<Heading>, nodes: Array<Node>, subsections?: Array<Section>): Section;
/**
 * Wikitext made of [nodes], as they are.
 */
export declare function wikitextOf(nodes: Array<Node>): Wikitext;
/**
 * Wikitext holding [text] as one piece of literal text, parsed as nothing else.
 */
export declare function literalText(text: string): Wikitext;
/**
 * Wikitext with nothing in it.
 */
export declare function emptyWikitext(): Wikitext;
/**
 * Parses [text], the way a wiki with [options] reads it when they are given, and checks that writing it back
 * gives the same text. Throws otherwise; `describeError` says from which offset.
 */
export declare function parseExact(text: string, options?: Nullable<ParseOptions>): Wikitext;
/**
 * Whether [text] parses, the way a wiki with [options] reads it, into wikitext that writes back unchanged.
 */
export declare function roundTrips(text: string, options?: Nullable<ParseOptions>): boolean;
/**
 * What a run did, in counts, with its refusals and failures.
 */
export declare class RunReport {
    private constructor();
    /**
     * How many pages were handled.
     */
    get processed(): number;
    /**
     * How many edits were saved.
     */
    get saved(): number;
    /**
     * How many edits were computed but not saved, which in a dry run is every one.
     */
    get pending(): number;
    /**
     * How many pages were left alone, deliberately or because they did not exist.
     */
    get skipped(): number;
    /**
     * How many edits the wiki refused.
     */
    get refused(): number;
    /**
     * How many pages failed for a reason that was not about the page.
     */
    get failed(): number;
    /**
     * How many pages were not worked on because the run had stopped.
     */
    get notAttempted(): number;
    /**
     * Why the run stopped early, such as `"policy"`, which a reached `maxEdits` also says, or `null` when it
     * did not.
     */
    get stopReason(): Nullable<StopReason>;
    /**
     * The refusals and failures, up to the first hundred.
     */
    get problems(): Array<Problem>;
}
export declare namespace RunReport {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RunReport;
    }
}
/**
 * One page a run changed, or would change.
 */
export declare class Change {
    private constructor();
    /**
     * The page's title.
     */
    get title(): string;
    /**
     * The change as a unified diff.
     */
    get diff(): string;
    /**
     * The revision saved, or `null` when the run did not save it.
     */
    get revision(): Nullable<number>;
}
export declare namespace Change {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Change;
    }
}
/**
 * One page a run could not do.
 */
export declare class Problem {
    private constructor();
    /**
     * The page's title.
     */
    get title(): string;
    /**
     * `"refused"` when the wiki refused the edit, `"failed"` when the wiki could not serve the page.
     */
    get kind(): ProblemKind;
    /**
     * What went wrong.
     */
    get detail(): string;
}
export declare namespace Problem {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Problem;
    }
}
/**
 * A Wikibase entity: an item, a property, a lexeme or a file's structured data.
 */
export declare class Entity {
    private constructor();
    /**
     * The label in [language], or `null` when it has none there.
     */
    label(language: string): Nullable<string>;
    /**
     * The description in [language], or `null`.
     */
    description(language: string): Nullable<string>;
    /**
     * The aliases in [language].
     */
    aliases(language: string): Array<string>;
    /**
     * The statements for [property], such as `"P31"`, in the entity's order.
     */
    claims(property: string): Array<Claim>;
    /**
     * The statement for [property] a reader would be shown: a preferred one, else a normal one, else `null`.
     */
    bestClaim(property: string): Nullable<Claim>;
    /**
     * The label in [language] only, without falling back on the label every language shares.
     */
    labelIn(language: string): Nullable<string>;
    /**
     * An item's page on the wiki with database name [site], or `null` when it has none.
     */
    siteLink(site: string): Nullable<EntitySiteLink>;
    /**
     * A lexeme's spelling in [language], or `null`.
     */
    lemma(language: string): Nullable<string>;
    /**
     * A file's caption in [language], or `null` unless this is a file's structured data.
     */
    caption(language: string): Nullable<string>;
    /**
     * The entity's id, such as `"Q42"`.
     */
    get id(): string;
    /**
     * `"item"`, `"property"`, `"lexeme"`, `"form"`, `"sense"`, `"mediainfo"`, or `"unknown"`.
     */
    get type(): EntityKind;
    /**
     * The revision the entity was read at, or `null` when the wiki did not say.
     */
    get lastRevision(): Nullable<number>;
    /**
     * The properties the entity has statements for.
     */
    get properties(): Array<string>;
    /**
     * The labels by language code; a lexeme's are its lemmas and a file's its captions.
     */
    get labels(): Record<string, LanguageValue>;
    /**
     * The descriptions by language code.
     */
    get descriptions(): Record<string, LanguageValue>;
    /**
     * The aliases by language code.
     */
    get allAliases(): Record<string, Array<LanguageValue>>;
    /**
     * The statements by the property they are about.
     */
    get statements(): Record<string, Array<Claim>>;
    /**
     * An item's pages by wiki database name; `null` unless it is an item.
     */
    get siteLinks(): Nullable<Record<string, EntitySiteLink>>;
    /**
     * What kind of value a property's statements hold, as Wikibase names it; `null` unless a property.
     */
    get dataType(): Nullable<string>;
    /**
     * A lexeme's spellings by language; `null` unless it is a lexeme.
     */
    get lemmas(): Nullable<Record<string, LanguageValue>>;
    /**
     * The item for a lexeme's language, or `null`.
     */
    get language(): Nullable<string>;
    /**
     * The item for a lexeme's part of speech, or `null`.
     */
    get lexicalCategory(): Nullable<string>;
    /**
     * A lexeme's inflected forms; `null` unless it is a lexeme.
     */
    get forms(): Nullable<Array<LexemeForm>>;
    /**
     * A lexeme's meanings; `null` unless it is a lexeme.
     */
    get senses(): Nullable<Array<LexemeSense>>;
}
export declare namespace Entity {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Entity;
    }
}
/**
 * One statement of an entity.
 */
export declare class Claim {
    private constructor();
    /**
     * The whole statement as Wikibase writes it, qualifiers and references included.
     */
    toJSON(): Record<string, JsonValue>;
    /**
     * The qualifiers for [property].
     */
    qualifiersFor(property: string): Array<Snak>;
    /**
     * This statement with what [options] gives in place of what it had.
     */
    copy(options: ClaimOptions): Claim;
    /**
     * This statement asserting [snak] instead.
     */
    withMainSnak(snak: Snak): Claim;
    /**
     * The statement's id, or `null` for one not saved yet.
     */
    get id(): Nullable<string>;
    /**
     * The property it is about, such as `"P31"`.
     */
    get property(): string;
    /**
     * `"preferred"`, `"normal"` or `"deprecated"`.
     */
    get rank(): StatementRank;
    /**
     * `"value"`, or `"somevalue"` for an unknown value, or `"novalue"` for a stated absence.
     */
    get snakType(): SnakKind;
    /**
     * The property's data type, such as `"wikibase-item"`, when the wiki gave it.
     */
    get dataType(): Nullable<string>;
    /**
     * The value as Wikibase writes it, `{ type, value }`, or `null` for `somevalue` and `novalue`.
     */
    get value(): Nullable<Record<string, JsonValue>>;
    /**
     * What the statement asserts.
     */
    get mainSnak(): Snak;
    /**
     * The value as its own type, or `null` for `somevalue` and `novalue`.
     */
    get dataValue(): Nullable<DataValue>;
    /**
     * The conditions on the assertion.
     */
    get qualifiers(): Array<Snak>;
    /**
     * The sources cited for it.
     */
    get references(): Array<Reference>;
}
export declare namespace Claim {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Claim;
    }
}
/**
 * Whether a page has excluded a bot with `{{nobots}}` or `{{bots}}`, which wikis block bots for ignoring.
 * `allow` is a whitelist: everybody not on it is denied.
 */
export declare class BotPolicy {
    constructor(botName: string, task?: Nullable<string>);
    /**
     * Whether this bot may edit [wikitext].
     */
    check(wikitext: string): EditPermission;
    /**
     * Whether this bot may edit a page already parsed.
     */
    checkParsed(code: Wikitext): EditPermission;
}
export declare namespace BotPolicy {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => BotPolicy;
    }
}
/**
 * Why a page may or may not be edited by a bot.
 */
export declare class EditPermission {
    private constructor();
    /**
     * Whether editing is allowed.
     */
    get isAllowed(): boolean;
    /**
     * Which exclusion applied, or `null` when nothing on the page objects.
     */
    get reason(): Nullable<string>;
}
export declare namespace EditPermission {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => EditPermission;
    }
}
/**
 * Text in one language, as labels, descriptions and lemmas are stored.
 */
export declare class LanguageValue {
    constructor(language: string, value: string);
    /**
     * The language code, or `"mul"` for a value that applies to all.
     */
    get language(): string;
    /**
     * The text.
     */
    get value(): string;
}
export declare namespace LanguageValue {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => LanguageValue;
    }
}
/**
 * A page on a wiki that an item is about.
 */
export declare class EntitySiteLink {
    constructor(site: string, title: string, badges?: Array<string>);
    /**
     * The wiki's database name, such as `"enwiktionary"`.
     */
    get site(): string;
    /**
     * The page's title on that wiki.
     */
    get title(): string;
    /**
     * Marks on the link, each an item id, such as the badge for a featured article.
     */
    get badges(): Array<string>;
}
export declare namespace EntitySiteLink {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => EntitySiteLink;
    }
}
/**
 * One inflected form of a lexeme.
 */
export declare class LexemeForm {
    private constructor();
    /**
     * The form's id, such as `"L1-F2"`.
     */
    get id(): string;
    /**
     * How the form is spelled, by language.
     */
    get representations(): Record<string, LanguageValue>;
    /**
     * What makes it this form, each an item id: plural, genitive, past participle.
     */
    get grammaticalFeatures(): Array<string>;
    /**
     * Statements about the form itself rather than about the word, by property.
     */
    get statements(): Record<string, Array<Claim>>;
}
export declare namespace LexemeForm {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => LexemeForm;
    }
}
/**
 * One sense of a lexeme.
 */
export declare class LexemeSense {
    private constructor();
    /**
     * The sense's id, such as `"L1-S1"`.
     */
    get id(): string;
    /**
     * What the sense means, by language.
     */
    get glosses(): Record<string, LanguageValue>;
    /**
     * Statements about this meaning rather than about the word, by property.
     */
    get statements(): Record<string, Array<Claim>>;
}
export declare namespace LexemeSense {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => LexemeSense;
    }
}
/**
 * The options of `abuseFilters`.
 */
export declare interface AbuseFiltersOptions {
    /**
     * Which filters, by state: each of `"enabled"`, `"deleted"`, `"private"`, `"protected"` and
     * `"suppressed"` that has to hold, or with a leading `!` that must not.
     */
    readonly show?: Nullable<Array<Negatable<AbuseFilterState>>>;
    /**
     * What to read about each, from `"id"`, always read, `"description"`, `"pattern"`, `"actions"`, `"hits"`,
     * `"comments"`, `"last-editor"`, `"last-edit-time"`, `"flags"` and the deprecated `"status"`,
     * `"private"`, `"protected"` and `"suppressed"`. The id, description, actions and flags unless given.
     */
    readonly properties?: Nullable<Array<AbuseFilterProperty>>;
    /**
     * `"lowest-id-first"`, the default, or `"highest-id-first"`.
     */
    readonly order?: Nullable<AbuseFilterOrder>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `newPages`.
 */
export declare interface NewPagesOptions {
    /**
     * Which namespace's new pages, by number, the main one unless given.
     */
    readonly namespace?: Nullable<number>;
    /**
     * `"unreviewed"`, the default, `"reviewed"` or `"all"`.
     */
    readonly reviewed?: Nullable<NewPagesReviewed>;
    /**
     * Whether to include redirects, `false` unless given.
     */
    readonly redirects?: Nullable<boolean>;
    /**
     * Only pages created by these accounts, at most `maxCreators`.
     */
    readonly creators?: Nullable<Array<string>>;
    /**
     * Whether to leave out pages this account created, `false` unless given.
     */
    readonly hideOwnPages?: Nullable<boolean>;
    /**
     * `"newest-first"`, the default, `"oldest-first"`, or, each stopping after one batch of at most 200
     * pages, `"newest-review-first"` or `"oldest-review-first"`.
     */
    readonly order?: Nullable<NewPagesOrder>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `newPageStats`.
 */
export declare interface NewPageStatsOptions {
    /**
     * Which namespace's backlog, by number, the main one unless given.
     */
    readonly namespace?: Nullable<number>;
    /**
     * Count only pages created by these accounts in `matching`, at most `maxCreators`.
     */
    readonly creators?: Nullable<Array<string>>;
    /**
     * Whether to leave this account's own pages out of `matching`, `false` unless given.
     */
    readonly hideOwnPages?: Nullable<boolean>;
}
/**
 * What a wiki can do only because an extension is installed, reached as `wiki.extensions`. Every method
 * rejects when its extension is not installed, or for a listing throws on walking it, since an empty result
 * would read as "nothing there".
 */
export declare class ExtensionService {
    private constructor();
    /**
     * Whether [extension] is installed, by the name MediaWiki reports.
     */
    has(extension: string): boolean;
    /**
     * The coordinates of each of [titles] that has any, from GeoData.
     */
    coordinates(titles: Array<string>): Promise<Array<PageCoordinates>>;
    /**
     * The titles of the pages near a point, nearest first, from GeoData.
     * 
     * @param latitude - degrees north of the equator.
     * @param longitude - degrees east of the meridian.
     * @param radius - in metres, 1000 unless given. The wiki caps it.
     * @param limit - how many to return, 10 unless given.
     */
    nearby(latitude: number, longitude: number, radius?: Nullable<number>, limit?: Nullable<number>): Promise<Array<string>>;
    /**
     * The lead image of each of [titles] that has one, from PageImages, as a file name without its namespace.
     */
    pageImages(titles: Array<string>): Promise<Array<PageValue>>;
    /**
     * The opening plain text of each of [titles], from TextExtracts: [sentences] sentences, 2 unless given,
     * or the whole lead section with `0`.
     */
    extracts(titles: Array<string>, sentences?: Nullable<number>): Promise<Array<PageValue>>;
    /**
     * The Wikidata item each of [titles] is about, where it has one, from WikibaseClient.
     */
    wikibaseItems(titles: Array<string>): Promise<Array<PageValue>>;
    /**
     * The problems Linter found, of [category] when given, the first [limit] when given.
     */
    lintErrors(category?: Nullable<string>, limit?: Nullable<number>): AsyncIterable<LintError>;
    /**
     * This session's notifications, from Echo, at most [limit], 25 unless given.
     */
    notifications(unreadOnly?: boolean, limit?: Nullable<number>): Promise<Array<Notification>>;
    /**
     * Thanks the author of [revision], through Thanks. It cannot be taken back.
     */
    thank(revision: number, source?: Nullable<string>): Promise<void>;
    /**
     * A short URL for [url], from UrlShortener.
     */
    shortenUrl(url: string): Promise<string>;
    /**
     * The reviewed state of each of [titles] that has ever been reviewed, from FlaggedRevs.
     */
    flagged(titles: Array<string>): Promise<Array<FlaggedInfo>>;
    /**
     * Marks [revision] reviewed. Needs the `review` right.
     * 
     * @param revision - the revision.
     * @param flags - the review dimensions the wiki defines, by name. The wiki's defaults unless given.
     * @param comment - the note to record with the review.
     */
    review(revision: number, flags?: Nullable<Record<string, number>>, comment?: string): Promise<void>;
    /**
     * The abuse filters the wiki defines, from AbuseFilter. Needs the `abusefilter-view` right.
     */
    abuseFilters(options?: Nullable<AbuseFiltersOptions>): AsyncIterable<AbuseFilter>;
    /**
     * The pages in the new pages feed, from PageTriage: the list a patrolling bot works through.
     */
    newPages(options?: Nullable<NewPagesOptions>): AsyncIterable<TriagePage>;
    /**
     * How long the new pages backlog is, from PageTriage.
     */
    newPageStats(options?: Nullable<NewPageStatsOptions>): Promise<TriageStats>;
    /**
     * The name of GeoData, as `has` takes it.
     */
    get geoData(): string;
    /**
     * The name of PageImages, as `has` takes it.
     */
    get pageImagesExtension(): string;
    /**
     * The name of TextExtracts, as `has` takes it.
     */
    get textExtracts(): string;
    /**
     * The name of WikibaseClient, as `has` takes it.
     */
    get wikibaseClient(): string;
    /**
     * The name of Linter, as `has` takes it.
     */
    get linter(): string;
    /**
     * The name of Echo, as `has` takes it.
     */
    get echo(): string;
    /**
     * The name of Thanks, as `has` takes it.
     */
    get thanks(): string;
    /**
     * The name of UrlShortener, as `has` takes it.
     */
    get urlShortener(): string;
    /**
     * The name of FlaggedRevs, as `has` takes it.
     */
    get flaggedRevs(): string;
    /**
     * The name of AbuseFilter, as `has` takes it, which has a space.
     */
    get abuseFilter(): string;
    /**
     * The name of PageTriage, as `has` takes it.
     */
    get pageTriage(): string;
    /**
     * The most accounts `newPages` and `newPageStats` can filter by at once.
     */
    get maxCreators(): number;
}
export declare namespace ExtensionService {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ExtensionService;
    }
}
/**
 * One page, and one thing an extension says about it.
 */
export declare class PageValue {
    private constructor();
    /**
     * The page.
     */
    get title(): string;
    /**
     * What the extension says.
     */
    get value(): string;
}
export declare namespace PageValue {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PageValue;
    }
}
/**
 * A point on the globe, as GeoData records it for a page.
 */
export declare class Coordinate {
    private constructor();
    /**
     * Degrees north, negative for south.
     */
    get latitude(): number;
    /**
     * Degrees east, negative for west.
     */
    get longitude(): number;
    /**
     * Which globe, `"earth"` unless the wiki says otherwise.
     */
    get globe(): string;
    /**
     * Whether this is the page's main coordinate rather than one of several it mentions.
     */
    get isPrimary(): boolean;
    /**
     * What is at the point, such as `"city"` or `"mountain"`, or `null`.
     */
    get type(): Nullable<string>;
}
export declare namespace Coordinate {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Coordinate;
    }
}
/**
 * The coordinates of one page.
 */
export declare class PageCoordinates {
    private constructor();
    /**
     * The page.
     */
    get title(): string;
    /**
     * Its coordinates.
     */
    get coordinates(): Array<Coordinate>;
}
export declare namespace PageCoordinates {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PageCoordinates;
    }
}
/**
 * One problem the Linter extension found on a page.
 */
export declare class LintError {
    private constructor();
    /**
     * The error's own id, stable while the problem is still on the page.
     */
    get id(): number;
    /**
     * Its category, such as `"obsolete-tag"` or `"missing-end-tag"`.
     */
    get category(): string;
    /**
     * The page it was found on, or `null`.
     */
    get page(): Nullable<string>;
    /**
     * Where in the wikitext it starts, as a byte offset, or `null`.
     */
    get rangeStart(): Nullable<number>;
    /**
     * Where in the wikitext it ends, as a byte offset, or `null`.
     */
    get rangeEnd(): Nullable<number>;
    /**
     * Whatever else Linter recorded about it, which varies by category.
     */
    get details(): Record<string, string>;
}
export declare namespace LintError {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => LintError;
    }
}
/**
 * One notification from Echo.
 */
export declare class Notification {
    private constructor();
    /**
     * Its own id.
     */
    get id(): number;
    /**
     * Its type, such as `"edit-user-talk"`, `"mention"` or `"reverted"`.
     */
    get type(): string;
    /**
     * The page it is about, or `null`.
     */
    get title(): Nullable<string>;
    /**
     * Who caused it, or `null` when the wiki did not say.
     */
    get agent(): Nullable<string>;
    /**
     * When it happened, or `null`.
     */
    get timestamp(): Nullable<Date>;
    /**
     * Whether it has already been seen.
     */
    get isRead(): boolean;
}
export declare namespace Notification {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Notification;
    }
}
/**
 * What a wiki running FlaggedRevs says about a page's reviewed state.
 */
export declare class FlaggedInfo {
    private constructor();
    /**
     * The page.
     */
    get title(): string;
    /**
     * The revision readers are shown, which lags the newest while changes are pending.
     */
    get stableRevisionId(): number;
    /**
     * The review level, as the wiki configures them.
     */
    get level(): number;
    /**
     * The level as the wiki names it, such as `"stable"`, or `null`.
     */
    get levelText(): Nullable<string>;
    /**
     * When the oldest unreviewed edit was made, or `null` when nothing is pending.
     */
    get pendingSince(): Nullable<Date>;
    /**
     * Whether edits are waiting for review, so that the newest revision is not the one shown.
     */
    get hasPendingChanges(): boolean;
}
export declare namespace FlaggedInfo {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => FlaggedInfo;
    }
}
/**
 * The state of an abuse filter.
 */
export declare class AbuseFilterFlags {
    private constructor();
    /**
     * Whether it checks edits at all.
     */
    get isEnabled(): boolean;
    /**
     * Whether it has been deleted, which leaves it listed but inert.
     */
    get isDeleted(): boolean;
    /**
     * Whether its rules are hidden from the public.
     */
    get isPrivate(): boolean;
    /**
     * Whether it reads protected variables, which hides its rules from more people still.
     */
    get isProtected(): boolean;
    /**
     * Whether it is hidden even from administrators. Always `false` before MediaWiki 1.47.
     */
    get isSuppressed(): boolean;
}
export declare namespace AbuseFilterFlags {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AbuseFilterFlags;
    }
}
/**
 * One abuse filter: what was asked for, and what this account may see.
 */
export declare class AbuseFilter {
    private constructor();
    /**
     * The filter's number.
     */
    get id(): number;
    /**
     * The public description, or `null`.
     */
    get description(): Nullable<string>;
    /**
     * What it does when it matches, such as `"warn"` or `"disallow"`.
     */
    get actions(): Array<string>;
    /**
     * The rules it matches against, or `null`.
     */
    get pattern(): Nullable<string>;
    /**
     * Notes left by its editors, or `null`.
     */
    get comments(): Nullable<string>;
    /**
     * How often it has matched, or `null`.
     */
    get hits(): Nullable<number>;
    /**
     * Who last changed it, or `null`.
     */
    get lastEditor(): Nullable<string>;
    /**
     * When it was last changed, or `null`.
     */
    get lastEdited(): Nullable<Date>;
    /**
     * Its state, when `"flags"` was asked for.
     */
    get flags(): Nullable<AbuseFilterFlags>;
    /**
     * The properties asked for that the wiki withheld from this account, as `properties` names them.
     */
    get redacted(): Array<AbuseFilterProperty>;
}
export declare namespace AbuseFilter {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AbuseFilter;
    }
}
/**
 * One page in the new pages feed.
 */
export declare class TriagePage {
    private constructor();
    /**
     * The page's title.
     */
    get title(): string;
    /**
     * When it was created, or `null`.
     */
    get created(): Nullable<Date>;
    /**
     * Who created it, or `null` when the account has been hidden.
     */
    get creator(): Nullable<string>;
    /**
     * Whether the creator is a temporary account.
     */
    get creatorIsTemporary(): boolean;
    /**
     * How far it has got through review: `"unreviewed"`, `"reviewed"`, `"patrolled"` or `"autopatrolled"`, or
     * `null` for a state this library does not know.
     */
    get status(): Nullable<TriageStatus>;
    /**
     * Who last reviewed it, or `null` when nobody has or the reviewer has been hidden.
     */
    get reviewer(): Nullable<string>;
    /**
     * When its review state last changed, or `null`.
     */
    get reviewUpdated(): Nullable<Date>;
    /**
     * Whether it is a redirect.
     */
    get isRedirect(): boolean;
    /**
     * Whether nothing links to it, as PageTriage last counted.
     */
    get isOrphan(): boolean;
}
export declare namespace TriagePage {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TriagePage;
    }
}
/**
 * A queue in the new pages feed.
 */
export declare class TriageBacklog {
    private constructor();
    /**
     * How many pages are in it.
     */
    get count(): number;
    /**
     * When the longest-waiting entered the queue or last changed review state, or `null` when it is empty.
     */
    get oldest(): Nullable<Date>;
}
export declare namespace TriageBacklog {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TriageBacklog;
    }
}
/**
 * How long the new pages backlog is.
 */
export declare class TriageStats {
    private constructor();
    /**
     * Articles waiting for review.
     */
    get unreviewedArticles(): TriageBacklog;
    /**
     * Redirects waiting for review.
     */
    get unreviewedRedirects(): TriageBacklog;
    /**
     * Drafts waiting for review, or `null` where the wiki does not review drafts in the feed.
     */
    get unreviewedDrafts(): Nullable<TriageBacklog>;
    /**
     * Articles reviewed in the past week.
     */
    get reviewedArticles(): number;
    /**
     * Redirects reviewed in the past week.
     */
    get reviewedRedirects(): number;
    /**
     * Articles, reviewed or not, created by the accounts the stats were asked about.
     */
    get matching(): number;
}
export declare namespace TriageStats {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TriageStats;
    }
}
/**
 * The options of `deletedFiles`.
 */
export declare interface DeletedFilesOptions {
    /**
     * The name to start at, inclusive.
     */
    readonly from?: Nullable<string>;
    /**
     * The name to stop at, inclusive.
     */
    readonly to?: Nullable<string>;
    /**
     * Only names beginning with this.
     */
    readonly prefix?: Nullable<string>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `upload`.
 */
export declare interface UploadOptions {
    /**
     * The upload summary.
     */
    readonly comment?: Nullable<string>;
    /**
     * The description page to create. Left to the wiki unless given.
     */
    readonly text?: Nullable<string>;
    /**
     * Whether to acknowledge every warning in advance, `false` unless given.
     */
    readonly ignoreWarnings?: Nullable<boolean>;
    /**
     * How many bytes go in one request, `defaultChunk` unless given.
     */
    readonly chunkSize?: Nullable<number>;
}
/**
 * Files, reached as `wiki.files`. A file's title needs no `File:` prefix.
 * 
 * `download` and `upload` read and write the filesystem, so they work in Node only, and in a browser reject
 * saying so.
 */
export declare class FileService {
    private constructor();
    /**
     * The upload history of each of [files] the wiki has, newest version first, [versions] of each: one, the
     * current file, unless given.
     */
    info(files: Array<string>, versions?: Nullable<number>): Promise<Array<FileVersions>>;
    /**
     * The current version of [file], or `null` when there is no such file.
     */
    latest(file: string): Promise<Nullable<FileInfo>>;
    /**
     * The titles of the pages that use [file], the first [limit] when given.
     */
    usage(file: string, limit?: Nullable<number>): AsyncIterable<string>;
    /**
     * Where [file] is used on other wikis, the first [limit] when given. Needs GlobalUsage.
     */
    globalUsage(file: string, limit?: Nullable<number>): AsyncIterable<GlobalUsage>;
    /**
     * The titles of the files with the content hash [sha1], at most [limit], `duplicateLimit` unless given.
     */
    duplicatesOf(sha1: string, limit?: Nullable<number>): Promise<Array<string>>;
    /**
     * The titles of the files that have been deleted, in title order. Needs the `deletedhistory` right.
     */
    deletedFiles(options?: Nullable<DeletedFilesOptions>): AsyncIterable<string>;
    /**
     * Where this wiki's files come from.
     */
    repositories(): Promise<Array<FileRepository>>;
    /**
     * Downloads [file] to [destination], a file to write or a directory to write into under the file's own
     * name, answering with where it went. Streamed, not held in memory. Node only.
     */
    download(file: string, destination: string): Promise<string>;
    /**
     * Uploads the file at [path] under the title [to], in chunks when it is large, and says how the wiki
     * answered: warnings and refusals are results. Node only.
     */
    upload(path: string, to: string, options?: Nullable<UploadOptions>): Promise<UploadResult>;
    /**
     * Publishes a stashed upload under the title [to], acknowledging the warnings it produced, from the
     * `fileKey` of a `"warned"` result. Costs no second transfer.
     */
    publishStashed(fileKey: string, to: string, comment?: string, text?: Nullable<string>): Promise<UploadResult>;
    /**
     * How many bytes of a file go in one request unless told otherwise.
     */
    get defaultChunk(): number;
    /**
     * How many duplicates `duplicatesOf` reports unless told otherwise.
     */
    get duplicateLimit(): number;
}
export declare namespace FileService {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => FileService;
    }
}
/**
 * The upload history of one file.
 */
export declare class FileVersions {
    private constructor();
    /**
     * The file, prefix included.
     */
    get title(): string;
    /**
     * Each version, newest first.
     */
    get versions(): Array<FileInfo>;
}
export declare namespace FileVersions {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => FileVersions;
    }
}
/**
 * One version of a file.
 */
export declare class FileInfo {
    private constructor();
    /**
     * Where the file itself can be fetched, `""` on a version hidden from this account.
     */
    get url(): string;
    /**
     * The wiki page describing it, or `null`.
     */
    get descriptionUrl(): Nullable<string>;
    /**
     * Its size in bytes.
     */
    get size(): number;
    /**
     * Its width in pixels, `0` for one with no dimensions, such as audio.
     */
    get width(): number;
    /**
     * Its height in pixels, `0` for one with no dimensions.
     */
    get height(): number;
    /**
     * Whether it has pixel dimensions.
     */
    get hasDimensions(): boolean;
    /**
     * The MIME type, such as `"image/jpeg"`, or `null`.
     */
    get mimeType(): Nullable<string>;
    /**
     * How MediaWiki classifies it, such as `"BITMAP"` or `"AUDIO"`, or `null`.
     */
    get mediaType(): Nullable<string>;
    /**
     * The content hash, or `null`.
     */
    get sha1(): Nullable<string>;
    /**
     * When this version was uploaded, or `null`.
     */
    get timestamp(): Nullable<Date>;
    /**
     * Who uploaded it, or `null`.
     */
    get user(): Nullable<string>;
    /**
     * The upload summary, or `null`.
     */
    get comment(): Nullable<string>;
}
export declare namespace FileInfo {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => FileInfo;
    }
}
/**
 * Where a file on a shared repository is used, on another wiki.
 */
export declare class GlobalUsage {
    private constructor();
    /**
     * The database name of the wiki using it.
     */
    get wiki(): string;
    /**
     * The title of the page using it, on that wiki.
     */
    get title(): string;
    /**
     * A link to that page, or `null` when the wiki gave none.
     */
    get url(): Nullable<string>;
}
export declare namespace GlobalUsage {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => GlobalUsage;
    }
}
/**
 * Where a wiki's files come from.
 */
export declare class FileRepository {
    private constructor();
    /**
     * The internal name, such as `"local"` or `"shared"`.
     */
    get name(): string;
    /**
     * The name meant for a reader, such as `"Wikimedia Commons"`.
     */
    get displayName(): string;
    /**
     * Whether the files live on this wiki.
     */
    get isLocal(): boolean;
    /**
     * Where a file's own page lives, or `null`.
     */
    get url(): Nullable<string>;
    /**
     * Where the files themselves are served from, or `null`.
     */
    get rootUrl(): Nullable<string>;
    /**
     * Whether this session may upload here.
     */
    get canUpload(): boolean;
}
export declare namespace FileRepository {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => FileRepository;
    }
}
/**
 * What became of an upload.
 */
export declare class UploadResult {
    private constructor();
    /**
     * `"uploaded"`, `"warned"`, when the wiki holds the bytes but wants its warnings acknowledged, or
     * `"refused"`.
     */
    get kind(): UploadKind;
    /**
     * On `"uploaded"`, the title the file now has.
     */
    get title(): Nullable<string>;
    /**
     * On `"uploaded"`, what the wiki knows about the stored file, when it said.
     */
    get info(): Nullable<FileInfo>;
    /**
     * On `"warned"`, the warnings by kind, such as `"duplicate"` or `"exists"`.
     */
    get warnings(): Nullable<Record<string, string>>;
    /**
     * On `"warned"`, what `publishStashed` takes, when the wiki gave one.
     */
    get fileKey(): Nullable<string>;
    /**
     * On `"warned"`, whether the only complaint is that an identical file is already here.
     */
    get isDuplicateOnly(): Nullable<boolean>;
    /**
     * On `"refused"`, the wiki's code for the refusal.
     */
    get code(): Nullable<string>;
    /**
     * On `"refused"`, its text for a person.
     */
    get detail(): Nullable<string>;
}
export declare namespace UploadResult {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => UploadResult;
    }
}
/**
 * The options of a listing that takes namespaces and a limit.
 */
export declare interface ListOptions {
    /**
     * Only pages in these namespaces, by number. The listing says what it reads without them.
     */
    readonly namespaces?: Nullable<Array<number>>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `categoryMembers`.
 */
export declare interface CategoryMembersOptions {
    /**
     * What to list: `"page"`, the default, `"subcategory"` or `"file"`.
     */
    readonly type?: Nullable<CategoryMemberType>;
    /**
     * Only members in these namespaces, by number. Every namespace unless given.
     */
    readonly namespaces?: Nullable<Array<number>>;
    /**
     * The order: `"sortkey"`, the default, which is what a reader sees, or `"timestamp"`, by when each joined
     * the category, newest last.
     */
    readonly sort?: Nullable<CategorySort>;
    /**
     * The sort key to begin at.
     */
    readonly startSortKey?: Nullable<string>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `backlinks`.
 */
export declare interface BacklinksOptions {
    /**
     * Only pages in these namespaces, by number. Every namespace unless given.
     */
    readonly namespaces?: Nullable<Array<number>>;
    /**
     * Whether to read pages that link through a redirect too, as well as the redirect, `true` unless given.
     */
    readonly includeRedirects?: Nullable<boolean>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `allPages`.
 */
export declare interface AllPagesOptions {
    /**
     * Which namespace to walk, by number, the main one unless given.
     */
    readonly namespace?: Nullable<number>;
    /**
     * The title to start at, inclusive.
     */
    readonly from?: Nullable<string>;
    /**
     * The title to stop at, inclusive. Without it the walk runs to the end of the namespace.
     */
    readonly to?: Nullable<string>;
    /**
     * Only titles beginning with this.
     */
    readonly prefix?: Nullable<string>;
    /**
     * Whether to walk backwards, in which case [from] is the later title and [to] the earlier one.
     */
    readonly descending?: Nullable<boolean>;
    /**
     * `"all"`, the default, `"only-redirects"` or `"no-redirects"`.
     */
    readonly redirects?: Nullable<RedirectFilter>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `allCategories`.
 */
export declare interface AllCategoriesOptions {
    /**
     * The category to start at, inclusive.
     */
    readonly from?: Nullable<string>;
    /**
     * The category to stop at, inclusive.
     */
    readonly to?: Nullable<string>;
    /**
     * Only categories beginning with this.
     */
    readonly prefix?: Nullable<string>;
    /**
     * Only categories with at least this many members.
     */
    readonly min?: Nullable<number>;
    /**
     * Only categories with at most this many members.
     */
    readonly max?: Nullable<number>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `allLinkTargets`, `allRedirectTargets` and `allTransclusionTargets`.
 */
export declare interface AllTargetsOptions {
    /**
     * Which namespace to walk, by number. The main one unless given, or the template one for
     * `allTransclusionTargets`.
     */
    readonly namespace?: Nullable<number>;
    /**
     * The title to start at, inclusive.
     */
    readonly from?: Nullable<string>;
    /**
     * The title to stop at, inclusive.
     */
    readonly to?: Nullable<string>;
    /**
     * Only titles beginning with this.
     */
    readonly prefix?: Nullable<string>;
    /**
     * Whether to give each target once, `true` unless given. Otherwise a title linked a thousand times
     * arrives a thousand times.
     */
    readonly unique?: Nullable<boolean>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `allFileUsages`, whose targets are all files.
 */
export declare interface AllFileUsagesOptions {
    /**
     * The file to start at, inclusive.
     */
    readonly from?: Nullable<string>;
    /**
     * The file to stop at, inclusive.
     */
    readonly to?: Nullable<string>;
    /**
     * Only files beginning with this.
     */
    readonly prefix?: Nullable<string>;
    /**
     * Whether to give each file once, `true` unless given.
     */
    readonly unique?: Nullable<boolean>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `search`.
 */
export declare interface SearchOptions {
    /**
     * Which namespaces to search, by number, the main one unless given.
     */
    readonly namespaces?: Nullable<Array<number>>;
    /**
     * Which part of a page to look at: `"text"`, the default, `"title"` or `"near-match"`.
     */
    readonly scope?: Nullable<SearchScope>;
    /**
     * The order: `"relevance"`, the default, `"last-edit-descending"`, `"last-edit-ascending"`,
     * `"created-descending"`, `"created-ascending"` or `"title"`.
     */
    readonly sort?: Nullable<SearchSort>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `externalLinkUsage`.
 */
export declare interface ExternalLinkUsageOptions {
    /**
     * The protocol, such as `"https"`, which the wiki indexes apart: a page linking to `http://example.org`
     * is not found by a search for the `https` form.
     */
    readonly protocol?: Nullable<string>;
    /**
     * Only pages in these namespaces, by number. Every namespace unless given.
     */
    readonly namespaces?: Nullable<Array<number>>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * Everything a wiki can be asked to list, reached as `wiki.lists`.
 * 
 * Each listing pages through the API as it is walked, so taking ten results from a category of a million
 * costs one request, and ending a walk early stops the paging. An option that names none of its values throws
 * `IllegalArgumentException` when the listing is asked for, as does a title the wiki would not accept.
 */
export declare class ListService {
    private constructor();
    /**
     * The titles in [category], which needs no `Category:` prefix.
     */
    categoryMembers(category: string, options?: Nullable<CategoryMembersOptions>): AsyncIterable<string>;
    /**
     * The titles of the pages that link to [target].
     */
    backlinks(target: string, options?: Nullable<BacklinksOptions>): AsyncIterable<string>;
    /**
     * The titles of the pages that transclude [template], which needs no `Template:` prefix: how a bot finds
     * every use of a template. Every namespace unless told.
     */
    transclusions(template: string, options?: Nullable<ListOptions>): AsyncIterable<string>;
    /**
     * The titles [page] links to. Every namespace unless told.
     */
    linksFrom(page: string, options?: Nullable<ListOptions>): AsyncIterable<string>;
    /**
     * The titles of the templates transcluded on [page].
     */
    templatesOn(page: string, limit?: Nullable<number>): AsyncIterable<string>;
    /**
     * The titles of the categories [page] belongs to.
     */
    categoriesOf(page: string, limit?: Nullable<number>): AsyncIterable<string>;
    /**
     * The titles of the files used on [page], whether displayed or transcluded through a template.
     */
    filesOn(page: string, limit?: Nullable<number>): AsyncIterable<string>;
    /**
     * The links from [page] to the same subject on other languages' wikis.
     */
    languageLinksOn(page: string, limit?: Nullable<number>): AsyncIterable<LanguageLink>;
    /**
     * The links from [page] to other wikis through the interwiki map, such as `w:Etsy`.
     */
    interwikiLinksOn(page: string, limit?: Nullable<number>): AsyncIterable<InterwikiLink>;
    /**
     * The external URLs [page] links to, the other direction from [externalLinkUsage].
     */
    externalLinksOn(page: string, limit?: Nullable<number>): AsyncIterable<string>;
    /**
     * The title of every page in a namespace, in title order.
     */
    allPages(options?: Nullable<AllPagesOptions>): AsyncIterable<string>;
    /**
     * The title of every category that has anything in it, in title order. Categories with no members are not
     * enumerated, whether or not anything links to them.
     */
    allCategories(options?: Nullable<AllCategoriesOptions>): AsyncIterable<string>;
    /**
     * The titles of the maintenance categories the wiki files pages into by itself.
     */
    trackingCategories(limit?: Nullable<number>): AsyncIterable<string>;
    /**
     * Every title anything links to, whether or not it exists: the way to find red links.
     */
    allLinkTargets(options?: Nullable<AllTargetsOptions>): AsyncIterable<string>;
    /**
     * Every title anything redirects to, in the shape of [allLinkTargets].
     */
    allRedirectTargets(options?: Nullable<AllTargetsOptions>): AsyncIterable<string>;
    /**
     * Every title anything transcludes, in the shape of [allLinkTargets].
     */
    allTransclusionTargets(options?: Nullable<AllTargetsOptions>): AsyncIterable<string>;
    /**
     * Every file anything uses, in the shape of [allLinkTargets].
     */
    allFileUsages(options?: Nullable<AllFileUsagesOptions>): AsyncIterable<string>;
    /**
     * The titles beginning with [query], as the wiki's own search box completes them. Not a substring search.
     * The main namespace unless told.
     */
    prefixSearch(query: string, options?: Nullable<ListOptions>): AsyncIterable<string>;
    /**
     * The titles of the pages a search for [query] finds.
     */
    search(query: string, options?: Nullable<SearchOptions>): AsyncIterable<string>;
    /**
     * The titles the wiki lists on the special page [name], such as `"Wantedcategories"`.
     */
    specialPage(name: string, limit?: Nullable<number>): AsyncIterable<string>;
    /**
     * The titles of the pages linking to [url], a whole address or a bare host: how a bot finds every page
     * citing a dead domain.
     */
    externalLinkUsage(url: string, options?: Nullable<ExternalLinkUsageOptions>): AsyncIterable<string>;
    /**
     * The titles of random pages, the main namespace unless told. Each request gets a fresh set, so the same
     * page may come twice.
     */
    randomPages(options?: Nullable<ListOptions>): AsyncIterable<string>;
    /**
     * The titles on the watchlist of the account logged in. Every namespace unless told.
     */
    watchlist(options?: Nullable<ListOptions>): AsyncIterable<string>;
    /**
     * The titles of the pages carrying a page property, such as `"disambiguation"` or `"wikibase_item"`.
     */
    pagesWithProperty(property: string, limit?: Nullable<number>): AsyncIterable<string>;
    /**
     * The titles protected against creation. Every namespace unless told.
     */
    protectedTitles(options?: Nullable<ListOptions>): AsyncIterable<string>;
}
export declare namespace ListService {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ListService;
    }
}
/**
 * A link from a page to the same subject on another language's wiki. The title is that wiki's, which may
 * differ from this one's.
 */
export declare class LanguageLink {
    private constructor();
    /**
     * The language code, as the wiki's interwiki map spells it.
     */
    get code(): string;
    /**
     * The title on the other wiki.
     */
    get title(): string;
    /**
     * The full URL, or `null` when the wiki did not say.
     */
    get url(): Nullable<string>;
    /**
     * The language's name in its own language, or `null` when the wiki did not say.
     */
    get autonym(): Nullable<string>;
    /**
     * The language's name in the wiki's content language, or `null` when the wiki did not say.
     */
    get name(): Nullable<string>;
}
export declare namespace LanguageLink {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => LanguageLink;
    }
}
/**
 * A link to another wiki through the interwiki map.
 */
export declare class InterwikiLink {
    private constructor();
    /**
     * The interwiki prefix, `"w"` for Wikipedia.
     */
    get prefix(): string;
    /**
     * The title on the other wiki.
     */
    get title(): string;
    /**
     * The full URL, or `null` when the wiki did not say.
     */
    get url(): Nullable<string>;
}
export declare namespace InterwikiLink {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InterwikiLink;
    }
}
/**
 * The options of `events`.
 */
export declare interface LogEventsOptions {
    /**
     * The log to read, such as `"block"`, `"move"`, `"delete"`, `"upload"` or `"newusers"`. Every log unless
     * given.
     */
    readonly type?: Nullable<string>;
    /**
     * A specific action, such as `"move/move_redir"`. It already names its log, so it wins over [type].
     */
    readonly action?: Nullable<string>;
    /**
     * Only this account's entries.
     */
    readonly user?: Nullable<string>;
    /**
     * Only entries about this page.
     */
    readonly page?: Nullable<string>;
    /**
     * Only entries about pages in these namespaces, by number. Every namespace unless given.
     */
    readonly namespaces?: Nullable<Array<number>>;
    /**
     * `"newest-first"`, the default, or `"oldest-first"`.
     */
    readonly order?: Nullable<TimeOrder>;
    /**
     * Where in time to begin.
     */
    readonly start?: Nullable<Date>;
    /**
     * Where in time to stop.
     */
    readonly end?: Nullable<Date>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `recentChanges`.
 */
export declare interface RecentChangesOptions {
    /**
     * Which namespaces to watch, by number. Every namespace unless given.
     */
    readonly namespaces?: Nullable<Array<number>>;
    /**
     * Which kinds to include, from `"edit"`, `"new"`, `"log"`, `"categorize"` and `"external"`. Every kind
     * unless given.
     */
    readonly types?: Nullable<Array<ChangeType>>;
    /**
     * Only this account's changes.
     */
    readonly user?: Nullable<string>;
    /**
     * Constrains the flags: each is required, or left out with a leading `!`. `["!bot"]` leaves out edits
     * flagged as a bot's, which is usually what a bot watching for human activity wants.
     */
    readonly show?: Nullable<Array<RecentChangeFilter>>;
    /**
     * Only changes carrying this change tag.
     */
    readonly tag?: Nullable<string>;
    /**
     * Whether to give only changes that are still the newest revision of their page.
     */
    readonly topOnly?: Nullable<boolean>;
    /**
     * `"newest-first"`, the default, or `"oldest-first"`.
     */
    readonly order?: Nullable<TimeOrder>;
    /**
     * Where in time to begin.
     */
    readonly start?: Nullable<Date>;
    /**
     * Where in time to stop.
     */
    readonly end?: Nullable<Date>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `watchlistChanges`.
 */
export declare interface WatchlistChangesOptions {
    /**
     * Which namespaces to watch, by number. Every namespace unless given.
     */
    readonly namespaces?: Nullable<Array<number>>;
    /**
     * Which kinds to include, from `"edit"`, `"new"`, `"log"`, `"categorize"` and `"external"`. Every kind
     * unless given.
     */
    readonly types?: Nullable<Array<ChangeType>>;
    /**
     * Constrains the flags: each is required, or left out with a leading `!`, such as `["!bot"]`.
     */
    readonly show?: Nullable<Array<WatchlistFilter>>;
    /**
     * Whether to give every revision in the window rather than only the latest per page.
     */
    readonly allRevisions?: Nullable<boolean>;
    /**
     * `"newest-first"`, the default, or `"oldest-first"`.
     */
    readonly order?: Nullable<TimeOrder>;
    /**
     * Where in time to begin.
     */
    readonly start?: Nullable<Date>;
    /**
     * Where in time to stop.
     */
    readonly end?: Nullable<Date>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * What has happened on a wiki, reached as `wiki.logs`. Each listing pages as it is walked, and an option that
 * names none of its values throws `IllegalArgumentException` when the listing is asked for.
 */
export declare class LogService {
    private constructor();
    /**
     * Entries from the wiki's logs, every log unless told.
     */
    events(options?: Nullable<LogEventsOptions>): AsyncIterable<LogEvent>;
    /**
     * Recent changes, which mixes edits, page creations and log actions in one stream.
     */
    recentChanges(options?: Nullable<RecentChangesOptions>): AsyncIterable<RecentChange>;
    /**
     * Changes to the pages on the watchlist of the account logged in.
     */
    watchlistChanges(options?: Nullable<WatchlistChangesOptions>): AsyncIterable<RecentChange>;
}
export declare namespace LogService {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => LogService;
    }
}
/**
 * One entry from a wiki log: a block, a move, a deletion, an upload.
 */
export declare class LogEvent {
    private constructor();
    /**
     * The entry's own id, which orders the log and never repeats.
     */
    get id(): number;
    /**
     * The log it belongs to, such as `"block"`, `"move"`, `"delete"`, `"upload"` or `"newusers"`.
     */
    get type(): string;
    /**
     * What was done, such as `"block"`, `"reblock"`, `"move_redir"`, `"revision"` or `"overwrite"`.
     */
    get action(): string;
    /**
     * The title of the page it is about, or `null` when that is hidden.
     */
    get page(): Nullable<string>;
    /**
     * The wiki's own id for that page, or `null` when the wiki did not say.
     */
    get pageId(): Nullable<number>;
    /**
     * Who did it, or `null` when that is hidden.
     */
    get user(): Nullable<string>;
    /**
     * When it was done.
     */
    get timestamp(): Date;
    /**
     * The reason given, or `null` when that is hidden.
     */
    get comment(): Nullable<string>;
    /**
     * The change tags on it.
     */
    get tags(): Array<string>;
    /**
     * The fields particular to this kind of entry.
     */
    get details(): LogDetails;
    /**
     * Whether any part of it is hidden.
     */
    get isSuppressed(): boolean;
}
export declare namespace LogEvent {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => LogEvent;
    }
}
/**
 * The part of a log entry that depends on what kind of entry it is. [kind] says which, and only the fields it
 * names are set: the others are `null`.
 */
export declare class LogDetails {
    private constructor();
    /**
     * `"none"` for an entry with no fields of its own; `"move"`, with [target] and [suppressedRedirect];
     * `"block"`, with [duration], [expiry], [flags], [isPartial] and [isInfinite]; `"upload"`, with [sha1];
     * `"protect"`, with [protections] and [description]; `"rights"`, with [added] and [removed]; `"patrol"`,
     * with [revision], [previous] and [automatic]; `"unknown"` for a log this library does not model, with
     * [fields].
     */
    get kind(): LogDetailsKind;
    /**
     * Where a moved page went.
     */
    get target(): Nullable<string>;
    /**
     * Whether the mover suppressed the redirect a move normally leaves behind.
     */
    get suppressedRedirect(): Nullable<boolean>;
    /**
     * A block's duration as entered, such as `"1 week"`, or `null` on an unblock.
     */
    get duration(): Nullable<string>;
    /**
     * When a block ends, or `null` if it never does, and on an unblock.
     */
    get expiry(): Nullable<Date>;
    /**
     * A block's flags, such as `"nocreate"`, `"noemail"` or `"autoblock"`.
     */
    get flags(): Nullable<Array<string>>;
    /**
     * Whether a block covers only some pages or actions rather than the whole wiki.
     */
    get isPartial(): Nullable<boolean>;
    /**
     * Whether a block never expires, which an unblock also says: read the entry's action first.
     */
    get isInfinite(): Nullable<boolean>;
    /**
     * The hash of an uploaded file.
     */
    get sha1(): Nullable<string>;
    /**
     * The restrictions a change of protection set.
     */
    get protections(): Nullable<Array<Protection>>;
    /**
     * The wiki's own summary of a change of protection, meant for a reader.
     */
    get description(): Nullable<string>;
    /**
     * The groups a user gained.
     */
    get added(): Nullable<Array<string>>;
    /**
     * The groups a user lost.
     */
    get removed(): Nullable<Array<string>>;
    /**
     * The revision marked patrolled.
     */
    get revision(): Nullable<number>;
    /**
     * The revision patrolled before it, when the log records one.
     */
    get previous(): Nullable<number>;
    /**
     * Whether the wiki patrolled it by itself.
     */
    get automatic(): Nullable<boolean>;
    /**
     * An unmodelled entry's own scalar fields under the API's names, as strings.
     */
    get fields(): Nullable<Record<string, string>>;
}
export declare namespace LogDetails {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => LogDetails;
    }
}
/**
 * One restriction on a page: an action, who may still do it, and until when.
 */
export declare class Protection {
    private constructor();
    /**
     * Whether it is still in force at [now].
     */
    isActiveAt(now: Date): boolean;
    /**
     * The restricted action, such as `"edit"` or `"move"`.
     */
    get action(): string;
    /**
     * The group that may still do it, such as `"sysop"` or `"autoconfirmed"`.
     */
    get level(): string;
    /**
     * When the restriction lapses, or `null` if it does not.
     */
    get expiry(): Nullable<Date>;
    /**
     * Whether it propagates to transcluded pages.
     */
    get cascading(): boolean;
}
export declare namespace Protection {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Protection;
    }
}
/**
 * One entry from recent changes or a watchlist: an edit, a page creation or a log action.
 */
export declare class RecentChange {
    private constructor();
    /**
     * The recent-changes id, which orders the stream.
     */
    get id(): number;
    /**
     * `"edit"`, `"new"`, `"log"`, `"categorize"` or `"external"`.
     */
    get type(): ChangeType;
    /**
     * The title of the page changed, or `null` when that is hidden.
     */
    get page(): Nullable<string>;
    /**
     * The wiki's own id for that page, or `null` when the wiki did not say.
     */
    get pageId(): Nullable<number>;
    /**
     * Who changed it, or `null` when that is hidden.
     */
    get user(): Nullable<string>;
    /**
     * When it happened.
     */
    get timestamp(): Date;
    /**
     * The edit summary or log reason, or `null` when that is hidden.
     */
    get comment(): Nullable<string>;
    /**
     * The revision it produced, or `null` on a log entry.
     */
    get revision(): Nullable<number>;
    /**
     * The revision it was made from, or `null` on a page creation and on a log entry.
     */
    get previousRevision(): Nullable<number>;
    /**
     * How many bytes the page gained, negative for a removal.
     */
    get sizeChange(): number;
    /**
     * Whether the editor marked it minor.
     */
    get minor(): boolean;
    /**
     * Whether it was flagged a bot edit.
     */
    get bot(): boolean;
    /**
     * Whether the edit created the page.
     */
    get isNew(): boolean;
    /**
     * Whether somebody has reviewed it, also `false` where that is not known, which [patrolled] tells apart.
     */
    get isPatrolled(): boolean;
    /**
     * The log entry, when this is a log action rather than an edit.
     */
    get logEvent(): Nullable<LogEvent>;
    /**
     * The change tags on it.
     */
    get tags(): Array<string>;
    /**
     * Whether somebody has reviewed it, or `null` where the wiki did not say: it tells only a session with
     * the `patrol` or `patrolmarks` right, on a wiki that patrols.
     */
    get patrolled(): Nullable<boolean>;
    /**
     * Whether this is a log action rather than an edit.
     */
    get isLogEntry(): boolean;
}
export declare namespace RecentChange {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RecentChange;
    }
}
/**
 * The options of `applyTags`, which needs a tag to add or remove and something to put it on.
 */
export declare interface ApplyTagsOptions {
    /**
     * The tags to apply.
     */
    readonly add?: Nullable<Array<string>>;
    /**
     * The tags to take off.
     */
    readonly remove?: Nullable<Array<string>>;
    /**
     * Revision ids to tag.
     */
    readonly revisions?: Nullable<Array<number>>;
    /**
     * Recent-changes ids to tag.
     */
    readonly recentChanges?: Nullable<Array<number>>;
    /**
     * Log ids to tag.
     */
    readonly logEntries?: Nullable<Array<number>>;
    /**
     * Why, which is recorded in the tag log.
     */
    readonly reason?: Nullable<string>;
}
/**
 * What a wiki says about itself beyond its site information, reached as `wiki.meta`: fetched when first asked
 * for and kept for as long as the wiki is open.
 */
export declare class MetaService {
    private constructor();
    /**
     * One `siprop` block of the wiki's site information, such as `"rightsinfo"`, as plain JSON, or `null`
     * when the wiki does not have it.
     */
    property(name: string): Promise<Nullable<JsonValue>>;
    /**
     * The interface messages [keys] the wiki has, in [language], the wiki's own unless given.
     */
    messages(keys: Array<string>, language?: Nullable<string>): Promise<Record<string, string>>;
    /**
     * The interface message [key], or `null` when the wiki has no such message.
     */
    message(key: string, language?: Nullable<string>): Promise<Nullable<string>>;
    /**
     * The magic words the wiki knows, by name, with every alias it accepts.
     */
    magicWords(): Promise<Record<string, Array<string>>>;
    /**
     * The wiki's own counters: `pages`, `articles`, `edits`, `users`, `activeusers`.
     */
    statistics(): Promise<Record<string, number>>;
    /**
     * What the wiki knows about languages, by code: [codes], or every one it knows, several hundred, unless
     * given.
     */
    languages(codes?: Nullable<Array<string>>): Promise<Record<string, LanguageInfo>>;
    /**
     * Adds or removes change tags on revisions, recent changes or log entries. Needs the `changetags` right,
     * and `applychangetags` for the tags themselves.
     */
    applyTags(options: ApplyTagsOptions): Promise<void>;
    /**
     * Defines, retires or removes a change tag. Needs the `managechangetags` right.
     * 
     * @param tag - the tag.
     * @param operation - `"create"`, `"delete"`, `"activate"` or `"deactivate"`.
     * @param reason - why, which is recorded in the tag log.
     */
    manageTag(tag: string, operation: TagOperation, reason?: string): Promise<void>;
}
export declare namespace MetaService {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => MetaService;
    }
}
/**
 * What a wiki knows about a language.
 */
export declare class LanguageInfo {
    private constructor();
    /**
     * The wiki's own code for it.
     */
    get code(): string;
    /**
     * The name in the wiki's content language, such as `"French"`.
     */
    get name(): string;
    /**
     * The name in the language itself, such as `"français"`.
     */
    get autonym(): string;
    /**
     * Which way it is written: `"left-to-right"` or `"right-to-left"`.
     */
    get direction(): TextDirection;
    /**
     * The languages the wiki falls back to when this one has no translation, in order.
     */
    get fallbacks(): Array<string>;
    /**
     * The IETF tag, which is not always the wiki's own code, or `null`.
     */
    get bcp47(): Nullable<string>;
}
export declare namespace LanguageInfo {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => LanguageInfo;
    }
}
/**
 * What one edit says. Exactly one of [text], [appendText] and [prependText] is required, and a contradictory
 * edit rejects before anything is sent.
 */
export declare interface EditOptions {
    /**
     * The full new wikitext.
     */
    readonly text?: Nullable<string>;
    /**
     * Text to add at the end of the page instead of replacing it.
     */
    readonly appendText?: Nullable<string>;
    /**
     * Text to add at the start of the page instead of replacing it.
     */
    readonly prependText?: Nullable<string>;
    /**
     * The edit summary.
     */
    readonly summary?: Nullable<string>;
    /**
     * Whether to mark the edit minor, `false` unless given.
     */
    readonly minor?: Nullable<boolean>;
    /**
     * Whether to flag the edit as a bot's, hiding it from default recent-changes views, `true` unless given.
     */
    readonly bot?: Nullable<boolean>;
    /**
     * The revision the edit was computed from, a page's `revision`, so that the wiki can detect a conflict.
     */
    readonly baseRevision?: Nullable<number>;
    /**
     * Whether to refuse to create the page if it does not exist.
     */
    readonly noCreate?: Nullable<boolean>;
    /**
     * Whether to refuse to edit the page if it already exists.
     */
    readonly createOnly?: Nullable<boolean>;
    /**
     * Change tags to attach to the edit.
     */
    readonly tags?: Nullable<Array<string>>;
    /**
     * Which section to edit: a section's `index` from `wiki.render.sections`, or `"new"` to start one.
     */
    readonly section?: Nullable<string>;
    /**
     * The heading of a `section: "new"` edit. Meaningless on any other section.
     */
    readonly sectionTitle?: Nullable<string>;
    /**
     * What the edit does to the account's watchlist: `"no-change"`, `"preferences"`, the default, `"watch"`
     * or `"unwatch"`.
     */
    readonly watchlist?: Nullable<WatchMode>;
    /**
     * How long to watch the page for, as MediaWiki spells durations: `"1 month"`. Forever unless given.
     */
    readonly watchlistExpiry?: Nullable<string>;
}
/**
 * The options of `move`.
 */
export declare interface MoveOptions {
    /**
     * The log summary.
     */
    readonly reason?: Nullable<string>;
    /**
     * Whether to leave a redirect behind, `true` unless given; `false` needs the `suppressredirect` right.
     */
    readonly leaveRedirect?: Nullable<boolean>;
    /**
     * Whether to move the talk page with it, `true` unless given.
     */
    readonly moveTalk?: Nullable<boolean>;
    /**
     * Whether to move its subpages with it, `false` unless given.
     */
    readonly moveSubpages?: Nullable<boolean>;
    /**
     * `"no-change"`, `"preferences"`, the default, `"watch"` or `"unwatch"`.
     */
    readonly watchlist?: Nullable<WatchMode>;
}
/**
 * The options of `delete`.
 */
export declare interface DeleteOptions {
    /**
     * The deletion summary, which the log records.
     */
    readonly reason?: Nullable<string>;
    /**
     * Whether to delete the page's talk page with it, `false` unless given.
     */
    readonly deleteTalk?: Nullable<boolean>;
    /**
     * `"no-change"`, `"preferences"`, the default, `"watch"` or `"unwatch"`.
     */
    readonly watchlist?: Nullable<WatchMode>;
}
/**
 * The options of `undelete`.
 */
export declare interface UndeleteOptions {
    /**
     * The log summary.
     */
    readonly reason?: Nullable<string>;
    /**
     * Whether to restore the page's talk page with it, `false` unless given.
     */
    readonly undeleteTalk?: Nullable<boolean>;
    /**
     * `"no-change"`, `"preferences"`, the default, `"watch"` or `"unwatch"`.
     */
    readonly watchlist?: Nullable<WatchMode>;
}
/**
 * One restriction to put on a page with `protect`.
 */
export declare interface ProtectionRequest {
    /**
     * The action to restrict, such as `"edit"` or `"move"`.
     */
    readonly action: string;
    /**
     * The group that may still do it, such as `"sysop"` or `"autoconfirmed"`.
     */
    readonly level: string;
    /**
     * When the restriction lapses. It does not unless given.
     */
    readonly expiry?: Nullable<Date>;
    /**
     * Whether it propagates to transcluded pages, `false` unless given.
     */
    readonly cascading?: Nullable<boolean>;
}
/**
 * The options of `protect`.
 */
export declare interface ProtectOptions {
    /**
     * The log summary.
     */
    readonly reason?: Nullable<string>;
    /**
     * Whether to protect the pages this one transcludes too, `false` unless given.
     */
    readonly cascade?: Nullable<boolean>;
    /**
     * `"no-change"`, `"preferences"`, the default, `"watch"` or `"unwatch"`.
     */
    readonly watchlist?: Nullable<WatchMode>;
}
/**
 * The options of `rollback`.
 */
export declare interface RollbackOptions {
    /**
     * The edit summary, the wiki's own unless given.
     */
    readonly summary?: Nullable<string>;
    /**
     * Whether to mark the reverted edits and the revert as a bot's, `true` unless given.
     */
    readonly markBot?: Nullable<boolean>;
    /**
     * `"no-change"`, `"preferences"`, the default, `"watch"` or `"unwatch"`.
     */
    readonly watchlist?: Nullable<WatchMode>;
}
/**
 * The options of `undo`.
 */
export declare interface UndoOptions {
    /**
     * The edit summary.
     */
    readonly summary?: Nullable<string>;
    /**
     * The older end of the range to undo. Only the one revision unless given.
     */
    readonly through?: Nullable<number>;
}
/**
 * The options of `mergeHistory`.
 */
export declare interface MergeHistoryOptions {
    /**
     * Merge only revisions at or before this moment. The whole history unless given.
     */
    readonly upTo?: Nullable<Date>;
    /**
     * The log summary.
     */
    readonly reason?: Nullable<string>;
}
/**
 * The options of `importPage`.
 */
export declare interface ImportPageOptions {
    /**
     * Whether to bring every revision rather than only the latest, `true` unless given.
     */
    readonly fullHistory?: Nullable<boolean>;
    /**
     * Whether to bring the templates the page uses as well, `false` unless given.
     */
    readonly includeTemplates?: Nullable<boolean>;
    /**
     * Import beneath this title instead of at the page's own.
     */
    readonly rootPage?: Nullable<string>;
    /**
     * The log summary.
     */
    readonly summary?: Nullable<string>;
}
/**
 * The options of `expandTexts`.
 */
export declare interface ExpandTextsOptions {
    /**
     * The page to expand them as, which decides what `{{PAGENAME}}` and relative transclusions mean.
     */
    readonly title?: Nullable<string>;
    /**
     * Whether to report the categories each text would put a page in, `false` unless given. Each text is then
     * sent alone.
     */
    readonly categories?: Nullable<boolean>;
    /**
     * Unsaved templates or modules to expand with.
     */
    readonly sandbox?: Nullable<TemplateSandboxOptions>;
    /**
     * The revision to expand as, which `{{REVISIONID}}` reads. As a preview unless given.
     */
    readonly revision?: Nullable<number>;
}
/**
 * Reading and writing pages, reached as `wiki.pagesService`. Every fetch is one explicit call. A title has
 * its namespace prefix; those that are read as a category or a file need none.
 * 
 * What a wiki refuses to edit is a result, an `EditResult`. Any other failure rejects the promise, as does an
 * option that names none of its values.
 */
export declare class PageService {
    private constructor();
    /**
     * The current text of [title], or `null` when there is no such page.
     */
    content(title: string): Promise<Nullable<Page>>;
    /**
     * The current text of each of [titles] that exists, in the order asked for, in as few requests as the
     * wiki allows.
     */
    contents(titles: Array<string>): Promise<Array<Page>>;
    /**
     * [contents], never from a response cache: for reads whose answer must be current, such as the text an
     * edit about to be saved is computed from.
     */
    freshContents(titles: Array<string>): Promise<Array<Page>>;
    /**
     * Whether a page exists, in one request and without fetching its text.
     */
    exists(title: string): Promise<boolean>;
    /**
     * Applies an edit and says how the wiki answered: a refusal is a result, not an error. Rejects without
     * sending anything when [options] contradict themselves.
     */
    edit(title: string, options: EditOptions): Promise<EditResult>;
    /**
     * Renames a page, answering with its new title. A refused move rejects.
     */
    move(from: string, to: string, options?: Nullable<MoveOptions>): Promise<string>;
    /**
     * Deletes a page. Needs the `delete` right.
     */
    delete(title: string, options?: Nullable<DeleteOptions>): Promise<void>;
    /**
     * The protections in force on each of [titles] the wiki knows.
     */
    protections(titles: Array<string>): Promise<Array<PageProtections>>;
    /**
     * What the logged-in account may do to each of [titles]. [actions] are the actions to test, named as the
     * API names them, `"edit"` alone unless given.
     */
    testActions(titles: Array<string>, actions?: Nullable<Array<string>>): Promise<Array<PageActions>>;
    /**
     * Who has edited each of [titles].
     */
    contributors(titles: Array<string>): Promise<Array<PageContributors>>;
    /**
     * How much each of [categories], which need no `Category:` prefix, holds. One that is empty or is no
     * category is left out.
     */
    categoryInfo(categories: Array<string>): Promise<Array<CategoryInfo>>;
    /**
     * What links to each of [titles], fifty titles to a request. One that nothing links to is left out.
     */
    backlinksOf(titles: Array<string>): Promise<Array<RelatedPages>>;
    /**
     * What transcludes each of [titles], as [backlinksOf] does, which take their prefix.
     */
    transclusionsOf(titles: Array<string>): Promise<Array<RelatedPages>>;
    /**
     * What uses each of [files], which need no `File:` prefix, as [backlinksOf] does.
     */
    fileUsageOf(files: Array<string>): Promise<Array<RelatedPages>>;
    /**
     * Sets the protections on a page, replacing whatever was there. An empty array unprotects it.
     * 
     * @param title - the page.
     * @param protections - the restrictions to put on it.
     * @param options - the rest.
     */
    protect(title: string, protections: Array<ProtectionRequest>, options?: Nullable<ProtectOptions>): Promise<void>;
    /**
     * Reverts every consecutive edit by [user] at the top of a page, in one action that refuses if the page
     * has been edited since. Needs the `rollback` right.
     */
    rollback(title: string, user: string, options?: Nullable<RollbackOptions>): Promise<EditResult>;
    /**
     * Undoes [revision], or the range from `through` to it. The wiki does the merge, and refuses when later
     * edits touched the same lines.
     */
    undo(title: string, revision: number, options?: Nullable<UndoOptions>): Promise<EditResult>;
    /**
     * Purges the parser cache of [titles], re-rendering their links tables too when [forceLinkUpdate].
     */
    purge(titles: Array<string>, forceLinkUpdate?: boolean): Promise<void>;
    /**
     * Restores every deleted revision of a page. Needs the `undelete` right.
     */
    undelete(title: string, options?: Nullable<UndeleteOptions>): Promise<void>;
    /**
     * Merges the history of page [from] into page [to]. Needs the `mergehistory` right.
     */
    mergeHistory(from: string, to: string, options?: Nullable<MergeHistoryOptions>): Promise<void>;
    /**
     * Copies [page], as the other wiki spells it, here from the wiki with the interwiki prefix [source], such
     * as `"commons"`. Needs the `import` right.
     */
    importPage(source: string, page: string, options?: Nullable<ImportPageOptions>): Promise<void>;
    /**
     * Sets a page's language, which decides its reading direction. Needs the `pagelang` right.
     */
    setLanguage(title: string, language: string, reason?: string): Promise<void>;
    /**
     * Changes a page's content model, such as to `"Scribunto"`. Needs the `editcontentmodel` right.
     */
    changeContentModel(title: string, model: string, summary?: string): Promise<void>;
    /**
     * Adds [titles] to this session's watchlist, or removes them when [watch] is `false`.
     * 
     * @param titles - the pages.
     * @param watch - `true` to add, `false` to remove.
     * @param expiry - how long to watch for, as MediaWiki spells durations: `"1 month"`. Forever unless given.
     */
    watch(titles: Array<string>, watch?: boolean, expiry?: Nullable<string>): Promise<void>;
    /**
     * Expands templates and parser functions in [wikitext] as the wiki would, as the page [title] if given.
     */
    expandText(wikitext: string, title?: Nullable<string>): Promise<string>;
    /**
     * Expands many [texts] as [expandText] does, in as few requests as it can, in the order given.
     */
    expandTexts(texts: Array<string>, options?: Nullable<ExpandTextsOptions>): Promise<Array<ExpandedText>>;
    /**
     * The redirects to each of [titles], only those in [namespaces] when given. A page no redirect points to
     * is left out.
     */
    redirectsTo(titles: Array<string>, namespaces?: Nullable<Array<number>>): Promise<Array<RelatedPages>>;
}
export declare namespace PageService {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PageService;
    }
}
/**
 * The protections in force on one page.
 */
export declare class PageProtections {
    private constructor();
    /**
     * The page.
     */
    get title(): string;
    /**
     * Its protections.
     */
    get protections(): Array<Protection>;
}
export declare namespace PageProtections {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PageProtections;
    }
}
/**
 * What the logged-in account may do to one page.
 */
export declare class PageActions {
    private constructor();
    /**
     * Whether [action] is permitted. `false` for an action that was not tested.
     */
    allows(action: string): boolean;
    /**
     * Why [action] was refused, or nothing if it is permitted or was not tested.
     */
    reasons(action: string): Array<string>;
    /**
     * The page.
     */
    get title(): string;
    /**
     * Each action that was tested, with the error codes refusing it: none when it is permitted.
     */
    get refusals(): Record<string, Array<string>>;
}
export declare namespace PageActions {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PageActions;
    }
}
/**
 * Someone who has edited a page.
 */
export declare class Contributor {
    private constructor();
    /**
     * The account name.
     */
    get name(): string;
    /**
     * The account's id, `0` when the wiki did not report one.
     */
    get id(): number;
}
export declare namespace Contributor {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Contributor;
    }
}
/**
 * Who has edited one page.
 */
export declare class PageContributors {
    private constructor();
    /**
     * The page.
     */
    get title(): string;
    /**
     * How many edits came from addresses rather than accounts.
     */
    get anonymous(): number;
    /**
     * The named accounts that have edited it.
     */
    get users(): Array<Contributor>;
}
export declare namespace PageContributors {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PageContributors;
    }
}
/**
 * How much one category holds, as the wiki counts it.
 */
export declare class CategoryInfo {
    private constructor();
    /**
     * The category, prefix included.
     */
    get title(): string;
    /**
     * Everything in it: pages, files and subcategories together.
     */
    get size(): number;
    /**
     * Ordinary pages.
     */
    get pages(): number;
    /**
     * Files.
     */
    get files(): number;
    /**
     * Subcategories.
     */
    get subcategories(): number;
    /**
     * Whether it is hidden from the box at the foot of its members.
     */
    get hidden(): boolean;
}
export declare namespace CategoryInfo {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => CategoryInfo;
    }
}
/**
 * One page, and the pages that point at it.
 */
export declare class RelatedPages {
    private constructor();
    /**
     * The page.
     */
    get title(): string;
    /**
     * The titles pointing at it.
     */
    get pages(): Array<string>;
}
export declare namespace RelatedPages {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RelatedPages;
    }
}
/**
 * What `expandTexts` made of one text.
 */
export declare class ExpandedText {
    private constructor();
    /**
     * The expanded wikitext.
     */
    get text(): string;
    /**
     * The categories it would put a page in, without their prefix; none when they were not asked for.
     */
    get categories(): Array<string>;
}
export declare namespace ExpandedText {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ExpandedText;
    }
}
/**
 * Unsaved templates and modules to parse with, through the TemplateSandbox extension. Needs [prefixes] or a
 * [title] with its [text].
 */
export declare interface TemplateSandboxOptions {
    /**
     * Pages under which sandboxed versions live. With `User:Me/sandbox`, a transclusion of `Template:Foo`
     * uses `User:Me/sandbox/Template:Foo` where that page exists.
     */
    readonly prefixes?: Nullable<Array<string>>;
    /**
     * One page to stand in for with [text], such as `Module:foo`, for this parse only.
     */
    readonly title?: Nullable<string>;
    /**
     * What [title] says for this parse.
     */
    readonly text?: Nullable<string>;
    /**
     * The content model of [text], `Scribunto` for a module. The wiki goes by the title unless given.
     */
    readonly contentModel?: Nullable<string>;
}
/**
 * What to parse with `wiki.render.parse`, how, and which parts of the result to report: a saved [page], or
 * unsaved [text] parsed as if it were at [context].
 */
export declare interface ParseRequest {
    /**
     * A saved page to parse.
     */
    readonly page?: Nullable<string>;
    /**
     * Unsaved wikitext to parse.
     */
    readonly text?: Nullable<string>;
    /**
     * The title to parse [text] as, which decides what relative links and `PAGENAME` mean.
     */
    readonly context?: Nullable<string>;
    /**
     * Which parts of the result to report, from `"text"`, `"links"`, `"templates"`, `"categories"`,
     * `"images"`, `"external-links"`, `"limit-report"`, `"toc-data"` and the deprecated `"sections"`.
     * `contents` unless given.
     */
    readonly properties?: Nullable<Array<ParseProperty>>;
    /**
     * Unsaved templates or modules to parse with.
     */
    readonly sandbox?: Nullable<TemplateSandboxOptions>;
    /**
     * The revision to parse [text] as, which `{{REVISIONID}}` reads. As a preview unless given.
     */
    readonly revision?: Nullable<number>;
    /**
     * Which of the wiki's parsers to use, `"legacy"` or `"parsoid"`. Left to the wiki unless given.
     */
    readonly parser?: Nullable<ParserName>;
}
/**
 * The wiki's own view of a page, reached as `wiki.render`: what it renders, and what it resolves to,
 * templates and all. Where a property is taken, it is one of those [ParseRequest.properties] lists.
 */
export declare class RenderService {
    private constructor();
    /**
     * The headings of [page], in order, each with the `index` an edit takes as its section.
     */
    sections(page: string): Promise<Array<RenderedSection>>;
    /**
     * The rendered HTML of the saved [page].
     */
    render(page: string): Promise<string>;
    /**
     * The rendered HTML of unsaved [wikitext], parsed as if it were at the title [context].
     */
    renderText(wikitext: string, context: string): Promise<string>;
    /**
     * What the saved [page] resolves to: only [properties], `contents` unless given.
     */
    resolve(page: string, properties?: Nullable<Array<ParseProperty>>): Promise<ParsedPage>;
    /**
     * What unsaved [wikitext] would resolve to at the title [context], without saving it.
     */
    resolveText(wikitext: string, context: string, properties?: Nullable<Array<ParseProperty>>): Promise<ParsedPage>;
    /**
     * Parses what [request] names and reports the parts it asks for, all in one request. The one way to parse
     * with a sandbox. Rejects when it names both a page and a text, or neither, or a text without a context.
     */
    parse(request: ParseRequest): Promise<ParsedPage>;
    /**
     * What a page points at, which is what asking without properties reports.
     */
    get contents(): Array<ParseProperty>;
    /**
     * Every property, leaving out those kept only for compatibility.
     */
    get all(): Array<ParseProperty>;
}
export declare namespace RenderService {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RenderService;
    }
}
/**
 * One heading of a page, as the wiki renders it.
 */
export declare class RenderedSection {
    private constructor();
    /**
     * What an edit takes as its `section`. A string: the wiki numbers some subsections `T-1`.
     */
    get index(): string;
    /**
     * The heading text, with markup already resolved.
     */
    get heading(): string;
    /**
     * The heading level, `2` for a second-level heading.
     */
    get level(): number;
    /**
     * Depth in the contents list, which starts at 1 whatever the heading level is.
     */
    get tocLevel(): number;
    /**
     * The section number as the contents list shows it, such as `"1.2"`.
     */
    get number(): string;
    /**
     * The fragment that links to this section.
     */
    get anchor(): string;
    /**
     * Where the section starts in the wikitext, or `null` for one a template produced.
     */
    get byteOffset(): Nullable<number>;
}
export declare namespace RenderedSection {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RenderedSection;
    }
}
/**
 * A link a page resolves to once its templates have expanded.
 */
export declare class ResolvedLink {
    private constructor();
    /**
     * Where it points.
     */
    get page(): string;
    /**
     * Whether that page exists. `false` is a red link.
     */
    get exists(): boolean;
}
export declare namespace ResolvedLink {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ResolvedLink;
    }
}
/**
 * How much of each parser limit a parse used.
 */
export declare class LimitReport {
    private constructor();
    /**
     * Every row of the report by name, such as `limitreport-expensivefunctioncount`, each with its values in
     * order: for a limit, what the parse used and then the limit.
     */
    get entries(): Record<string, Array<string>>;
    /**
     * How long Lua ran, in milliseconds, or `null` when no module did.
     */
    get luaTime(): Nullable<number>;
    /**
     * How much memory Lua took at most, in bytes, or `null` when no module ran.
     */
    get luaMemory(): Nullable<number>;
    /**
     * How many expensive parser functions the parse called, or `null`.
     */
    get expensiveFunctions(): Nullable<number>;
}
export declare namespace LimitReport {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => LimitReport;
    }
}
/**
 * What a page or a draft turns into once the wiki has parsed it: what was asked for, the rest empty.
 */
export declare class ParsedPage {
    private constructor();
    /**
     * The rendered HTML, when it was asked for.
     */
    get html(): Nullable<string>;
    /**
     * The headings, each carrying the `index` an edit takes.
     */
    get sections(): Array<RenderedSection>;
    /**
     * Every page the text links to, red links included.
     */
    get links(): Array<ResolvedLink>;
    /**
     * Every template it transcludes, after expansion rather than as written.
     */
    get templates(): Array<ResolvedLink>;
    /**
     * The titles of the categories it files itself under, in the order the wiki lists them.
     */
    get categories(): Array<string>;
    /**
     * The titles of the files it uses.
     */
    get images(): Array<string>;
    /**
     * The external URLs it links to.
     */
    get externalLinks(): Array<string>;
    /**
     * The revision parsed, or `null` when unsaved wikitext was.
     */
    get revision(): Nullable<number>;
    /**
     * How much of each parser limit the parse used, when that was asked for.
     */
    get limitReport(): Nullable<LimitReport>;
}
export declare namespace ParsedPage {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ParsedPage;
    }
}
/**
 * The options of `history`.
 */
export declare interface HistoryOptions {
    /**
     * `"newest-first"`, the default, or `"oldest-first"`.
     */
    readonly order?: Nullable<TimeOrder>;
    /**
     * Where in time to begin.
     */
    readonly start?: Nullable<Date>;
    /**
     * Where in time to stop.
     */
    readonly end?: Nullable<Date>;
    /**
     * Only this account's revisions.
     */
    readonly user?: Nullable<string>;
    /**
     * Leave out this account's revisions.
     */
    readonly excludeUser?: Nullable<string>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `allRevisions` and `allDeletedRevisions`.
 */
export declare interface AllRevisionsOptions {
    /**
     * Only revisions in these namespaces, by number. Every namespace unless given.
     */
    readonly namespaces?: Nullable<Array<number>>;
    /**
     * Only this account's revisions.
     */
    readonly user?: Nullable<string>;
    /**
     * `"newest-first"`, the default, or `"oldest-first"`.
     */
    readonly order?: Nullable<TimeOrder>;
    /**
     * Where in time to begin.
     */
    readonly start?: Nullable<Date>;
    /**
     * Where in time to stop.
     */
    readonly end?: Nullable<Date>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `revisionDelete`, which needs [hide] or [show] to have something in it.
 */
export declare interface RevisionDeleteOptions {
    /**
     * What to hide, from `"content"`, `"comment"` and `"user"`.
     */
    readonly hide?: Nullable<Array<RevisionPart>>;
    /**
     * What to restore, from `"content"`, `"comment"` and `"user"`.
     */
    readonly show?: Nullable<Array<RevisionPart>>;
    /**
     * The log summary.
     */
    readonly reason?: Nullable<string>;
    /**
     * Whether to hide it from administrators too, which needs the `suppressrevision` right.
     */
    readonly suppress?: Nullable<boolean>;
}
/**
 * The history of pages, reached as `wiki.revisions`. An option that names none of its values throws
 * `IllegalArgumentException` when a listing is asked for, and rejects a promise otherwise.
 */
export declare class RevisionService {
    private constructor();
    /**
     * The revisions of [page], newest first unless told.
     */
    history(page: string, options?: Nullable<HistoryOptions>): AsyncIterable<Revision>;
    /**
     * The page as it was at [revision], or `null` when there is no such revision.
     */
    contentAt(revision: number): Promise<Nullable<Page>>;
    /**
     * The pages as they were at each of [revisions], read many to a request, in the order asked for. A
     * revision that does not exist, or whose text is hidden, is left out.
     */
    contentsAt(revisions: Array<number>): Promise<Array<Page>>;
    /**
     * The revisions [ids] that exist, in the order asked for: how to follow a log entry back to its edit.
     */
    byId(ids: Array<number>): Promise<Array<Revision>>;
    /**
     * The difference between two revisions as the wiki draws it, in HTML.
     */
    compare(from: number, to: number): Promise<string>;
    /**
     * Every revision made across the wiki in a window. Reaches past the thirty days `wiki.logs.recentChanges`
     * keeps, at the cost of a much heavier query.
     */
    allRevisions(options?: Nullable<AllRevisionsOptions>): AsyncIterable<Revision>;
    /**
     * The deleted revisions of [page]. Needs the `deletedhistory` right.
     * 
     * @param page - the page.
     * @param order - `"newest-first"`, the default, or `"oldest-first"`.
     * @param limit - how many to give before stopping.
     */
    deletedHistory(page: string, order?: Nullable<TimeOrder>, limit?: Nullable<number>): AsyncIterable<Revision>;
    /**
     * Deleted revisions across the wiki, in the shape of [allRevisions]. Needs the `deletedhistory` right.
     */
    allDeletedRevisions(options?: Nullable<AllRevisionsOptions>): AsyncIterable<Revision>;
    /**
     * Hides or restores parts of [revisions] of [page]. Needs the `deleterevision` right.
     * 
     * Rejects when there are no revisions, when nothing is hidden or shown, or when one part is both.
     */
    revisionDelete(page: string, revisions: Array<number>, options?: Nullable<RevisionDeleteOptions>): Promise<void>;
}
export declare namespace RevisionService {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RevisionService;
    }
}
/**
 * The options of `contributions`.
 */
export declare interface ContributionsOptions {
    /**
     * Only edits in these namespaces, by number. Every namespace unless given.
     */
    readonly namespaces?: Nullable<Array<number>>;
    /**
     * Where in time to begin.
     */
    readonly start?: Nullable<Date>;
    /**
     * Where in time to stop.
     */
    readonly end?: Nullable<Date>;
    /**
     * Filters the wiki applies, from `"minor"`, `"new"`, `"top"`, `"patrolled"` and `"autopatrolled"`: each
     * is required, or left out with a leading `!`, such as `["new", "!minor"]`.
     */
    readonly show?: Nullable<Array<ContributionFilter>>;
    /**
     * Only edits carrying this change tag.
     */
    readonly tag?: Nullable<string>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `block`.
 */
export declare interface BlockOptions {
    /**
     * When the block ends. It does not unless given.
     */
    readonly expiry?: Nullable<Date>;
    /**
     * Whether to block only logged-out edits from this address, which leaves it usable by account holders.
     */
    readonly anonymousOnly?: Nullable<boolean>;
    /**
     * Whether to stop new accounts being made from it, `true` unless given.
     */
    readonly preventAccountCreation?: Nullable<boolean>;
    /**
     * Whether to extend the block to addresses the account then uses, `true` unless given.
     */
    readonly autoBlock?: Nullable<boolean>;
}
/**
 * The options of `blocks`.
 */
export declare interface BlocksOptions {
    /**
     * Only these targets, which is cheaper than listing and filtering.
     */
    readonly users?: Nullable<Array<string>>;
    /**
     * Which kinds to include, from `"account"`, `"ip"`, `"range"` and `"temp"`: each is required, or left out
     * with a leading `!`, such as `["!temp"]`.
     */
    readonly show?: Nullable<Array<BlockFilter>>;
    /**
     * Where in time to begin.
     */
    readonly start?: Nullable<Date>;
    /**
     * Where in time to stop.
     */
    readonly end?: Nullable<Date>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `allUsers`.
 */
export declare interface AllUsersOptions {
    /**
     * The account name to start at.
     */
    readonly from?: Nullable<string>;
    /**
     * The account name to stop at.
     */
    readonly to?: Nullable<string>;
    /**
     * Only accounts beginning with this.
     */
    readonly prefix?: Nullable<string>;
    /**
     * Only accounts in these groups.
     */
    readonly group?: Nullable<Array<string>>;
    /**
     * Whether to skip accounts that have never edited, which on a large wiki is most of them.
     */
    readonly withEditsOnly?: Nullable<boolean>;
    /**
     * Whether to skip the accounts a wiki auto-creates for logged-out editors.
     */
    readonly excludeTemporary?: Nullable<boolean>;
    /**
     * How many to give before stopping.
     */
    readonly limit?: Nullable<number>;
}
/**
 * The options of `changeRights`, which needs [add] or [remove] to have something in it.
 */
export declare interface ChangeRightsOptions {
    /**
     * The groups to add.
     */
    readonly add?: Nullable<Array<string>>;
    /**
     * The groups to take away.
     */
    readonly remove?: Nullable<Array<string>>;
    /**
     * The log summary.
     */
    readonly reason?: Nullable<string>;
    /**
     * How long the added memberships last, as MediaWiki spells durations. Permanent unless given.
     */
    readonly expiry?: Nullable<string>;
}
/**
 * The people editing a wiki, reached as `wiki.users`.
 */
export declare class UserService {
    private constructor();
    /**
     * What the wiki knows about the accounts [names], by the name asked for. A name with no account behind it
     * comes back with `isMissing` set.
     */
    info(names: Array<string>): Promise<Record<string, UserInfo>>;
    /**
     * The account this session is logged in as, with the rights the session has.
     */
    current(): Promise<UserInfo>;
    /**
     * The edits [user], an account or address, has made, newest first.
     */
    contributions(user: string, options?: Nullable<ContributionsOptions>): AsyncIterable<Contribution>;
    /**
     * Blocks [user], an account or address, with [reason], which they will see. Needs the `block` right.
     * Rejects with what the wiki said when it refuses.
     */
    block(user: string, reason: string, options?: Nullable<BlockOptions>): Promise<void>;
    /**
     * Lifts the block on [user]. Needs the `block` right.
     */
    unblock(user: string, reason?: string): Promise<void>;
    /**
     * The blocks in force, newest first.
     */
    blocks(options?: Nullable<BlocksOptions>): AsyncIterable<BlockRecord>;
    /**
     * Every account on the wiki, in name order.
     */
    allUsers(options?: Nullable<AllUsersOptions>): AsyncIterable<UserInfo>;
    /**
     * Adds [user] to groups, takes them from others, or both. Needs the `userrights` right.
     * 
     * Rejects when nothing is added or taken away, or when one group is both.
     */
    changeRights(user: string, options?: Nullable<ChangeRightsOptions>): Promise<void>;
}
export declare namespace UserService {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => UserService;
    }
}
/**
 * What a wiki knows about one account, at the moment it was asked.
 */
export declare class UserInfo {
    private constructor();
    /**
     * Whether the user holds [right].
     */
    hasRight(right: string): boolean;
    /**
     * Whether the user is in [group]. Prefer [hasRight]: groups are how rights are granted.
     */
    inGroup(group: string): boolean;
    /**
     * The account name, spelled as the wiki spells it.
     */
    get name(): string;
    /**
     * The account id, or `null` for an anonymous editor.
     */
    get id(): Nullable<number>;
    /**
     * The groups the account is in.
     */
    get groups(): Array<string>;
    /**
     * What the account may do.
     */
    get rights(): Array<string>;
    /**
     * How many edits the account has made.
     */
    get editCount(): number;
    /**
     * When the account was created, or `null` for one older than the wiki's records.
     */
    get registration(): Nullable<Date>;
    /**
     * The block in force, or `null` when there is none.
     */
    get block(): Nullable<BlockInfo>;
    /**
     * Whether the name is an IP address rather than an account.
     */
    get isAnonymous(): boolean;
    /**
     * Whether this is an account the wiki auto-created for a logged-out editor, who will most likely never
     * see a message left on it.
     */
    get isTemporary(): boolean;
    /**
     * Whether there is no such account.
     */
    get isMissing(): boolean;
    /**
     * The grammatical gender the account chose, or `null`.
     */
    get gender(): Nullable<string>;
    /**
     * Whether this user can be sent email through the wiki.
     */
    get emailable(): boolean;
    /**
     * Whether the account is blocked.
     */
    get isBlocked(): boolean;
    /**
     * Whether the editor is a person's own account: not an address, a temporary account or a missing one.
     */
    get isRegistered(): boolean;
}
export declare namespace UserInfo {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => UserInfo;
    }
}
/**
 * A block in force.
 */
export declare class BlockInfo {
    private constructor();
    /**
     * The block's own id, or `null` when the wiki did not say.
     */
    get id(): Nullable<number>;
    /**
     * Who imposed it, or `null` when the wiki did not say.
     */
    get by(): Nullable<string>;
    /**
     * The reason given, or `null` when the wiki did not say.
     */
    get reason(): Nullable<string>;
    /**
     * When it started, or `null` when the wiki did not say.
     */
    get since(): Nullable<Date>;
    /**
     * When it ends, or `null` if it does not.
     */
    get expiry(): Nullable<Date>;
    /**
     * Whether it covers only some pages or actions rather than the whole wiki.
     */
    get isPartial(): boolean;
    /**
     * Whether it never expires.
     */
    get isInfinite(): boolean;
}
export declare namespace BlockInfo {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => BlockInfo;
    }
}
/**
 * One entry in a wiki's block list, which names what is blocked.
 */
export declare class BlockRecord {
    private constructor();
    /**
     * The block itself.
     */
    get info(): BlockInfo;
    /**
     * The account, address or range blocked, or `null` when that is hidden.
     */
    get target(): Nullable<string>;
    /**
     * Whether the wiki applied this itself, following an address used by a blocked account.
     */
    get isAutomatic(): boolean;
    /**
     * Whether the target is an address range.
     */
    get isRange(): boolean;
}
export declare namespace BlockRecord {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => BlockRecord;
    }
}
/**
 * One edit by a user.
 */
export declare class Contribution {
    private constructor();
    /**
     * The title of the page edited.
     */
    get page(): string;
    /**
     * The wiki's own id for that page, or `null` when the wiki did not say.
     */
    get pageId(): Nullable<number>;
    /**
     * The revision the edit produced.
     */
    get revision(): Revision;
    /**
     * Whether this edit created the page.
     */
    get isNew(): boolean;
    /**
     * Whether it is still the page's newest revision.
     */
    get isTop(): boolean;
    /**
     * How many bytes the page gained, negative for a removal.
     */
    get sizeChange(): number;
}
export declare namespace Contribution {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Contribution;
    }
}
/**
 * How `wiki.call` sends a request.
 */
export declare interface CallOptions {
    /**
     * Whether this changes the wiki, which sends it by POST and paces it as a write. `false` unless given.
     */
    readonly write?: Nullable<boolean>;
    /**
     * Whether a response cache may answer it, `true` unless given.
     */
    readonly cacheable?: Nullable<boolean>;
}
/**
 * What a wiki says about itself, read once when it is opened.
 */
export declare class SiteInfo {
    private constructor();
    /**
     * Whether [extension] is installed, compared ignoring case.
     */
    hasExtension(extension: string): boolean;
    /**
     * The wiki's database name, such as `"enwiktionary"`.
     */
    get id(): string;
    /**
     * The name the wiki calls itself, such as `"Wiktionary"`.
     */
    get siteName(): string;
    /**
     * Its content language, which is not always the language of its titles.
     */
    get language(): string;
    /**
     * Its host, without a scheme.
     */
    get server(): string;
    /**
     * The path pattern articles are served under, carrying a `$1` for the title.
     */
    get articlePath(): string;
    /**
     * The title of its main page.
     */
    get mainPage(): string;
    /**
     * The MediaWiki version string, such as `"MediaWiki 1.47.0-wmf.17"`.
     */
    get generator(): string;
    /**
     * The MediaWiki version alone, such as `"1.47.0-wmf.17"`.
     */
    get version(): string;
    /**
     * Its namespaces.
     */
    get namespaces(): Namespaces;
    /**
     * Its interwiki prefixes.
     */
    get interwiki(): Interwiki;
    /**
     * The zone the wiki writes local times in, such as `"Europe/Berlin"`.
     */
    get timezone(): string;
    /**
     * Minutes that zone is ahead of UTC at the moment the site information was read.
     */
    get timeOffsetMinutes(): number;
    /**
     * Whether this wiki creates temporary accounts, and the shape of their names.
     */
    get tempAccounts(): TempAccountConfig;
    /**
     * The extensions installed, by name.
     */
    get extensions(): Array<string>;
}
export declare namespace SiteInfo {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SiteInfo;
    }
}
/**
 * Whether a wiki creates temporary accounts, and the shape of their names.
 */
export declare class TempAccountConfig {
    private constructor();
    /**
     * Whether [name] is a temporary account on this wiki.
     */
    matches(name: string): boolean;
    /**
     * Whether the wiki creates them.
     */
    get enabled(): boolean;
    /**
     * The name patterns, each with `$1` where the generated part goes.
     */
    get matchPatterns(): Array<string>;
}
export declare namespace TempAccountConfig {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TempAccountConfig;
    }
}
/**
 * The account a wiki sees, or an anonymous session.
 */
export declare class Identity {
    private constructor();
    /**
     * Whether the account holds [right].
     */
    has(right: string): boolean;
    /**
     * The account name as the wiki spells it, or the address of an anonymous session.
     */
    get name(): string;
    /**
     * The account's id, `0` for an anonymous session.
     */
    get id(): number;
    /**
     * The groups the account is in, sorted.
     */
    get groups(): Array<string>;
    /**
     * What the account may do, sorted.
     */
    get rights(): Array<string>;
    /**
     * Whether the wiki has flagged the account as a bot.
     */
    get isBot(): boolean;
    /**
     * Whether the session is anonymous.
     */
    get isAnonymous(): boolean;
    /**
     * How many titles, page ids, revision ids or usernames one query may name for this account.
     */
    get batchLimit(): number;
}
export declare namespace Identity {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Identity;
    }
}
/**
 * One namespace as a wiki describes it.
 */
export declare class NamespaceInfo {
    private constructor();
    /**
     * Its number, which is its identity across wikis.
     */
    get id(): number;
    /**
     * The English canonical name, such as `"Category"`, or `null` or `""` for the main namespace.
     */
    get canonicalName(): Nullable<string>;
    /**
     * The wiki's own name for it, such as `"Kategoria"`.
     */
    get localName(): Nullable<string>;
    /**
     * Other accepted prefixes, such as `"WP"` for `"Wikipedia"`.
     */
    get aliases(): Array<string>;
    /**
     * How the first letter of a title here is treated: `"first-letter"` or `"case-sensitive"`.
     */
    get case(): TitleCase;
    /**
     * Whether a slash in a title makes a subpage here.
     */
    get subpages(): boolean;
    /**
     * Every prefix that resolves to this namespace.
     */
    get prefixes(): Array<string>;
}
export declare namespace NamespaceInfo {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => NamespaceInfo;
    }
}
/**
 * The namespaces of one wiki.
 */
export declare class Namespaces {
    private constructor();
    /**
     * The namespace numbered [id], or `null` when the wiki has none.
     */
    get(id: number): Nullable<NamespaceInfo>;
    /**
     * The namespace [prefix] refers to, compared as MediaWiki does, or `null` when it names none.
     */
    byPrefix(prefix: string): Nullable<NamespaceInfo>;
    /**
     * The local [title] with its namespace's name on this wiki, as MediaWiki displays it.
     * 
     * @throws IllegalArgumentException when [title] is not local.
     */
    format(title: Title): string;
    /**
     * All of them, ordered by number.
     */
    get all(): Array<NamespaceInfo>;
}
export declare namespace Namespaces {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Namespaces;
    }
}
/**
 * The interwiki prefixes a wiki recognises, such as `w`, `wikt` or `de`.
 */
export declare class Interwiki {
    private constructor();
    /**
     * Whether [prefix] is an interwiki prefix on this wiki rather than page text.
     */
    has(prefix: string): boolean;
    /**
     * Whether [prefix] names this same wiki, and so resolves to a local page.
     */
    isSelf(prefix: string): boolean;
}
export declare namespace Interwiki {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Interwiki;
    }
}
/**
 * One parameter of an API module, as the wiki describes it.
 */
export declare class ParamDescription {
    private constructor();
    /**
     * Its name, without the module's prefix.
     */
    get name(): string;
    /**
     * Its type, such as `"string"`, or `null` when it takes a fixed set instead.
     */
    get type(): Nullable<string>;
    /**
     * The values it accepts, when it takes a fixed set.
     */
    get values(): Array<string>;
    /**
     * For a parameter taking a count, the most results a request returns without `apihighlimits`.
     */
    get limit(): Nullable<number>;
    /**
     * The most an account with `apihighlimits` may ask for.
     */
    get highLimit(): Nullable<number>;
    /**
     * Whether the module refuses a request without it.
     */
    get required(): boolean;
    /**
     * Whether it accepts several values joined by a pipe.
     */
    get multiValued(): boolean;
    /**
     * What the wiki uses when it is not sent.
     */
    get default(): Nullable<string>;
    /**
     * Whether the wiki has announced it is going away.
     */
    get deprecated(): boolean;
    /**
     * Values still accepted but announced as going away.
     */
    get deprecatedValues(): Array<string>;
    /**
     * Whether the value is a credential, which must never reach a URL or a log.
     */
    get sensitive(): boolean;
    /**
     * For a multivalued parameter, the most values this account may join in one request.
     */
    get valueLimit(): Nullable<number>;
    /**
     * For a multivalued parameter, the most values an account with `apihighlimits` may join.
     */
    get highValueLimit(): Nullable<number>;
    /**
     * Values accepted but marked internal or unstable, which may change without notice.
     */
    get internalValues(): Array<string>;
}
export declare namespace ParamDescription {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ParamDescription;
    }
}
/**
 * One API module, as the wiki describes it.
 */
export declare class ModuleDescription {
    private constructor();
    /**
     * Whether it takes a parameter called [parameter] on this wiki.
     */
    has(parameter: string): boolean;
    /**
     * The parameter called [parameter], or `null` when this wiki's version of it has none.
     */
    get(parameter: string): Nullable<ParamDescription>;
    /**
     * Its own name, such as `"usercontribs"`.
     */
    get name(): string;
    /**
     * Its full path, such as `"query+usercontribs"`, which is how it is asked for.
     */
    get path(): string;
    /**
     * Its parameters, by name.
     */
    get parameters(): Record<string, ParamDescription>;
    /**
     * Whether it changes the wiki.
     */
    get isWrite(): boolean;
    /**
     * Whether it must be POSTed.
     */
    get mustBePosted(): boolean;
    /**
     * What provides it: `"MediaWiki"` for core, otherwise the extension's name.
     */
    get source(): Nullable<string>;
    /**
     * The extension's display name, which differs from [source] for some extensions.
     */
    get sourceName(): Nullable<string>;
    /**
     * For a submodule of `query`, which of `list`, `prop` or `meta` it belongs to.
     */
    get group(): Nullable<ModuleGroup>;
    /**
     * The prefix its own parameters carry, such as `"uc"`.
     */
    get prefix(): string;
    /**
     * Whether the wiki has announced it is going away.
     */
    get deprecated(): boolean;
    /**
     * Whether it is for MediaWiki's own use, with no compatibility promise.
     */
    get internal(): boolean;
}
export declare namespace ModuleDescription {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ModuleDescription;
    }
}
/**
 * What a wiki says its own API accepts, reached as `wiki.paramInfo`. Answers are kept for as long as the wiki
 * is open.
 */
export declare class ParamInfo {
    private constructor();
    /**
     * The description of [module], named as `paraminfo` names it, such as `"query+categorymembers"`, or
     * `null` when this wiki has no such module.
     */
    module(module: string): Promise<Nullable<ModuleDescription>>;
    /**
     * The most results [module] returns in one request through [parameter], for an account with
     * `apihighlimits` when [highLimits] is `true`. `null` when it has no such limit, or the wiki did not say.
     */
    limit(module: string, parameter: string, highLimits: boolean): Promise<Nullable<number>>;
    /**
     * Whether [module] takes [parameter] on this wiki.
     */
    supports(module: string, parameter: string): Promise<boolean>;
    /**
     * The values [parameter] of [module] accepts on this wiki, or `null` when there is no such parameter, or
     * it takes free text.
     */
    values(module: string, parameter: string): Promise<Nullable<Array<string>>>;
    /**
     * Whether this wiki has announced that [value] of [parameter] is going away.
     */
    isDeprecated(module: string, parameter: string, value: string): Promise<boolean>;
    /**
     * Fetches each of [modules] not already known, in as few requests as it can.
     */
    prefetch(modules: Array<string>): Promise<void>;
    /**
     * Every module matching [patterns], which may use the `*` the API accepts, such as `"query+*"`.
     */
    modules(patterns: Array<string>): Promise<Array<ModuleDescription>>;
    /**
     * How many modules one request may name.
     */
    get maxModules(): number;
}
export declare namespace ParamInfo {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ParamInfo;
    }
}
/**
 * The tokens writes need, reached as `wiki.tokens`: fetched on first use and kept. [type] is one of the
 * wiki's token types, `"csrf"`, what an ordinary write needs, unless given.
 */
export declare class TokenStore {
    private constructor();
    /**
     * The token of [type]. Rejects in an anonymous session, which has no real one.
     */
    token(type?: Nullable<string>): Promise<string>;
    /**
     * Drops the kept token of [type], so that the next use fetches a new one.
     */
    invalidate(type?: Nullable<string>): Promise<void>;
    /**
     * Drops every kept token.
     */
    clear(): Promise<void>;
    /**
     * Calls [block] with a token and answers with what it returns, which may be a promise. When that is a
     * response saying the wiki rejected the token, as `wiki.call` gives one, [block] is called once more with
     * a fresh token; a second such answer rejects.
     */
    withFreshToken<T>(block: (token: string) => Awaitable<T>, type?: Nullable<string>): Promise<T>;
    /**
     * The token every ordinary write needs.
     */
    get csrf(): string;
    /**
     * The token needed to start a login.
     */
    get login(): string;
    /**
     * The token needed to patrol a revision.
     */
    get patrol(): string;
    /**
     * The token needed to roll a page back.
     */
    get rollback(): string;
    /**
     * The token for changing group membership.
     */
    get userRights(): string;
    /**
     * The token needed to add or remove watchlist entries.
     */
    get watch(): string;
}
export declare namespace TokenStore {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TokenStore;
    }
}
/**
 * What a wiki's configuration changes about how its wikitext parses, from `wiki.parseOptions()`.
 */
export declare class ParseOptions {
    private constructor();
    /**
     * Parses [text] the way the wiki these options came from reads it.
     */
    parse(text: string): Wikitext;
    /**
     * The tags whose bodies the preprocessor steps over, without their brackets.
     */
    get extensionTags(): Array<string>;
    /**
     * The extension tags whose body is itself wikitext, such as `ref`.
     */
    get parsedTags(): Array<string>;
    /**
     * The extension tags whose body has only its templates expanded, such as `inputbox`.
     */
    get preprocessedTags(): Array<string>;
    /**
     * The HTML elements the wiki allows.
     */
    get htmlTags(): Array<string>;
    /**
     * The URL schemes that make a link.
     */
    get protocols(): Array<string>;
    /**
     * Whether `-{ }-` is markup.
     */
    get languageConversion(): boolean;
    /**
     * The names of the File namespace.
     */
    get fileNamespaces(): Array<string>;
}
export declare namespace ParseOptions {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ParseOptions;
    }
}
/**
 * Names a wiki recognises in `{{...}}`, each compared exactly or ignoring case.
 */
export declare class MagicWords {
    private constructor();
    /**
     * Whether [name] is one of them.
     */
    has(name: string): boolean;
    /**
     * The names compared exactly.
     */
    get caseSensitive(): Array<string>;
    /**
     * The names compared ignoring case.
     */
    get caseInsensitive(): Array<string>;
}
export declare namespace MagicWords {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => MagicWords;
    }
}
/**
 * How a wiki names pages, as far as telling which page a `{{...}}` transcludes needs.
 */
export declare class TitleRules {
    private constructor();
    /**
     * Whether a template named [title] is a parser function or a variable rather than a page. [hasArguments]
     * says whether it is given any parameters: `{{PAGENAME|x}}` transcludes a page.
     */
    isParserFunction(title: string, hasArguments?: boolean): boolean;
    /**
     * The page a template named [title] transcludes, normalised, or `null` for a parser function or a
     * variable. [hasArguments] says whether it is given any parameters.
     */
    key(title: string, hasArguments?: boolean): Nullable<string>;
    /**
     * The page [template] transcludes on this wiki, normalised, or `null` for a parser function.
     */
    keyOf(template: Template): Nullable<string>;
    /**
     * The templates of [wikitext] that transclude any of [names], however each is written.
     */
    templatesIn(wikitext: Wikitext, names: Array<string>): Array<Template>;
    /**
     * Every namespace name and alias, with the namespace's number.
     */
    get namespaces(): Record<string, number>;
    /**
     * The name each namespace, by number, is written with in a key.
     */
    get canonicalNames(): Record<string, string>;
    /**
     * The namespaces whose titles keep their first letter as written.
     */
    get caseSensitive(): Array<number>;
    /**
     * The parser functions written without a `#`, such as `lc`.
     */
    get functions(): MagicWords;
    /**
     * The variables, such as `PAGENAME`.
     */
    get variables(): MagicWords;
    /**
     * The prefixes that save a template's expansion instead of the template, such as `subst`.
     */
    get substitutions(): Array<string>;
    /**
     * The other prefixes that change how a template is transcluded, such as `msg`.
     */
    get modifiers(): Array<string>;
}
export declare namespace TitleRules {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TitleRules;
    }
}
/**
 * Which text a replacement may touch. Prose's is what is left out: only headings are in unless opened up.
 */
export declare interface TextScopeOptions {
    /**
     * Text inside `{{template}}` names and parameters, and the names of `{{{arguments}}}`.
     */
    readonly templates?: Nullable<boolean>;
    /**
     * The target of a `[[link|text]]`, as opposed to its display text.
     */
    readonly linkTargets?: Nullable<boolean>;
    /**
     * Text inside `<!-- comments -->`.
     */
    readonly comments?: Nullable<boolean>;
    /**
     * Text inside `<nowiki>`, `<pre>` and other tags MediaWiki does not parse.
     */
    readonly rawTags?: Nullable<boolean>;
    /**
     * Text in `== headings ==`; in unless this says otherwise.
     */
    readonly headings?: Nullable<boolean>;
    /**
     * Text inside tag attributes.
     */
    readonly tagAttributes?: Nullable<boolean>;
}
/**
 * One match of a regular expression in the text, as a replacement is given it.
 */
export declare class TextMatch {
    private constructor();
    /**
     * The text matched.
     */
    get value(): string;
    /**
     * Each group's text, the whole match first; `""` for a group that took no part.
     */
    get groups(): Array<string>;
    /**
     * Where the match starts in the piece of text it was found in.
     */
    get index(): number;
}
export declare namespace TextMatch {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TextMatch;
    }
}
/**
 * One piece of parsed wikitext. [kind] says which it is, and so which field is set; every node writes itself
 * back exactly as it was read.
 */
export declare class Node {
    private constructor();
    /**
     * This node as wikitext.
     */
    toString(): string;
    /**
     * `"text"`, `"comment"`, `"template"`, `"argument"`, `"wiki-link"`, `"external-link"`, `"heading"`,
     * `"html-entity"` or `"tag"`.
     */
    get kind(): NodeKind;
    /**
     * A `"text"`'s text.
     */
    get text(): Nullable<string>;
    /**
     * A `"comment"`.
     */
    get comment(): Nullable<Comment>;
    /**
     * A `"template"`.
     */
    get template(): Nullable<Template>;
    /**
     * An `"argument"`.
     */
    get argument(): Nullable<Argument>;
    /**
     * A `"wiki-link"`.
     */
    get link(): Nullable<Link>;
    /**
     * An `"external-link"`.
     */
    get externalLink(): Nullable<ExternalLink>;
    /**
     * A `"heading"`.
     */
    get heading(): Nullable<Heading>;
    /**
     * An `"html-entity"`.
     */
    get htmlEntity(): Nullable<HtmlEntity>;
    /**
     * A `"tag"`.
     */
    get tag(): Nullable<Tag>;
}
export declare namespace Node {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Node;
    }
}
/**
 * A comment, `<!-- ... -->`.
 */
export declare class Comment {
    private constructor();
    /**
     * This comment as wikitext.
     */
    toString(): string;
    /**
     * What sits between the markers.
     */
    get contents(): string;
    /**
     * Whether it ends with `-->`. One that does not runs to the end of the page.
     */
    get closed(): boolean;
    /**
     * This comment as a node.
     */
    get node(): Node;
}
export declare namespace Comment {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Comment;
    }
}
/**
 * A template's argument, `{{{name|default}}}`.
 */
export declare class Argument {
    private constructor();
    /**
     * This argument as wikitext.
     */
    toString(): string;
    /**
     * The argument's name, as written between the braces.
     */
    get name(): Wikitext;
    /**
     * What to use when the argument is not supplied, or `null` when the markup names nothing.
     */
    get defaultValue(): Nullable<Wikitext>;
    /**
     * This argument as a node.
     */
    get node(): Node;
}
export declare namespace Argument {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Argument;
    }
}
/**
 * A link off the wiki, `[url title]`, or a bare URL in running text.
 */
export declare class ExternalLink {
    private constructor();
    /**
     * This link as wikitext.
     */
    toString(): string;
    /**
     * The URL, which may hold a template its expansion completes.
     */
    get url(): Wikitext;
    /**
     * The label after the URL, or `null`.
     */
    get title(): Nullable<Wikitext>;
    /**
     * Whether it was bracketed. A bare URL in running text was not.
     */
    get brackets(): boolean;
    /**
     * What is written between the URL and a label, `" "` when there is none to separate.
     */
    get separator(): string;
    /**
     * This link as a node.
     */
    get node(): Node;
}
export declare namespace ExternalLink {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ExternalLink;
    }
}
/**
 * A heading, `== Heading ==`.
 */
export declare class Heading {
    private constructor();
    /**
     * This heading as wikitext.
     */
    toString(): string;
    /**
     * The text between the equals signs.
     */
    get title(): Wikitext;
    /**
     * How many equals signs are on each side: 2 for a top-level section.
     */
    get level(): number;
    /**
     * This heading as a node.
     */
    get node(): Node;
}
export declare namespace Heading {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Heading;
    }
}
/**
 * An entity, `&amp;`, `&#65;` or `&#x41;`.
 */
export declare class HtmlEntity {
    private constructor();
    /**
     * This entity as wikitext.
     */
    toString(): string;
    /**
     * The entity's body: a name, or the digits of a numeric one.
     */
    get value(): string;
    /**
     * Whether it was written as a number rather than as a name.
     */
    get numeric(): boolean;
    /**
     * The `x` or `X` as written in a hexadecimal one, or `null`.
     */
    get hexChar(): Nullable<HexMarker>;
    /**
     * This entity as a node.
     */
    get node(): Node;
}
export declare namespace HtmlEntity {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => HtmlEntity;
    }
}
/**
 * One attribute of a tag, with the whitespace that surrounded it.
 */
export declare class Attribute {
    private constructor();
    /**
     * This attribute as it was written.
     */
    toString(): string;
    /**
     * The attribute's name as written.
     */
    get name(): Wikitext;
    /**
     * Its value, or `null` for a bare attribute such as `nowrap`.
     */
    get value(): Nullable<Wikitext>;
    /**
     * The quote character used, or `null` where the value was unquoted.
     */
    get quote(): Nullable<QuoteMark>;
    /**
     * The whitespace before the name.
     */
    get padFirst(): string;
    /**
     * The whitespace before the equals sign.
     */
    get padBeforeEq(): string;
    /**
     * The whitespace after the equals sign.
     */
    get padAfterEq(): string;
}
export declare namespace Attribute {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Attribute;
    }
}
/**
 * A tag: `<ref>...</ref>`, `<br />`, or the wiki markup that stands in for one.
 */
export declare class Tag {
    private constructor();
    /**
     * This tag as wikitext.
     */
    toString(): string;
    /**
     * The tag's name as written; MediaWiki compares it ignoring case.
     */
    get name(): string;
    /**
     * What sits between the opening and closing tags, or `null`.
     */
    get contents(): Nullable<Wikitext>;
    /**
     * Its attributes, in the order written.
     */
    get attributes(): Array<Attribute>;
    /**
     * Whether it was written as `<br />` rather than as a pair.
     */
    get selfClosing(): boolean;
    /**
     * The markup that made the tag when it was not written as HTML: `'''` for bold, `*` for a list item.
     */
    get wikiMarkup(): Nullable<string>;
    /**
     * The whitespace before the closing `>`.
     */
    get padding(): string;
    /**
     * Whether the closing tag was absent: a `<br>`, or an opening tag MediaWiki ends somewhere else.
     */
    get implicitClose(): boolean;
    /**
     * The closing tag as written when it differs from `</name>`, or `null`.
     */
    get closing(): Nullable<string>;
    /**
     * Whether [contents] is the body as written rather than parsed, as in `<nowiki>` or `<pre>`.
     */
    get verbatim(): boolean;
    /**
     * This tag as a node.
     */
    get node(): Node;
}
export declare namespace Tag {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Tag;
    }
}
/**
 * Where a piece of wikitext sits in the text it writes back as: from [start] up to just before [end].
 */
export declare class SourceRange {
    private constructor();
    /**
     * Where it starts, counted in UTF-16 code units as JavaScript counts them.
     */
    get start(): number;
    /**
     * Where it ends, just past its last code unit.
     */
    get end(): number;
}
export declare namespace SourceRange {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SourceRange;
    }
}
/**
 * Where the pieces of parsed wikitext sit in its text, from `Wikitext.ranges`.
 */
export declare class SourceRanges {
    private constructor();
    /**
     * Where [node] was written, or `null` when it is not part of the wikitext these came from.
     */
    ofNode(node: Node): Nullable<SourceRange>;
    /**
     * Where [parameter] was written, its name and `=` included for a named one.
     */
    ofParameter(parameter: Parameter): Nullable<SourceRange>;
    /**
     * Where [attribute] was written, the whitespace before it included.
     */
    ofAttribute(attribute: Attribute): Nullable<SourceRange>;
    /**
     * Where [wikitext], such as a parameter's value, was written.
     */
    ofWikitext(wikitext: Wikitext): Nullable<SourceRange>;
}
export declare namespace SourceRanges {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => SourceRanges;
    }
}
export type Awaitable<T> = T | PromiseLike<T>;
export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };
export type ApiValue = string | number | boolean | Date | null | undefined | Array<string | number | Date>;
export type MessageValue = string | number;
export type Ignored = unknown;
export type ExitStatus = number | void;
export type Negatable<T extends string> = T | `!${T}`;
export type RunStatus = "queued" | "running" | "waiting" | "done" | "failed" | "cancelled" | "declined";
export type RunLevel = "info" | "saved" | "skipped" | "error";
export type FleetEventKind = "said" | "changed";
export type SparqlValueType = "uri" | "literal" | "bnode";
export type TitleKind = "local" | "interwiki" | "invalid";
export type TitleProblem = "empty" | "illegal-character" | "relative-path" | "signature" | "percent-escape" | "too-long";
export type EditRefusal = "conflict" | "protected" | "permission-denied" | "rate-limited" | "filtered" | "captcha" | "page-changed" | "rejected";
export type EditStatus = "saved" | "unchanged" | EditRefusal;
export type TextScopeName = "prose" | "everywhere";
export type StopReason = "policy" | "policy-unreachable" | "auth" | "read-only" | "configuration" | "refused";
export type PageOutcomeKind = "missing" | "skipped" | "unchanged" | "pending" | "saved" | "refused" | "failed" | "not-attempted";
export type ProblemKind = "refused" | "failed";
export type NewEntityKind = "item" | "property" | "lexeme" | "form" | "sense" | "mediainfo";
export type EntityKind = NewEntityKind | "unknown";
export type MergeConflict = "description" | "sitelink" | "statement";
export type FamilyKind = "wikimedia" | "single-site";
export type LogLevel = "trace" | "debug" | "info" | "warn" | "error" | "off";
export type TitleCase = "first-letter" | "case-sensitive";
export type ProofreadQuality = "without-text" | "not-proofread" | "problematic" | "proofread" | "validated";
export type DecodedPageKind = "existing" | "missing" | "invalid";
export type SnakKind = "value" | "somevalue" | "novalue";
export type StatementRank = "preferred" | "normal" | "deprecated";
export type SparqlAuthKind = "none" | "cookie";
export type ChangeType = "edit" | "new" | "log" | "categorize" | "external";
export type ErrorKind = "timeout" | "unreachable" | "server-error" | "refused" | "rate-limited" | "maxlag" | "api" | "not-logged-in" | "login-failed" | "no-credentials" | "missing-right" | "permission-denied" | "account-blocked" | "bad-token" | "missing-page" | "not-redirect" | "unresolvable-redirect" | "bad-title" | "off-wiki" | "unsupported-content-model" | "read-only" | "unknown-site" | "missing-extension" | "version-too-old" | "unsupported" | "round-trip" | "dump-format";
export type DumpProblem = "unexpected-eof" | "bad-checksum" | "bad-data";
export type DataValueKind = "text" | "entity-ref" | "time" | "quantity" | "globe-coordinate" | "monolingual" | "unknown";
export type AbuseFilterState = "enabled" | "deleted" | "private" | "protected" | "suppressed";
export type AbuseFilterProperty = "id" | "description" | "pattern" | "actions" | "hits" | "comments" | "last-editor" | "last-edit-time" | "flags" | "status" | "private" | "protected" | "suppressed";
export type AbuseFilterOrder = "lowest-id-first" | "highest-id-first";
export type NewPagesReviewed = "unreviewed" | "reviewed" | "all";
export type NewPagesOrder = "newest-first" | "oldest-first" | "newest-review-first" | "oldest-review-first";
export type TriageStatus = "unreviewed" | "reviewed" | "patrolled" | "autopatrolled";
export type UploadKind = "uploaded" | "warned" | "refused";
export type CategoryMemberType = "page" | "subcategory" | "file";
export type CategorySort = "sortkey" | "timestamp";
export type RedirectFilter = "all" | "only-redirects" | "no-redirects";
export type SearchScope = "text" | "title" | "near-match";
export type SearchSort = "relevance" | "last-edit-descending" | "last-edit-ascending" | "created-descending" | "created-ascending" | "title";
export type TimeOrder = "newest-first" | "oldest-first";
export type LogDetailsKind = "none" | "move" | "block" | "upload" | "protect" | "rights" | "patrol" | "unknown";
export type TagOperation = "create" | "delete" | "activate" | "deactivate";
export type TextDirection = "left-to-right" | "right-to-left";
export type WatchMode = "no-change" | "preferences" | "watch" | "unwatch";
export type ParseProperty = "text" | "links" | "templates" | "categories" | "images" | "external-links" | "limit-report" | "toc-data" | "sections";
export type ParserName = "legacy" | "parsoid";
export type RevisionPart = "content" | "comment" | "user";
export type NodeKind = "text" | "comment" | "template" | "argument" | "wiki-link" | "external-link" | "heading" | "html-entity" | "tag";
export type ModuleGroup = "list" | "prop" | "meta";
export type EntitySearchKind = Exclude<NewEntityKind, "mediainfo">;
export type EventType = ChangeType | (string & {});
export type HexMarker = "x" | "X";
export type QuoteMark = "\"" | "'";
export type DumpCompression = "none" | "gzip" | "bzip2";
export type ContributionFilter = Negatable<"minor" | "new" | "top" | "patrolled" | "autopatrolled">;
export type BlockFilter = Negatable<"account" | "ip" | "range" | "temp">;
export type RecentChangeFilter = Negatable<"minor" | "bot" | "anon" | "redirect" | "patrolled" | "autopatrolled"> | (string & {});
export type WatchlistFilter = Negatable<"minor" | "bot" | "anon" | "patrolled" | "autopatrolled" | "unread"> | (string & {});
