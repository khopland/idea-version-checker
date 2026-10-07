#!/usr/bin/env bash
set -euo pipefail

# Listing releases distinguishes an absent release from authentication/API failures.
releases=$(gh api "repos/$GITHUB_REPOSITORY/releases" --paginate | jq -s 'add')
release=$(jq -c --arg version "$VERSION" '[.[] | select(.tag_name == $version or .tag_name == ("v" + $version))] | sort_by(.draft) | first // empty' <<< "$releases")
if [[ -n "$release" && $(jq -r '.draft' <<< "$release") == false ]]; then
  echo "Version $VERSION is already released; increment version and finalize its changelog for the next release."
  exit 0
fi

# Never move a published tag or attach new binaries to a tag for another commit.
tag=${VERSION}
if [[ -n "$release" ]]; then
  tag=$(jq -r '.tag_name' <<< "$release")
fi
remote_tag=$(git ls-remote origin "refs/tags/$tag" "refs/tags/$tag^{}")
if [[ -n "$remote_tag" ]]; then
  tag_sha=$(awk '/\^\{\}$/ { peeled=$1 } { direct=$1 } END { print peeled ? peeled : direct }' <<< "$remote_tag")
  if [[ "$tag_sha" != "$GITHUB_SHA" ]]; then
    echo "Tag $tag already points to another commit; leaving its release unchanged."
    exit 0
  fi
fi

mkdir -p build/release-notes
notes="build/release-notes/$VERSION.md"
./gradlew getChangelog --project-version="$VERSION" --no-header --quiet --console=plain --output-file="$notes"
prerelease=false
if [[ "$CHANNEL" == eap ]]; then prerelease=true; fi
if [[ -n "$release" ]]; then
  gh release edit "$tag" --draft --target "$GITHUB_SHA" --title "$VERSION" --notes-file "$notes" --prerelease="$prerelease"
else
  gh release create "$tag" --draft --target "$GITHUB_SHA" --title "$VERSION" --notes-file "$notes" --prerelease="$prerelease"
fi
gh release upload "$tag" build/distributions/*.zip --clobber
echo "Prepared $tag from tested commit $GITHUB_SHA. Publish this draft to start Marketplace delivery."
