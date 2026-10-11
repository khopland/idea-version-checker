# Release implementation ledger

Updated 2026-10-11. The [HTML release plan](release-plan-2026-10-10.html) records the audit of commit `17c4975`; this ledger tracks implementation after that audit. Development remains `1.3.0-SNAPSHOT` until the release gates are satisfied. A snapshot is intentionally not eligible for release automation.

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

## Gradle ownership indexing (F07)

The generated metadata task now indexes requested coordinates, their source
owners and resolvable configurations once, then reuses identical coordinate /
baseline probes within that source and update mode. Original dependency
attributes, capabilities, constraints, inherited configurations and
version-specific substitutions retain the existing resolution path.

A real IDEA Gradle task-manager parity fixture compared the indexed path with
the previous algorithm: 100 repeated declarations returned identical candidates,
with owner-project visits reduced from **300 to 3** and semantic probes from
**100 to 1**. Two catalog aliases still checked both owners and rejected the
substituted consumer; semantic probes fell from 4 to 2. Existing authenticated,
DSL/catalog, qualifier and capability integration cases also passed. Anonymous
traces expose owner-project, owner-configuration and semantic-probe counts.

Full validation passed: **402 plugin tests in 61 classes**, zero failures/errors/
skips; 13 release and 16 script tests; `check`, `buildPlugin`, project-configuration
validation and all three IDEA verifier targets (10 existing experimental API
uses, no deprecated/scheduled uses). The full Gradle run took 2m 47s. Archive
SHA-256: `c1ef1877ab2e289fef405865ef4d854ccaf21dae0faa5ceb3f2a0509f5745d26`.
Log: `/tmp/version-checker-gradle-index-full.log`.

These are algorithm/parity checks, not native UI latency measurements. Cross-file
aggregation, native scale/typing and the remaining release matrix remain open.

## Phase 3: result retention and save preparation (F09/F10)

The [retention and input-preparation record](result-retention.md) describes the
new budgets, lifecycle policy, regression coverage and remaining thread work.

- Result storage is bounded by 2,048 contexts, 1,024 combined complete/partial
  reports and 1,000,000 weighted payload units. Expiry cleanup physically removes
  payloads and inactive source/generation records; provider refresh removes all
  its bookkeeping. Disposal clears retained reports.
- Eviction retires context identities. Fresh registrations use a never-reused
  generation, so late complete/partial workers and held previews cannot revive an
  evicted result. A 10,000-cycle model soak asserts the bounds throughout.
- Refresh/preview/retry save-path discovery now runs in cancellable background
  work, with only selective document saving on EDT. Gradle init-directory and
  Maven credential-relocation discovery run outside read locks; collecting save
  paths no longer hashes/fingerprints provider inputs. npm collects paths without
  reading configuration bytes or probing a runtime.
- F09 is partially complete: snapshot and quick-fix/apply fingerprint reads still
  need a separate capture/revalidation design. Native slow-disk/typing and heap
  measurements remain open. No release identity or aggregation default changed.

Full validation passed on 2026-10-11: **419 plugin tests in 62 classes**, zero
failures/errors/skips; 13 release and 16 script tests; `check`, `buildPlugin`,
project-configuration validation and all three IDEA verifier targets (10 existing
experimental API uses, no deprecated/scheduled uses). The Gradle run took 2m 17s.
Archive SHA-256: `fc60965a8480bce07330d13e808b3e25d22ea05a722d1a758cdfee2a18078626`.
Exact commands, artifact notes and evidence limits are in the linked record.

## Shared result freshness (F11)

The [candidate-freshness record](candidate-freshness.md) defines the common
quick-fix/preview lease policy and its remaining input-read boundary. Production
Maven/npm/Gradle inspection fixes now require the originating result identity,
refresh generation and original deadline, in addition to their existing input,
text, ignore and shared-consumer checks. Retained hints preserve their original
metadata age; partial observations do not authorize complete previews. A new
partial publication retires descriptors from its previous observation.

Same-object/same-timestamp replacement, refresh, cancellation, eviction and
disposal reject held leases. Quick fixes and bulk guards check cheap authorization
before provider fingerprints, avoiding those reads when the result is already
stale. Existing recovery still offers **Refresh This File**. Live-result external
fingerprint reads remain an F09 follow-up.

Full validation passed on 2026-10-11: **430 plugin tests in 62 classes**, zero
failures/errors/skips; 13 release and 16 script tests; `check`, `buildPlugin`,
project-configuration validation and all three IDEA verifier targets (10 existing
experimental API uses, no deprecated/scheduled uses). The run took 2m 28s.
Archive SHA-256: `eec872c887de4ec7bad0f2228826d7038592b9156e9f295024675b6ec4f160bc`.
The candidate-freshness record preserves the policy and native acceptance gaps.

## Gradle background fingerprint capture (F09)

The [Gradle input-capture record](gradle-input-capture.md) documents the next F09
slice. Gradle discovery, coordinator validation and preview preparation now read
user-home properties and nested init scripts on IO outside IDE read actions,
sharing one capture across linked builds. A final read overlays the latest
unsaved document text and settings; a changed user-home selection retries the
capture. The coordinator uses a suspend validation contract before native work
and publication, leaving other providers' existing semantics intact.

Same-size/same-timestamp disk edits, additions/removals, unsaved external edits
during capture, user-home changes and cancellation have regression coverage.
Synchronous inspection and final edit guards still read current contents, and
reject outside-VFS edits before changing dependency text. Maven/npm external
capture and those synchronous paths remain open; no native latency claim follows
from the thread-boundary and work-count checks.

Full validation passed on 2026-10-11: **436 plugin tests in 63 classes**, zero
failures/errors/skips; 13 release and 16 script tests; `check`, `buildPlugin`,
project-configuration validation and all three IDEA verifier targets (10 existing
experimental API uses, no deprecated/scheduled uses). The run took 1m 53s.
Archive SHA-256: `9b1971289ff0848aa665774e104b6861e0a3b972f08b69cea5800d8cfe82b9c0`.
Commands, archive identity and scope limits are in the linked capture record.

## Remaining release gates

- **F01: finalize the release artifact.** After validation and UX acceptance, choose a new RC such as `1.3.0-rc.1`, finalize a dated changelog entry, verify the packaged descriptor/change notes/checksum, then test the exact archive in IDEA. Promote with a new stable version after EAP acceptance. No release is published by this implementation batch.
- **F03: native npm workflows.** Current-IDE editor/preview/local/workspace edit and authentication-recovery checks passed. Complete minimum-IDE coverage, ignore/recovery, cancellation and offline/runtime/configuration cases; preserve those gaps in the [native record](native-validation-2026-10-10/phase2.md).
- **F04: supported-IDE Gradle UX.** Minimum-IDE Groovy diagnostics/local fix and current-IDE Groovy/shared Kotlin catalog workflows passed. Complete the per-version DSL/catalog and repeated performance matrix. Binary compatibility alone does not close this gate.
- **Native scale and failure matrix.** Measure small projects, 100-module projects and dense edits on named hardware; record warm/cold states, authentication failures, offline behavior, cancellation, imports, plugin unload and typing/input delays. Retain completed/incomplete sample counts and archive hashes. The HTML plan's latency budgets remain proposed targets.

## Next engineering work

1. **F06/F07: Gradle query batching.** Ownership indexing is enabled. The [bounded cross-file prototype](gradle-scan-prototype.md) is implemented behind a development-only JVM switch, with native resolver work/parity and cancellation/freshness regressions. Keep aggregation off by default until controlled native UI scale/typing and failure measurements pass; mode sharing and refresh-flag changes remain deferred.
2. **F09: move remaining external input reads off the EDT.** Save-path preparation now uses background discovery without fingerprinting; Gradle background discovery/validation/preview captures external disk inputs outside read locks. Complete Maven/npm capture and synchronous inspection/edit revalidation; keep fresh unsaved/external-input guards before apply and profile slow disks/large init trees.
3. **F10: validate bounded state in native workloads.** Result/source/generation budgets, expiry cleanup and late-worker regressions are implemented. Complete native import/rename/unload and heap observations; the model soak establishes map bounds, not retained heap size.
4. **F11: native acceptance of shared freshness.** Common result leases and held-fix regressions are implemented. Validate expiry/refresh recovery with actual Alt+Enter descriptors on the supported IDEs; report-level partial leases currently retire descriptors on every new partial observation.
5. **F12: explicit coverage.** Track checked, ignored, unsupported and manual-review declarations so status, empty previews and partial/failure states share an honest explanation.
6. **F13: retain preview state.** Update mode/scope in one dialog while preserving search, sorting and compatible selections; pending checks must never authorize an old plan.
7. **F14: consistent recovery.** Preview failures now offer fresh retry and npm-specific guidance. Complete the remaining status/automatic-check and native failure matrix, including npm offline/configuration behavior, without weakening native provider semantics.

The broader onboarding, accessibility, repository diagnostics, persistent metadata and scheduling proposals remain in the HTML plan. Prioritize measured bottlenecks and release blockers; preserve the existing provider isolation, cancellation, authentication and shared-declaration safeguards during each change.
