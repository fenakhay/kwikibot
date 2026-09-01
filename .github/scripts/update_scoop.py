"""Point the Scoop bucket at a release.

Usage: update_scoop.py <version> <sha256-of-the-windows-archive>
"""

import json
import pathlib
import sys

RELEASES = "https://github.com/fenakhay/kwikibot/releases/download"


def main() -> None:
    version, digest = sys.argv[1], sys.argv[2]

    path = pathlib.Path("bucket/kwikibot.json")
    manifest = json.loads(path.read_text(encoding="utf-8"))

    manifest["version"] = version
    architecture = manifest["architecture"]["64bit"]
    architecture["url"] = f"{RELEASES}/v{version}/kwikibot-{version}-windows-x64.zip"
    architecture["hash"] = digest

    path.write_text(json.dumps(manifest, indent=4) + "\n", encoding="utf-8")
    print(f"bucket/kwikibot.json now points at {version}")


if __name__ == "__main__":
    main()
