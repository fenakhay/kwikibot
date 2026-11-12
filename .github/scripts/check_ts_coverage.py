"""Fail when an export of the npm package has no use in the TypeScript programs under its smoke directory.

Usage: check_ts_coverage.py [definitions] [programs]

Lists every export in the definitions, kwikibot-js/api/npm/core.d.ts unless given, and every public member of
the classes and interfaces among them, leaving out what Kotlin generates. A use is what the TypeScript compiler
resolves to that very declaration, so `page.title` does not count for `Revision.title`. It needs `typescript`
installed where the programs are, kwikibot-js/smoke/typed unless given, or above them.

COMPILE_ONLY names, with why, what cannot run without a network or a terminal: nothing yet, and it should stay
that way. Those have to be used in compile_only.ts, which is compiled and never run. Everything else has to be
used in a program that runs.
"""

import json
import pathlib
import re
import subprocess
import sys

COMPILE_ONLY: dict[str, str] = {}

GENERATED = re.compile(r"copy|component\d+|hashCode|toString|equals")

USES = r"""
const path = require("node:path");
const ts = require(require.resolve("typescript", { paths: [process.cwd()] }));
const [definitions, ...files] = process.argv.slice(1).map((file) => path.resolve(file));
const program = ts.createProgram(files, {
  strict: true,
  target: ts.ScriptTarget.ES2022,
  module: ts.ModuleKind.NodeNext,
  moduleResolution: ts.ModuleResolutionKind.NodeNext,
  types: ["node"],
  noEmit: true,
});
const checker = program.getTypeChecker();
const nameOf = (symbol) => {
  const seen = symbol.flags & ts.SymbolFlags.Alias ? checker.getAliasedSymbol(symbol) : symbol;
  for (const declaration of seen.declarations ?? []) {
    if (!declaration.getSourceFile().fileName.endsWith("core.d.ts")) continue;
    const owner = declaration.parent;
    if (ts.isClassDeclaration(owner) || ts.isInterfaceDeclaration(owner)) return `${owner.name.text}.${seen.name}`;
    if (ts.isSourceFile(owner) || ts.isVariableDeclarationList(owner)) return seen.name;
    if (ts.isVariableDeclaration(owner)) return seen.name;
  }
  return null;
};
const uses = {};
for (const file of files) {
  const found = new Set();
  const note = (symbol) => {
    const name = symbol && nameOf(symbol);
    if (name) found.add(name);
  };
  const visit = (node) => {
    if (ts.isImportDeclaration(node)) return;
    if (ts.isIdentifier(node)) note(checker.getSymbolAtLocation(node));
    if (ts.isNewExpression(node)) {
      const symbol = checker.getSymbolAtLocation(node.expression);
      const name = symbol && nameOf(symbol);
      if (name) found.add(`${name}.constructor`);
    }
    if (ts.isObjectLiteralExpression(node)) {
      const type = checker.getContextualType(node);
      for (const property of node.properties) {
        const key = property.name && ts.isIdentifier(property.name) ? property.name.text : null;
        if (!key || !type) continue;
        note(checker.getNonNullableType(type).getProperty(key));
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(program.getSourceFile(file));
  uses[path.basename(file)] = [...found];
}
console.log(JSON.stringify(uses));
"""


def exports(definitions: str) -> list[str]:
    """Every export, and every public member of the classes and interfaces among them, as `Owner.member`."""
    text = re.sub(r"/\*\*.*?\*/", "", definitions, flags=re.DOTALL)
    names: list[str] = []
    owner = None
    for line in text.splitlines():
        if owner is None:
            found = re.match(r"export declare (?:abstract )?(class|interface) (\w+)", line)
            if found:
                owner = found.group(2)
                names.append(owner)
                continue
            found = re.match(r"export declare (?:function|const) (\w+)", line)
            found = found or re.match(r"export type (\w+)", line)
            if found:
                names.append(found.group(1))
        elif line.startswith("}"):
            owner = None
        else:
            member = re.match(r"\s+(?:readonly )?(?:get )?(\w+)\??[(<:]", line)
            if line.lstrip().startswith("private "):
                continue
            if line.lstrip().startswith("constructor("):
                names.append(f"{owner}.constructor")
            elif member and not GENERATED.fullmatch(member.group(1)):
                names.append(f"{owner}.{member.group(1)}")
    return list(dict.fromkeys(names))


def uses(definitions: pathlib.Path, programs: pathlib.Path) -> dict[str, set[str]]:
    """What each program under [programs] uses of [definitions], by the program's name."""
    files = sorted(programs.resolve().glob("*.ts"))
    arguments = ["node", "-e", USES, str(definitions.resolve()), *map(str, files)]
    result = subprocess.run(arguments, cwd=programs, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"could not read the programs: {result.stderr}")
    return {file: set(used) for file, used in json.loads(result.stdout).items()}


def main() -> None:
    arguments = sys.argv[1:]
    definitions = pathlib.Path(arguments[0] if arguments else "kwikibot-js/api/npm/core.d.ts")
    programs = pathlib.Path(arguments[1] if len(arguments) > 1 else "kwikibot-js/smoke/typed")

    wanted = exports(definitions.read_text(encoding="utf-8"))
    used = uses(definitions, programs)
    compiled = used.pop("compile_only.ts", set())
    ran: set[str] = set().union(*used.values())

    problems = []
    for name in wanted:
        if name in COMPILE_ONLY:
            if name not in compiled:
                problems.append(f"{name} is allowed not to run, but compile_only.ts does not use it")
        elif name not in ran:
            place = ", only in compile_only.ts" if name in compiled else ""
            problems.append(f"{name} has no use in a program that runs{place}")
    problems += [f"{name} is allow-listed but not exported" for name in COMPILE_ONLY if name not in wanted]

    allowed = sum(name in COMPILE_ONLY for name in wanted)
    members = sum("." in name for name in wanted)
    print(f"{len(wanted) - members} exports and {members} members, {allowed} of them allowed not to run")
    if problems:
        raise SystemExit("\n".join(problems))


if __name__ == "__main__":
    main()
