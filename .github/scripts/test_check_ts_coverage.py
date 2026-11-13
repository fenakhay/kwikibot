"""Tests check_ts_coverage.py on small programs, each proving a use counts or does not.

Usage: test_check_ts_coverage.py [directory holding node_modules/typescript]

The directory is kwikibot-js/smoke unless given.
"""

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))

import check_ts_coverage

TOOLCHAIN = pathlib.Path(sys.argv.pop(1) if len(sys.argv) > 1 else "kwikibot-js/smoke").resolve()

DEFINITIONS = """
export declare class Foo {
    constructor();
    private readonly __nominal;
    get alive(): string;
    get other(): string;
}
export declare interface Options {
    readonly option?: string;
}
export declare function run(): void;
export type Kind = "a" | "b";
"""

EVERYTHING = """
import { Foo, run, type Kind, type Options } from "kwikibot";
const foo = new Foo();
console.log(foo.alive, foo.other);
const options: Options = { option: "x" };
const kind: Kind = "a";
run();
console.log(options, kind);
"""


class Coverage(unittest.TestCase):
    def check(self, programs: dict[str, str]) -> list[str]:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            package = root / "node_modules" / "kwikibot"
            package.mkdir(parents=True)
            (package / "core.d.ts").write_text(DEFINITIONS)
            (package / "package.json").write_text('{"name":"kwikibot","types":"core.d.ts","main":"x.js"}')
            (root / "node_modules" / "typescript").symlink_to(TOOLCHAIN / "node_modules" / "typescript")
            (root / "package.json").write_text('{"type":"module"}')
            typed = root / "typed"
            typed.mkdir()
            for name, source in programs.items():
                (typed / name).write_text(source)
            return check_ts_coverage.problems(package / "core.d.ts", typed)[1]

    def assert_only_missing(self, found: list[str], *missing: str) -> None:
        self.assertEqual(sorted(f"{name} has no use in a program that runs" for name in missing), sorted(found))

    def with_foo_other(self, source: str) -> list[str]:
        prelude = EVERYTHING.replace("foo.alive, foo.other", "foo.alive")
        return self.check({"program.ts": prelude + source})

    def test_a_use_in_code_that_runs_counts(self):
        self.assertEqual([], self.check({"program.ts": EVERYTHING}))

    def test_a_call_from_a_function_that_is_called_counts(self):
        self.assertEqual([], self.with_foo_other("function used(f: Foo) { return f.other; }\nused(foo);"))

    def test_a_use_in_an_arrow_function_that_is_called_counts(self):
        self.assertEqual([], self.with_foo_other("const used = (f: Foo) => f.other;\nused(foo);"))

    def test_a_use_in_a_callback_counts(self):
        self.assertEqual([], self.with_foo_other("[foo].map((f) => f.other);"))

    def test_a_comment_does_not_count(self):
        self.assert_only_missing(self.with_foo_other("// foo.other\n/* foo.other */"), "Foo.other")

    def test_a_string_does_not_count(self):
        self.assert_only_missing(self.with_foo_other('console.log("foo.other", `foo.other`);'), "Foo.other")

    def test_a_function_nobody_calls_does_not_count(self):
        self.assert_only_missing(self.with_foo_other("function unused(f: Foo) { return f.other; }"), "Foo.other")

    def test_an_arrow_function_nobody_calls_does_not_count(self):
        self.assert_only_missing(self.with_foo_other("const unused = (f: Foo) => f.other;"), "Foo.other")

    def test_a_function_only_discarded_does_not_count(self):
        source = "function misuses(f: Foo) { return f.other; }\nvoid misuses;\nvoid [misuses];"
        self.assert_only_missing(self.with_foo_other(source), "Foo.other")

    def test_a_function_called_only_from_a_discarded_one_does_not_count(self):
        source = "function inner(f: Foo) { return f.other; }\nfunction outer() { inner(foo); }\nvoid outer;"
        self.assert_only_missing(self.with_foo_other(source), "Foo.other")

    def test_a_discarded_read_does_not_count(self):
        self.assert_only_missing(self.with_foo_other("void foo.other;"), "Foo.other")

    def test_code_under_a_literal_false_does_not_count(self):
        self.assert_only_missing(self.with_foo_other("if (false as boolean) { console.log(foo.other); }"), "Foo.other")

    def test_code_after_false_and_does_not_count(self):
        self.assert_only_missing(self.with_foo_other("console.log(false && foo.other);"), "Foo.other")

    def test_code_after_a_return_does_not_count(self):
        source = "function early() { return 1; console.log(foo.other); }\nearly();"
        self.assert_only_missing(self.with_foo_other(source), "Foo.other")

    def test_a_member_named_only_in_a_type_does_not_count(self):
        source = "let copied: typeof foo.other = '';\nconsole.log(copied);"
        self.assert_only_missing(self.with_foo_other(source), "Foo.other")

    def test_a_type_named_in_a_type_counts(self):
        without = EVERYTHING.replace("const kind: Kind = \"a\";", "const kind = \"a\";")
        self.assert_only_missing(self.check({"program.ts": without}), "Kind")
        self.assertEqual([], self.check({"program.ts": EVERYTHING}))

    def test_a_program_all_ts_does_not_name_is_a_failure_and_counts_for_nothing(self):
        found = self.check(
            {
                "all.ts": 'const PROGRAMS = ["named"] as const;\nconsole.log(PROGRAMS);\n',
                "named.ts": EVERYTHING.replace("foo.alive, foo.other", "foo.alive"),
                "forgotten.ts": 'import { Foo } from "kwikibot";\nconsole.log(new Foo().other);\n',
            }
        )
        self.assertIn("forgotten.ts is a program nothing runs: name it in all.ts", found)
        self.assertIn("Foo.other has no use in a program that runs", found)

    def test_a_helper_a_named_program_imports_is_reached(self):
        found = self.check(
            {
                "all.ts": 'const PROGRAMS = ["named"] as const;\nconsole.log(PROGRAMS);\n',
                "named.ts": EVERYTHING.replace("foo.alive, foo.other", "foo.alive")
                + 'import { helper } from "./support.js";\nhelper(foo);\n',
                "support.ts": 'import type { Foo } from "kwikibot";\n'
                "export function helper(f: Foo) { return f.other; }\n",
            }
        )
        self.assertEqual([], found)

    def test_a_helper_nobody_calls_does_not_count(self):
        found = self.check(
            {
                "all.ts": 'const PROGRAMS = ["named"] as const;\nconsole.log(PROGRAMS);\n',
                "named.ts": EVERYTHING.replace("foo.alive, foo.other", "foo.alive")
                + 'import "./support.js";\n',
                "support.ts": 'import type { Foo } from "kwikibot";\n'
                "export function helper(f: Foo) { return f.other; }\n",
            }
        )
        self.assert_only_missing(found, "Foo.other")

    def test_compile_only_counts_for_nothing_that_has_to_run(self):
        found = self.check(
            {
                "program.ts": EVERYTHING.replace("foo.alive, foo.other", "foo.alive"),
                "compile_only.ts": 'import { Foo } from "kwikibot";\nfunction a(f: Foo) { return f.other; }\nvoid a;\n',
            }
        )
        self.assertEqual(["Foo.other has no use in a program that runs, only in compile_only.ts"], found)

    def test_a_private_member_is_not_asked_for(self):
        self.assertNotIn("Foo.__nominal", check_ts_coverage.exports(DEFINITIONS))


if __name__ == "__main__":
    unittest.main()
