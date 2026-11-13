"""Prints the npm dist-tag for a release, so that an older tag does not move `latest` back."""

import sys

from release_version import is_older


def dist_tag(version: str, latest: str) -> str:
    """`latest`, unless [version] comes before the [latest] on npm, which is empty when there is none."""
    return "backport" if latest and is_older(version, latest) else "latest"


if __name__ == "__main__":
    print(dist_tag(sys.argv[1], sys.argv[2]))
