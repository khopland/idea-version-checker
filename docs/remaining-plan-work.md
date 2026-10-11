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

## Maven background external capture (F09)

Settings/credential relocation discovery and saved file-state reads now run on IO
outside read actions for Maven discovery, coordinator validation and preview
preparation. One immutable capture covers all imported module paths and shared
settings; the final read overlays current POM/editor/import state. Changed settings
or distribution paths discard the capture and retry. Existing synchronous apply
validation is retained. Focused Maven/coordinator/lifecycle tests passed, including
new thread-boundary, relocated-credential, path-change, unsaved-sibling and
cancellation cases. Log: `/tmp/version-checker-maven-capture-focused.log`.

At this stage Maven retained its saved size/mtime identity, plus unsaved document
stamps. The [external guard follow-up](external-input-guards.md) below replaces
that saved identity with content fingerprints.
Full native integration and compatibility validation will be repeated after the
remaining provider capture work.

## npm background external capture (F09)

npm discovery, result validation, runtime-context preparation and preview
preparation now capture registry/runtime configuration, ancestor manifest bytes,
other-manager file existence and runtime file state on IO outside read actions.
Captures are shared across selected workspaces; latest unsaved/VFS inputs are
added in the final read. Changed workspace roots, environment or runtime paths
retry capture. Saved-content hashing still rejects outside-VFS configuration edits;
final synchronous edit guards remain in place.

Five new real-disk cases cover thread boundaries/shared reads, context parity,
external configuration/manager changes, unsaved edits during capture, runtime
selection changes and cancellation. All **445 plugin tests in 64 classes** passed
with all native integration flags, zero failures/errors/skips, plus packaging and
project-configuration validation (1m 40s). Log:
`/tmp/version-checker-provider-capture-full.log`. IDE verification will run again
after the remaining engineering changes. Synchronous inspection/edit reads remain
an F09 gap; no slow-disk/native latency claim is made.

## Stable review window and source diffs (F13)

Production mode/scope changes and Refresh now replace results in the existing
review window. Exact declaration/old-version/target choices survive compatible
rechecks and temporary scope exclusion; new or changed targets start unchecked.
Search, sorting, focus and scroll stay in the existing controls. Starting a
recheck immediately retires held plans, and failed/cancelled checks keep Apply
disabled while offering Refresh for the latest selection. Rejected apply refreshes
in place. Superseded requests and disposed windows cannot replace current results.

A highlighted row can open its declaration or a read-only full-file before/after
diff. Shared Maven properties and Gradle catalog owners preview the actual owner
edit. npm bulk edits use captured document replacements so the applied text matches
the diff and retains existing JSON spacing. Existing atomic validation and grouped
undo remain in place. Native keyboard/focus, diff-window and supported-IDE usability
acceptance are still required; headless UI/model tests do not establish those gates.

Full validation passed: **453 plugin tests in 65 classes**, zero failures/errors/
skips, all Maven/npm/Gradle native integration flags, `check`, `buildPlugin` and
project-configuration validation (1m 37s). New tests cover retained choices,
superseded plans, failure/rejected-apply recovery in one window and source/diff
parity for Maven, npm and shared Gradle edits. Log:
`/tmp/version-checker-review-full.log`. Compatibility verification will be repeated
after the next engineering batch.

## Diagnostic and paused-plan recovery (F14/F11)

Displayed/copied failure details now redact URL user information and queries,
authentication headers, common token/password assignments, Maven credential XML,
private keys and local paths before samples are truncated or retained. Repository
hosts, exception types and native error codes remain available. Retry identities
keep exact source paths and continue to target only failed files. This is a display
boundary; native repository configuration and resolver semantics are unchanged.

Failed status tooltips use the same provider-owned safe advice as previews,
including npm offline-cache and authentication outcomes, without starting queries.
A held bulk plan now rejects paused checks before running provider input guards;
resume permits the still-current plan. Native keyboard/recovery and the broader
failure matrix remain open.

Full validation passed with **459 plugin tests in 66 classes**, zero failures/
errors/skips, all native integration flags, packaging, project-configuration
validation and all three IDEA verifier targets (10 existing experimental API
uses, no deprecated/scheduled-for-removal uses). The run took 3m 14s; log:
`/tmp/version-checker-diagnostics-full.log`. A final focused run also checks paths
containing spaces. Compatibility verdicts validate APIs, not native usability.

## Explicit declaration coverage (F12)

Provider reports now carry checked, skipped, manual-review and failed outcomes for
each recognized declaration. Ignore filtering adds a presentation-only ignored
outcome while retaining the raw report and original age. npm aliases still share
metadata queries; explicit deprecation policies and local/peer/complex selectors
are not counted as queried. Maven records inactive profiles, reactor parents and
non-fixed/missing versions, plus fast/platform exclusions; native batch and fallback
category failures identify affected declarations. Gradle records recognized
unsupported forms and native manual-review outcomes without changing resolution.

Status and prepared reviews share these outcomes. Empty reviews distinguish zero
updates among completed checks from zero checked declarations with exclusions.
Running coverage counts fresh completed units, pending units and known exclusions;
retained hints do not count as completions of a new check. Whole-project coverage
uses physical source identity to count shared Maven consumers once. Unreported
provider coverage stays unknown rather than becoming a fabricated zero or total.
Recognized unsupported inputs and reasons remain subject to result payload budgets.

Counts cover recognized declarations in successfully discovered supported files;
unparsed Gradle forms and wholly excluded managers/files are not a claimed audit.
Native readability, screen-reader behavior and the remaining supported-IDE decision
matrix still require acceptance.

Full validation passed: **471 plugin tests in 67 classes**, zero failures/errors/
skips, all native integration flags, packaging and project-configuration validation
(1m 53s). All three IDEA verifier targets passed for the same production archive,
with 10 existing experimental API uses and no deprecated/scheduled uses. Release
and script suites passed **13 + 16 tests**. The Maven priority regression now checks
the early exclusion publication separately from its native batches/session.
Logs: `/tmp/version-checker-coverage-final-full.log`,
`/tmp/version-checker-coverage-full.log` (verifier plus the corrected test failure),
and `/tmp/version-checker-coverage-maven-priority.log`.
Archive SHA-256: `92af330f135c3ce032b7f1f4d21eca08b610449ec13c8dc1cfa3676ce927e2f9`.

## Fresh external edit guards (F09/F11)

The [external input guard record](external-input-guards.md) documents this
follow-up. Maven saved settings, relocated credentials and ancestor `.mvn`
configuration now use cancellable streamed content fingerprints. Same-size,
same-timestamp rewrites reject old results, held local/shared quick fixes and
prepared edits without a VFS refresh. Current unsaved document stamps remain
part of the fingerprint; unchanged timestamp-only touches retain identity.

Gradle final bulk validation shares one fresh user-home capture across linked
roots and rereads it on every validation. A twelve-root regression reads nine
external files nine times during an accepted apply, preserving atomic rejection
when external bytes change. This establishes work counts, not native latency.

Synchronous inspection/edit disk reads and native slow-disk/typing acceptance
remain open. Maven hashing adds content reads to those synchronous paths; this
batch closes an input-identity gap rather than claiming that F09 is complete.

Final validation passed with **475 plugin tests in 67 classes**, zero failures,
errors or skips, all native integration flags, packaging, project-configuration
validation and all three IDEA verifier targets (ten existing experimental API
uses per target, no deprecated/scheduled uses). The run took 2m 3s; release and
script suites passed **13 + 16 tests**. The linked record contains commands and
archive identity. Log: `/tmp/version-checker-final-input-full.log`.

## Held fixes during partial checks (F11)

The [partial-fix freshness record](partial-fix-freshness.md) refines the earlier
report-level policy. Fixes rendered from an exact partial snapshot bind to their
contributing declaration tokens. Unrelated completions keep a held fix usable;
reobserved, removed or replaced contributors retire it. Shared Maven properties,
npm workspace aliases and Gradle catalog values guard their represented consumers.
Unknown or remapped scopes keep whole-observation guards.

Refresh, cancellation, expiry, owner/snapshot replacement, eviction, disposal
and completion retire the partial lineage. Scope binding cannot broaden an
existing authorization, revive removed rows, renew metadata age or authorize a
complete preview. Token storage participates in the existing payload budget;
Maven guard indexing/reuse remains local to an inspection pass.

Native Alt+Enter and focus acceptance remain open. This implements the previously
listed per-candidate refinement without claiming completion of native F11 gates.

Final validation passed with **488 plugin tests in 67 classes**, zero failures,
errors or skips, all native integration flags, packaging, project-configuration
validation and all three IDEA verifier targets (ten existing experimental API
uses per target, no deprecated/scheduled uses). The run took 1m 58s; release and
script suites passed **13 + 16 tests**. The linked record preserves scope limits,
commands and archive identity. Log: `/tmp/version-checker-scoped-lease-full.log`.

## Inspection and edit captures (F09)

The [inspection/edit capture record](inspection-edit-inputs.md) closes the
remaining production synchronous external-input paths for Maven, npm and Gradle.
Visitors use bounded, short-lived background captures; the status widget also
prepares outside read locks. Every quick fix and review invocation obtains a new
saved-input capture, then rechecks current IDE state and result authorization
before the atomic edit command. Display captures cannot authorize edits.

Native current-IDE testing found and corrected two additional issues: selected
document saving lacked write-intent access on accessibility-dispatched Apply,
and unsupported Gradle inspection inputs created a highlighting feedback loop.
The [2026-10-11 native record](native-validation-2026-10-11/README.md) distinguishes
the failing archives from the corrected archive and preserves the remaining
minimum-IDE npm and controlled performance gaps.

The subsequent [100-module Gradle run](native-validation-2026-10-11/gradle-large.md)
found duplicate discovery through macOS path aliases. Its failed trace and
profiling/tooling limits are retained. The correction passed the native count
retest (10,000 declarations, 101 sources) and **494 plugin tests in 68 classes**,
plus 13 release and 16 script tests, packaging and all three verifier targets.
Native automation still timed out inspecting the 10,000-row review, so the
interaction/performance scale gate remains open.

Final validation passed **493 plugin tests in 68 classes**, 13 release tests and
16 script tests, all native integration flags, packaging/project-configuration
checks and all three verifier targets. The compatible archive has 14 experimental
API usages per target (ten existing progress usages and four write-intent
references), with no deprecated/scheduled usages. Its exact hash and final-archive
native Apply/undo and idle-smoke outcomes are in the linked records.

This completes the explicit external disk-read boundary work. Slow-disk,
large-init-directory, VFS/PSI cost, native typing/EDT and retained-heap acceptance
remain open. No aggregation default or release identity changed.

## Sorter warning and review findings follow-up

The [11 October fix record](plan-fixes-2026-10-11.md) closes the reproduced Swing
sorter warning and all three findings from the linked project review: exact Gradle
targets now pass source-configuration validation, unknown Gradle ownership needs
review instead of counting as checked, and Maven excludes dev/EAP candidates.
New regressions reproduced the sorter, Gradle-target and Maven-qualifier failures
before their fixes and pass afterward; adapter coverage also verifies R02.

The [native 10,000-declaration follow-up](native-validation-2026-10-11/gradle-large.md#sorter-and-interaction-follow-up)
passed sorting, scrolling, filtering, keyboard selection, Apply/save across 100
files and one-command undo with exact byte restoration. A controlled delayed
Refresh was cancelled without a preview or edit endpoint. The tested snapshot
archive is `1fb04a19f48ff67dcda8be92c05b53d5cc2e24b179c8e14f2286e4694266c12a`.
Validation passed 498 plugin tests, 13 release tests, 16 script tests and all three
verifier targets, retaining the 14-usage experimental API baseline.

Next prioritize controlled typing/EDT/write and slow-disk measurements, retained
heap/15-minute idle observations, and the remaining supported-IDE and
accessibility matrix. These single functional checks do not close performance
acceptance. Aggregation stays off by default; minimum-IDE npm remains blocked,
and RC/EAP release gates remain open.

## Remaining release gates

- **F01: finalize the release artifact.** After validation and UX acceptance, choose a new RC such as `1.3.0-rc.1`, finalize a dated changelog entry, verify the packaged descriptor/change notes/checksum, then test the exact archive in IDEA. Promote with a new stable version after EAP acceptance. No release is published by this implementation batch.
- **F03: native npm workflows.** Current-IDE editor/preview/local/workspace edit and authentication-recovery checks passed. Minimum-IDE npm is **blocked**: the isolated IDEA 2025.3 profile disables Ultimate/JavaScript support, and the user confirmed no licensed installation/profile is available. Complete the remaining ignore/recovery, cancellation and offline/runtime/configuration cases; preserve those gaps in the [native record](native-validation-2026-10-11/README.md).
- **F04: supported-IDE Gradle UX.** Minimum-IDE Groovy diagnostics/local fix and current-IDE Groovy/shared Kotlin catalog workflows passed. Complete the per-version DSL/catalog and repeated performance matrix. Binary compatibility alone does not close this gate.
- **Native scale and failure matrix.** Measure small projects, 100-module projects and dense edits on named hardware; record warm/cold states, authentication failures, offline behavior, cancellation, imports, plugin unload and typing/input delays. Retain completed/incomplete sample counts and archive hashes. The HTML plan's latency budgets remain proposed targets.

## Next engineering work

1. **F06/F07: Gradle query batching.** Ownership indexing is enabled. The [bounded cross-file prototype](gradle-scan-prototype.md) is implemented behind a development-only JVM switch, with native resolver work/parity and cancellation/freshness regressions. Keep aggregation off by default until controlled native UI scale/typing and failure measurements pass; mode sharing and refresh-flag changes remain deferred.
2. **F09: validate the completed input boundary in native workloads.** Background external capture now covers discovery, validation, previews, inspections and per-invocation edits for all three providers. Fresh saved inputs and final unsaved/import/settings/result guards remain required before Apply. Profile slow disks, large init trees and live VFS/PSI work; see the [capture record](inspection-edit-inputs.md).
3. **F10: validate bounded state in native workloads.** Result/source/generation budgets, expiry cleanup and late-worker regressions are implemented. Complete native import/rename/unload and heap observations; the model soak establishes map bounds, not retained heap size.
4. **F11: native acceptance of shared freshness.** Common result leases and held-fix regressions are implemented. Validate expiry/refresh recovery with actual Alt+Enter descriptors on the supported IDEs; declaration-scoped partial leases now preserve held fixes across unrelated completions, while unknown/remapped scopes retain conservative report-level guards.
5. **F12: native coverage acceptance.** Explicit provider outcomes, matching status/review counts, narrow-scope reasons, partial/failure totals and unknown-coverage handling are implemented. Validate readability/accessibility and document recognized-form limits; excluded managers and unparsed forms are not counted as an audit.
6. **F13: native review acceptance.** Stable in-window refresh, compatible review choices, pending-plan rejection and source/diffs are implemented. Validate keyboard/focus, scrolling, diff windows and failed-scope recovery on the supported IDEs.
7. **F14: native recovery acceptance.** In-window retry, npm preview/status guidance, sanitized displayed diagnostics and paused-plan guards are implemented. Complete the automatic/status and native failure matrix, including npm offline/configuration behavior, without weakening native provider semantics.

The broader onboarding, accessibility, repository diagnostics, persistent metadata and scheduling proposals remain in the HTML plan. Prioritize measured bottlenecks and release blockers; preserve the existing provider isolation, cancellation, authentication and shared-declaration safeguards during each change.
