# Failed-preview recovery follow-up

The [phase 2 native session](phase2.md) exposed a generic `NpmViewFailure`
notification during HTTP 401 recovery. Failed previews now display provider-owned
recovery advice and offer **Retry Preview** alongside **Show Details**.

Retry keeps the latest mode, scope and current-file identity, including changes
made after the original preview opened. It invalidates metadata before preparing
a new preview; failed or partial results cannot authorize edits. The existing
preview entry point is preserved. Shared code uses a core advice interface and
does not link optional npm classes.

npm advice distinguishes registry authentication/permissions, unavailable offline
metadata, network/proxy failures, certificate trust and missing package/version
metadata. Native npm output and unknown codes are not copied into the summary.
Missing local runtime and unsupported package-manager selections have configuration
guidance; the full native runtime/configuration matrix remains unvalidated.

The feedback service owns preview notifications, expires them during disposal and
releases its active callbacks. Paused checks and expired notifications reject
retry. Cancellation continues to propagate without a failure notification.

## Regression coverage

New regressions exercise safe npm advice, unknown-error summaries, one-shot retry,
pause/resume and service disposal. A provider-backed preview regression changes
both mode and scope, fails the next check, and verifies that retry invalidates
metadata, queries only the selected file in the latest mode, restores the preview
and leaves source bytes unchanged. Existing cancellation, stale-edit, notification
recovery and packaged-plugin lifecycle tests remain part of full validation.

## Native final-archive check

IDEA 2026.2.3 (IU-262.10968.63, bundled JBR 25.0.4), macOS 27.0 on the same
Apple M4 / 16 GiB host, loaded `1.3.0-SNAPSHOT` with SHA-256:

`ad2c6500b472b03d74f4d78d01edb5d96f264b1024b984b808a0e495133aa39b`

The isolated npm workspace and Node 22.23.1 / npm 10.9.8 runtime matched the
earlier phase 2 session. This archive includes the built-in error reporter,
recovery correction and corresponding packaged release note.

- A three-row Current File/Patch preview was changed to Minor, then Whole Project.
  The disposable registry rejected the newly requested gamma metadata with 401.
  The notification explained registry access, credentials and package permissions
  in `.npmrc`, without displaying `NpmViewFailure` as the summary. Show Details
  opened the native E401 explanation.
- Restoring registry access and selecting **Retry Preview** performed fresh native
  checks and opened **Whole Project / Minor + patch** with five selected/visible
  declarations targeting 1.1.0, including the alias and member declarations.
- Setting the fixture's `.npmrc` to offline mode with a fresh empty cache, then
  refreshing the preview, produced **ENOTCACHED**. The notification explained
  missing offline metadata and npm configuration; Show Details confirmed the
  native code. Restoring the original `.npmrc` and selecting **Retry Preview**
  returned the same five-row Whole Project/Minor preview. Both previews were
  cancelled without applying edits.
- Root/member manifest selectors and lockfile bytes remained unchanged;
  `node_modules` remained absent. The fixture's original `.npmrc` was restored,
  and the owned IDEA process and registry server were stopped.

The [anonymous trace](idea-2026.2.3-npm-preview-recovery.log) contains 172 stage
records from this final-archive session. Its modes, scopes, failures and retries
are mixed functional observations, not a latency sample distribution. The
[registry observations](idea-2026.2.3-npm-preview-recovery-registry.jsonl) record
eight 200 responses and one 401, without headers or credentials. ENOTCACHED ran
against npm's empty local cache and did not make HTTP requests to the fixture.
No standalone CLI metadata sanity check was included in this capture.

Full automated validation passed with **401 plugin tests in 61 classes**, zero
failures/errors/skips, plus **13 release tests and 16 script tests**. All native
integration flags were enabled. Test sources were fully recompiled with Kotlin
incremental compilation disabled; configuration caching was disabled so the
packaged release note was reread. This was not a clean-machine build.
The build completed in 3m 32s. Plugin Verifier reported compatibility with IDEA
2025.3.6.1, 2026.1.4 and 2026.2.3, retaining the existing 10 experimental progress
API usages and no deprecated/scheduled-for-removal usages.

```sh
JAVA_HOME=/Users/kristoffer/.sdkman/candidates/java/21.0.12+1.1-tem \
  ./gradlew compileTestKotlin --rerun check buildPlugin \
  verifyPluginProjectConfiguration verifyPlugin -Pkotlin.incremental=false \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true \
  --no-configuration-cache --console=plain
```

## Remaining release evidence

This correction addresses the observed preview recovery gap. Minimum-IDE native
npm, automatic/status failure presentation, remaining runtime/cancellation native
cases, controlled scale and typing measurements and exact RC/stable archive
validation remain open. It does not certify the plan's latency budgets.
