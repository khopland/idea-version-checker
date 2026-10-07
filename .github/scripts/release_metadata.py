"""Validate repository version/changelog before preparing or publishing a release."""

import argparse
import os
from pathlib import Path
import re
import sys


VERSION_PATTERN = r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"


def metadata(root, tag=None, prerelease=None):
    properties = (root / "gradle.properties").read_text()
    versions = re.findall(r"^version\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
    if len(versions) != 1:
        raise ValueError("gradle.properties must contain exactly one version")
    version = versions[0]
    match = re.fullmatch(VERSION_PATTERN, version)
    if not match:
        raise ValueError(f"Version {version!r} must be a semantic version without build metadata")
    suffix = match.group(4)
    if suffix and any(part.isdigit() and len(part) > 1 and part.startswith("0") for part in suffix.split(".")):
        raise ValueError("Numeric prerelease identifiers cannot have leading zeros")
    is_prerelease = suffix is not None
    changelog = (root / "CHANGELOG.md").read_text()
    entry = re.search(
        rf"^## \[{re.escape(version)}\] - \d{{4}}-\d{{2}}-\d{{2}}\s*\n(.*?)(?=^## |\Z)",
        changelog, re.MULTILINE | re.DOTALL,
    )
    # A snapshot or an unfinalized changelog is fine in CI, but cannot be released.
    releasable = bool(entry and re.search(r"^\s*- \S", entry.group(1), re.MULTILINE))
    releasable = releasable and "snapshot" not in version.lower()
    if tag is not None:
        if tag not in (version, f"v{version}"):
            raise ValueError(f"Release tag {tag!r} does not match project version {version!r}")
        if not releasable:
            raise ValueError(f"Finalize a dated, nonempty CHANGELOG.md entry for {version} before releasing")
        if prerelease is not None and prerelease != is_prerelease:
            raise ValueError("GitHub prerelease status must match the version's prerelease suffix")
    return {"version": version, "channel": "eap" if is_prerelease else "default", "releasable": str(releasable).lower()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag")
    parser.add_argument("--prerelease", choices=("true", "false"))
    args = parser.parse_args()
    try:
        values = metadata(Path.cwd(), args.tag, None if args.prerelease is None else args.prerelease == "true")
    except ValueError as error:
        print(f"Release metadata error: {error}", file=sys.stderr)
        return 1
    output = "".join(f"{key}={value}\n" for key, value in values.items())
    print(output, end="")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as handle:
            handle.write(output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
