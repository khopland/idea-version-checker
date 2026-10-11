# Native 100-module Gradle follow-up

2026-10-11, same Apple M4 / 16 GiB host and isolated IDEA 2026.2.3 installation
as the [npm acceptance record](README.md). The first archive was
`0b3e71ec74054ac9d52acf6037ccd4df1de81ea481340994da0abea501cf8a9f`.
`versionchecker.gradleScanAggregation=true` was enabled only in the disposable
test profile. The shipped default remains off.

Fixture generation used the checked-in scripts and a local file repository:

```sh
python3 scripts/create-native-maven-fixture.py /tmp/version-checker-native-repository-20261011
python3 scripts/create-native-gradle-fixture.py /tmp/version-checker-acceptance-gradle-large-20261011 \
  --repository /tmp/version-checker-native-repository-20261011/repository \
  --modules 100 --declarations-per-module 100 --repeat-artifact alpha
```

The fixture has 100 dependency-bearing modules, each with 100 identical alpha
declarations, plus an empty aggregator. All selectors start at 1.0.0; the generated
repository offers patch/minor/major targets. Import succeeded. A Current File/Patch
review displayed 100 checked/selected declarations; its one native endpoint took
3,693.12 ms including the uncached native check. Whole Project was selected in the
same review, and Apply was disabled while the check ran.

## Failed alias-discovery run

The [anonymous trace](idea-2026.2.3-gradle-large-alias.log) records a whole-project
endpoint with **20,000** changes and 202 source checks, rather than the expected
10,000 changes and 101 source files. The linked root used `/tmp`; Gradle's imported
modules used `/private/tmp`, including the canonical root. Discovery's ancestry
test treated those as separate trees and collected both aliases. The mixed session
made 11 native invocations: one for Current File, then ten whole-project batches.
Its whole-project endpoint took 6,793.66 ms. This is a failed correctness sample,
not evidence that the intended 10,000-row scale is accepted.

Discovery now compares VFS-known canonical locations when deduplicating roots,
recognizing contained modules and deduplicating source files. The linked root is
ordered first so its namespace wins when the imported root is an alias. This
preserves editor/source paths rather than replacing them with new physical paths.
The real-filesystem regression links a symlinked root and canonical imported
root/module paths, and asserts one declaration and one snapshot per source in
the linked namespace. Explicit external IO remains outside the read lock; link
targets come from the IDE's VFS metadata.

## Corrected count retest

The corrected archive is
`2f6911ff638d2bd04e0baeeefe62e751fc766fd0e72bde20e559c1cbc791c3ac`.
After restarting the isolated IDE with it, Current File/Patch displayed 100
checked and selected declarations. Its endpoint took 2,906.87 ms. Switching the
same review to Whole Project disabled Apply while checking. The
[corrected anonymous trace](idea-2026.2.3-gradle-large-fixed.log) then recorded
**10,000** changes and **101** source checks at the preview endpoint, taking
6,822.82 ms. The session made six native invocations: one Current File invocation
and five whole-project batches. The whole-project native queries covered 9,900
declarations, reusing the already checked 100; semantic probes totaled 99.
This is one mixed-cache correctness retest. The build/verifier run overlapped
part of the session, so these durations are not controlled latency samples.

Inspecting the completed 10,000-row review through native accessibility timed
out after 120 seconds, despite requesting a shorter tool timeout. A subsequent
thread dump showed the EDT waiting in `EventQueue.getNextEvent`. The IDE also
logged a Swing sorter row-count warning during replacement; final scrolling,
filtering and keyboard acceptance remain open. No update was applied. All 10,000
fixture selectors remained 1.0.0 on disk, and no compiled class files were
produced. The owned IDE and loopback npm fixture server were stopped; the user's
IDE was untouched.

Full validation passed **494 plugin tests in 68 classes**, with zero failures,
errors or skips and all three native integration flags enabled. Release/script
suites passed **13 + 16 tests**. Packaging, project-configuration validation and
Plugin Verifier passed against IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3, each with
the existing 14 experimental API usages and no deprecated/scheduled usages.
The full run took 3m 11s; local log: `/tmp/version-checker-gradle-alias-full.log`.

```sh
JAVA_HOME=/Users/kristoffer/.sdkman/candidates/java/21.0.12+1.1-tem
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true \
  --no-configuration-cache --console=plain
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B -m unittest discover -s scripts -p 'test_*.py'
```

## Profiling and tooling limits

The [JFR sample summary](idea-2026.2.3-gradle-large-alias-profile.json) covers import,
startup and both reviews. Of 11,308 execution/native samples, 135 were on the EDT
and two included a plugin frame. This sampling does not establish EDT spans,
typing delay, retained heap or latency p95. The raw JFR stays in the disposable
local workspace and is not published; the summary contains class/method names
and counts only.

After the oversized review opened, native screenshot inspection timed out after
120 seconds, and a subsequent keyboard/AX request timed out after ten seconds.
Separate thread dumps before and after the timeout showed the EDT parked in
`EventQueue.getNextEvent`. That does not establish a plugin UI freeze or successful
keyboard interaction. No update was applied. The owned IDE was stopped after
recording the evidence; the user's running IDE was untouched.

The repeated latency/typing, filtering, dense Apply/undo, cancellation and heap
matrix still gates aggregation/default and stable release. Incomplete/tooling
samples are retained separately from completed interaction measurements.

## Sorter and interaction follow-up

The [review-fix batch](../plan-fixes-2026-10-11.md) reproduces and corrects the
sorter notification order. Native IDEA 2026.2.3 was restarted with archive
`1fb04a19f48ff67dcda8be92c05b53d5cc2e24b179c8e14f2286e4694266c12a` in the same
isolated profile. Aggregation was enabled only in that disposable profile.
The [anonymous trace](idea-2026.2.3-sorter-followup.log) and
[functional summary](idea-2026.2.3-sorter-followup.json) record this session.

- Current File/Patch displayed 100 checked and selected declarations. With the
  filter set to `module-099`, switching to Whole Project showed pending work with
  Apply disabled, then 10,000 checked declarations and 100 visible rows. The
  original 100 selected rows remained hidden; newly introduced targets were
  unselected. Keeping a filter active avoided enumerating all 10,000 rows through
  accessibility, which had timed out in the prior session.
- Clicking the Declaration header sorted the filtered rows. Highlighting the
  first visible row and pressing Space selected it, giving 101 selected rows,
  including the original 100 hidden selections. Clearing selection, removing the
  filter and selecting visible rows selected all 10,000 declarations. Scrolling
  the unfiltered sorted table moved the visible rows from module-000 into
  module-004. A screenshot showed `10000 selected · 10000 of 10000 visible`.
  Reapplying the module filter showed 9,900 selected rows hidden by the filter.
- Apply closed the review and saved **10,000 targets at 1.0.1 across 100 files**.
  One native undo followed by Save restored all **10,000 selectors to 1.0.0**.
  Every file's SHA-256 matched its pre-Apply bytes. No compiled class files were
  produced.
- A first Refresh/cancel attempt completed its 3,616.38 ms preview before Cancel
  was delivered; it is retained as a completed refresh, not a cancellation pass.
  For a controlled retest, a reversible 15-second `projectsEvaluated` delay was
  added to the disposable root script after the 10,000-row review was prepared.
  Refresh disabled Apply; Cancel closed the review during the native check.
  That interaction has no preview-ready or edit endpoint. The delay was removed
  and all declaration files remained identical to their baseline. The native
  invocation finished after 15,780.45 ms, after the injected delay; the host
  check's cancellation endpoint was earlier. This establishes no late result or
  edit, but does not establish prompt native-worker termination.

There were **zero sorter warnings and zero new IDEA error log entries** during
the session. The existing IDE error indicator was already present at startup.
One large mixed-cache preview took 8,326.60 ms; the 10,000-edit invocation-to-text
endpoint took 1,119.31 ms. These are single functional observations, with the
automated build/tests overlapping part of the session. They are not controlled
latency distributions, EDT/write-pause spans, typing responsiveness measurements
or a cancellation latency certification.

The ordinary 10,000-row review's sorting, scrolling, filtering, keyboard selection
and Apply/undo functional checks now pass on this current-IDE archive. Repeated
performance, native dense-single-file interaction, screen-reader/focus coverage,
other IDEs and retained-heap acceptance remain open. Aggregation's shipped
default and release identity are unchanged.
