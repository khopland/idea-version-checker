# Release implementation ledger

Updated 2026-10-10. The [HTML release plan](release-plan-2026-10-10.html) records the audit of commit `17c4975`; this ledger tracks implementation after that audit. Development remains `1.3.0-SNAPSHOT` until the release gates are satisfied. A snapshot is intentionally not eligible for release automation.

## First implementation batch

| Finding                                 | Change                                                                                                                                                                                                                      | Status                                                                                                                                                       |
|-----------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| F01: published release identity         | Development version changed to `1.3.0-SNAPSHOT`; archive notes use `Unreleased`. Release instructions use a new version and retain published tags.                                                                          | Implemented; final RC/stable version and dated notes remain a release gate.                                                                                  |
| F02: missing current IDEA branch        | Plugin Verifier now targets 2025.3.6.1, 2026.1.4 and 2026.2.3. Compilation/platform tests remain on the minimum supported IDE.                                                                                              | Added; validation results below.                                                                                                                             |
| F05: ignored updates counted in status  | One presentation policy feeds report filtering and complete/incremental status counts. Notices remain visible, and removing an ignore restores cached updates without querying repositories or resetting expiry.            | Implemented with a widget regression spanning running and completed checks.                                                                                  |
| F08: per-declaration Gradle application | Captured document changes survive combined/selected plans. All files and other edit types validate before writing; descending replacements run in a bulk document update with one PSI commit per file and one undo command. | Implemented with 10,000 dense edits, mixed lengths, selection, undo, caret/unchanged-range preservation, stale/uncommitted captures and overlap regressions. |
| Current-IDE API notices                 | Preview selectors use a renderer subclass instead of the deprecated factories. Diagnostic refresh resolves PSI files in a cancellable background read action, then restarts highlighting on the EDT.                        | Replaces the renderer factories scheduled for removal and the deprecated `ReadAction.run` call identified by verification.                                   |
| F15: broken documentation               | Restored this ledger and replaced the missing fast-hints review link with the current HTML review. Updated release/version guidance.                                                                                        | Implemented.                                                                                                                                                 |
| Release validation consistency          | Tagged release jobs now run the performance-report Python tests and explicit plugin-project configuration validation, in addition to existing integration tests and compatibility checks.                                   | Implemented.                                                                                                                                                 |

IDEA 2026.2.3 is the current stable release checked against the [JetBrains announcement](https://blog.jetbrains.com/idea/2026/09/intellij-idea-2026-2-3/) on 2026-10-10. That establishes the verification target, not native UX coverage.

## Validation of this batch

The final local run passed on 2026-10-10 with SDKMAN Temurin Java 21.0.12+1.1, on an Apple M4 / Mac16,1 with 16 GiB RAM:

- **395 plugin tests in 59 classes**, with zero failures, errors or skips. All native integration flags were enabled: 6 Maven, 4 npm and 3 Gradle integration methods, plus 4 packaged-plugin lifecycle methods.
- **13 release-automation tests and 12 performance-report tests** passed. The actual project metadata returns `releasable=false`; tagged-release validation rejects `1.3.0-SNAPSHOT`.
- **`check`, `buildPlugin`, `verifyPluginProjectConfiguration` and `verifyPlugin` passed** in 2m 4s. Compilation, tests, packaging and verification completed; this was an incremental run using local Gradle outputs/caches, not a clean-machine build.
- **IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3 are binary-compatible.** Each verifier verdict retains 10 experimental progress-API usages, with no deprecated or scheduled-for-removal usages in this archive. This does not establish native window behavior on the new IDE branch.
- **10,000 selected Gradle edits in one file commit PSI once and undo together.** The final full-suite observation took **3,510 ms** for application on this host; a preceding focused run took 2,050 ms. These single headless observations ran under different contention and do not establish native p95, a production speedup, or compliance with the HTML plan's write-time budgets. Native scale/typing measurements and further write-path profiling remain required.
- **Packaged descriptor and notes checked:** `1.3.0-SNAPSHOT`, minimum build `253.33813.55`, with the new Unreleased Gradle/status/API changes present. Changed Markdown local links and HTML anchors were checked.

Archive: `build/distributions/version-checker-1.3.0-SNAPSHOT.zip`  
SHA-256: `2160464c1166bf18a026388d1be2e0a89187ee299319e0457e891fff945a0d21`

This archive was built from the first-batch working tree on top of audit commit `17c4975`, before the changes were split into commits. Final validation log: `/tmp/version-checker-first-batch-full.log`; current XML reports: `build/test-results/test`; verifier verdicts: `build/reports/pluginVerifier/*/plugins/io.github.khopland.version-checker/1.3.0-SNAPSHOT/verification-verdict.txt`. The packaged descriptor/validation summary is saved locally as `/tmp/version-checker-first-batch-artifact.json`. Rebuild and validate again after finalizing a release version.

```bash
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B -m unittest discover -s scripts -p 'test_*.py'
python3 .github/scripts/release_metadata.py
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true --console=plain
```

Use SDKMAN Java 21 for the plugin build. Headless test timings are work-count evidence; no production latency improvement or native p95 claim follows from them.

## Phase 2 progress

The [native follow-up record](native-validation-2026-10-10/phase2.md) contains
archive identities, sanitized traces, functional outcomes and remaining gaps:

- Native npm works in the available IDEA 2026.2.3 installation despite “Unlock
  Ultimate.” Local/workspace updates, alias/range preservation, grouped undo,
  unchanged lockfile, all preview modes/scopes and HTTP 401 recovery were checked.
- Gradle diagnostics and a local fix were observed on minimum IDEA 2025.3.6.1.
  Current IDEA 2026.2.3 passed small Groovy edits, a shared Kotlin catalog edit,
  and a 1,000-declaration apply/undo case.
- Current-IDE smoke tests passed with Maven disabled for Gradle, Gradle disabled
  for npm and JavaScript disabled for Maven. Settings/unload coverage remains open.
- The single contended 1,000-edit endpoint took 622.93 ms. A JFR sample summary
  was recorded; it does not establish input delay, maximum EDT span or p95.
- Full validation passed again: 395 plugin tests, 13 release tests, 16 script
  tests and all three verifier targets. The rebuilt archive includes concurrent
  error-reporter changes and differs from the native-tested archive; both hashes
  are recorded in the follow-up. Final-archive native validation remains required.

Phase 2 is not complete. Minimum-IDE npm, controlled scale/typing measurements
and the remaining native failure/usability matrix still gate stable release.

### Recovery follow-up

The generic npm preview failure observed above led to an F14 correction:
provider-owned safe guidance now explains authentication, offline-cache,
network and certificate failures. Failed previews offer **Retry Preview**, which
forces fresh metadata and preserves the latest mode, scope and file. Preview
notifications are owned by the feedback service and expire on disposal/unload;
paused checks and expired notifications cannot start a retry. The shared preview
code depends on a core advice contract rather than optional npm classes.

See the [recovery validation record](native-validation-2026-10-10/preview-recovery.md)
for the exact archive, native authentication retry and regression results. This
addresses the observed preview failure; the broader F14 failure/status matrix and
minimum-IDE native npm coverage remain open.

The final snapshot passed native HTTP 401 and empty-offline-cache recovery in
IDEA 2026.2.3, with unchanged manifests/lockfile and the latest Whole Project/Minor
selection retained after retry. Full validation now passes 401 plugin tests in
61 classes, with 13 release and 16 script tests. Exact artifact identity and
sanitized traces are included in the recovery record.

## Remaining release gates

- **F01: finalize the release artifact.** After validation and UX acceptance, choose a new RC such as `1.3.0-rc.1`, finalize a dated changelog entry, verify the packaged descriptor/change notes/checksum, then test the exact archive in IDEA. Promote with a new stable version after EAP acceptance. No release is published by this implementation batch.
- **F03: native npm workflows.** Current-IDE editor/preview/local/workspace edit and authentication-recovery checks passed. Complete minimum-IDE coverage, ignore/recovery, cancellation and offline/runtime/configuration cases; preserve those gaps in the [native record](native-validation-2026-10-10/phase2.md).
- **F04: supported-IDE Gradle UX.** Minimum-IDE Groovy diagnostics/local fix and current-IDE Groovy/shared Kotlin catalog workflows passed. Complete the per-version DSL/catalog and repeated performance matrix. Binary compatibility alone does not close this gate.
- **Native scale and failure matrix.** Measure small projects, 100-module projects and dense edits on named hardware; record warm/cold states, authentication failures, offline behavior, cancellation, imports, plugin unload and typing/input delays. Retain completed/incomplete sample counts and archive hashes. The HTML plan's latency budgets remain proposed targets.

## Next engineering work

1. **F06/F07: Gradle query batching.** Build a scan session per resolution context; index requests once rather than scanning every project/configuration for each request. Preserve native repository/variant semantics and prove candidates/request counts on authenticated fixtures before sharing metadata across modes.
2. **F09: move external input reads off the EDT.** Separate the small save/model step from cancellable hashing and configuration discovery. Keep fresh unsaved/external-input guards before apply.
3. **F10: bound result state.** Add documented budgets and expiry cleanup for results, source/context tracking and generation state while proving that evicted in-flight results cannot reauthorize old edits.
4. **F11: align quick-fix and preview freshness.** Introduce a common candidate lease with result identity, generation and original deadline. Keep stale-edit recovery and shared-consumer safety.
5. **F12: explicit coverage.** Track checked, ignored, unsupported and manual-review declarations so status, empty previews and partial/failure states share an honest explanation.
6. **F13: retain preview state.** Update mode/scope in one dialog while preserving search, sorting and compatible selections; pending checks must never authorize an old plan.
7. **F14: consistent recovery.** Preview failures now offer fresh retry and npm-specific guidance. Complete the remaining status/automatic-check and native failure matrix, including npm offline/configuration behavior, without weakening native provider semantics.

The broader onboarding, accessibility, repository diagnostics, persistent metadata and scheduling proposals remain in the HTML plan. Prioritize measured bottlenecks and release blockers; preserve the existing provider isolation, cancellation, authentication and shared-declaration safeguards during each change.
