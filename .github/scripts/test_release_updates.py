"""Tests that an older tag cannot rewind the Scoop bucket, the README or the Homebrew formula."""

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).parent
sys.path.insert(0, str(SCRIPTS))

import npm_dist_tag
import release_version
import update_readme
import update_scoop

README = """implementation("com.fenakhay.kwikibot:kwikibot:1.10.0")
testImplementation("com.fenakhay.kwikibot:kwikibot-testkit:1.10.0")
kwikibot = "1.10.0"
<version>1.10.0</version>
"""


class Versions(unittest.TestCase):
    def test_versions_compare_as_numbers(self):
        self.assertTrue(release_version.is_older("1.9.0", "1.10.0"))
        self.assertFalse(release_version.is_older("1.10.0", "1.9.0"))
        self.assertFalse(release_version.is_older("1.10.0", "1.10.0"))


class NpmTag(unittest.TestCase):
    def test_an_older_release_does_not_take_latest(self):
        self.assertEqual(npm_dist_tag.dist_tag("1.9.0", "1.10.0"), "backport")

    def test_a_newer_release_takes_latest(self):
        self.assertEqual(npm_dist_tag.dist_tag("1.10.0", "1.9.0"), "latest")

    def test_a_first_release_takes_latest(self):
        self.assertEqual(npm_dist_tag.dist_tag("1.5.0", ""), "latest")


class Scoop(unittest.TestCase):
    def manifest(self, directory: str) -> pathlib.Path:
        path = pathlib.Path(directory) / "kwikibot.json"
        url = "https://example.org/v1.10.0/kwikibot-1.10.0-windows-x64.zip"
        body = {"version": "1.10.0", "architecture": {"64bit": {"url": url, "hash": "old"}}}
        path.write_text(json.dumps(body), encoding="utf-8")
        return path

    def test_an_older_tag_changes_nothing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.manifest(directory)
            before = path.read_text(encoding="utf-8")
            self.assertFalse(update_scoop.update(path, "1.9.0", "new"))
            self.assertEqual(before, path.read_text(encoding="utf-8"))

    def test_a_newer_tag_moves_it_on(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.manifest(directory)
            self.assertTrue(update_scoop.update(path, "1.11.0", "new"))
            manifest = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual("1.11.0", manifest["version"])
            self.assertEqual("new", manifest["architecture"]["64bit"]["hash"])
            self.assertIn("/v1.11.0/", manifest["architecture"]["64bit"]["url"])


class Readme(unittest.TestCase):
    def test_an_older_tag_changes_nothing(self):
        self.assertEqual(README, update_readme.updated(README, "1.9.0"))

    def test_a_newer_tag_moves_every_instruction_on(self):
        moved = update_readme.updated(README, "1.11.0")
        self.assertEqual(README.replace("1.10.0", "1.11.0"), moved)

    def test_a_reworded_readme_still_fails(self):
        with self.assertRaises(SystemExit):
            update_readme.updated("nothing to see", "1.11.0")


class Homebrew(unittest.TestCase):
    DIGEST = "a" * 64

    def write(self, out: pathlib.Path, version: str) -> None:
        script = SCRIPTS / "brew_formula.py"
        arguments = [sys.executable, str(script), "owner/repo", version, str(out), self.DIGEST, self.DIGEST]
        subprocess.run(arguments, check=True, capture_output=True)

    def test_an_older_tag_changes_nothing(self):
        with tempfile.TemporaryDirectory() as directory:
            out = pathlib.Path(directory) / "Formula" / "kwikibot.rb"
            self.write(out, "1.10.0")
            before = out.read_text(encoding="utf-8")
            self.write(out, "1.9.0")
            self.assertEqual(before, out.read_text(encoding="utf-8"))

    def test_a_newer_tag_moves_it_on(self):
        with tempfile.TemporaryDirectory() as directory:
            out = pathlib.Path(directory) / "Formula" / "kwikibot.rb"
            self.write(out, "1.9.0")
            self.write(out, "1.10.0")
            self.assertIn('version "1.10.0"', out.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
