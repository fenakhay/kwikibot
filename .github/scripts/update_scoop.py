"""Point the Scoop bucket at a release, unless it already points at a newer one.

Usage: update_scoop.py <version> <sha256-of-the-windows-archive>
"""

import json
import pathlib
import sys

from release_version import is_older

RELEASES = "https://github.com/fenakhay/kwikibot/releases/download"


def update(path: pathlib.Path, version: str, digest: str) -> bool:
    """Whether the manifest at [path] now points at [version]; one for a newer release is left alone."""
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if is_older(version, manifest["version"]):
        return False

    manifest["version"] = version
    architecture = manifest["architecture"]["64bit"]
    architecture["url"] = f"{RELEASES}/v{version}/kwikibot-{version}-windows-x64.zip"
    architecture["hash"] = digest

    path.write_text(json.dumps(manifest, indent=4) + "\n", encoding="utf-8")
    return True


def main() -> None:
    version, digest = sys.argv[1], sys.argv[2]
    if update(pathlib.Path("bucket/kwikibot.json"), version, digest):
        print(f"bucket/kwikibot.json now points at {version}")
    else:
        print(f"bucket/kwikibot.json already points past {version}")


if __name__ == "__main__":
    main()
