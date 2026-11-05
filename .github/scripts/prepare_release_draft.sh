#!/usr/bin/env bash
# Creates or refreshes the draft GitHub release for $VERSION on the tested commit $GITHUB_SHA, with the
# CHANGELOG.md section as notes and the tested ZIP from build/distributions. Publishing the draft creates
# the v$VERSION tag and starts the release workflow.
set -euo pipefail

tag="v$VERSION"

# Listing releases distinguishes an absent release from authentication/API failures.
releases=$(gh api "repos/$GITHUB_REPOSITORY/releases" --paginate | jq -s 'add // []')

# Releases made before tags had a v prefix are tagged with the bare version.
if jq -e --arg version "$VERSION" 'any(.[]; (.draft | not) and (.tag_name == $version or .tag_name == ("v" + $version)))' <<< "$releases" > /dev/null; then
  echo "Version $VERSION is already released; increment pluginVersion and add its changelog section for the next release."
  exit 0
fi

# Never move a published tag or attach new binaries to a tag for another commit.
remote_tag=$(git ls-remote origin "refs/tags/$tag" "refs/tags/$tag^{}")
if [[ -n "$remote_tag" ]]; then
  tag_sha=$(awk '/\^\{\}$/ { peeled=$1 } { direct=$1 } END { print peeled ? peeled : direct }' <<< "$remote_tag")
  if [[ "$tag_sha" != "$GITHUB_SHA" ]]; then
    echo "::error::Tag $tag already points to $tag_sha, not $GITHUB_SHA. Delete the stray tag or increment pluginVersion."
    exit 1
  fi
fi

mkdir -p build/release-notes
notes="build/release-notes/$VERSION.md"
awk -v header="## [$VERSION]" '
  /^## / { if (found) exit; if (index($0, header) == 1) { found = 1; next } }
  found && !/^\[[^]]*\]: / { print }
' CHANGELOG.md | sed -e '/./,$!d' | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}' > "$notes"
if [[ ! -s "$notes" ]]; then
  echo "::error file=CHANGELOG.md::The '## [$VERSION]' section in CHANGELOG.md is empty."
  exit 1
fi

# Only one version is in flight at a time, so version drafts other than this one are stale.
draft_id=$(jq -r --arg tag "$tag" '[.[] | select(.draft and .tag_name == $tag)] | first | .id // empty' <<< "$releases")
jq -r --arg keep "$draft_id" '
  .[] | select(.draft and (.id | tostring) != $keep and (.tag_name | test("^v?[0-9]+\\.[0-9]+\\.[0-9]+")))
  | "\(.id) \(.tag_name)"
' <<< "$releases" |
  while read -r id stale_tag; do
    gh api --method DELETE "repos/$GITHUB_REPOSITORY/releases/$id"
    echo "Deleted the stale draft release for $stale_tag."
  done

(cd build/distributions && sha256sum ./*.zip > SHA256SUMS)
prerelease=false
if [[ "$VERSION" == *-* ]]; then prerelease=true; fi
if [[ -n "$draft_id" ]]; then
  gh release edit "$tag" --draft --target "$GITHUB_SHA" --title "$PLUGIN_NAME $VERSION" --notes-file "$notes" --prerelease="$prerelease"
else
  gh release create "$tag" --draft --target "$GITHUB_SHA" --title "$PLUGIN_NAME $VERSION" --notes-file "$notes" --prerelease="$prerelease"
fi
gh release upload "$tag" build/distributions/*.zip build/distributions/SHA256SUMS --clobber
echo "Prepared $tag from tested commit $GITHUB_SHA. Publish this draft to start the Marketplace release." | tee -a "$GITHUB_STEP_SUMMARY"
