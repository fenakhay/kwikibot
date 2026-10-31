"""Write out what the changelog says about one release.

Usage: changelog_section.py <version> [output-file]

Fails when the changelog has no section for the version, so a release cannot go out without notes.
"""

import pathlib
import re
import sys


def section(changelog: str, version: str) -> str:
    """The body of the section headed with the version, without its heading."""
    heading = re.compile(rf"^## \[{re.escape(version)}\].*$", re.MULTILINE)
    found = heading.search(changelog)
    if found is None:
        raise SystemExit(f"CHANGELOG.md has no section for {version}")

    rest = changelog[found.end():]
    following = re.search(r"^(## |\[[^\]]+\]: )", rest, re.MULTILINE)
    body = rest[: following.start()] if following else rest
    if not body.strip():
        raise SystemExit(f"CHANGELOG.md has an empty section for {version}")

    return body.strip() + "\n"


def main() -> None:
    version = sys.argv[1]
    body = section(pathlib.Path("CHANGELOG.md").read_text(encoding="utf-8"), version)

    if len(sys.argv) > 2:
        pathlib.Path(sys.argv[2]).write_text(body, encoding="utf-8")
    else:
        sys.stdout.write(body)


if __name__ == "__main__":
    main()
