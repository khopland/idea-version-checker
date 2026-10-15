# Project review — 14 October 2026

Reviewed commit: 7642e16ec482f0cfc91d7c51cd99a64439a4e544 (Version Checker 1.3.0).

The plan is `project-review-plan.html`. Hosted preview: https://plan-api.k8r.no/p/jrXzOA6WTYPcj2aa5rvBq26U (expires 21 October 2026). Production code and existing tests were not edited during the original review. The implementation update below records subsequent changes.

## Validation

- Default run: `./gradlew test verifyPluginProjectConfiguration --console=plain`.
  503 tests completed; 502 passed, one failed. The project configuration task completed.
- Failure reproduced independently: `BuildCoordinatorTest.testDisablingDuringInspectionDiscardsLateResultAndAllowsResume`.
  The checkbox lookup at line 178 searches for “Check for newer dependency”, while settings uses “Enable version checks”.
  It throws NoSuchElementException before exercising cancellation.
- Six observation probes passed in an initial focused run and a final isolated run (6 tests, no failures/errors/skips).
  The final buildSrc probe changes AND SAVES the Kotlin source before checking freshness and applying the held plan.
- Native integration: 17 passed, no failures/errors/skips: MavenSettingsIntegrationTest 7,
  NpmRegistryIntegrationTest 4, GradleRepositoryIntegrationTest 6.
- Release automation Python tests: 13 passed.
- Performance/fixture Python tests: 19 passed.
- `./gradlew verifyPlugin --console=plain`: successful. IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3:
  all verdicts Compatible; each reports one deprecated API usage and 14 experimental API usages.
- Native npm 10.9.8 reproduction uses temporary manifests, an empty temporary cache, offline mode,
  package-lock-only and ignore-scripts. Matching direct dependency/override specs reach metadata lookup
  (ENOTCACHED with an empty cache). Changing only dependency alpha 1.2.3 to 1.2.9 gives EOVERRIDE:
  “Override for alpha@1.2.9 conflicts with direct dependency”. No lifecycle scripts run.

Commands:

```sh
./gradlew test --tests '*MavenSettingsIntegrationTest' --tests '*NpmRegistryIntegrationTest' --tests '*GradleRepositoryIntegrationTest' -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true --console=plain
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B -m unittest discover -s scripts -p 'test_*.py'
./gradlew verifyPlugin --console=plain
```

## Observation probes

`ProjectReviewProbeTest.kt.txt` contains six targeted probes. They assert PRESENT defects and are evidence,
not desired-behavior regression tests. They were removed from the active source set after review.
To reproduce the original findings, use a separate checkout of reviewed commit `7642e16`, then temporarily copy this file to:

`src/test/kotlin/io/github/khopland/versionchecker/ProjectReviewProbeTest.kt`

The current implementation changes the observed behaviors and internal APIs; these probes are historical evidence, not current acceptance tests.

Then run `./gradlew test --tests '*ProjectReviewProbeTest' --console=plain` and remove only that temporary file.
For actual fixes, invert the relevant assertions and add native scenarios where indicated in the plan.
The buildSrc test uses a synthetic candidate to isolate the input guard; it is not a native resolution exploit demonstration.
The npm directory exclusion probe verifies rejection; the Gradle analogue is source inspection.

## Limits

This is a broad source review with focused reproduction, not proof of all runtime states.
An interactive IDEA usability/accessibility audit and production remote-repository benchmarks were not performed.
The generated HTML is self-contained, readable without JavaScript and designed for desktop/mobile/printing.

## Implementation update — 14 October 2026

The first implementation slice addresses F1–F4. Its original PRs are merged; #23 promotes the fixes that landed in the former baseline branch into main. No release has been made.
The hosted plan is updated in place at the same stable URL with the first-slice history in version 3 ([immutable revision](https://plan-api.k8r.no/p/jrXzOA6WTYPcj2aa5rvBq26U/v/3)); it expires 21 October 2026. The continuation below addresses F5–F7 and S5. Broader phase 2 architecture and phases 3–4 remain open.

Original PR history (all merged; see #23 below for promotion into main):

1. [#19: settings/Maven baseline and F3 test repair](https://github.com/khopland/idea-version-checker/pull/19) — preserves the previously unpublished local commit `7642e16` and includes stable checkbox identity with a wording-change regression (`111c63a`). The superseded standalone #18 is closed.
2. [#20: F1 npm override guards](https://github.com/khopland/idea-version-checker/pull/20) — manual review across local/workspace/bulk edits, safe own references preserved, cross references guarded (`f200640`).
3. [#21: F2 Gradle build-logic inputs](https://github.com/khopland/idea-version-checker/pull/21) — sources/resources, literal included builds, unsaved changes, additions and coverage limits (`d1f8ba8`).
4. [#22: F4 typed catalog parsing](https://github.com/khopland/idea-version-checker/pull/22) — valid siblings retained, anchored review diagnostics, malformed shared owners blocked (`a54ce7c`), plus this plan and validation update.

Implementation validation on the combined code:

- `./gradlew test verifyPluginProjectConfiguration --console=plain`: 521 tests passed before the final literal-prefix guard regression was added.
- Final combined run with all native integration flags: **541 tests passed**, no failures, errors or skips. This includes 522 default tests and 19 native tests (Maven 7, npm 5, Gradle 7). Project configuration verification passed.
- New native Gradle fixture changes real buildSrc Java policy: the held edit is rejected, and a fresh native check rejects the formerly allowed target.
- New npm fixture uses offline, package-lock-only, ignore-scripts and an empty temporary cache: matching cross-reference specs reach ENOTCACHED; changing the referenced dependency produces EOVERRIDE. This verifies npm's override validation, not a successful installation.
- Plugin Verifier passed for IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3. Each target reports the same one deprecated and 14 experimental API usages as the review baseline.
- Release automation Python tests: 13 passed. Performance/fixture Python tests: 19 passed.
- `git diff --check`: passed.

Final native command (SDKMAN JDK 21):

```sh
JAVA_HOME=/Users/kristoffer/.sdkman/candidates/java/21.0.12+1.1-tem ./gradlew test verifyPluginProjectConfiguration -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true --console=plain
```

Implementation limits:

- npm guards are deliberately conservative. Automatic grouped dependency/override edits remain deferred.
- Static Gradle discovery cannot infer every file read by arbitrary executable build code. Known computed included-build and external/computed applied-script paths require review; included-build trees are captured conservatively. Ordinary application sources and generated build output remain excluded. No performance improvement is claimed.
- Malformed typed catalog entries use existing skipped coverage and the bulk Needs review list. Syntactically invalid TOML retains its existing empty-result behavior; a broader diagnostic model remains future work.
- The original observation probes assert the old defects. They remain historical evidence and are not acceptance tests for the corrected implementation.

## Continuation — F5–F7 and Gradle probe extraction

Latest hosted plan: version 4, at the same stable URL ([immutable revision](https://plan-api.k8r.no/p/jrXzOA6WTYPcj2aa5rvBq26U/v/4)); expires 21 October 2026.

- [#23: promote approved first-slice fixes into main](https://github.com/khopland/idea-version-checker/pull/23). Original #20–#22 merged into the former baseline branch after #19 had merged, so a promotion is required. Merge this first.
- [#24: F5 project ownership exclusions](https://github.com/khopland/idea-version-checker/pull/24), commit `91109b1`. Selected roots and enclosing generated names stay checkable; generated descendants stay excluded. Declared npm workspaces retain root overrides across imported content roots. An actual outside workspace root causes checks and edits to be declined; unrelated ancestor workspaces do not block independent projects.
- [#25: F6 review coverage and F7 numeric classification](https://github.com/khopland/idea-version-checker/pull/25), commit `f7b9d9c`. Classifier/extension strings, Groovy named/bracket maps and Kotlin mapOf retain anchored, noneditable review declarations. Shared BigInteger parsing fixes overflow severity while preserving qualifiers, service packs and channels.
- [#26: S5 packaged Gradle probe and plan update](https://github.com/khopland/idea-version-checker/pull/26), commit `a22b7b1` plus this documentation. Requests, paths, mode, task and patterns are JSON data; the response protocol rejects missing or incomplete results. Resolver branches and target validation remain native.

All follow-up PRs target main directly and can merge in any order after #23. Before the promotion merges, their diffs include the pending first slice; afterward each shows only its own changes. No branch retargeting is required.

Validation on the combined code:

- Focused parser, ownership, adapter and protocol run: 80 tests passed before adding the two outside-workspace cases.
- Final full/native run: **561 tests passed**, no failures, errors or skips: 542 default tests plus 19 native tests (Maven 7, npm 5, Gradle 7). Project configuration verification passed.
- Native Gradle fixtures preserve indexed/legacy result parity, owner/configuration/attribute/capability/constraint/substitution checks, service-pack selection, changed buildSrc policy rejection, and bounded invocation counts.
- Twelve dedicated ownership regressions cover selected/enclosing generated names, genuine generated descendants, current-file actions, nested projects, workspace-root overrides, outside workspace roots and symlinks. Their discovery helper waits for indexing; production still intentionally returns no npm files in dumb mode.
- Four protocol tests cover JSON data escaping, version/result/declaration rejection and packaging of the script in the distributed plugin ZIP.
- A separate temporary npm 10.9.8 fixture confirmed `npm prefix` from a member returns its parent workspace and an offline, package-lock-only, ignore-scripts command uses the parent .npmrc. The empty cache produced ENOTCACHED for a fake registry; no scripts, network requests or package installation occurred.
- Plugin Verifier passed for IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3. Each target retains one deprecated API usage and 14 experimental API usages, matching the previous slice.
- `git diff --check`: passed.

The final native command is the same SDKMAN JDK 21 command recorded above. No performance improvement is claimed. Broader common update planning, provider input inventory design, synchronization/tool-window workflows and future product scope remain open in the plan. Classifier/map coverage adds recognition only; arbitrary helper calls, computed map objects and add(configuration, map) remain outside that added scope.

## Architecture continuation — 15 October 2026

The user clarified latest main as the base; the slice starts at `1bde219`, with #19–#26 already merged. All three new PRs target main directly and can merge independently in any order.

- [#27: S1 npm prepared updates](https://github.com/khopland/idea-version-checker/pull/27), commit `ad96f90`. One provider-owned preparation function supplies local, workspace and bulk edits, blockers, physical target identity and follow-up. Captured edits are reused by local fixes. Unsupported or inconsistent synthetic baselines remain review-only. Declaration/workspace result lease scopes, settings guards, input validation, operator/alias behavior, no downgrades and atomic undo remain intact.
- [#28: S4 Gradle project input inventory](https://github.com/khopland/idea-version-checker/pull/28), commit `7444b24`. Typed inventory keys and one tracked-input selection policy drive saving, unsaved checks, fingerprint input selection and fresh physical validation. Canonical internal module aliases do not turn application sources into build logic. External home capture, missing markers, cancellation and manual-review limits remain provider-owned.
- [#29: S2 shared edit boundary](https://github.com/khopland/idea-version-checker/pull/29), commit `4b48044`, plus this documentation. Named authorization, capture and final-validation types distinguish the shared apply stages while keeping provider callback signatures compatible. Authorization is still checked before capture and inside the atomic write command.

Validation on the combined implementation:

- **572 tests passed**, no failures, errors or skips: 553 default tests plus 19 native integration tests (Maven 7, npm 5, Gradle 7). Project configuration verification passed.
- Three npm regression methods cover 13 local/bulk eligibility cases, blocked workspace members with safe independent edits, and wrong physical targets. Existing workspace lease, scaling, alias, stale-input, no-downgrade, undo and native registry tests pass.
- Six Gradle inventory regressions cover saving/unsaved selection, physical additions before VFS refresh, generated boundaries, symlink identities, unobserved imported roots and canonical internal modules with application sources. Existing same-stamp, buildSrc/native policy, input capture, scaling and bounded native invocation checks pass.
- Edit-guard regressions verify background capture outside read/write access, validation after write access is acquired, and failed provider capture preventing all edits without evaluating earlier captured guards. Existing combined selection, result authorization, cancellation and undo tests pass.
- Two new inventory fixture assumptions were corrected before the final run: fixture-filesystem lookup now mirrors the adapter, and unobserved VFS roots use a controlled resolver rather than a lookup that can populate VFS.
- Plugin Verifier passed for IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3. Each target retains one deprecated API usage and 14 experimental API usages, matching the previous slice.
- `git diff --check`: passed.

The hosted plan is updated in place as version 5 ([immutable revision](https://plan-api.k8r.no/p/jrXzOA6WTYPcj2aa5rvBq26U/v/5)); original expiry remains 21 October 2026. Historical validation above remains unchanged.

Remaining scope: S1 Maven/Gradle preparation migration, S2 provider callback/ownership migration, other providers' S4 inventories and broader input-role modeling, S3 orchestration/controller work, S6 proven cache mechanics, and the product roadmap. This slice does not claim a standalone engine, generic native resolution, exhaustive Gradle input coverage or a measured performance improvement.


## Provider preparation and cache contracts — 15 October 2026

The continuation starts from main `6ebfd63`, after #27–#29 merged. These PRs target main independently:

- [#30: S6 shared cache lifecycle contracts](https://github.com/khopland/idea-version-checker/pull/30), commit `d9730a2`. Six inherited lifecycle tests run against both actual Maven and npm caches through a test-only adapter, covering caller cancellation, retry, generation isolation, completion TTL, eviction and disposal. Provider-specific batching/session/payload/budget tests remain; production caches are unchanged.
- [#31: S1 Gradle preparation and this plan update](https://github.com/khopland/idea-version-checker/pull/31), commit `9919f4e` plus documentation. One parsed target group supplies inspection and bulk eligibility, affected declarations, captured edit and follow-up. Native notices, failed reports, duplicate/missing/conflicting consumers, unsupported selectors, physical identity mismatches and downgrades block edits. Existing lease/input/thread/undo guards remain.
- [#32: S1 Maven preparation](https://github.com/khopland/idea-version-checker/pull/32), commit `2b39db9`. Consumer replacement agreement, notices/blockers and captured XML edits are provider-owned and shared. Cached raw-property checks protect local shared edits, including transitive aliases, attributes and nested references, with imported/profile ownership resolution. Active-profile shadowing remains supported. Explicit local overrides remain separate.

Validation on the combined implementation:

- 583 tests passed: 564 default tests plus 19 native integration tests (Maven 7, npm 5, Gradle 7), with no failures, errors or skips. Project configuration verification passed. Existing native request-count and bounded-invocation checks pass.
- Plugin Verifier passed for IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3. Each target retains one deprecated API usage and 14 experimental API usages, matching the previous slice.
- Focused checks passed: 29 metadata cache tests, 49 Gradle adapter/parser/inspection/preparation tests, and 145 Maven/bulk tests. Final added Maven parity assertions passed separately before the combined run. The Gradle matrix covers 16 safe/blocked cases; notice ordering and physical sibling identity have separate regressions.
- Review caught a transitive alias-usage gap and an active-profile shadowing regression before publication; both were corrected and covered. Initial Gradle test compilation exhausted its default heap; the final build uses a 2 GB Gradle heap.
- `git diff --check`: passed.

Final command:

```sh
JAVA_HOME=/Users/kristoffer/.sdkman/candidates/java/21.0.12+1.1-tem ./gradlew test verifyPluginProjectConfiguration verifyPlugin -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true -Dorg.gradle.jvmargs=-Xmx2g --console=plain
```

The hosted plan is updated in place as version 6 ([immutable revision](https://plan-api.k8r.no/p/jrXzOA6WTYPcj2aa5rvBq26U/v/6)); original expiry remains 21 October 2026. Earlier validation remains historical.

Remaining scope: complete Maven local/bulk alias and inherited scope parity (bulk intentionally remains narrower); S2 provider callback/ownership migration; S3 orchestration/controller work; remaining S4 provider inventories and input-role modeling; and S6 production cache consolidation only if the shared contracts support a clean abstraction. The broader product roadmap remains proposed. No performance improvement is claimed.


## Provider guards and preview request control — 15 October 2026

The continuation starts from main `77a5934`, after #30–#32 merged. Both PRs target main independently:

- [#33: S2 provider input guards](https://github.com/khopland/idea-version-checker/pull/33), commit `cf1bb5d`. `BuildSystemAdapter.prepareEditValidation` and saved-input validation return `CapturedEditInputs`; quick fixes and bulk plans store `EditInputCapture`. Background IO/read/write boundaries, result authorization, partial report scopes, cancellation and atomic undo stay intact. Live authorization callback signatures remain compatible and are not claimed migrated.
- [#34: S3 preview request controller and this plan](https://github.com/khopland/idea-version-checker/pull/34), commit `85ebf9a` plus documentation. A dialog-owned controller tracks request serials, cancels old work, rejects superseded results/failures and restores review after current failure/cancellation. Explicit request/result models carry mode, scope, refresh, prepared preview, matching adapters and availability. The service retains native preparation and apply/save responsibilities.

Validation on the combined implementation:

- 587 tests passed: 568 default tests plus 19 native integration tests (Maven 7, npm 5, Gradle 7), with no failures, errors or skips. Project configuration verification passed. Existing native request-count and bounded-invocation checks pass.
- Plugin Verifier passed for IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3. Each target retains one deprecated API usage and 14 experimental API usages, matching the previous slice.
- 85 focused shared-plan/provider/coordinator tests passed for guard migration. Thirteen preview tests passed: four new race/recovery tests, five dialog-lifetime tests and four failure-recovery tests. New coverage asserts IO loading, EDT delivery, interaction tracing, superseded failure while a successor is pending, cancellation/retry and disposal with late noncancellable work.
- Independent review found no safety blockers. The typed migration merged cleanly with Maven preparation; final validation includes all merged #32 behavior.
- The first combined run exposed stale incremental test bytecode calling the pre-migration `BulkUpdatePlan` constructor. Bytecode inspection confirmed the mismatch; that invalid run was stopped, then all compile/instrument/test tasks were rerun with Kotlin incremental compilation disabled. No production workaround was added. Plugin Verifier completed successfully during the initial run.
- `git diff --check`: passed.

Fresh full/native command:

```sh
JAVA_HOME=/Users/kristoffer/.sdkman/candidates/java/21.0.12+1.1-tem ./gradlew test verifyPluginProjectConfiguration -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true -Pkotlin.incremental=false -Dorg.gradle.jvmargs=-Xmx2g --rerun-tasks --console=plain
```

The hosted plan is updated in place as version 7 ([immutable revision](https://plan-api.k8r.no/p/jrXzOA6WTYPcj2aa5rvBq26U/v/7)); original expiry remains 21 October 2026. Earlier validation remains historical.

Remaining scope: Maven local/bulk alias and inherited scope parity; further result authorization/ownership typing; S3 CheckExecutor extraction and complete preview state/model separation; remaining S4 provider inventories and input roles; conditional S6 production cache consolidation; and the product roadmap. This slice does not claim complete S2/S3 migration or measured performance improvements.


## Maven property safety and npm input inventory — 15 October 2026

The continuation starts from main `4131239`, after #33–#34 merged. Both PRs target main independently:

- [#35: S4 npm resolution input inventory](https://github.com/khopland/idea-version-checker/pull/35), commit `955cee6`. One path-only typed inventory drives capture, saving, unsaved checks, fingerprint inputs and resolution contexts. Configuration/manifest content, other-manager existence markers and runtime identity remain distinct. Original disk/editor namespaces, missing markers, user/global config paths, configured runtime stamps, cancellation and layout retries remain intact. Workspace declaration discovery stays provider-owned.
- [#36: S1 Maven property safety and this plan](https://github.com/khopland/idea-version-checker/pull/36), commit `796cd09` plus documentation. Local and bulk preparation share indexed property-usage and ownership checks. Safe exact local alias chains can be updated in bulk by changing their literal owner; alias/reference text stays intact. Every imported consumer context requires selected results and the same replacement, including same-coordinate inherited consumers. Explicit local overrides retain their operation and leases; inherited-only bulk edits remain review-only.

Validation on the combined implementation:

- 592 tests passed on the full/native rerun: 573 default tests plus 19 native integration tests (Maven 7, npm 5, Gradle 7), with no failures, errors or skips. Project configuration verification passed. Existing native request-count and bounded-invocation checks pass.
- Plugin Verifier passed for IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3. Each target retains one deprecated API usage and 14 experimental API usages, matching the previous slice.
- Focused checks passed: 40 npm tests and 71 Maven/shared-plan tests. Three new npm regressions cover runtime config across save/unsaved/capture, marker existence-only semantics and missing root/runtime paths. Maven regressions cover two-hop alias agreement/conflict/missing updates, preserved references, unknown transitive uses and unselected inherited contexts.
- Review caught a quadratic npm path projection and inherited Maven consumer-context loss before publication. Both were corrected; the shared Maven check preserves the importing child's context and active profile. An npm fixture waits for indexing after adding a runtime config file.
- The first full/native run had one rejection at the final apply check in the existing 10,000-declaration Maven scaling fixture. The test alone and its full class passed separately; the full/native retry passed without changing or weakening any guard. Plugin Verifier passed during the initial run.
- `git diff --check`: passed.

Full/native retry command:

```sh
JAVA_HOME=/Users/kristoffer/.sdkman/candidates/java/21.0.12+1.1-tem ./gradlew test verifyPluginProjectConfiguration -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true -Pkotlin.incremental=false -Dorg.gradle.jvmargs=-Xmx2g --console=plain
```

The hosted plan is updated in place as version 8 ([immutable revision](https://plan-api.k8r.no/p/jrXzOA6WTYPcj2aa5rvBq26U/v/8)); original expiry remains 21 October 2026. Earlier validation remains historical.

Remaining scope: inherited Maven local/bulk scope parity; Maven input inventory and broader provider input roles; further result authorization/ownership typing; S3 CheckExecutor and complete preview state/model separation; conditional S6 production cache consolidation; and the product roadmap. No performance improvement is claimed.


## Maven inventory and check execution — 15 October 2026

The continuation starts from main `4f4e0ed`, including its existing minimum-release-age setting. Both PRs target main independently:

- [#37: S4 Maven input inventory](https://github.com/khopland/idea-version-checker/pull/37), commit `a041860`. Save-path discovery, background capture and fingerprint projection share one Maven inventory policy. Selected saving retains every imported POM for ownership checks but only selected distributions/ancestors. Capture retains all imported external configuration, deduplicated saved-byte hashing, missing/relocated security paths, layout retries and cancellation. The synchronous fallback keeps its prior narrow lazy per-home behavior.
- [#38: S3 CheckExecutor and this plan](https://github.com/khopland/idea-version-checker/pull/38), commit `81aed9a` plus documentation. Provider queues, pending/running state, revision authority, partial publication and cleanup now belong to an owned execution component. The service retains discovery, refresh scheduling, editor subscriptions and native scan-session orchestration. Disposal permanently rejects new work/late publication; pause remains resumable. Existing minimum-release-age result invalidation remains intact.

Validation on the combined implementation:

- 606 tests passed: 586 default tests plus 20 native integration tests (Maven 8, npm 5, Gradle 7), with no failures, errors or skips. Project configuration verification passed. Existing native request-count, bounded-invocation and large-project scaling checks pass.
- Plugin Verifier passed for IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3. Each target retains one deprecated API usage and 14 experimental API usages, matching the previous slice.
- Five new inventory regressions cover selected save scope, per-distribution isolation, relocated/missing security inputs, cancellation and adapter save/capture freshness. Existing same-stamp, unselected-sibling, input read-count and 10,000-declaration scaling checks pass.
- Two executor regressions cover queued cancellation/disposal, cancellation-resistant late completion, all execution entry points after disposal, provider-scoped cache authority and pause/resume. Existing coordinator, recovery, widget, partial-result, queue priority, trace and dynamic plugin unload checks pass.
- Review caught broad synchronous inventory discovery and missing cancellation defaults before completion; the fallback remains narrow and cancellation-aware. All changes were rebased onto the newer minimum-release-age main before the full run. Earlier interrupted subagent runs are not counted as successful validation.
- `git diff --check`: passed.

Commands:

```sh
JAVA_HOME=/Users/kristoffer/.sdkman/candidates/java/21.0.12+1.1-tem ./gradlew test verifyPluginProjectConfiguration -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true -Pkotlin.incremental=false -Dorg.gradle.jvmargs=-Xmx2g --console=plain
JAVA_HOME=/Users/kristoffer/.sdkman/candidates/java/21.0.12+1.1-tem ./gradlew verifyPlugin -Pkotlin.incremental=false -Dorg.gradle.jvmargs=-Xmx2g --console=plain
```

The hosted plan is updated in place as version 9 ([immutable revision](https://plan-api.k8r.no/p/jrXzOA6WTYPcj2aa5rvBq26U/v/9)); original expiry remains 21 October 2026. Earlier validation remains historical.

Remaining scope: complete preview state/model separation and remaining orchestration cleanup; broader provider input-role modeling and coverage; inherited Maven local/bulk scope parity; further authorization ownership typing; conditional S6 production cache consolidation; and the product roadmap. No performance improvement or exhaustive build-input inference is claimed.


## Core workflow delivery — 15 October 2026

This delivery starts from latest main `fcea34a`, after #37–#38 merged. Two independent PRs target main:

- [#39: dependency tool window and JSON export](https://github.com/khopland/idea-version-checker/pull/39), commit `7e0e5fb`. Dependency Versions keeps bounded, expiring cached observations visible with root/source/provider/package details, outcome/change filters, source navigation and explicit refresh/fresh review. Rendering does not query native tools. Failed partial checks remain visible even with known candidates. Export contains the displayed cached presentation rows with sanitized fields, relative/redacted source paths and an explicit fresh-review requirement; no inputs, settings, native payloads or edit authority are exported.
- [#40: review explanations and explicit native follow-ups](https://github.com/khopland/idea-version-checker/pull/40), commits `1ef78ae`, `19ed3c5` and `dbca07a`, plus this documentation. Reviews show captured versions, consumers and context; structured Needs review entries retain reasons and source navigation. Successful applied selection controls Maven/Gradle save-and-reload actions. npm previews its configured runtime and exact lockfile-only command, disables lifecycle scripts, revalidates saved inputs/trust/workspace/configuration/lockfiles and reports changed outputs or possible partial writes. Notification ownership expires actions on disposal. Native writes remain separate from declaration undo.

Validation on the combined implementation:

- **626 tests passed**: 605 default tests plus 21 native integration tests (Maven 8, npm 6, Gradle 7), no failures, errors or skips. Project configuration verification passed. Existing request-count, bounded-invocation, scaling and dynamic plugin unload checks pass.
- Native npm fixture proves lockfile-only behavior, disabled lifecycle scripts and no installed dependency synchronization. Model regressions cover failed partial results, expiry, filtering, output redaction, source navigation, structured provider facts, notification provider selection/disposal, cancellable hashing and failure/cancellation reporting.
- Plugin Verifier passed for IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3: all Compatible. Each reports 5 deprecated and 20 experimental API usages. The extra 4/6 compared with the previous 1/14 baseline are generated ToolWindowFactory interface delegates. No compatibility errors were reported.
- The first full run found an outdated reflective packaged-plan constructor in the dynamic dialog fixture and a stale npm reminder wording expectation. Both were corrected; the complete rerun above passed. The failed run is not counted as successful validation.
- `git diff --check`: passed. Interactive IDEA usability/accessibility review has not been performed. No release has been made, and both PRs remain open for review.

Commands:

```sh
JAVA_HOME=/Users/kristoffer/.sdkman/candidates/java/21.0.12+1.1-tem ./gradlew test verifyPluginProjectConfiguration -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true -Pkotlin.incremental=false -Dorg.gradle.jvmargs=-Xmx2g --console=plain
JAVA_HOME=/Users/kristoffer/.sdkman/candidates/java/21.0.12+1.1-tem ./gradlew verifyPlugin -Pkotlin.incremental=false -Dorg.gradle.jvmargs=-Xmx2g --console=plain
```

The hosted plan is updated in place as version 10 ([immutable revision](https://plan-api.k8r.no/p/jrXzOA6WTYPcj2aa5rvBq26U/v/10)); original expiry remains 21 October 2026. Earlier validation remains historical.

The bounded core workflow is delivered for review. A reload request does not verify synchronization/build success. npm output paths are reported for version control diff review; integrated post-command diff viewing, installed dependency synchronization, Gradle lock updates and automated build verification remain native/manual follow-ups. The tool window uses the session cache rather than durable history.

Deferred proposals: inherited Maven bulk scope parity; complete preview state/model separation and further ownership/input-role cleanup; conditional production cache consolidation; release notes/date enrichment beyond the existing Maven minimum age; security/OSV; team policy/grouped updates; additional managers/declarations; and a headless engine. These require separate scope or evidence and are not prerequisites to the delivered conservative workflow. Merge/release and interactive acceptance remain outstanding.
