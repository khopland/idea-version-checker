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
To rerun, temporarily copy this file to:

`src/test/kotlin/io/github/khopland/versionchecker/ProjectReviewProbeTest.kt`

Then run `./gradlew test --tests '*ProjectReviewProbeTest' --console=plain` and remove only that temporary file.
For actual fixes, invert the relevant assertions and add native scenarios where indicated in the plan.
The buildSrc test uses a synthetic candidate to isolate the input guard; it is not a native resolution exploit demonstration.
The npm directory exclusion probe verifies rejection; the Gradle analogue is source inspection.

## Limits

This is a broad source review with focused reproduction, not proof of all runtime states.
An interactive IDEA usability/accessibility audit and production remote-repository benchmarks were not performed.
The generated HTML is self-contained, readable without JavaScript and designed for desktop/mobile/printing.

## Implementation update — 14 October 2026

The first implementation slice addresses F1–F4. It is committed and open for review, not merged or released.
The hosted plan is updated in place at the same stable URL as version 3 ([immutable revision](https://plan-api.k8r.no/p/jrXzOA6WTYPcj2aa5rvBq26U/v/3)); it expires 21 October 2026. F5–F7 and phases 2–4 remain open.

Merge the PR stack in this order:

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
