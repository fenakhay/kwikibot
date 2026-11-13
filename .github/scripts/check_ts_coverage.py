"""Fail when an export of the npm package has no use in the TypeScript programs under its smoke directory.

Usage: check_ts_coverage.py [definitions] [programs]

Lists every export in the definitions, kwikibot-js/api/npm/core.d.ts unless given, and every public member of
the classes and interfaces among them, leaving out what Kotlin generates. A use is what the TypeScript compiler
resolves to that very declaration, so `page.title` does not count for `Revision.title`. It needs `typescript`
installed where the programs are, kwikibot-js/smoke/typed unless given, or above them.

A use has to be one that runs, since a comment, a string or a function nobody calls proves nothing. Only code
reached from the top of a program counts: a function counts once something reached refers to it other than to
discard it with `void`, and what follows a `return` or a `throw`, or sits under a literal `false`, does not. A
program counts once `all.ts`, which is what runs them, names it or imports it, or something it reaches does, and
one nothing reaches is itself a failure. A member read only in a type, as `typeof page.title` is, does not count
either, as it is never read; the name of a class or a type does, since many are never constructed by a program.
What it cannot see is a statement that is reached by this reckoning and never runs, such as one after an `await`
of a promise that never settles.

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
const resolved = (symbol) => (symbol.flags & ts.SymbolFlags.Alias ? checker.getAliasedSymbol(symbol) : symbol);
const nameOf = (symbol) => {
  const seen = resolved(symbol);
  for (const declaration of seen.declarations ?? []) {
    if (!declaration.getSourceFile().fileName.endsWith("core.d.ts")) continue;
    const owner = declaration.parent;
    if (ts.isClassDeclaration(owner) || ts.isInterfaceDeclaration(owner)) return `${owner.name.text}.${seen.name}`;
    if (ts.isSourceFile(owner) || ts.isVariableDeclarationList(owner)) return seen.name;
    if (ts.isVariableDeclaration(owner)) return seen.name;
  }
  return null;
};
const isFunctionLike = (node) => node && (ts.isArrowFunction(node) || ts.isFunctionExpression(node));
const waitsToBeCalled = (node) =>
  ts.isFunctionDeclaration(node) || (ts.isVariableDeclaration(node) && isFunctionLike(node.initializer));
const isFalse = (node) => {
  while (ts.isAsExpression(node) || ts.isParenthesizedExpression(node)) node = node.expression;
  return node.kind === ts.SyntaxKind.FalseKeyword;
};
const ends = (node) => ts.isReturnStatement(node) || ts.isThrowStatement(node);
const uses = {};
const entered = new Set();
const entering = [];
const enter = (node) => {
  if (!entered.has(node)) {
    entered.add(node);
    entering.push(node);
  }
};
const foundIn = (node) => (uses[path.basename(node.getSourceFile().fileName)] ??= new Set());
const note = (node, symbol, inType) => {
  if (!symbol) return;
  const name = nameOf(symbol);
  if (name && !(inType && name.includes("."))) foundIn(node).add(name);
  if (inType) return;
  for (const declaration of resolved(symbol).declarations ?? []) {
    if (waitsToBeCalled(declaration) && !declaration.getSourceFile().isDeclarationFile) enter(declaration);
  }
};
const visit = (node, inType, everything) => {
  if (ts.isImportDeclaration(node)) return;
  if (!everything && waitsToBeCalled(node) && !entered.has(node)) return;
  if (!everything && ts.isVoidExpression(node) && !ts.isCallExpression(node.expression)) return;
  if (!everything && ts.isIfStatement(node) && isFalse(node.expression)) {
    if (node.elseStatement) visit(node.elseStatement, inType, everything);
    return;
  }
  if (
    !everything &&
    ts.isBinaryExpression(node) &&
    node.operatorToken.kind === ts.SyntaxKind.AmpersandAmpersandToken &&
    isFalse(node.left)
  ) {
    return;
  }
  const typed = inType || ts.isTypeNode(node);
  if (ts.isIdentifier(node)) note(node, checker.getSymbolAtLocation(node), typed);
  if (ts.isNewExpression(node)) {
    const symbol = checker.getSymbolAtLocation(node.expression);
    const name = symbol && nameOf(symbol);
    if (name) foundIn(node).add(`${name}.constructor`);
  }
  if (ts.isObjectLiteralExpression(node)) {
    const type = checker.getContextualType(node);
    for (const property of node.properties) {
      const key = property.name && ts.isIdentifier(property.name) ? property.name.text : null;
      if (!key || !type) continue;
      note(property, checker.getNonNullableType(type).getProperty(key), false);
    }
  }
  if (!everything && (ts.isBlock(node) || ts.isSourceFile(node) || ts.isModuleBlock(node))) {
    for (const statement of node.statements) {
      visit(statement, typed, everything);
      if (ends(statement)) break;
    }
    return;
  }
  ts.forEachChild(node, (child) => visit(child, typed, everything));
};
const sourceOf = (name) => files.map((file) => program.getSourceFile(file)).find((source) => path.basename(source.fileName) === name);
const importedBy = (source) => {
  const named = [];
  const look = (node) => {
    if (ts.isImportDeclaration(node) && node.moduleSpecifier.text.startsWith(".")) {
      named.push(path.basename(node.moduleSpecifier.text).replace(/\.js$/, ".ts"));
    }
    if (
      ts.isVariableDeclaration(node) &&
      node.name.getText() === "PROGRAMS" &&
      ts.isAsExpression(node.initializer) &&
      ts.isArrayLiteralExpression(node.initializer.expression)
    ) {
      for (const item of node.initializer.expression.elements) named.push(`${item.text}.ts`);
    }
    ts.forEachChild(node, look);
  };
  look(source);
  return named;
};
const entry = sourceOf("all.ts");
const run = new Set();
const reach = (source) => {
  if (!source || run.has(source)) return;
  run.add(source);
  for (const name of importedBy(source)) reach(sourceOf(name));
};
if (entry) reach(entry);
else for (const file of files) if (path.basename(file) !== "compile_only.ts") reach(program.getSourceFile(file));
const unreached = files
  .map((file) => program.getSourceFile(file))
  .filter((source) => !run.has(source) && path.basename(source.fileName) !== "compile_only.ts")
  .map((source) => path.basename(source.fileName));
for (const source of run) {
  uses[path.basename(source.fileName)] ??= new Set();
  visit(source, false, false);
}
while (entering.length > 0) {
  const declaration = entering.pop();
  ts.forEachChild(declaration, (child) => visit(child, false, false));
}
const compileOnly = sourceOf("compile_only.ts");
if (compileOnly) {
  uses["compile_only.ts"] = new Set();
  visit(compileOnly, false, true);
}
const answer = Object.fromEntries(Object.entries(uses).map(([file, used]) => [file, [...used]]));
console.log(JSON.stringify({ uses: answer, unreached }));
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


def uses(definitions: pathlib.Path, programs: pathlib.Path) -> tuple[dict[str, set[str]], list[str]]:
    """What each program under [programs] uses of [definitions], by its name, and the programs nothing runs."""
    files = sorted(programs.resolve().glob("*.ts"))
    arguments = ["node", "-e", USES, str(definitions.resolve()), *map(str, files)]
    result = subprocess.run(arguments, cwd=programs, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"could not read the programs: {result.stderr}")
    answer = json.loads(result.stdout)
    return {file: set(used) for file, used in answer["uses"].items()}, answer["unreached"]


def problems(definitions: pathlib.Path, programs: pathlib.Path) -> tuple[list[str], list[str]]:
    """What is exported, and what is wrong with how the programs under [programs] use it."""
    wanted = exports(definitions.read_text(encoding="utf-8"))
    used, unreached = uses(definitions, programs)
    compiled = used.pop("compile_only.ts", set())
    ran: set[str] = set().union(*used.values()) if used else set()

    found = [f"{program} is a program nothing runs: name it in all.ts" for program in unreached]
    for name in wanted:
        if name in COMPILE_ONLY:
            if name not in compiled:
                found.append(f"{name} is allowed not to run, but compile_only.ts does not use it")
        elif name not in ran:
            place = ", only in compile_only.ts" if name in compiled else ""
            found.append(f"{name} has no use in a program that runs{place}")
    found += [f"{name} is allow-listed but not exported" for name in COMPILE_ONLY if name not in wanted]
    return wanted, found


def main() -> None:
    arguments = sys.argv[1:]
    definitions = pathlib.Path(arguments[0] if arguments else "kwikibot-js/api/npm/core.d.ts")
    programs = pathlib.Path(arguments[1] if len(arguments) > 1 else "kwikibot-js/smoke/typed")

    wanted, found = problems(definitions, programs)

    allowed = sum(name in COMPILE_ONLY for name in wanted)
    members = sum("." in name for name in wanted)
    print(f"{len(wanted) - members} exports and {members} members, {allowed} of them allowed not to run")
    if found:
        raise SystemExit("\n".join(found))


if __name__ == "__main__":
    main()
