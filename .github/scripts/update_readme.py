"""Point the README's installation instructions at a release.

Usage: update_readme.py <version>

Fails when a kind of instruction it expects is gone, so a reworded README cannot quietly keep an old version.
"""

import pathlib
import re
import sys

PATTERNS = (
    r'(com\.fenakhay\.kwikibot:kwikibot(?:-testkit)?:)\d+\.\d+\.\d+',
    r'(^kwikibot = ")\d+\.\d+\.\d+',
    r'(<version>)\d+\.\d+\.\d+',
)


def main() -> None:
    version = sys.argv[1]

    path = pathlib.Path("README.md")
    text = path.read_text(encoding="utf-8")

    for pattern in PATTERNS:
        text, count = re.subn(pattern, rf"\g<1>{version}", text, flags=re.MULTILINE)
        if count == 0:
            raise SystemExit(f"README.md has nothing matching {pattern}")

    path.write_text(text, encoding="utf-8")
    print(f"README.md now installs {version}")


if __name__ == "__main__":
    main()
