# Performance checks

Use this guide to capture current behavior and run performance fixtures.
For past results, see the [October 2026 report](archive/performance-2026-10.md).
Its test totals, IDE versions, and measurements describe earlier runs.

## Capture an IDE trace

1. Open **Help → Diagnostic Tools → Debug Log Settings**.
2. Enable this category:

   ```text
   #io.github.khopland.versionchecker.CheckPerformance
   ```

3. Run one scenario in one IDEA process.
4. Save the relevant `idea.log` capture.
5. Disable the category after the capture.

Record the source commit, plugin archive hash, IDE version, runtime, fixture, scope, mode, and cache condition.
Keep cold and warm runs separate.
Do not combine different project sizes or IDE versions in one capture.

Trace entries contain fixed stages, anonymous interaction IDs, monotonic timestamps, durations, and counts.
They do not log build paths, coordinates, repository settings, command arguments, or credentials.
Interaction zero identifies an uncorrelated stage.
IDs are local to one process. Use the ID and origin timestamp together when a log spans restarts.

Tracing and debug-only paint checks add measurement overhead.
IDE paint endpoints exclude compositor latency.

## Summarize a trace

Run the [trace summarizer](../scripts/summarize-performance.py) from the repository root:

```sh
python3 scripts/summarize-performance.py build/performance/idea.log \
  --output build/performance/summary.json \
  --case small-maven-warm-patch \
  --ecosystem maven --scope current-file --mode patch --cache warm \
  --modules 1 --declarations 3 --idea-version 2026.1.4 \
  --repository-case fast-local
```

Replace the example values with the actual fixture conditions.
Use a case label that contains no sensitive data.
Run `python3 scripts/summarize-performance.py --help` for accepted values.

The report includes sample counts, median, nearest-rank p95, maximum, incomplete interactions, stage distributions, and native invocation counts.
It separates local fixes from bulk application.
It partitions overlapping stages instead of adding their durations twice.

| Stage or endpoint | Meaning and limit |
| --- | --- |
| `FIRST_INSPECTION_RESULT` | Time from check start to the first accepted candidate or notice. Excludes queue wait and IDE rendering. |
| `FILE_ACTIVATED` → `DIAGNOSTIC_VISIBLE` | Time until plugin markup intersects the selected viewport and an explicit paint completes. |
| `PREVIEW_INVOKED` → `PREVIEW_READY` | Time until the preview dialog is showing and its content paint completes. |
| `FIX_INVOKED` → `EDITOR_TEXT_CHANGED` | Local fix duration. Rejected or cancelled edits have no changed-text endpoint. |
| `BULK_APPLY_INVOKED` → `EDITOR_TEXT_CHANGED` | Bulk application duration, starting at dialog acceptance. |
| `EDIT_INPUT_CAPTURE` | Input preparation, including its modal wait. |
| `EDIT_WRITE_COMMAND` | Atomic callback after write access. Excludes command acquisition and later saving or rechecking. |
| `EDIT_PLAN_VALIDATION` | Initial validation inside the atomic callback. |
| `DOCUMENT_REPLACEMENTS` / `DOCUMENT_COMMIT` | Replacement work and document commits within the write. |

Native invocation counts describe recorded host calls, not HTTP requests.
Use independent repository request counters to measure remote traffic.
An invocation can overlap several samples. Do not interpret overlap totals as unique requests.
Incomplete captures cannot establish total native work.

## Run automated fixtures

Use Java 21 through SDKMAN.
Native fixtures require their configured build runtime and network access for uncached artifacts.

```sh
./gradlew test -PmavenIntegration=true -PnpmIntegration=true \
  -PgradleIntegration=true -PperformanceTrace=true
```

For focused local checks:

```sh
./gradlew test --tests '*SnapshotScalingTest' --tests '*GradleDocumentUpdateTest'
```

These tests check scaling behavior, declaration changes, invalidation, and undo.
Their timings do not define a production latency budget.

Select an optional benchmark explicitly:

| Test class | Required option |
| --- | --- |
| `NpmVersionHistoryBenchmarkTest` | `-PnpmHistoryBenchmark=true` |
| `MavenVersionHistoryBenchmarkTest` | `-PmavenHistoryBenchmark=true` |
| `NpmInspectionBenchmarkTest` | `-PnpmInspectionBenchmark=true` |
| `GradlePreparationBenchmarkTest` | `-PgradlePreparationBenchmark=true` |
| `MavenPreparationBenchmarkTest` | `-PmavenPreparationBenchmark=true` |

For example:

```sh
./gradlew test --tests '*MavenVersionHistoryBenchmarkTest' -PmavenHistoryBenchmark=true
```

The full delayed-repository matrix also requires the native Maven integration option:

```sh
./gradlew test -PmavenIntegration=true -PmavenLatencyBenchmark=true \
  --tests '*MavenSettingsIntegrationTest.testBatchedMetadataSharesOneHundredArtifactsAcrossOneHundredModulesAndModes'
```

Benchmark output is available in `build/test-results/test/TEST-*.xml` under `system-out`.
See the [development guide](user-guide.md#development-and-verification) for native integration setup.

## Compare results

Keep declaration coverage and fixture conditions equal across implementations.
A reduced check is not an equal-scope speed comparison.
Separate native work, local preparation, atomic writes, and visible IDE endpoints.
A successful reload request does not verify dependency versions or application compatibility.

Collect repeated cold and warm runs before setting regression budgets.
A single sample or a five-sample comparison cannot establish a production p95.
Retain individual values and explain the sampling method.
Record missing runtimes and incomplete scenarios as blocked or not run.
Use the [acceptance procedure](ide-acceptance.md) for interactive measurements and candidate identity.
