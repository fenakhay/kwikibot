"""Point the README's installation instructions at a release, leaving any that name a newer one alone.

Usage: update_readme.py <version>

Fails when a kind of instruction it expects is gone, so a reworded README cannot quietly keep an old version.
"""

import pathlib
import re
import sys

from release_version import is_older

PATTERNS = (
    r'(com\.fenakhay\.kwikibot:kwikibot(?:-testkit)?:)(\d+\.\d+\.\d+)',
    r'(^kwikibot = ")(\d+\.\d+\.\d+)',
    r'(<version>)(\d+\.\d+\.\d+)',
)


def updated(text: str, version: str) -> str:
    """[text] with each instruction it holds moved forward to [version]."""

    def forward(found: re.Match[str]) -> str:
        return found.group(0) if is_older(version, found.group(2)) else found.group(1) + version

    for pattern in PATTERNS:
        text, count = re.subn(pattern, forward, text, flags=re.MULTILINE)
        if count == 0:
            raise SystemExit(f"README.md has nothing matching {pattern}")
    return text


def main() -> None:
    version = sys.argv[1]

    path = pathlib.Path("README.md")
    path.write_text(updated(path.read_text(encoding="utf-8"), version), encoding="utf-8")
    print(f"README.md installs {version} or newer")


if __name__ == "__main__":
    main()
