"""Write the Homebrew formula for a release, unless the tap already has a newer one.

Usage: brew_formula.py <repo> <version> <out> <arm64-sha> <linux-sha>
"""

import pathlib
import re
import sys

from release_version import is_older

TEMPLATE = '''class Kwikibot < Formula
  desc "Command-line tool for talking to a MediaWiki wiki"
  homepage "https://github.com/{repo}"
  license "MPL-2.0"
  version "{version}"

  on_macos do
    depends_on arch: :arm64
    url "{base}/kwikibot-{version}-macos-arm64.tgz"
    sha256 "{arm}"
  end

  on_linux do
    url "{base}/kwikibot-{version}-linux-x64.tgz"
    sha256 "{linux}"
  end

  def install
    bin.install "kwikibot"
  end

  test do
    assert_match version.to_s, shell_output("#{{bin}}/kwikibot version")
  end
end
'''


def holds_newer(path: pathlib.Path, version: str) -> bool:
    """Whether the formula at [path] is for a release after [version]."""
    if not path.exists():
        return False
    found = re.search(r'^\s*version "([\d.]+)"', path.read_text(encoding="utf-8"), re.MULTILINE)
    return found is not None and is_older(version, found.group(1))


def main() -> None:
    if len(sys.argv) != 6:
        raise SystemExit(__doc__.strip())
    repo, version, out, arm, linux = sys.argv[1:]

    for name, digest in (("arm64", arm), ("linux", linux)):
        if len(digest) != 64:
            raise SystemExit(f"{name}: expected a sha256, got {digest!r}")

    formula = TEMPLATE.format(
        repo=repo,
        version=version,
        base=f"https://github.com/{repo}/releases/download/v{version}",
        arm=arm,
        linux=linux,
    )

    path = pathlib.Path(out)
    if holds_newer(path, version):
        print(f"{out} is already for a release after {version}")
        return

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(formula, encoding="utf-8")
    print(formula)


if __name__ == "__main__":
    main()
