# Improvement delivery validation — 17 October 2026

This is a combined-source review candidate for PRs [#51](https://github.com/khopland/idea-version-checker/pull/51), [#53](https://github.com/khopland/idea-version-checker/pull/53) and [#54](https://github.com/khopland/idea-version-checker/pull/54), with the roadmap/checklist from [#52](https://github.com/khopland/idea-version-checker/pull/52). It is not a release or complete IDE acceptance. The [machine-readable record](validation.json) identifies source heads, the exact archive, test counts and verifier verdicts.

Archive: `version-checker-1.3.0.zip`  
SHA-256: `551a508f8322312d1479004306453ef52fe3b2dc43bed1c3f1f483fac13e4425`

## Automated results

- **721 default/native Maven, npm and Gradle tests passed**, zero failures/errors/skips, on the final combined source.
- **Six Maven 4 integration tests passed** on IDEA 2026.2.3 with Maven 4.0.0-rc-7. The Maven distribution checksum was checked before extraction.
- Packaging, project-configuration validation and Plugin Verifier passed for IDEA **2026.1.4 / IU-261.26222.65** and **2026.2.3 / IU-262.10968.63**.
- Verifier retains 7 deprecated / 25 experimental usages on the minimum IDE and 9 / 25 on the newer IDE. The new awaited Maven import API is experimental; compatibility was checked on both supported targets.
- Fixture/performance Python tests passed 19 tests; release automation passed 13 tests. GitHub Build/Test/Maven 4/Verifier checks were green on the feature PR heads when reviewed.

```sh
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true --console=plain
./gradlew testMaven4 -Pmaven4Home=/path/to/apache-maven-4.0.0-rc-7 --console=plain
```

The final full run followed one transient failure in the existing 10,000-declaration Maven scaling test: its input guard rejected Apply. The complete scaling class then passed in isolation, and the full 721-test rerun passed. No production guard or assertion was weakened. This is not evidence that the transient cause has been diagnosed.

## Exact-ZIP native smoke

Observed through native UI automation on macOS 27.0.1 / Mac16,1, with separate configuration, system, plugin and log directories for each IDE. The official IDE runtimes were cloned into disposable app bundles with unique bundle IDs and locally signed wrappers. Each plugin directory contained a clean extraction of the archive above. Startup logs confirmed Version Checker 1.3.0.

The fixture used a local file repository with `fixture:alpha` versions 1.0.0, 1.0.1, 1.1.0 and 2.0.0. An imported parent defined `alpha.version=1.0.0` and dependency management; the child used alpha without a version. **Both parent and child were imported** so IDEA could establish DOM provenance. Opening the child alone initially offered a literal local override; that is not acceptance of an inherited-property workflow with unimported external provenance.

| Scenario | IDEA 2026.1.4 | IDEA 2026.2.3 |
| --- | --- | --- |
| Inherited-property action | Created `<alpha.version>2.0.0</alpha.version>` in a new child `<properties>` block. | Same observed result. |
| Update session | One changed file, zero omitted files; original session retained. Explicit reload/verify buttons available. | Same observed result. |
| Before reload | Explicit verification returned **Unknown**, with no resolution bound to captured reload inputs. | Session began **Unknown**; separate pre-reload verification was not run. |
| Native full import | Completion recorded separately; expected versions explicitly not yet compared. | Same observed result. |
| Explicit verification after import | **Confirmed: imported dependency version 2.0.0.** | Same observed result. |
| Undo and save | One declaration command restored exact baseline bytes. Session downgraded prior confirmation to **Unknown**; another verification rejected changed build files. | Undo/save restored the same baseline; session downgraded prior confirmation to **Unknown**. |

Restored POM SHA-256: `61ad028cd8c379a691208126ec187b7de19bfcf8ae976650a1c5fdb264b4f42a`.

Interactive observations above are recorded from the tool transcript; raw screenshot exports were not retained. Local native profiles/logs and fixture bytes remain under `build/acceptance-2026-10-17` in the original checkout; automated logs were retained under `/tmp/version-checker-complete-final.log` and `/tmp/version-checker-final-maven4-current.log`. These local files are not portable CI artifacts.

IDEA 2026.2.3 emitted platform/licensing/profiler errors and a built-in Maven synchronization write-context error during accessibility-driven actions. No Version Checker frames appeared in those severe blocks. These errors prevent describing this as a clean full-IDE acceptance run; the observed plugin state transitions remain the bounded smoke evidence above.

## Still uncompleted

The [full matrix](../../ide-acceptance.md) remains the release-acceptance checklist: repeated cold/warm interaction measurements, theme/scaling combinations, narrow-width keyboard/accessibility acceptance, complete failure/cancellation scenarios, and native npm/Gradle follow-up checks on this exact ZIP. No regression budgets or broader acceptance passes are inferred from these smoke checks.
