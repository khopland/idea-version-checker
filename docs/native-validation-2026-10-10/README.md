# Native validation: 10 October 2026

Small Maven cases ran in native IDEA windows with the packaged plugin. Both IDEA
2025.3.6.1 and 2026.1.4 now have at least 100 completed samples for each measured
endpoint. The full plan's performance and usability matrix is still incomplete.
Small warm Gradle editor, preview and literal-fix cases have also completed 100
repetitions each in 2026.1.4; the 2025.3.6.1 Gradle preview has 100 repetitions.

The [phase 2 follow-up](phase2.md) adds native npm editor, preview, apply/undo and
authentication-recovery checks in IDEA 2026.2.3 using working JavaScript/Node
support in the available installation. It also adds minimum-IDE Gradle
diagnostics/fix evidence, current-IDE shared/dense edits and provider-disabled
smoke tests. The earlier npm blocker still applies to the minimum-IDE profile;
phase 2 and the full release matrix remain incomplete.

The [preview recovery follow-up](preview-recovery.md) fixes the generic npm
failure notification found during phase 2. The final archive passed native
authentication and empty-offline-cache recovery with fresh retry preserving the
latest scope/mode; the automated suite now contains 401 passing plugin tests.

## Results

| IDEA | Endpoint | Completed samples | Median | Nearest-rank p95 | Evidence |
|------|----------|------------------:|-------:|-----------------:|----------|
| 2025.3.6.1 | Cached editor diagnostic | 105 | 2.31 ms | 3.49 ms | [Report](idea-2025.3.6.1-maven-small-editor.json), [trace](idea-2025.3.6.1-maven-small-editor.log) |
| 2025.3.6.1 | Cached preview, 3 rows | 100 | 27.52 ms | 43.86 ms | [Report](idea-2025.3.6.1-maven-small-preview.json), [trace](idea-2025.3.6.1-maven-small-preview.log) |
| 2025.3.6.1 | Literal local fix | 100 | 2.63 ms | 5.45 ms | [Report](idea-2025.3.6.1-maven-small-fix.json), [trace](idea-2025.3.6.1-maven-small-fix.log) |
| 2026.1.4 | Cached editor diagnostic | 100 | 4.78 ms | 6.11 ms | [Report](idea-2026.1.4-maven-small-editor.json), [trace](idea-2026.1.4-maven-small-editor.log) |
| 2026.1.4 | Cached preview, 3 rows | 100 | 25.42 ms | 34.23 ms | [Report](idea-2026.1.4-maven-small-preview.json), [trace](idea-2026.1.4-maven-small-preview.log) |
| 2026.1.4 | Literal local fix | 100 | 3.89 ms | 6.58 ms | [Report](idea-2026.1.4-maven-small-fix.json), [trace](idea-2026.1.4-maven-small-fix.log) |

The completed latency samples are below the plan's editor/preview/fix p95
targets for this condition only. They do not establish performance for other
ecosystems, large projects, shared edits, cold caches or delayed repositories.

Editor sampling alternated an ordinary Java source tab and a POM with cached,
visible version diagnostics. There were 110 activation pairs and 105 completed
diagnostic endpoints in 2025.3; incomplete interactions are excluded by the
report. The final 2026.1 capture used direct tab clicks for 100 activation pairs
and has 100 completed diagnostic endpoints. An earlier Switcher-based attempt
was interrupted by native input routing; it was not used for this final capture.
All final editor and preview captures contain zero native query stages. Preview
sampling cancelled each dialog and reused current Patch reports.

Local-fix sampling used Alt+Enter to update alpha from 1.0.0 to 2.0.0, verified the
changed editor text, then undid and saved to restore the hint. No fix interaction
owns a native query, and no query starts inside a measured fix interval. Undo and
hint restoration did cause background native work: 94 goals in the 2025.3
capture and 110 in the final 2026.1 capture. Already running queries overlapped 15 of the
2025.3 fixes; none overlapped the 2026.1 fixes. Thus the 2025.3 capture does not
certify a globally idle repository throughout every fix, although fixes did not
initiate or wait for those goals.

The earlier interrupted 2026.1 fix capture remains available as a
[partial report](idea-2026.1.4-maven-small-fix-partial.json) and
[trace](idea-2026.1.4-maven-small-fix-partial.log). It contains 75 verified fix/undo
cycles plus one further completed fix, with median 3.80 ms and p95 5.35 ms. Its
76 endpoints are retained and flagged below the required count. Raising the
sandbox project window restored input, allowing a fresh uninterrupted capture
of 100 verified fix/undo cycles. No query starts inside, belongs to, or overlaps
any of those final fix intervals. Native input interruptions are not recorded
as plugin failures or completed usability cases.

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
- The archive used for the 2026.1 latency captures has SHA-256
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

Further 2026.1 checks verified descending declaration sorting (gamma, beta,
alpha), filtered Space toggles affecting gamma, repeated toggles retaining the
highlighted row, Shift+Tab returning to the filter, and two hidden selections
surviving filtering to zero visible rows. Clear selection removed hidden checks
and disabled Apply. Applying three Major edits changed all three declarations;
one undo restored all three. An empty Patch preview retained mode/scope controls,
readable empty-state text, disabled Apply and working Refresh, which returned
another empty preview.

Mode navigation also exposed premature dialog disposal before Enter. Committed
selection handling now keeps navigation within the popup, defers rebuilding until
the key/mouse event finishes, and rejects Apply against a pending mode/scope
change. The follow-up below confirms this behavior and stale-preview rejection in
2026.1. The Gradle follow-up below also checks failed-file Retry and
delayed/offline/authentication states in 2026.1. The corresponding Maven cases,
other IDEA version and remaining keyboard/focus matrix still require dedicated
sessions. Automated regression coverage is separate evidence.

### Packaged preview/recovery follow-up, IDEA 2026.1.4

The packaged plugin from `3873549` was checked in the same isolated small Maven
fixture. Archive SHA-256:
`13d23f453088c2fb929438ebc00af2ee97b1a62a73366a118dd1bc56b752c7e4`.
The [sanitized trace](idea-2026.1.4-preview-recovery.log) records this usability
session, which mixes modes/scopes and is separate from performance samples.

- With Patch prepared, opening Updates and pressing Down highlighted Minor
  while keeping the dialog and Patch rows unchanged. Escape restored Patch and
  kept the dialog open. Down followed by Enter rebuilt the Minor preview with
  1.1.0 targets; Enter did not insert text in the editor.
- Committing Whole Project rebuilt the single-module preview from cached Minor
  reports. Refresh displayed readable Whole Project/Minor progress and queried
  fresh results. The initial preview, mode change, scope change and Refresh have
  distinct IDs 2, 3, 4 and 5, each with one ready endpoint.
- Apply changed alpha/beta/gamma to 1.1.0 under fix ID 6, followed by one
  changed-text endpoint with count 3. One editor undo restored all three to
  1.0.0. These are one apply/undo cycle, not a 100-sample fix measurement.
- A second Patch preview stayed prepared while the main window was activated
  through Window → maven-small and beta was edited to 1.1.0. Reinvoking Review
  brought the original dialog forward, still showing its earlier beta baseline.
  Apply rejected that plan with a readable changed-input explanation and no
  partial edits. Dismissing the explanation rebuilt a two-row preview for alpha
  and gamma, preserving beta's edit. Cancelling it and undoing the manual edit
  restored the fixture.
- The rejected fix ID 12 has no changed-text endpoint. Recovery has its own
  preview ID 13 and ready endpoint with count 2. Reinvocation ID 11 has no new
  ready endpoint because it focused the existing dialog; an incomplete endpoint
  in this trace is not by itself a failure.

These cases verify native interaction and trace boundaries in 2026.1 only. They
do not complete the remaining failure, keyboard, large-project or typing cases.

## Native Gradle editor and preview, IDEA 2026.1.4

| Endpoint | Completed samples | Median | Nearest-rank p95 | Evidence |
|----------|------------------:|-------:|-----------------:|----------|
| Cached current-file Major editor diagnostic | 100 | 3.08 ms | 9.82 ms | [Report](idea-2026.1.4-gradle-small-editor.json), [trace](idea-2026.1.4-gradle-small-editor.log) |
| Cached current-file Patch preview, 3 rows | 100 | 31.35 ms | 44.28 ms | [Report](idea-2026.1.4-gradle-small-preview.json), [trace](idea-2026.1.4-gradle-small-preview.log) |

The same isolated IDEA 2026.1.4 process imported one Java Gradle project with
three Groovy literal dependency declarations for fixture:alpha/beta/gamma. The
wrapper uses Gradle 9.8.0 and the Gradle daemon uses SDKMAN Eclipse Temurin
25.0.3+9-LTS. The local file repository and published versions match the Maven
fixture. The packaged archive has SHA-256
`13d23f453088c2fb929438ebc00af2ee97b1a62a73366a118dd1bc56b752c7e4`.

The Patch report and Gradle installation were primed before capture. Each
repetition invoked Review Dependency Updates, verified the showing Gradle
dialog reported cached results and three selected/visible rows, then cancelled
back to build.gradle. All 100 interactions have a ready endpoint and no
incomplete previews. No native Gradle invocation belongs to, starts within, or
overlaps these preview intervals; the entire capture contains no native query.
This meets the preview target for this condition only. The later follow-up below
adds small literal-fix latency, a 2025.3 preview and selected recovery cases;
large-project behavior, Kotlin/catalog performance and cold setup remain pending.

This native session also exposed declaration labels containing only script
offsets, such as dependency@138. Preview and shared-version review labels now
include dependency coordinates alongside offsets or catalog aliases. Platform
regressions verify filtering repeated declarations by artifact and applying only
the selected matches, plus both consumer identities in one shared catalog edit.

The corrected labels were then checked with a newly packaged archive, SHA-256
`f43ffbbe135f36d28d5da8e6d9f3816cc366bec1bb1705fbb302e768292b86a3`.
The [separate follow-up trace](idea-2026.1.4-gradle-preview-filter.log) records
one fresh Patch preview (ID 2), filtering by fixture:beta after clearing selection,
selecting the one visible match and applying it (fix ID 3, changed count 1).
Only beta changed to 1.0.1; alpha and gamma remained 1.0.0. One undo and save
restored beta. This is a usability check, separate from the earlier warm timing
capture. The sandbox reported a bundled Groovy GrabDependencies service-in-class-
initializer error on startup; the log attributes it to the Groovy plugin.

The corrected archive also supplied the warm editor capture. Direct tab clicks
alternated build.gradle and Fixture.java 100 times. The three build-file hints
were in the viewport, and each build-file activation has a completed diagnostic
endpoint. All 100 source-file activations lack a Version Checker diagnostic and
remain counted as incomplete in the anonymous report, rather than becoming
latency samples. There are no native invocations in this capture. The ordinary
source tab showed Project JDK is not defined; the imported Gradle project and
native resolution used the configured Gradle JVM above. The build file also had
three unrelated Groovy warnings alongside the three Version Checker warnings.
The measured endpoints require Version Checker inspection markup specifically.

### Native Gradle fixes, previews and recovery follow-up

| IDEA | Endpoint | Completed samples | Median | Nearest-rank p95 | Evidence |
|------|----------|------------------:|-------:|-----------------:|----------|
| 2026.1.4 | Cached current-file Major literal fix | 100 | 2.88 ms | 4.24 ms | [Report](idea-2026.1.4-gradle-small-fix.json), [trace](idea-2026.1.4-gradle-small-fix.log) |
| 2025.3.6.1 | Cached current-file Patch preview, 3 rows | 100 | 31.32 ms | 53.24 ms | [Report](idea-2025.3.6.1-gradle-small-preview.json), [trace](idea-2025.3.6.1-gradle-small-preview.log) |

Both captures use separate isolated installations, one imported Java/Groovy
module, three literal declarations, Gradle 9.8.0, SDKMAN Temurin 25.0.3+9-LTS
and the same primed local repository described above. Tracing was enabled.
The 2026.1 installation used archive SHA-256
`f43ffbbe135f36d28d5da8e6d9f3816cc366bec1bb1705fbb302e768292b86a3`.
The 2025.3 installation used the final archive, SHA-256
`55874205f573f1d89810bd1bda48c778df3b565d452f489063092b4da462fea9`,
including the multiline viewport-tracing correction. The preview capture does
not itself validate that diagnostic endpoint.

Each 2026.1 fix changed only alpha from 1.0.0 to 2.0.0; beta and gamma remained
unchanged. Undo and save restored alpha after every completed cycle. Native input
routing interrupted one attempted cycle without changing the declaration;
raising the project window allowed the remaining cycles to finish. Only the
100 verified fixes have endpoints. None owns, starts or overlaps a native
invocation; one background native invocation elsewhere in the capture is
retained. Each 2025.3 preview showed three selected/visible cached rows and was
cancelled. All 100 previews completed, and the capture contains no native query.
These p95 results meet the corresponding targets for these conditions only.

Opening the disposable 2025.3 fixture required disabling the isolated sandbox's
native file chooser and using IDEA's chooser. Editor/fix sampling was attempted
but did not produce a verified Version Checker diagnostic or fix. An unrelated
Groovy intention was immediately undone and excluded. The [phase 2 follow-up](phase2.md)
subsequently observed the expected inspection markup and local fix with the
snapshot archive on 2025.3. That functional check does not supply the missing
repeated editor/fix performance measurements.

The separate [2026.1 recovery trace](idea-2026.1.4-gradle-recovery.log) records a
mixed usability session, rather than a 100-sample benchmark:

- Enabling Gradle offline mode and refreshing displayed an offline status with
  instructions to disable Work offline; no native query ran while offline.
  Disabling it and refreshing restored checking against the local repository.
- A disposable loopback Maven repository with test-only Basic credentials
  rejected authentication with HTTP 401. IDEA displayed a retry status,
  repository/authentication guidance and visible Retry Failed Checks and Show
  Details actions. Opening Show Details was not verified.
- After authentication was accepted and the server delayed each request by
  1,000 ms, Retry Failed Checks progressed through checking to three updates and
  zero notices. Retry interaction 319 has a 21,503.87 ms native invocation;
  first useful acceptance and complete checking remain distinct trace stages.
  This duration is one delayed retry, not a latency distribution.
- The HTTP observation log contained 23 request observations, including eight
  401 responses (some were Basic authentication challenges) and fifteen served
  requests, with no JAR requests. The original file-repository settings were
  restored and the temporary server was stopped.

These checks cover this small Gradle fixture in 2026.1 only. They do not establish
typing responsiveness, profiler results, cold setup or the full failure matrix.

## Reproduction and remaining work

Create an equivalent disposable fixture with:

```sh
python3 scripts/create-native-maven-fixture.py /tmp/version-checker-small
python3 scripts/create-native-maven-fixture.py /tmp/version-checker-large \
  --modules 100 --declarations-per-module 100
python3 scripts/create-native-gradle-fixture.py /tmp/version-checker-gradle-small \
  --repository /tmp/version-checker-small/repository
python3 scripts/create-native-gradle-fixture.py /tmp/version-checker-gradle-large \
  --repository /tmp/version-checker-large/repository \
  --modules 100 --declarations-per-module 100
python3 scripts/create-native-gradle-fixture.py /tmp/version-checker-gradle-catalog \
  --repository /tmp/version-checker-small/repository --dsl kotlin --catalog
```

The large generator was checked to contain 100 dependency-bearing modules,
10,000 declarations and one additional aggregator POM; it has not been imported
or measured in a native session. The generator refuses a nonempty destination.
The Gradle generator was also checked to contain 100 dependency-bearing projects
and 10,000 dependency uses, plus a root aggregator; that large fixture has not
been imported or measured natively. Small Groovy and Kotlin/shared-catalog
fixtures passed wrapper `help`, and nonempty-destination protection was checked.
Catalog fixtures distinguish dependency uses from unique library declarations
and the single shared version. The wrapper is copied from this checkout.
Open the generated Maven project, prime reports, enable tracing, and use separate
captures for each endpoint and condition. Regenerate reports with
[summarize-performance.py](../../scripts/summarize-performance.py) and the
metadata stored in each JSON's `conditions` object. Trace files here contain only
the numeric, anonymous fixed-stage schema, without surrounding IDEA log lines.

Pending: large Maven cases, Gradle 2025.3 editor/fix measurements, remaining large
and per-version Kotlin/catalog Gradle cases, shared/property/parent fixes,
cold/setup and remaining per-ecosystem/per-version failure cases, controlled
typing profiling and the remaining usability matrix. Minimum-IDE npm remains
unvalidated; current-IDE npm functional evidence is recorded in [phase2.md](phase2.md).
Conditional native-resolution experiments remain deferred pending evidence of
the corresponding repeated work; see [remaining-plan-work.md](../remaining-plan-work.md).
