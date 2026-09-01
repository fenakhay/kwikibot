"""Write the Homebrew formula for a release.

Usage: brew_formula.py <repo> <version> <out> <arm64-sha> <x64-sha> <linux-sha>
"""

import pathlib
import sys

TEMPLATE = '''class Kwikibot < Formula
  desc "Command-line tool for talking to a MediaWiki wiki"
  homepage "https://github.com/{repo}"
  license "MIT"
  version "{version}"

  on_macos do
    on_arm do
      url "{base}/kwikibot-{version}-macos-arm64.tar.gz"
      sha256 "{arm}"
    end
    on_intel do
      url "{base}/kwikibot-{version}-macos-x64.tar.gz"
      sha256 "{intel}"
    end
  end

  on_linux do
    url "{base}/kwikibot-{version}-linux-x64.tar.gz"
    sha256 "{linux}"
  end

  def install
    libexec.install Dir["*"]
    launcher = Dir[libexec/"**/kwikibot"].find {{ |path| File.file?(path) && File.executable?(path) }}
    odie "no kwikibot launcher in the archive" if launcher.nil?
    bin.install_symlink launcher => "kwikibot"
  end

  test do
    assert_match version.to_s, shell_output("#{{bin}}/kwikibot version")
  end
end
'''


def main() -> None:
    repo, version, out, arm, intel, linux = sys.argv[1:7]

    formula = TEMPLATE.format(
        repo=repo,
        version=version,
        base=f"https://github.com/{repo}/releases/download/v{version}",
        arm=arm,
        intel=intel,
        linux=linux,
    )

    path = pathlib.Path(out)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(formula, encoding="utf-8")
    print(formula)


if __name__ == "__main__":
    main()
