#!/usr/bin/env bash
# Prints pluginVersion from gradle.properties after checking that it is a semantic version with a
# "## [<version>]" section in CHANGELOG.md. With a tag argument, also checks that the tag is v<version>.
set -euo pipefail

version=$(sed -n 's/^pluginVersion=//p' gradle.properties)
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]]; then
  echo "::error file=gradle.properties::pluginVersion '$version' is not a version such as 1.2.0 or 1.2.0-beta.1." >&2
  exit 1
fi
if ! grep -q "^## \[${version//./\\.}\]" CHANGELOG.md; then
  echo "::error file=CHANGELOG.md::CHANGELOG.md has no '## [$version]' section for pluginVersion $version." >&2
  exit 1
fi
if [[ $# -gt 0 && "$1" != "v$version" ]]; then
  echo "::error::Tag $1 does not match pluginVersion $version (expected v$version)." >&2
  exit 1
fi
echo "$version"
