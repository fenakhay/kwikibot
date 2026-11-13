"""Fail when a union of string literals in the npm package's definitions misses a value, or has one too many.

Usage: check_ts_unions.py [repository root]

Every union in kwikibot-js/build.gradle.kts has to be accounted for here, by where its values really come from:

- ENUMS: a Kotlin enum, spelled as the facade spells it.
- WHENS: the string literals a `when` of the facade or of the shared binding layer answers with. The compiler
  makes such a `when` exhaustive.
- ACCEPTED: values no Kotlin type holds, each saying why, and whether the union is open to more. These are
  checked against the union, so a change to either has to be a change to both.
- DERIVED: built from other unions, so what those are checked for covers them.

A new union that is none of these fails, so it cannot be left out.
"""

import pathlib
import re
import sys


ENUMS: dict[str, tuple[str, str]] = {
    "RunStatus": ("kwikibot-fleet", "RunStatus"),
    "RunLevel": ("kwikibot-fleet", "Level"),
    "TitleProblem": ("kwikibot-model", "Reason"),
    "StopReason": ("kwikibot-bot", "StopReason"),
    "TitleCase": ("kwikibot-model", "TitleCase"),
    "ProofreadQuality": ("kwikibot-client", "ProofreadQuality"),
    "StatementRank": ("kwikibot-wikibase", "Rank"),
    "DumpProblem": ("kwikibot-client", "Code"),
    "AbuseFilterOrder": ("kwikibot-client", "AbuseFilterOrder"),
    "AbuseFilterProperty": ("kwikibot-client", "AbuseFilterProperty"),
    "NewPagesOrder": ("kwikibot-client", "NewPagesOrder"),
    "TriageStatus": ("kwikibot-client", "TriageStatus"),
    "CategoryMemberType": ("kwikibot-client", "CategoryMemberType"),
    "CategorySort": ("kwikibot-client", "CategorySort"),
    "RedirectFilter": ("kwikibot-client", "RedirectFilter"),
    "SearchScope": ("kwikibot-client", "SearchScope"),
    "SearchSort": ("kwikibot-client", "SearchSort"),
    "TimeOrder": ("kwikibot-client", "TimeOrder"),
    "TagOperation": ("kwikibot-client", "TagOperation"),
    "TextDirection": ("kwikibot-model", "TextDirection"),
    "WatchMode": ("kwikibot-client", "WatchMode"),
    "ParseProperty": ("kwikibot-client", "ParseProperty"),
    "ParserName": ("kwikibot-client", "Parser"),
    "RevisionPart": ("kwikibot-client", "RevisionPart"),
    "DumpCompression": ("kwikibot-client", "Compression"),
}

ROOTS = (
    "kwikibot-js/src/jsMain/kotlin/",
    "kwikibot-bindings/src/commonMain/kotlin/",
)

WHENS: dict[str, tuple[str, str, str]] = {
    "FleetEventKind": ("Fleet.kt", "public val kind: FleetEventKind", "unsafeCast<FleetEventKind>"),
    "TitleKind": ("Titles.kt", "public val kind: TitleKind", "unsafeCast<TitleKind>"),
    "PageOutcomeKind": ("com/fenakhay/kwikibot/bindings/bot/Outcomes.kt", "public fun kindOf(", "\n    }"),
    "NodeKind": ("service/WikitextNodes.kt", "public val kind: NodeKind", "unsafeCast<NodeKind>"),
    "LogDetailsKind": ("service/LogService.kt", "public val kind: LogDetailsKind", "unsafeCast<LogDetailsKind>"),
    "FamilyKind": ("service/Family.kt", "public val kind: FamilyKind", "unsafeCast<FamilyKind>"),
    "DecodedPageKind": ("service/ResponseDecoder.kt", "public val kind: DecodedPageKind", "unsafeCast<DecodedPageKind>"),
    "SnakKind": ("service/Snaks.kt", "public val kind: SnakKind", "unsafeCast<SnakKind>"),
    "UploadKind": ("service/FileService.kt", "public val kind: UploadKind", "unsafeCast<UploadKind>"),
    "DataValueKind": ("service/WikibaseValues.kt", "public val kind: DataValueKind", "unsafeCast<DataValueKind>"),
    "SparqlAuthKind": ("service/Streams.kt", "public val kind: SparqlAuthKind", "unsafeCast<SparqlAuthKind>"),
    "EditRefusal": ("com/fenakhay/kwikibot/bindings/client/EditOutcomes.kt", "public fun refusalOf(", "\n    }"),
    "ErrorKind": ("com/fenakhay/kwikibot/bindings/errors/Errors.kt", "private fun kindOf(", "\n}\n"),
}

ACCEPTED: dict[str, tuple[frozenset[str], bool, str]] = {
    "EditStatus": (frozenset({"saved", "unchanged"}), False, "the two answers of `answerOf`, and `EditRefusal`"),
    "ProblemKind": (frozenset({"refused", "failed"}), False, "the two cases of `problemOf` in the binding layer's Outcomes.kt"),
    "NewEntityKind": (
        frozenset({"item", "property", "lexeme", "form", "sense", "mediainfo"}),
        False,
        "what `entityKindOf` accepts, spelled as Wikibase spells them",
    ),
    "EntityKind": (frozenset({"unknown"}), False, "`NewEntityKind`, and what `argumentName` answers for any other id"),
    "MergeConflict": (
        frozenset({"description", "sitelink", "statement"}),
        False,
        "what MediaWiki's wbmergeitems takes as ignoreconflicts, passed on as strings",
    ),
    "LogLevel": (
        frozenset({"trace", "debug", "info", "warn", "error", "off"}),
        False,
        "kotlin-logging's Level, an enum in a library",
    ),
    "TextScopeName": (frozenset({"prose", "everywhere"}), False, "the two cases of `textScopeNamed` in the binding layer's TextScopes.kt"),
    "NewPagesReviewed": (frozenset({"unreviewed", "reviewed", "all"}), False, "the three cases of `reviewedNamed`"),
    "AbuseFilterState": (
        frozenset({"enabled", "deleted", "private", "protected", "suppressed"}),
        False,
        "what MediaWiki's abusefilters takes as abfshow, passed on as strings",
    ),
    "SparqlValueType": (
        frozenset({"uri", "literal", "bnode"}),
        True,
        "what the SPARQL results format defines, read as the service sends it, and older services send others",
    ),
    "ChangeType": (
        frozenset({"edit", "new", "log", "categorize", "external"}),
        False,
        "what MediaWiki's recentchanges takes as rctype and answers with",
    ),
    "ModuleGroup": (
        frozenset({"action", "format", "list", "prop", "meta"}),
        True,
        "what MediaWiki's paraminfo says, read as it comes, and extensions name groups of their own",
    ),
    "HexMarker": (frozenset({"x", "X"}), False, "what a numeric HTML entity can be written with"),
    "QuoteMark": (frozenset({'\\"', "'"}), False, "what an HTML attribute can be quoted with"),
    "ContributionFilter": (
        frozenset({"minor", "new", "top", "patrolled", "autopatrolled"}),
        False,
        "what MediaWiki's usercontribs takes as ucshow, passed on as strings",
    ),
    "BlockFilter": (
        frozenset({"account", "ip", "range", "temp"}),
        False,
        "what MediaWiki's blocks takes as bkshow, passed on as strings",
    ),
    "RecentChangeFilter": (
        frozenset({"minor", "bot", "anon", "redirect", "patrolled", "autopatrolled"}),
        True,
        "what MediaWiki's recentchanges takes as rcshow, which an extension can add to",
    ),
    "WatchlistFilter": (
        frozenset({"minor", "bot", "anon", "patrolled", "autopatrolled", "unread"}),
        True,
        "what MediaWiki's watchlist takes as wlshow, which an extension can add to",
    ),
}

DERIVED = {"EntitySearchKind", "EventType", "Awaitable", "Negatable", "JsonValue", "ApiValue", "MessageValue", "Ignored", "Thrown", "ExitStatus"}

SPELLED_APART = {"media-info": "mediainfo"}


def unions(script: str) -> dict[str, tuple[frozenset[str], bool]]:
    """Every alias the build appends to the definitions, with its literals and whether it is open to more."""
    found = {}
    for match in re.finditer(r"export type (\w+)(?:<[^=]*>)? = (.*?);\n", script):
        name, body = match.groups()
        found[name] = (frozenset(re.findall(r'"((?:[^"\\]|\\.)*)"', body)), "string & {}" in body)
    return found


def enum_values(root: pathlib.Path, module: str, name: str) -> set[str]:
    """The values of the enum called [name] in [module], as the facade spells them."""
    for file in sorted((root / module / "src" / "commonMain").rglob("*.kt")):
        source = re.sub(r"/\*.*?\*/", "", file.read_text(encoding="utf-8"), flags=re.DOTALL)
        match = re.search(rf"\benum class {name}\b[^{{]*\{{", source)
        if not match:
            continue
        depth, end = 1, match.end()
        while depth and end < len(source):
            depth += {"{": 1, "}": -1}.get(source[end], 0)
            end += 1
        body = re.sub(r"@\w+\((?:[^()]|\([^()]*\))*\)", "", source[match.end() : end - 1])
        entries = body.split(";", 1)[0]
        values = re.findall(r"^\s*([A-Z][A-Z0-9_]*)\b", entries, flags=re.MULTILINE)
        spelled = (value.lower().replace("_", "-") for value in values)
        return {SPELLED_APART.get(value, value) for value in spelled}
    raise SystemExit(f"there is no enum class {name} in {module}")


def source_of(root: pathlib.Path, file: str) -> str:
    """The text of [file], under the first of [ROOTS] that holds it."""
    for base in ROOTS:
        path = root / base / file
        if path.is_file():
            return path.read_text(encoding="utf-8")
    raise SystemExit(f"there is no {file} under {', '.join(ROOTS)}")


def when_values(root: pathlib.Path, file: str, start: str, end: str) -> set[str]:
    """The strings a `when` answers with, between [start] and [end] in [file], under one of [ROOTS]."""
    source = source_of(root, file)
    first = source.index(start)
    return set(re.findall(r'->\s*"([^"]*)"', source[first : source.index(end, first + len(start))]))


def problems(root: pathlib.Path, script: str) -> list[str]:
    """What is wrong with the unions in [script], the text of the build file."""
    found = []
    declared = unions(script)
    for name, (literals, is_open) in declared.items():
        if name in ENUMS:
            expected, expected_open = enum_values(root, *ENUMS[name]), False
        elif name in WHENS:
            expected, expected_open = when_values(root, *WHENS[name]), False
        elif name in ACCEPTED:
            values, expected_open, _ = ACCEPTED[name]
            expected = set(values)
        elif name in DERIVED:
            continue
        else:
            found.append(f"{name} is a union this script does not know where the values of come from")
            continue
        for missing in sorted(expected - literals):
            found.append(f"{name} leaves out {missing!r}, which the code can give or take")
        for extra in sorted(literals - expected):
            found.append(f"{name} says {extra!r}, which the code never gives or takes")
        if is_open != expected_open:
            found.append(f"{name} should {'be' if expected_open else 'not be'} open to other strings")
    for name in (*ENUMS, *WHENS, *ACCEPTED, *DERIVED):
        if name not in declared:
            found.append(f"{name} is listed here but the definitions have no such union")
    return found


def main() -> None:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    script = (root / "kwikibot-js" / "build.gradle.kts").read_text(encoding="utf-8")
    found = problems(root, script)
    print(f"{len(unions(script))} unions checked")
    if found:
        raise SystemExit("\n".join(found))


if __name__ == "__main__":
    main()
