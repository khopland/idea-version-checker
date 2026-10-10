# Native validation: 10 October 2026

Small Maven cases ran in native IDEA windows with the packaged plugin. IDEA
2025.3.6.1 has at least 100 completed samples for each measured endpoint. IDEA
2026.1.4 has a partial local-fix capture. The full plan's performance and usability
matrix is still incomplete.

**Native npm validation is blocked.** JavaScript/Node support is disabled in the
available IDEA sandbox. No usable licensed profile was supplied or verified, and
the user explicitly requested recording this blocker. Headless authenticated npm
integration tests passed, but do not replace native editor/preview validation.

## Results

| IDEA | Endpoint | Completed samples | Median | Nearest-rank p95 | Evidence |
|------|----------|------------------:|-------:|-----------------:|----------|
| 2025.3.6.1 | Cached editor diagnostic | 105 | 2.31 ms | 3.49 ms | [Report](idea-2025.3.6.1-maven-small-editor.json), [trace](idea-2025.3.6.1-maven-small-editor.log) |
| 2025.3.6.1 | Cached preview, 3 rows | 100 | 27.52 ms | 43.86 ms | [Report](idea-2025.3.6.1-maven-small-preview.json), [trace](idea-2025.3.6.1-maven-small-preview.log) |
| 2025.3.6.1 | Literal local fix | 100 | 2.63 ms | 5.45 ms | [Report](idea-2025.3.6.1-maven-small-fix.json), [trace](idea-2025.3.6.1-maven-small-fix.log) |
| 2026.1.4 | Literal local fix, partial | 76 | 3.80 ms | 5.35 ms | [Report](idea-2026.1.4-maven-small-fix-partial.json), [trace](idea-2026.1.4-maven-small-fix-partial.log) |

The completed 2025.3 latency samples are below the plan's editor/preview/fix p95
targets for this condition only. They do not establish performance for other
ecosystems, large projects, shared edits, cold caches or delayed repositories.

Editor sampling alternated an ordinary Java source tab and a POM with cached,
visible version diagnostics. There were 110 activation pairs and 105 completed
diagnostic endpoints; incomplete interactions are excluded by the report. Both
the editor and preview captures contain zero native query stages. Preview
sampling cancelled each dialog and reused current Patch reports.

Local-fix sampling used Alt+Enter to update alpha from 1.0.0 to 2.0.0, verified the
changed editor text, then undid and saved to restore the hint. No fix interaction
owns a native query, and no query starts inside a measured fix interval. Undo and
hint restoration did cause background native work: 94 goals in the 2025.3
capture and 79 in the 2026.1 capture. Already running queries overlapped 15 of the
2025.3 fixes; none overlapped the 2026.1 fixes. Thus the 2025.3 capture does not
certify a globally idle repository throughout every fix, although fixes did not
initiate or wait for those goals.

The 2026.1 capture contains 75 verified fix/undo cycles plus one further completed
fix. Sampling stopped after input moved to the Terminal panel and subsequent
native input did not reliably restore editor focus. All 76 completed trace
endpoints are retained; the report flags the unmet 100-sample requirement. This
interruption is not recorded as a plugin failure or a completed usability case.

## Conditions and source

- macOS 27.0, aarch64, 10 CPU cores; 2 GiB IDEA maximum heap.
- IDEA 2025.3.6.1: IU-253.33813.55, bundled JBR 21.0.11+10-b1163.116.
- IDEA 2026.1.4: IU-261.26222.65, bundled JBR 25.0.3+9-b329.124.
- Both Maven servers: SDKMAN Eclipse Temurin 25.0.4+7-LTS, bundled Maven 3.9.11,
  Versions Maven Plugin 2.21.0.
- One imported module/POM, three literal dependency declarations, current-file
  scope. Editor and fix checks use Major; preview uses Patch.
- A local file repository publishes 1.0.0, 1.0.1, 1.1.0 and 2.0.0 for
  fixture:alpha/beta/gamma. Native installations and reports were primed before
  the warm captures. No network-delay or HTTP-request measurement is claimed.
- Separate test configuration/system/plugin/log directories were used. Node/npm
  and Gradle are not involved in these Maven cases.
- The 2025.3 editor capture used commit `9e6a9e1`. Its preview and fix captures
  used that code plus the dialog renderer and Tab-traversal corrections. The
  low-priority Ignore correction was subsequently loaded in 2026.1.
- The final archive loaded in 2026.1 has SHA-256
  `4f4807986b70c7173a75fd4f72cc3081912cd65565a5f8f2aa96dec87ed33e1e`.

Tracing was enabled. Editor and preview endpoints include the explicit paint and
logging overhead described in [performance.md](../performance.md). OS input
delivery, automation round trips and compositor latency are outside the measured
plugin boundaries. No typing/EDT profiler result is inferred from these timings.

## Native usability findings

Native 2025.3 testing found raw enum labels in the mode/scope selectors and Tab
cycling within table cells. The dialog now renders the human-readable labels and
uses Tab/Shift+Tab to enter and leave the table. Rechecking 2025.3 verified initial
filter focus, readable Current File/Patch only labels, the filter mnemonic,
Space/repeated Space toggles, and hidden selections surviving filtering to zero
visible rows. Labels were also checked in 2026.1. Native 2026.1 verified Update
preceding Ignore in Alt+Enter after Ignore became a low-priority action.

Native sorting, empty-plan recovery, stale rejection, scope/mode changes,
failed-file Retry, delayed/offline/authentication states and the rest of the
keyboard/focus matrix still require dedicated cases on both versions. Automated
regression coverage for these behaviors is separate evidence.

## Reproduction and remaining work

Create an equivalent disposable fixture with:

```sh
python3 scripts/create-native-maven-fixture.py /tmp/version-checker-small
python3 scripts/create-native-maven-fixture.py /tmp/version-checker-large \
  --modules 100 --declarations-per-module 100
```

The large generator was checked to contain 100 dependency-bearing modules,
10,000 declarations and one additional aggregator POM; it has not been imported
or measured in a native session. The generator refuses a nonempty destination.
Open the generated Maven project, prime reports, enable tracing, and use separate
captures for each endpoint and condition. Regenerate reports with
[summarize-performance.py](../../scripts/summarize-performance.py) and the
metadata stored in each JSON's `conditions` object. Trace files here contain only
the numeric, anonymous fixed-stage schema, without surrounding IDEA log lines.

Pending: complete the 2026.1 small-Maven repetitions, large Maven cases, Gradle
native cases, shared/property/parent fixes, cold/setup and failure cases, typing
profiling, and the remaining usability matrix. npm remains blocked until an
authorized sandbox/profile with working JavaScript/Node support is available.
Conditional native-resolution experiments remain deferred pending evidence of
the corresponding repeated work; see [remaining-plan-work.md](../remaining-plan-work.md).
