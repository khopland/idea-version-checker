# Maven and npm performance work

The [performance plan, revision 2](https://plan-api.k8r.no/p/uQBB44ZJlfWkuneRIxSpEf51/v/2) now has dependency filtering (M1), combined npm queries (N1), workspace metadata sharing (N2), reusable Maven scan inputs (M2), and profiling hooks implemented. Runtime/discovery reuse and Maven session experiments remain follow-up work guided by profiling.

Maven's dependency goal receives both `dependencyIncludes` and `dependencyManagementIncludes`, built from the selected snapshot's resolved group/artifact pairs. The list ignores baseline differences and preserves Maven's native version selection. Empty dependency categories skip the goal. Coordinates that cannot safely be represented as exact inclusion patterns retain the broad query. Retrieval-failure exclusions apply alongside the inclusions, and metadata fallback uses the selected declarations.

npm normally executes `npm view '<name>@>=0.0.0' name version deprecated --json` with the existing authentication, configuration, runtime and freshness flags. The response supplies baseline deprecation notices and candidate deprecation checks for all update modes. Aliases and member manifests share that response within the same npm workspace and resolution configuration. Unsupported response formats and a range with no stable match use the previous versions/deprecation path; authentication errors, transport errors and cancellation propagate. Selectors and update modes are evaluated locally from the shared raw versions and notices.

## Workspace sharing and Maven scan inputs

The project-scoped npm cache shares successful raw metadata and in-flight requests by workspace root, configuration digest, package name and refresh generation. Configuration includes project/ancestor/user npm settings, environment, selected Node/npm runtime, known runtime-manager files and package-manager/workspace/runtime fields in `package.json`. Dependency selector edits reuse raw metadata; strict edit fingerprints still include the complete workspace manifests and deprecation policy. Credentials enter only the digest, never the cache key or traces.

Successful responses expire ten minutes after completion. Reports inherit the earliest metadata deadline, so creating another report from a warm response cannot extend its freshness. Completed entries use LRU eviction with limits of 512 packages, 100,000 versions and 8,000,000 characters of version/deprecation data. Oversized histories serve their current callers but are not retained. Failures and cancellations are not retained. Cancelling one caller preserves a request needed by another; cancelling its last caller stops the native worker. Project disposal or plugin unload stops all workers, including older generations.

Manual refreshes, scheduled scans and bulk previews begin a new generation once per adapter, allowing files in that scan to share fresh responses. Workers already serving callers may finish, but cannot join or populate the new generation. Independent npm roots and changed resolution settings remain isolated. The legacy query fallback can still require per-version deprecation commands when a registry does not supply complete combined metadata.

Maven receives the complete selected coordinates from the captured snapshot, groups them once, reads Maven config properties once and copies explicit profiles once per scan. Legacy lookup callers collect declarations once. Goals, branch retries, metadata expiration and retrieval fallbacks reuse those inputs. Effective repository data is evaluated lazily once and resolved separately for dependency and plugin repositories. Each POM still gets a fresh embedder; no cross-scan Maven session is retained.

## Reproduce the comparisons

```bash
./gradlew test -PmavenIntegration=true -PnpmIntegration=true -PperformanceTrace=true \
  --tests '*MavenSettingsIntegrationTest' \
  --tests '*NpmRegistryIntegrationTest'
```

The fixtures use IDEA's Maven server and configured Node/npm with authenticated local HTTP registries. The tests assert native HTTP request counts and candidate/notice parity. They alternate the old and new paths five times, print each duration and report the median and nearest-rank p95 (the maximum with five samples). Raw benchmark output is retained in `build/test-results/test/TEST-*.xml` under `system-out`.

The Maven comparison uses a child with one versionless declaration controlled by parent management. Four other regular dependencies are inherited from the parent. All five metadata timestamps are expired before each comparison so both paths check the same fresh registry data, retaining native artifacts. Broad queries retrieve five metadata documents; filtered queries retrieve one and preserve the selected update. Additional checks cover a newly published release under a daily policy, active/inactive profiles, a BOM import and an empty dependency category.

A parent that **only manages** five dependencies and a child that declares one already produce one request with the current broad path. The pinned Versions Plugin uses original-model dependency management when `processDependencyManagementTransitive=false`, which the checker already sets. The request reduction above concerns inherited regular dependencies; it does not demonstrate savings for unused parent-managed entries alone. See the pinned [extraction implementation](https://github.com/mojohaus/versions/blob/2.21.0/versions-common/src/main/java/org/codehaus/mojo/versions/utils/MavenProjectUtils.java#L91).

The npm comparison uses a scoped package and an alias in one manifest, with a deprecated baseline and highest patch candidate. The old path requires four commands/HTTP requests: versions, baseline notice, rejected candidate notice and accepted candidate notice. The combined path uses one command/request with identical candidates and notices. Tests also cover all modes and both file/project scopes, an older `latest` tag, one stable release, later deprecations, only prereleases, authentication failure, forced refresh, cancellation and unchanged lockfiles/install state.

## First-stage recorded run: 8 October 2026

The full validation run used IDEA 2025.3.6.1, its bundled Maven server 3.9.11, Versions Plugin 2.21.0, the Java 21 Gradle toolchain, Node 22.23.1 and npm 10.9.8 on macOS arm64. See the [raw samples and representative stage traces](performance-2026-10-08.txt).

| Fixture/path   | Metadata HTTP requests/check |   Median | p95, five samples |
|----------------|-----------------------------:|---------:|------------------:|
| Maven broad    |                            5 | 211.4 ms |          267.1 ms |
| Maven filtered |                            1 | 214.8 ms |          237.1 ms |
| npm legacy     |                            4 | 598.6 ms |         1116.2 ms |
| npm combined   |                            1 | 135.3 ms |          146.7 ms |

Maven's request reduction did not produce a median elapsed-time improvement in this small local fixture. npm reduced both requests and elapsed time. These measurements include native query/check work, not the complete editor interaction. The full suite passed 190 tests, including Maven/npm authentication, refresh and cancellation coverage and packaged-plugin lifecycle tests. Plugin verification reported compatibility with IDEA 2025.3.6.1 and 2026.1.4, with existing experimental API notices and a deprecated API notice on 2026.1.4.

## Workspace validation: 8 October 2026

The native npm fixture also checks 100 member manifests plus the workspace root, with 200 declarations of one scoped package through direct selectors and aliases. A fresh generation requires one native metadata command and one authenticated registry HTTP request for all 101 snapshots, preserving all 200 proposed edits. A unit workload checks 10,000 references to 100 packages and requires 100 loads per generation. See the [workspace validation record](performance-stage2-2026-10-08.txt). The single workspace timing is a work-count check, not a production latency comparison. The full suite passed 207 tests, and plugin build, configuration and compatibility checks passed for IDEA 2025.3.6.1 and 2026.1.4, retaining the existing API notices.

## Inspect real-project traces

In IDEA, open **Help → Diagnostic Tools → Debug Log Settings** and enable:

```text
#io.github.khopland.versionchecker.CheckPerformance
```

Trace entries in `idea.log` contain a fixed stage name, elapsed nanoseconds and a count:

```text
version-check stage=MAVEN_DEPENDENCY_GOAL elapsedNs=123456789 count=1
```

Stages cover Maven/npm snapshot capture, coordinator lock wait, overall checks, Maven declaration collection/embedder acquisition/effective model/settings/metadata expiration, each dependency/plugin/parent goal, npm command setup and CLI execution, and highlighting restart work. `HIGHLIGHT_QUEUE` includes the batching delay and restart scheduling; it does not measure completion of IDEA's inspection rendering. Goal/session/CLI counts count invocations, metadata expiration counts declarations, and highlighting counts affected files. Stages can overlap or contain other stages, so their durations must not be summed indiscriminately. Failed and cancelled operations can also emit timings.

The trace category records no file paths, coordinates, registry URLs, command arguments, configuration contents or tokens. Disable it after profiling. Compare current-file and whole-project refreshes separately, and record update mode, fresh/warm native caches and runtime versions alongside results. Counting metadata expiration does not prove HTTP revalidation; the local fixtures count requests independently.

## Measurement limits

These are small local fixtures with warm native installations and forced metadata revalidation. Maven server startup, setup and OS caches can affect the first sample; npm registry/cache startup can do the same. Five samples are sufficient to catch changed work counts, not to establish a reliable production p95 or a general speed multiplier. Large project workloads, cold installations, private remote repository latency and time to visible editor diagnostics still require measurement before choosing broader session or concurrency changes.
