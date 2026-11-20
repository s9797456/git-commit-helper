#!/usr/bin/env python3
"""Fail when public release notes or docs contain personal/credential-like data.

Run before every Marketplace / GitHub release:
    python3 scripts/check-release-notes.py
"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
FILES = [
    "marketplace/change-notes.html",
    "marketplace/change-notes-template.html",
    "marketplace/description.html",
    "README.md",
    "README.zh.md",
    "PUBLISHING.md",
    "SPEC.md",
    "src/main/resources/META-INF/plugin.xml",
    "build.gradle.kts",
]

EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
HOME_PATH = re.compile(r"(?i)(/Users/|/home/)[^/\s]+|C:\\Users\\[^\\\s]+")
# Only flag assignments that look like an actual value, not env-var wiring or placeholders.
CREDENTIAL = re.compile(
    r"(?i)(password|passwd|pwd|secret|api[_ -]?key|token|账号|密码)\s*[:=：]\s*([^\s<>]+)"
)
PLACEHOLDER = re.compile(r"^(\$|env\(|providers\.|\*\*\*|\.\.\.|<|YOUR_|CHANGE_ME|perm:\.\.\.)")


def main() -> int:
    failures = []
    for relative in FILES:
        path = ROOT / relative
        if not path.exists():
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if EMAIL.search(line) or HOME_PATH.search(line):
                failures.append((relative, number, "email/personal account"))
                continue
            match = CREDENTIAL.search(line)
            if match and not PLACEHOLDER.match(match.group(2)):
                failures.append((relative, number, "credential-like assignment"))
    if failures:
        print("Release-note privacy check failed:", file=sys.stderr)
        for relative, number, reason in failures:
            print(f"  {relative}:{number}: {reason}", file=sys.stderr)
        print("Remove personal email/account/password/token values before publishing.", file=sys.stderr)
        return 1
    print("Release-note privacy check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
