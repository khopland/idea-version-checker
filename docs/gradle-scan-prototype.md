# Gradle scan prototype

The F06 prototype defaults to off. Enable it in a disposable development/EAP
IDE profile with the JVM option:

```text
-Dversionchecker.gradleScanAggregation=true
```

Refresh and update-preview scans then share a short-lived provider-owned session.
Each native invocation contains at most 32 files or 2,048 declarations, except
that a single larger file stays intact. Compatible snapshots must have the same
linked build and complete resolution fingerprint. Selected-file ordering in the
coordinator is preserved; results return to their original declaration IDs.

The generated native metadata task indexes requested coordinates and resolution
contexts once after project evaluation. A catalog still checks every consuming
owner. Probe reuse includes source file, coordinate, baseline and update mode;
it cannot transfer a result from a script to a catalog or between distinct
owners. Repository, capability, attribute, inherited-configuration and
version-specific substitution behavior uses the existing native resolver path.
No application build task or artifact-file resolution is added. Refresh flags
remain unchanged pending configuration/latency measurements.

Batches start lazily so a fully cached preview creates no native task. Cached
reports are excluded from preview batch seeds. Closing/cancelling a scan cancels
its active and unstarted children. A changed Refresh generation or expired batch
cannot authorize another file: it falls back to a fresh individual lookup.
Every consumed report retains the batch's original start time and ten-minute
deadline. A failed batch falls back to individual lookups so an independently
checkable file can still succeed.

The shared coordinator now opens adapter-owned scan scopes. Maven owns its
embedder session, Gradle owns aggregation, and other providers use the default
scope. Shared preview/refresh code has no direct Maven scan-session dependency.

## Evidence and open gates

The real IDEA Gradle task-manager integration fixture checks 100 modules with
100 repeated declarations each: **10,000 declarations, five native invocations,
505 owner-project visits and 100 semantic probes**. A separate parity test
combines a script and a two-owner catalog in one invocation, preserves identical
candidates/manual-review reasons and keeps a substituted owner out of automatic
edits. Existing authenticated repository, DSL/catalog, capability and qualifier
cases remain in the integration suite. Session regressions cover bounds,
root/fingerprint/mode isolation, original freshness, failure fallback and
cancellation.

These headless integration checks establish algorithm/work counts. They do not
establish UI p95, typing delay or memory/EDT budgets. Controlled current/minimum
IDE profiling, repeated imports/unload and the full native failure matrix remain
required before enabling aggregation by default. No sharing across update modes
or persistent Gradle metadata cache is included.

Full validation passed on 2026-10-10 with SDKMAN Temurin Java 21.0.12+1.1:
409 plugin tests in 62 classes, zero failures/errors/skips; `check`, `buildPlugin`,
project-configuration validation and all three IDEA verifier targets. The
verifiers retain 10 existing experimental API uses with no deprecated or
scheduled-for-removal uses. The full run took 2m 47s; log:
`/tmp/version-checker-gradle-scan-full.log`.

Snapshot archive SHA-256:
`aa06f1b6a922c0e1892c0c5cf65e6f6f4ea69234448e0f2b7058e1a46242fe18`.
This archive has automated/integration evidence, not a completed native UI matrix.
