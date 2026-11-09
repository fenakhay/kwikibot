type Nullable<T> = T | null | undefined
declare function KtSingleton<T>(): T & (abstract new() => any);
export declare namespace io.ktor.client.engine.js {
    const initHook: any;
}
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
export declare function dumpPagesOf(bytes: Uint8Array, compression?: string, namespaces?: Nullable<Array<number>>): Array<DumpPage>;
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
export declare function scanDump(path: string, block: (page: DumpPage) => Nullable<any>, namespaces?: Nullable<Array<number>>, workers?: Nullable<number>): Promise<void>;
/**
 * One page of an XML dump, with the one revision the dump carries.
 */
export declare class DumpPage {
    private constructor();
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
 * When `serve` runs a bot.
 */
export declare class Schedule {
    private constructor();
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
    readonly run: (context: RunContext) => Nullable<any>;
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
    readonly accounts?: any;
    /**
     * A page of the wiki that has to say `false` for bots to save, read once a minute for all of them.
     */
    readonly stopPage?: Nullable<string>;
    /**
     * Answers a run's question, and may return a promise. Without it, the answer is no.
     */
    readonly onAsk?: Nullable<(bot: string, title: string, summary: string) => Nullable<any>>;
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
    step(name: string, work: (context: RunContext) => Nullable<any>): Promise<Nullable<any>>;
    /**
     * Parks this lane and asks a person, while other bots keep going.
     */
    ask(title: string, summary?: string): Promise<boolean>;
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
    run(titles: Array<string>, summary: string, transform: (text: string, title: string) => Nullable<any>, maxEdits?: Nullable<number>): Promise<RunReport>;
    /**
     * Aborted when the run is cancelled, for handing to `fetch` and the like.
     */
    get signal(): any;
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
    get status(): string;
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
    get status(): Nullable<string>;
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
    get type(): string;
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
     * The title in full, its fragment included: namespace prefix and text, or the interwiki prefix and the
     * rest, or as given when it is invalid.
     */
    toString(): string;
    /**
     * `"local"` for a page of this wiki, `"interwiki"` for one on another, `"invalid"` for no page at all.
     */
    get kind(): string;
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
    get invalidReason(): Nullable<string>;
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
 * `null` when it advertises none.
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
    run(titles: Array<string>, summary: string, transform: (text: string, title: string) => Nullable<any>, save?: boolean, onChange?: Nullable<(change: Change) => void>, stopPage?: Nullable<string>, maxEdits?: Nullable<number>): Promise<RunReport>;
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
    get status(): string;
    /**
     * The revision saved, or the current one when nothing changed; `null` when refused.
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
    replaceText(pattern: RegExp, replacement: string, scope?: string): Wikitext;
    /**
     * The wikitext, byte for byte as it was parsed apart from what was changed.
     */
    toString(): string;
    /**
     * The visible text, with markup removed: what a reader sees. For comparing content, never for writing
     * back.
     */
    get text(): string;
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
}
export declare namespace Section {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Section;
    }
}
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
     * Why the run stopped early, such as `"limit"`, or `null` when it did not.
     */
    get stopReason(): Nullable<string>;
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
    get kind(): string;
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
     * The entity's id, such as `"Q42"`.
     */
    get id(): string;
    /**
     * `"item"`, `"property"`, `"lexeme"`, `"form"`, `"sense"`, `"mediainfo"`, or `"unknown"`.
     */
    get type(): string;
    /**
     * The revision the entity was read at, or `null` when the wiki did not say.
     */
    get lastRevision(): Nullable<number>;
    /**
     * The properties the entity has statements for.
     */
    get properties(): Array<string>;
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
    toJSON(): any;
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
    get rank(): string;
    /**
     * `"value"`, or `"somevalue"` for an unknown value, or `"novalue"` for a stated absence.
     */
    get snakType(): string;
    /**
     * The property's data type, such as `"wikibase-item"`, when the wiki gave it.
     */
    get dataType(): Nullable<string>;
    /**
     * The value as Wikibase writes it, `{ type, value }`, or `null` for `somevalue` and `novalue`.
     */
    get value(): any;
}
export declare namespace Claim {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Claim;
    }
}