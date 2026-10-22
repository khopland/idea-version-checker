# Feature continuation and acceptance — 22 October 2026

Draft: the separate support-diagnostics PR identity and final result are pending.

This continues the [18 October record](../2026-10-18/README.md).
The [packaged plugin matrix](../../ide-acceptance.md) remains incomplete.
Automated integration tests and interactive IDE checks are recorded separately.
Each result applies only to its identified source and archive.

## Feature delivery

The 20 October plan identified seven work areas. These changes implement bounded slices of that plan.
The original HTML remains a local planning artifact and is not changed by this record.

| Plan area | Implementation | Boundary |
| --- | --- | --- |
| A. Current-build acceptance | This dated record, combined automated checks, and isolated IDE smoke checks. | The complete interactive matrix is pending. See the scenario table below. |
| B. Resolved-version confirmation | [#61](https://github.com/khopland/idea-version-checker/pull/61), merged: capture effective-version targets for ordinary Maven dependency updates. | Verification observes imported dependency models after reload. It does not run builds or tests. Gradle and npm effective-version confirmation remain separate work. |
| C. Target selection | [#62](https://github.com/khopland/idea-version-checker/pull/62), merged: choose independently checked patch, minor, or major branch targets per declaration. [#68](https://github.com/khopland/idea-version-checker/pull/68): explicitly load exact npm target requirements and known links. | The picker offers the highest eligible checked branch targets. It does not enumerate every historical release. npm compatibility remains unverified. |
| D. Shared rules | [#63](https://github.com/khopland/idea-version-checker/pull/63), merged: versioned project policy with exact ignores, reasons, expiry, deprecations, and allowed update levels. | Personal UI preferences remain local. Invalid or changed policy cannot authorize stale edits. |
| E. Gradle declarations | [#65](https://github.com/khopland/idea-version-checker/pull/65), merged: literal named local catalogs with physical ownership and native consumer checks. | Computed, published, external, or ambiguous registrations require review. Automatic Gradle plugin-version edits remain blocked on a separate native resolver. |
| F. Native file review | [#66](https://github.com/khopland/idea-version-checker/pull/66), merged: live structured follow-up steps and captured npm lockfile diffs. | Captured changes are historical observations. Declaration Undo does not undo native writes. No build/test execution or Gradle lockfile synchronization is added. |
| G. Platform cleanup | Source and native-resolution investigation only. | Safe removal requires a counterfactual effective model for every affected consumer. No automatic removal is implemented. |
| Preview freshness | [#67](https://github.com/khopland/idea-version-checker/pull/67): authoritative result-expiry countdown and expired-click rejection. | Unknown deadlines remain unknown. Presentation does not query repositories or renew result leases. |
| Shareable review | [#64](https://github.com/khopland/idea-version-checker/pull/64), merged: Markdown exports. | Exported observations do not authorize edits. |
| Support diagnostics | [#69](https://github.com/khopland/idea-version-checker/pull/69): preview and explicitly save a bounded sanitized report of cached outcomes, timing, settings and policy provenance. | Package names and project-relative paths remain visible for review. No checks, cache renewal or upload. |

After #62, #65 and #66 merged, #67 was rebased onto main.
#68 is stacked on #67 so their shared dialog cleanup merges together. Review #67 before #68.
The support-report continuation targets main independently.

## Latest combined candidate

| Item | Recorded identity |
| --- | --- |
| Source | [`020f4ccc2ae0f4ed144dcaa716a26b9dd2546dea`](https://github.com/khopland/idea-version-checker/commit/020f4ccc2ae0f4ed144dcaa716a26b9dd2546dea), published on `integration/all-planned-features` |
| Main baseline | `2b82fdea93d5a76ed69c5e897d818e3bd93bfe28`; includes merged #62, #65 and #66 |
| Archive | `version-checker-all-features-020f4cc.zip`, 2,723,927 bytes |
| SHA-256 | `46e3f1bf0c9895d65169cf740a00bdc0d4e3b540db5ebd9f256af64d0913aec3` |
| Pending PR heads included | #67 `9cd84dd7c724acb585982b2401177e64c8ccb4dc`; #68 `60d8570e76503ae798ddbf9c53d55410a9dd42f9`; #69 `053c93b50878052e12957ff33f89e5bb39e2c0d2` |

This candidate includes the support report and the rebased #67 → #68 stack.
A clean build with both build and Kotlin incremental caches disabled passed all **822 tests**
with native Maven, npm and Gradle integrations enabled, with zero failures, errors or skips.
Plugin packaging/configuration checks and Plugin Verifier passed on both supported IDE versions.
The latest candidate also passed all seven Maven 4 tests on IDEA 2026.2.3 with zero failures, errors or skips.
Plugin version reporting uses owned build metadata, with a packaging test comparing it to the actual descriptor.

Interactive acceptance was not run against this archive. The read-only IDE smoke checks below
belong to the earlier core-feature archive and do not certify the support-report UI or this newer ZIP.

## Core-feature candidate before support reporting

| Item | Recorded identity |
| --- | --- |
| Source | [`26a208b6fb3b8b379206e231a2341847280162ba`](https://github.com/khopland/idea-version-checker/commit/26a208b6fb3b8b379206e231a2341847280162ba), published on `integration/next-cycle-candidate` |
| Main baseline | `99da2154ffef8ed12ae2a813f552cdc57289455f` |
| Archive | `version-checker-combined-26a208b.zip`, 2,679,978 bytes |
| SHA-256 | `61321e9f5e510fff0bcdeb5536b5ce8a2d015c4583b6f462f9fbc698126b6fa5` |
| Included PR heads | #62 `4ec21740d4d3a87b1ecb6e81994445e316abc02f`; #65 `f2b7e26806b988f72f8f10b9ea1e1b9d6c73bdd2`; #66 `3471ba484c734821c66b7f3e888e7a10d537e5b5`; #67 `4ddd5705b3f3f6bdd71e5d52ba7ee15b2c808acd`; #68 `226d03e1dc9ffdbfdca0839aa23f7ab5b2d3b66d` |

This archive includes the cancellation retry and packaged lifecycle fixture corrections described below.
Support diagnostics remain a separate change and are excluded from this archive.
The final ZIP was extracted into empty isolated plugin directories for both supported IDE versions.
Configurations were isolated copies of earlier test profiles. System and log directories were fresh.
These were not pristine default configurations. Native cold/warm state was not controlled.
Both IDEs completed the final-archive read-only smoke checks recorded below.

## First interactive candidate

| Item | Recorded identity |
| --- | --- |
| Source | `2a6c3e61d43619b6b4672417989e0f49bf509b41` |
| Main baseline | `99da2154ffef8ed12ae2a813f552cdc57289455f` |
| Archive | `version-checker-combined-2a6c3e6.zip`, 2,680,016 bytes |
| SHA-256 | `a52cdd8d425ab5d9a79b891523b371b133fccf1aa0f90992dadafef8347021eb` |
| Included PR heads | #62 `4ec21740d4d3a87b1ecb6e81994445e316abc02f`; #65 `f2b7e26806b988f72f8f10b9ea1e1b9d6c73bdd2`; #66 `3471ba484c734821c66b7f3e888e7a10d537e5b5`; #67 `d99dcdb04800cc4d47c3dc38d96df4a5aea0950f`; #68 `edb120b6c59d28c91ffc5bbda61e59f220f3d2f3` |
| IDE | IDEA 2026.1.4, build `261.26222.65` |
| Runtime | JBR `25.0.3+9-b329.124` |
| Host | macOS 27.0.1, aarch64, 16 GiB memory |
| Installation | Isolated configuration copied from an earlier test profile; fresh system/log directories; ZIP extracted into an empty plugin directory. |

This ZIP predates the npm-details cancellation retry fix and the packaged lifecycle test fixture correction.
Results from it do not establish acceptance for the final candidate.

The native log confirms Version Checker 1.3.0 loaded.
The Maven fixture contains alpha, beta, and gamma dependencies at `1.0.0`.
Native checks displayed `2.0.0` for all three, with three of three declarations checked.
Filtering for alpha displayed one of three rows. Coverage still reported three checked declarations.

The log also contains `PREVIEW_READY` for three prepared changes. This is not a timing baseline.
The automation surface could not select the review dialog to inspect or activate its controls.
The macOS app binding returned the main frame for dialogs, and window enumeration was unavailable.
The export save dialog had the same limitation. No export file was produced.

The Maven POM remained at its baseline after fixture relocation and the read-only checks:
`4273b6092f4b3a8476058e288ac5ff29e31dc81551e798267c08b84adfc1b0ca` (SHA-256).
No Apply, Undo, native-session, target-choice, or export success is inferred from these observations.
The owned isolated test IDE was closed after collection.
Recorded observations used the corrected isolated root after a launcher-path preflight issue was resolved.

## Automated validation

The first combined native-integration run completed 813 tests: 812 passed and one packaged lifecycle test failed.
`DynamicPluginLifecycleTest` still reflected the old five-argument dialog constructor after the clock parameter was added.
The failure was in the packaged fixture's constructor lookup.
The fixture correction in #67 uses the six-argument constructor and reports dialog-show failures directly.
Its focused rerun passed 53 tests, including all four packaged lifecycle tests.

The npm-details request test also identified an independent cancellation retry gap.
The correction resets the active request state without allowing an obsolete completion to publish.
A fresh local run on the final combined source passed 814 tests with zero failures, errors, or skips.
Maven, npm, and Gradle native integrations were enabled.
The plugin archive and project configuration checks passed.
Plugin Verifier reported compatibility with both supported IDE builds.

| Verifier target | Result and retained notices |
| --- | --- |
| IDEA 2026.1.4, `261.26222.65` | Compatible; eight deprecated API usages and 25 experimental API usages. |
| IDEA 2026.2.3, `262.10968.63` | Compatible; ten deprecated API usages and 25 experimental API usages. |

Project configuration retains the existing `until-build` compatibility-range advisory.
The separate Maven 4 continuation passed seven tests with zero failures, errors, or skips on IDEA 2026.2.3.
Those 814-test and seven-test results apply to the core-feature candidate. The latest 822-test result includes the support report and rebased stack. Per-PR results remain scoped to their tested heads.
The latest heads of #62, #65, #66, #67, and #68 also had green CI checks on 22 October.

## Interactive scenario status

The following scenario table applies to the final archive, `61321e9f…`, on both IDE versions.
The earlier `a52cdd8d…` results remain separate above.

The final ZIP loaded Version Checker 1.3.0 on IDEA 2026.1.4 (`261.26222.65`, JBR `25.0.3+9-b329.124`)
and IDEA 2026.2.3 (`262.10968.63`, JBR `25.0.4+1-b508.27`) on the same host.
No Version Checker ERROR entry was found in the examined startup logs.
Bundled disabled-plugin warnings and an IDE error indicator were present. This is not a fully clean startup claim.

On IDEA 2026.1.4, the final archive displayed three Maven updates from `1.0.0` to `2.0.0`.
Filtering for gamma displayed one of three rows while coverage retained three checked declarations.
Selected-row details showed the current version, available version, POM source and root, with an
observation age of 49 seconds and expiry of nine minutes.

On IDEA 2026.2.3, the final archive displayed all three Maven updates from `1.0.0` to `2.0.0`.
Filtering for beta displayed one of three rows while coverage retained three checked declarations.
Selected-row details showed the current version, available version, POM source, and root.
The tool window displayed an observation age of 34 seconds and expiry of nine minutes.
No expiry transition was observed.
The POM retained the baseline SHA-256 recorded above. No native edit or export write was executed.
The owned isolated test IDE was closed after collection.

On IDEA 2026.1.4, the final archive loaded Version Checker 1.3.0 after reopening the Maven fixture.
It displayed the same three updates, with three of three declarations checked.
Filtering for gamma displayed one of three rows. Coverage retained three checked declarations.
Selected-row details showed the POM source/root, current version, available version, age of 49 seconds, and expiry of nine minutes.
No expiry transition was observed. The POM retained the same baseline SHA-256.
The IDE used build `261.26222.65` and JBR `25.0.3+9-b329.124`.
The owned isolated test IDE was closed after collection.

| Required scenario | IDEA 2026.1.4 | IDEA 2026.2.3 |
| --- | --- | --- |
| Startup and optional integrations | Plugin startup observed on the final ZIP. Missing optional integrations not exercised. | Plugin startup observed on the final ZIP. Missing optional integrations not exercised. |
| Native dependency checks and coverage | Three Maven updates observed; gamma filter displayed one of three rows with coverage of three. Source/version details observed. | Three Maven updates observed; beta filter displayed one of three rows with coverage of three. Source/version details observed. |
| Keyboard-only discovery | Not run as a complete keyboard traversal. | Not run. |
| Narrow layout, themes, and scaling | Not run. | Not run. |
| Observation and held-preview expiry | Not run interactively. | Not run. |
| Malformed catalog and empty-result coverage | Not run interactively. | Not run. |
| Target selection, scope changes, and late completion | Not run on the final ZIP; first archive blocked by review-dialog automation. | Not run. |
| Maven, npm, and shared-catalog Apply/Undo | Not run on the final ZIP; first archive blocked by review-dialog automation. No declaration changes applied. | Not run. |
| External and unsaved input rejection | Not run interactively. | Not run. |
| Maven reload and effective-version verification | Not run interactively. | Not run. |
| Reload failure and disconnected builds | Not run interactively. | Not run. |
| npm synchronization and captured native diffs | Not run interactively. | Not run. |
| Retained sessions, older actions, and project reopen | Not run interactively. | Not run. |
| JSON and Markdown export | Not run on the final ZIP; first archive blocked by save-dialog automation. No exported file. | Not run. |
| Small/large cold/warm interaction measurements | Not run. Cold/warm state was not controlled. | Not run. Cold/warm state was not controlled. |

Windows smoke remains blocked because this run has no Windows host.
The automated native fixtures do not substitute for any unobserved UI scenario.

## Concrete implementation blockers

### Gradle plugin-version checks

The current probe preserves library ownership through original resolvable dependency configurations.
Plugin requests instead use `pluginManagement` repositories and resolution rules.
A candidate can trigger different `useVersion` or `useModule` rules from the current version.
A library marker lookup cannot establish the candidate's native plugin resolution.

The inspected Gradle 9.8.1 public strategy API exposes `eachPlugin` rule registration.
Candidate rule replay uses internal APIs such as `PluginResolutionStrategyInternal.applyTo`.
Copying repository URLs would also discard credentials, content filters, and exclusive repository ownership.

A safe implementation needs a candidate probe that preserves those contexts without applying the candidate plugin.
Required fixtures include authenticated marker repositories, implementation remapping, candidate-only substitutions, included plugin builds, and shared library/plugin references.
Internal API use also needs explicit supported Gradle versions and compatibility checks across those versions.
Until that resolver exists, affected plugin declarations remain under manual review.

See the [current Gradle probe](../../../src/main/resources/gradle/version-checker.gradle) and #65's recorded scope.

### Maven platform override removal

Current verification observes imported dependency versions after an edit and reload.
It does not compute a separate effective model with an override removed.
An equal current override and imported version do not prove that removal preserves resolution.

Each proposed removal needs a counterfactual native model for every affected consumer.
That model must preserve POM identity, basedir, reactor, active profiles, extensions, settings, parents, and imports.
The existing helper reads candidate plugin POM prerequisites; it does not provide edited-source counterfactual dependency models.
Source-only property analysis cannot replace this native evidence.

No safe automatic removal slice was identified with the current facilities.
A future cleanup must use a separate guarded preview, independent Undo, and new reload/verification evidence.
See [Maven target capture](../../../src/main/kotlin/io/github/khopland/versionchecker/maven/MavenPreparedUpdate.kt),
[verification](../../../src/main/kotlin/io/github/khopland/versionchecker/maven/MavenUpdateVerificationService.kt), and
[the Maven helper](../../../maven-helper/src/main/java/io/github/khopland/versionchecker/helper/MetadataMojo.java).

## Evidence locations and continuation

The identity, observations, and hashes above are retained in this repository.
The following supporting files remain local. They are not available in a fresh checkout.
An unavailable evidence file is not a passed check.
No raw logs, registry configuration, or credentials are uploaded with this record.

Local evidence root: `/private/tmp/version-checker-feature-acceptance-2026-10-22`.
Copied configuration source: `build/acceptance-2026-10-17-review/profile-*/config` in the original workspace.

| Local path under that root | Contents |
| --- | --- |
| `candidate.json` | Core-feature candidate identity, checksum, size, and included PR heads. |
| `all-features-candidate.json` | Latest candidate identity including support reporting and the rebased PR stack. |
| `version-checker-all-features-020f4cc.zip` | Latest combined archive; automated validation only. |
| `final-readonly-smoke.json` | Final core-feature archive observations on both IDE versions and unchanged POM hash. |
| `version-checker-combined-2a6c3e6.zip` | First interactive archive. |
| `version-checker-combined-26a208b.zip` | Final combined archive. |
| `profile-2026.1.4/log/idea.log` | Plugin startup and `PREVIEW_READY` observation. |
| `profile-2026.2.3/log/idea.log` | Final-archive startup on IDEA 2026.2.3. |
| `final-readonly-smoke.json` | Final-archive identity and read-only observations on both IDE versions. |
| `maven/maven-small/pom.xml` | Unchanged Maven fixture after relocation. |
| `gradle/gradle/libs.versions.toml`, `gradle/gradle/tools.versions.toml` | Catalog fixtures prepared for later interaction checks. Their presence is not a completed check. |
| `fixture-baseline-after-isolation.json` | Baseline fixture hashes after relocation. |

The latest combined test log is `/private/tmp/version-checker-all-features-validation.log`.
The latest Maven 4 log is `/private/tmp/version-checker-all-features-maven4.log`.
The earlier core-feature test log is `/private/tmp/version-checker-next-cycle-final-validation.log`.
The Maven 4 continuation log is `/private/tmp/version-checker-next-cycle-maven4.log`.
The Gradle plugin investigation is `/private/tmp/version-checker-gradle-plugin-follow-up.md`.

Continue with the latest archive and isolated profiles on both supported IDE versions.
Complete the [required matrix](../../ide-acceptance.md#required-matrix) where the UI surface permits observation.
Record inaccessible cases as blocked. Keep older archive results separate.
