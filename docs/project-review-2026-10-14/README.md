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
