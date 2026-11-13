"""Tests check_ts_unions.py against this repository, by taking values out of its unions and adding others."""

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))

import check_ts_unions

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = (ROOT / "kwikibot-js" / "build.gradle.kts").read_text(encoding="utf-8")


class Unions(unittest.TestCase):
    def test_the_repository_is_clean(self):
        self.assertEqual([], check_ts_unions.problems(ROOT, SCRIPT))

    def test_a_value_left_out_of_a_union_from_an_enum_fails(self):
        script = SCRIPT.replace(' | "talk-of-non-main"', "")
        self.assertEqual(
            ["TitleProblem leaves out 'talk-of-non-main', which the code can give or take"],
            check_ts_unions.problems(ROOT, script),
        )

    def test_a_value_left_out_of_a_union_from_a_when_fails(self):
        script = SCRIPT.replace(' | "not-attempted"', "")
        self.assertEqual(
            ["PageOutcomeKind leaves out 'not-attempted', which the code can give or take"],
            check_ts_unions.problems(ROOT, script),
        )

    def test_a_value_left_out_of_an_accepted_union_fails(self):
        script = SCRIPT.replace('"action" | "format" | ', '"format" | ')
        self.assertEqual(
            ["ModuleGroup leaves out 'action', which the code can give or take"],
            check_ts_unions.problems(ROOT, script),
        )

    def test_a_value_the_code_never_gives_fails(self):
        script = SCRIPT.replace('"bnode"', '"bnode" | "blank"', 1)
        found = check_ts_unions.problems(ROOT, script)
        self.assertIn("SparqlValueType says 'blank', which the code never gives or takes", found)

    def test_closing_an_open_union_fails(self):
        script = SCRIPT.replace('"meta" | (string & {})', '"meta"')
        self.assertEqual(
            ["ModuleGroup should be open to other strings"], check_ts_unions.problems(ROOT, script)
        )

    def test_a_union_nobody_accounted_for_fails(self):
        script = SCRIPT.replace(
            "export type TimeOrder", 'export type Brand = "new";\n            export type TimeOrder'
        )
        found = check_ts_unions.problems(ROOT, script)
        self.assertEqual(["Brand is a union this script does not know where the values of come from"], found)

    def test_an_enum_with_arguments_and_members_is_read_whole(self):
        values = check_ts_unions.enum_values(ROOT, "kwikibot-client", "WatchMode")
        self.assertEqual({"no-change", "preferences", "watch", "unwatch"}, values)


if __name__ == "__main__":
    unittest.main()
