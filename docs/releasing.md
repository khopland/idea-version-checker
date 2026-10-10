# Release pipeline

The development version is `1.3.0-SNAPSHOT`. It uses the `Unreleased` change notes and cannot produce a release draft or pass tagged-release validation. Version `1.2.0` is already published; existing versions and tags are preserved. Complete the [release gates](remaining-plan-work.md) before finalizing a new version.

## One-time setup

1. Merge the pipeline changes into `main` before preparing the next version. GitHub must have Actions enabled; release creation uses the automatically supplied `GITHUB_TOKEN` with job-level `contents: write` permission.
2. Generate a [JetBrains Marketplace permanent token](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html) for the account that can update `io.github.khopland.version-checker`.
3. Add it as the repository Actions secret **`PUBLISH_TOKEN`** in [Settings → Secrets and variables → Actions](https://github.com/khopland/idea-version-checker/settings/secrets/actions). Alternatively, run `gh secret set PUBLISH_TOKEN --repo khopland/idea-version-checker` and paste the token into the interactive prompt. Keep tokens out of source files and chat.

Author signing is optional. To enable it, add **`PRIVATE_KEY`** (PEM private key) and **`CERTIFICATE_CHAIN`** (PEM certificate chain) as Actions secrets. Add **`PRIVATE_KEY_PASSWORD`** if the key is encrypted. Follow the [JetBrains signing instructions](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) to create the key and certificate. Both key and certificate must be supplied together; partial signing configuration stops delivery. Retain the key securely and track certificate expiry. With no signing secrets, the workflow publishes the unsigned distribution to Marketplace.

Publishing and signing secrets are only provided to the release steps that need them. Pull request checks do not receive them. Signing and publishing disable Gradle's configuration cache; release jobs do not write back the Gradle cache.

## Validate locally

```bash
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B -m unittest discover -s scripts -p 'test_*.py'
python3 .github/scripts/release_metadata.py
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true --console=plain
```

The native npm fixtures need Node/npm on `PATH`; CI uses Node 22.23.1 and its bundled npm. Maven uses IDEA's bundled server. Gradle fixtures download their distribution if needed. All three use authenticated local HTTP repositories; Maven's fixture also checks public demo metadata. Validation needs network access for toolchains and uncached artifacts. Reports are under `build/reports`; the current development archive is `build/distributions/version-checker-1.3.0-SNAPSHOT.zip`. Tagged release validation must additionally use `--tag <version> --prerelease <true|false>` after finalizing the changelog.

## Publish a stable version

1. Set a new stable version, for example `version=1.3.0`, in `gradle.properties`.
2. Move the completed changes from `CHANGELOG.md`'s `Unreleased` section into a dated `## [1.3.0] - YYYY-MM-DD` entry. Leave an empty `Unreleased` section for future work. The release entry needs at least one bullet.
3. Merge these changes into `main`. The **Build** workflow builds the ZIP, runs `check` with native Maven/npm/Gradle integration enabled, tests release automation and performance reporting, and runs Plugin Verifier against IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3.
4. After all checks pass, the workflow creates or updates only the matching GitHub draft, attaches the tested ZIP, and pins its target to the checked commit. Review the draft and its release notes in [GitHub Releases](https://github.com/khopland/idea-version-checker/releases). Publish it as a regular release.
5. The **Release** workflow checks out the release tag, verifies the tag against the project version and finalized changelog, reruns tests and compatibility checks, signs and verifies the signature when configured, and attaches the selected ZIP and `SHA256SUMS`. It uploads that exact ZIP to Marketplace's `default` channel. Marketplace approval can still be required before the update becomes available.
6. After publishing, set the next development version, for example `version=1.3.1-SNAPSHOT`. Development builds show the `Unreleased` change notes.

Draft creation uses bare tags such as `1.3.0`; release validation also accepts `v1.3.0`. Published GitHub releases are never edited by the draft job, existing tags are never moved, and unrelated drafts are preserved. A matching tag that points to a different commit makes draft preparation skip that version. Prepare a new version to release newer code.

Publish drafts through the GitHub UI or an authenticated human account. Publishing with a workflow's `GITHUB_TOKEN` does not trigger another workflow's release event. Merely pushing a tag does not start Marketplace delivery.

## Prereleases

Use a version with a semantic prerelease suffix, such as `1.3.0-rc.1`, and a matching dated changelog entry. The draft is marked as a prerelease. Publishing it uploads to Marketplace's **`eap`** channel. GitHub's prerelease flag must agree with the version suffix. Stable versions publish to `default`; `SNAPSHOT` versions cannot be released.

To promote a prerelease, prepare a new stable version and changelog entry, then publish its regular GitHub release. Changing an existing prerelease to stable does not publish it to the stable channel.

## Failures and recovery

Validation reports are retained as workflow artifacts. Fix validation or credential failures before retrying. Never move a release tag to repair source code; use a new version.

For a published GitHub release whose delivery failed, use **Actions → Release → Run workflow**, enter the existing tag, and leave **Upload to Marketplace** enabled only if that version has not already been uploaded. The workflow verifies that the GitHub release is published before building anything. It attaches GitHub assets before Marketplace upload, so a GitHub asset-upload failure occurs before delivery.

If Marketplace already received the version (including a manual upload, or an upload whose response was lost), disable **Upload to Marketplace** for asset-only recovery. Check Marketplace before retrying an ambiguous publishing failure: rerunning a successful delivery would attempt to upload the same version again. This recovery path requires a tag that includes the new pipeline files; it is intended for future releases, not the existing 1.0.0 draft.

## Local checks

Use Java 21 through SDKMAN, then run:

```sh
sdk use java 21.0.12+1.1-tem
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
./gradlew check buildPlugin verifyPlugin
```

For a finalized version, validate release metadata without uploading:

```sh
python3 .github/scripts/release_metadata.py --tag 1.0.1 --prerelease false
```

`publishPlugin` without additional properties uses Marketplace's stable channel. The workflow supplies `-PmarketplaceChannel=eap` for prereleases and `-PreleaseArchive=build/distributions/<selected ZIP>` to publish the same archive attached to GitHub. Delivery skips rebuild, signing, and automatic changelog patching after that archive has been selected.
