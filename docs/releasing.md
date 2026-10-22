# Release procedure

The [Build workflow](../.github/workflows/build.yml) validates changes and prepares release drafts.
The [Release workflow](../.github/workflows/release.yml) validates a published tag and delivers its archive to JetBrains Marketplace.

The project version comes from `gradle.properties`.
[Acceptance records](acceptance/README.md) identify the tested archives and their remaining checks.
Check [GitHub Releases](https://github.com/khopland/idea-version-checker/releases) for published releases and drafts.

## Supported IDEs

Compile against IDEA 2026.1.4, build 261.26222.65.
Run Plugin Verifier against IDEA 2026.1.4 and 2026.2.3.
The supported range is 2026.1.4 through 2026.2.x.

Use the [packaged plugin acceptance procedure](ide-acceptance.md) with the selected archive on both targets.
Each new archive requires its own acceptance results.

## Configure publishing

1. Enable GitHub Actions for the repository.
2. Generate a [JetBrains Marketplace permanent token](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html) for the account that can update `io.github.khopland.version-checker`.
3. Add the token as the repository Actions secret **`PUBLISH_TOKEN`**.

Use [Actions secrets settings](https://github.com/khopland/idea-version-checker/settings/secrets/actions) or this interactive command:

```sh
gh secret set PUBLISH_TOKEN --repo khopland/idea-version-checker
```

Keep tokens out of source files and chat.
Release jobs use the supplied `GITHUB_TOKEN` with `contents: write` permission for GitHub release assets.

Author signing is optional.
To enable signing, configure **`PRIVATE_KEY`** and **`CERTIFICATE_CHAIN`** as Actions secrets.
Add **`PRIVATE_KEY_PASSWORD`** if the key is encrypted.
See the [JetBrains signing instructions](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).

Supply the private key and certificate together. Partial signing configuration stops delivery.
Without signing secrets, the workflow selects the unsigned distribution.
Publishing and signing secrets are available only to the steps that need them.
Pull request checks do not receive these secrets.

## Validate locally

Use Java 21 through SDKMAN, then run:

```sh
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B -m unittest discover -s scripts -p 'test_*.py'
python3 .github/scripts/release_metadata.py
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true --console=plain
```

The npm fixtures require Node.js and npm on `PATH`. CI uses Node 22.23.1 and its bundled npm.
Maven uses IDEA's bundled server. Gradle fixtures download their distribution if necessary.
Validation needs network access for toolchains and uncached artifacts.
Reports are under `build/reports`. Archives are under `build/distributions`.

For a finalized version, validate its tag and prerelease flag without uploading.
Replace `1.3.0` with the version under review:

```sh
python3 .github/scripts/release_metadata.py --tag 1.3.0 --prerelease false
```

Complete interactive acceptance on the selected archive before publication.
Record its commit, SHA-256, runtime, fixture, results, and remaining blockers.

## Publish a stable version

1. Set the new version in `gradle.properties`.
2. Move completed changes from `Unreleased` into a dated `CHANGELOG.md` entry for that version.
3. Keep an empty `Unreleased` section. Include at least one bullet in the release entry.
4. Merge the changes into `main`.
5. Wait for the Build workflow to pass.
6. Review the matching GitHub draft, its target commit, release notes, and tested ZIP.
7. Publish the draft as a regular release through GitHub's UI or an authenticated human account.

Build CI runs packaging, tests, Maven 4 regressions, and compatibility verification independently.
Draft preparation waits for all four jobs.
It creates or updates only the matching draft and attaches the tested ZIP.

The Release workflow checks out the published tag.
It checks the version and changelog, then reruns tests, packaging, and compatibility checks.
If signing is configured, it signs the plugin and verifies the signature.
It attaches the selected ZIP and `SHA256SUMS` before Marketplace upload.
It uploads that exact ZIP to the `default` channel.
Marketplace approval can still be necessary before users receive the update.

After publication, set the next development version, such as `1.3.1-SNAPSHOT`.
Development builds use the `Unreleased` change notes.

Draft creation uses bare tags such as `1.3.0`. Release validation also accepts `v1.3.0`.
The draft job preserves published releases, existing tags, and unrelated drafts.
If a matching tag points to another commit, draft preparation skips that version.
Use a new version to release newer code. Do not move an existing release tag.

Publishing with a workflow's `GITHUB_TOKEN` does not trigger another workflow's release event.
Pushing a tag alone does not start Marketplace delivery.

## Publish a prerelease

Use a semantic prerelease version, such as `1.3.0-rc.1`, and a matching dated changelog entry.
The draft is marked as a prerelease.
GitHub's prerelease flag must agree with the version suffix.
Publishing sends the archive to Marketplace's `eap` channel.
`SNAPSHOT` versions cannot be released.

To promote a prerelease, prepare a new stable version and publish its regular GitHub release.
Changing an existing prerelease to stable does not publish it to the stable channel.

## Recover failed delivery

Validation reports are retained as workflow artifacts.
Correct validation or credential failures before you retry.
Use a new version for source corrections after a release.

For a published release with failed delivery:

1. Open **Actions → Release → Run workflow**.
2. Enter the existing published tag.
3. Enable **Upload to Marketplace** only if that version has not already been uploaded.
4. Run the workflow.

The workflow checks that the GitHub release is published before it builds.
It attaches GitHub assets before Marketplace upload.
If Marketplace already received the version, disable upload for asset-only recovery.
Check Marketplace before retrying an upload with an unknown result.
The recovery tag must contain the release workflow and its supporting scripts.

The workflow uses `-PmarketplaceChannel=eap` for prereleases.
It uses `-PreleaseArchive=build/distributions/<selected ZIP>` to publish the selected archive.
Delivery disables the Gradle configuration cache and skips rebuilding, signing, and changelog patching after archive selection.

## Investigate IDE errors

The plugin registers JetBrains' Marketplace error reporter in `plugin.xml`.
Users can submit reports through the IDE or enable **Send error reports to JetBrains automatically**.
In the plugin's Marketplace management page, open **Freezes** to examine attributed freeze reports.
Reports depend on user settings and the IDE identifying this plugin as involved.
See [JetBrains' freeze-reporting guide](https://blog.jetbrains.com/platform/2026/10/ui-freeze-reports-in-marketplace/).
