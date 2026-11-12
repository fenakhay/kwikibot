/*
 * Each of these is a string from a fixed set, which the package's definitions spell out as a union of string
 * literals of the same name, so that a typo does not compile in TypeScript. A Kotlin string has no such type,
 * so these stand in for it and are only ever cast to and from strings.
 */

/** A value of [T] that has to hold, or with a leading `!` that must not. */
public external interface Negatable<out T>

/** `"queued"`, `"running"`, `"waiting"`, `"done"`, `"failed"`, `"cancelled"` or `"declined"`. */
public external interface RunStatus

/** `"info"`, `"saved"`, `"skipped"` or `"error"`. */
public external interface RunLevel

/** `"said"` or `"changed"`. */
public external interface FleetEventKind

/** `"uri"`, `"literal"` or `"bnode"`. */
public external interface SparqlValueType

/** `"local"`, `"interwiki"` or `"invalid"`. */
public external interface TitleKind

/** Why a title names no page. */
public external interface TitleProblem

/** How the wiki answered an edit, `"saved"`, `"unchanged"` or an [EditRefusal]. */
public external interface EditStatus

/** Why the wiki refused an edit. */
public external interface EditRefusal

/** `"prose"` or `"everywhere"`. */
public external interface TextScopeName

/** Why a run stopped early. */
public external interface StopReason

/** What happened to one page of a run. */
public external interface PageOutcomeKind

/** `"refused"` or `"failed"`. */
public external interface ProblemKind

/** What kind of entity an id names, or `"unknown"`. */
public external interface EntityKind

/** What kind of entity can be created. */
public external interface NewEntityKind

/** `"description"`, `"sitelink"` or `"statement"`. */
public external interface MergeConflict

/** `"wikimedia"` or `"single-site"`. */
public external interface FamilyKind

/** `"trace"`, `"debug"`, `"info"`, `"warn"`, `"error"` or `"off"`. */
public external interface LogLevel

/** `"first-letter"` or `"case-sensitive"`. */
public external interface TitleCase

/** How far a scanned page has been proofread. */
public external interface ProofreadQuality

/** `"existing"`, `"missing"` or `"invalid"`. */
public external interface DecodedPageKind

/** `"value"`, `"somevalue"` or `"novalue"`. */
public external interface SnakKind

/** `"preferred"`, `"normal"` or `"deprecated"`. */
public external interface StatementRank

/** `"none"` or `"cookie"`. */
public external interface SparqlAuthKind

/** `"edit"`, `"new"`, `"log"`, `"categorize"` or `"external"`. */
public external interface ChangeType

/** What went wrong, as `ErrorInfo.kind` says it. */
public external interface ErrorKind

/** `"unexpected-eof"`, `"bad-checksum"` or `"bad-data"`. */
public external interface DumpProblem

/** Which kind of value a Wikibase value is. */
public external interface DataValueKind

/** A state an abuse filter can be in. */
public external interface AbuseFilterState

/** What can be read about an abuse filter. */
public external interface AbuseFilterProperty

/** `"lowest-id-first"` or `"highest-id-first"`. */
public external interface AbuseFilterOrder

/** `"unreviewed"`, `"reviewed"` or `"all"`. */
public external interface NewPagesReviewed

/** Which way new pages are listed. */
public external interface NewPagesOrder

/** `"unreviewed"`, `"reviewed"`, `"patrolled"` or `"autopatrolled"`. */
public external interface TriageStatus

/** `"uploaded"`, `"warned"` or `"refused"`. */
public external interface UploadKind

/** `"page"`, `"subcategory"` or `"file"`. */
public external interface CategoryMemberType

/** `"sortkey"` or `"timestamp"`. */
public external interface CategorySort

/** `"all"`, `"only-redirects"` or `"no-redirects"`. */
public external interface RedirectFilter

/** `"text"`, `"title"` or `"near-match"`. */
public external interface SearchScope

/** The order of a search's results. */
public external interface SearchSort

/** `"newest-first"` or `"oldest-first"`. */
public external interface TimeOrder

/** Which kind of detail a log entry has. */
public external interface LogDetailsKind

/** `"create"`, `"delete"`, `"activate"` or `"deactivate"`. */
public external interface TagOperation

/** `"left-to-right"` or `"right-to-left"`. */
public external interface TextDirection

/** `"no-change"`, `"preferences"`, `"watch"` or `"unwatch"`. */
public external interface WatchMode

/** What a parse can report. */
public external interface ParseProperty

/** `"legacy"` or `"parsoid"`. */
public external interface ParserName

/** `"content"`, `"comment"` or `"user"`. */
public external interface RevisionPart

/** Which kind of node a wikitext node is. */
public external interface NodeKind

/** `"none"`, `"gzip"` or `"bzip2"`. */
public external interface DumpCompression

/** What a user's contributions can be narrowed by. */
public external interface ContributionFilter

/** What a list of blocks can be narrowed by. */
public external interface BlockFilter

/** A flag recent changes can be narrowed by, or with a leading `!` left out by. */
public external interface RecentChangeFilter

/** A flag the watchlist can be narrowed by, or with a leading `!` left out by. */
public external interface WatchlistFilter

/** `"list"`, `"prop"` or `"meta"`. */
public external interface ModuleGroup

/** What kind of entity can be searched for: all but `"mediainfo"`. */
public external interface EntitySearchKind

/**
 * What an event says happened: a [ChangeType], or what another stream calls it, `""` when it does not say.
 */
public external interface EventType

/** `"x"` or `"X"`. */
public external interface HexMarker

/** A double or a single quote. */
public external interface QuoteMark
