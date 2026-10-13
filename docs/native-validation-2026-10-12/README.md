# Dense Apply and write-safety follow-up

12 October 2026, Apple M4 / Mac16,1, 16 GiB RAM, macOS 27.0.1. Native checks use
the available installed **IDEA 2026.2.0.1 (IU-262.8665.337), JBR 25.0.3**. This
adds native coverage; it does not replace acceptance on the configured 2026.2.3
target. Gradle 9.8.0 selected SDKMAN Java 25 through the existing `current` link.
Plugin builds/tests use SDKMAN Temurin 21.0.12+1.1.

Previous temporary profiles/fixtures were absent. Fresh config/system/plugins/log
paths, fixtures and a local file repository were created under `/tmp`. The vendor
bundle and normal user profile were unchanged. The cached 2026.2.3 launcher did
not start; a direct Java launch could not be bound by automation and was stopped.
No acceptance is inferred from it. Aggregation was enabled only in the disposable
profile; the shipped default remains off. Periodic checks remain off.

## Failure and correction

The original tested ZIP `1fb04a19…` displayed 1,000 checked/selected rows after
the user moved the disposable window to the active display and it was raised.
Before that, editor AX clicks reported `cannotClickOffscreenElement`. Find Action
worked through double-clicking its result. A Return attempt inserted a newline
into the disposable build file: Apply rejected the stale plan and refreshed to
999 rows without changing versions. Undo/save restored the exact baseline.

The instrumented ZIP SHA-256
`36126835158c237db9b8c44a7d3ff20fee411905a5dd8f4f25a6bef139128f6d`
applied/saved 1,000 edits but logged **Write-unsafe context** with plugin frames in
`DocumentVersionChange.Prepared.apply` and `BulkUpdatePlan.apply`. This is a failed
write-safety sample despite its changed-text endpoint. One undo/save restored the
exact baseline bytes. The [before-fix anonymous trace](idea-2026.2.0.1-dense-before-write-safe.log)
retains rejected and failed interactions.

Review Apply now schedules its callback with `Application.invokeLater` and
non-modal modality before fresh-input preparation and the atomic write.
Production reviews are modeless. Pending duplicate clicks are suppressed;
disposal or a new review generation cancels the queued selection. Final result,
input, range and shared-consumer guards still precede any change. This follows
the [IDE threading guidance](https://plugins.jetbrains.com/docs/intellij/threading-model.html#invoking-operations-on-edt-and-modality).
Regressions invoke Apply from `ModalityState.any()`, verify deferred write-safe
execution and single application, and reject queued work after closing/refreshing.
Coordinator recovery waits for scheduled Apply rather than synchronous closure.

## Corrected native observations

Native-tested ZIP SHA-256:
`a79974df07fe4877f2ab4f709ae9226c4bb5925303df7f4bf1c9ad5aa144eee9`.
The [anonymous trace](idea-2026.2.0.1-dense-write-safe.log) records startup,
review, successful applications and follow-up checks, using only fixed stages,
anonymous IDs, monotonic times and counts.

| One document | Invocation to changed text | Atomic callback | Replacements | PSI commit | Final validation |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1,000 edits | 336.57 ms | 310.88 ms | 198.45 ms | 110.39 ms | 1.96 ms |
| 10,000 edits | 1,089.76 ms | 1,080.17 ms | 896.74 ms | 178.98 ms | 4.40 ms |

Each size has **one completed functional sample**, all targets 1.0.0 → 1.0.1,
one `DOCUMENT_COMMIT`, and no native invocation owned by, started during, or
overlapping its changed-text endpoint. Apply closed the review and saved all
versions. **One native undo followed by Save restored exact document bytes and
SHA-256 at each size.** No automated build/test/verifier run overlapped these
corrected applications.

The 10,000-declaration fixture extended the 1,000-declaration fixture with
100-statement dependency blocks, retaining valid Groovy method sizes. IDEA
import/review recognized exactly 10,000 declarations/selected changes. Native
repository/import work happened before Apply. Initial fixture commands:

```sh
python3 scripts/create-native-maven-fixture.py /tmp/version-checker-native-20261012
python3 scripts/create-native-gradle-fixture.py /tmp/version-checker-dense-20261012 \
  --repository /tmp/version-checker-native-20261012/repository \
  --declarations-per-module 1000 --repeat-artifact alpha
```

Separate [1,000-edit](idea-2026.2.0.1-dense-1000.json) and
[10,000-edit](idea-2026.2.0.1-dense-10000.json) reports preserve the conditions.
Their numerical p95 equals the only sample and `minimumSampleCountMet=false`.
These are not reliable latency distributions, typing/input-delay measurements,
or certification of all plugin EDT budgets. Atomic callback timing excludes
command acquisition, save, undo and later IDE work.

Neither corrected Apply produced the previous plugin document-write error.
One separate IDEA write-safety error arose when the status-bar accessibility
description pumped modal progress during external fixture expansion. Its stack
contains `IdeStatusBarImpl`, `StatusBarAccessibilityUtil` and transferred PSI
tree-change work, with no Version Checker frame. This remains an observed
IDE/automation limitation, not a zero-error session or a proven plugin defect.

## Tooling and remaining gates

Bulk review uses `BULK_APPLY_INVOKED`; local/shared fixes retain `FIX_INVOKED`.
Reports separate those populations and include maximum durations/work counts.
Input capture, atomic callback, final validation, replacements and commits have
separate spans and exclusive nested partitions. See [the trace definitions](../performance.md#inspect-real-project-traces).
Older traces cannot be reliably split by edit count because a shared quick fix
can update several declarations.

Dense Apply/save/grouped-undo now has functional evidence on this additional IDE.
Repeated latency, typing while checking, 100-module input delays, slow disks/init
trees, paired 15-minute baseline idle CPU, retained heap after import/rename/unload,
and supported-IDE DSL/catalog/failure/accessibility matrices remain open.
Native npm on IDEA 2025.3 was excluded from release acceptance by the user on
2026-10-12. The earlier licensing limitation remains recorded as historical
evidence; it is no longer an outstanding gate. npm acceptance on newer IDEs and
automated npm integration tests remain in scope.
This run used `1.3.0-SNAPSHOT`. The subsequent stable candidate is documented in
the [1.3.0 acceptance record](../release-acceptance-1.3.0/README.md).

## Automated validation

Final validation passed **501 plugin tests in 68 classes**, zero failures/errors/
skips, with all native Maven/npm/Gradle integration flags; **13 release tests and
19 script tests**; `check`, packaging, project-configuration validation and all
three verifier targets. IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3 remain compatible,
each with the existing 14 experimental API usages and no deprecated/scheduled
usages. The incremental full Gradle run took 2m 12s. Log:
`/tmp/version-checker-continuation-final.log`.

```sh
JAVA_HOME=/Users/kristoffer/.sdkman/candidates/java/21.0.12+1.1-tem
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true --console=plain
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B -m unittest discover -s scripts -p 'test_*.py'
```

The final packaged snapshot adds the Unreleased write-safety note and is SHA-256
`35d30456c1153e4447450844c8d4a060fb6c592c1b7a87d5c37928331302beb6`.
Its descriptor/notes retain `1.3.0-SNAPSHOT`. No release was created or published.

After full validation, this exact ZIP passed a further native 1,000-edit Apply/
save/single-undo smoke test with **aggregation disabled**, matching the shipped
default. All 1,000 selectors changed to 1.0.1; one undo/save restored the exact
baseline bytes. The atomic callback took 177.86 ms and committed once. This is
another single observation, kept separate from the earlier aggregation-enabled
captures. There were zero new SEVERE/error log entries in this final session.
The [final default-path trace](idea-2026.2.0.1-final-default.log) retains the full
anonymous session.

The file was reduced from 10,000 declarations on disk while IDEA was stopped.
On restart, the first preview still saw the IDE's cached 10,000-declaration VFS
content. It was cancelled without applying. **Reload All from Disk** synchronized
the IDE before the final 1,000-row review and smoke test. This setup discrepancy
is preserved rather than counted as 1,000-row acceptance. No stale VFS plan was
applied; outside-VFS build-file race behavior needs a separate controlled test.
