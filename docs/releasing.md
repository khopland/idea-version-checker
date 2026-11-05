# Releasing Version Checker

Releases run through GitHub Actions. The version is `pluginVersion` in
`gradle.properties`, and every version needs a `## [<version>]` section in
`CHANGELOG.md`. CI fails without it, so the two cannot drift apart.

## Prepare

1. Bump `pluginVersion` in `gradle.properties`, for example to `1.4.0`, or
   `1.4.0-beta.1` for a prerelease.
2. Move the `[Unreleased]` entries under a new `## [<version>]` heading in
   `CHANGELOG.md`.
3. Open a pull request. The **Build** workflow lints the workflows and
   release metadata, runs the tests, builds the plugin, and runs Plugin
   Verifier. **CI** passes only when all of these pass.
4. Merge the pull request to `main`.

## Draft release

When a push to `main` passes **CI**, the **Release draft** job creates or
refreshes a draft GitHub release tagged `v<version>` on that commit. It holds
the tested ZIP, `SHA256SUMS`, and the changelog section as release notes.
Drafts for other versions are deleted, because only one version is in flight at
a time.

Download the ZIP from the draft and try it with **Settings → Plugins → gear
menu → Install Plugin from Disk…**. When it is ready, publish the draft.
Publishing creates the tag and starts the **Release** workflow. Do not push
release tags by hand; the draft owns the tag.

## Release workflow

The workflow has two jobs.

**Build and verify tagged plugin** has no secrets. It checks that the tag is
`v<pluginVersion>` and has a changelog section, runs the tests and Plugin
Verifier on the tagged sources, and builds the ZIP. It never reads the Gradle
cache that pull requests write to.

**Sign and publish** runs in the `marketplace` environment and gets the
secrets. Without rebuilding, it signs the tested ZIP when signing is
configured, records its build provenance, uploads the ZIP and `SHA256SUMS` to
the GitHub release, and publishes the ZIP to JetBrains Marketplace. Versions
with a suffix, such as `1.4.0-beta.1`, go to the `beta` channel; plain versions
go to `default`. The GitHub upload runs first, so a Marketplace release is
always on GitHub too.

Anyone can check that a downloaded ZIP was built by this workflow:

```sh
gh attestation verify <plugin>.zip --repo khopland/idea-version-checker
```

### Recovery

If a job fails, fix the cause and re-run it from the Actions tab.

A release event runs the workflow file from the tagged commit. If that file
cannot start, run **Release** manually from the Actions tab on `main` and enter
the published tag, for example `v1.4.0`. This uses the workflow from `main`
and builds the tagged sources. Clear **Upload to Marketplace** when that
version is already on the Marketplace and only the GitHub assets are missing.

## Build locally

```sh
./gradlew check verifyPlugin buildPlugin
./gradlew prepareRelease
```

`prepareRelease` stages the ZIP and `SHA256SUMS` in `build/release/`, signed
when `CERTIFICATE_CHAIN` and `PRIVATE_KEY` are set.

## Secrets and repository settings

Add these as secrets of the `marketplace` environment (**Settings →
Environments → marketplace**):

- `PUBLISH_TOKEN`: a JetBrains Marketplace token from
  [plugins.jetbrains.com/author/me/tokens](https://plugins.jetbrains.com/author/me/tokens).
- `CERTIFICATE_CHAIN` and `PRIVATE_KEY` (optional): the PEM certificate chain
  and private key that sign the ZIP. See
  [plugin signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).
  Set both or neither.
- `PRIVATE_KEY_PASSWORD` (optional): only if the private key is encrypted.

Recommended settings:

- In the `main` ruleset, require the **CI** status check.
- In the `marketplace` environment, allow deployments only from `main` (for
  recovery runs) and from tags matching `v*`.
