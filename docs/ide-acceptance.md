# Packaged plugin acceptance

Run this matrix against the exact candidate archive on IDEA **2026.1.4** and
**2026.2.3**. Automated tests and Plugin Verifier are prerequisites; they do not
replace interactive acceptance. Use a fresh isolated IDE configuration, system,
plugins and log directory per target. Extract the candidate into an empty plugin
directory so an older JAR cannot remain beside it. Keep normal IDE profiles intact.

See the [acceptance record index](acceptance/README.md) for earlier candidate results.
Results belong to their recorded ZIP, not future builds.

## Record the candidate

Retain this information with every result:

- Source commit, PR heads included, archive filename and SHA-256.
- IDE version/build, plugin version confirmed in the IDE log, bundled runtime,
  operating system and hardware.
- Fixture generation command, Node/npm or Maven/Gradle runtime, cold/warm state.
- Scenario, observed result, evidence path and pass/fail/blocked/not-run status.

```sh
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true --console=plain
shasum -a 256 build/distributions/*.zip
python3 scripts/create-native-maven-fixture.py build/acceptance/maven
python3 scripts/create-native-gradle-fixture.py build/acceptance/gradle \
  --repository build/acceptance/maven/repository --catalog
python3 scripts/create-native-npm-fixture.py --help
```

The npm fixture registry serves metadata only by default. Pass `--installable`
to serve tiny loopback-only packages for successful synchronization checks:

```sh
python3 scripts/create-native-npm-fixture.py build/acceptance/npm-installable --installable
```

The packages have no lifecycle scripts or dependencies. This verifies fixture
lockfile synchronization, not real application compatibility. Its delay/authentication controls can exercise
checking and recovery without a live private registry. Use fresh fixture paths
for repeat runs; the generators deliberately refuse to overwrite existing work.

## Required matrix

Each row runs on both IDE targets. Record theme and scaling combinations; check
keyboard use and narrow layouts in both light and dark themes at 100% and 200%
scaling. A missing runtime or unavailable license is a blocked result, not a pass.

| Scenario | Action | Required observation |
| --- | --- | --- |
| Startup and optional integrations | Load ZIP; open Maven, Gradle and npm fixtures. | Correct plugin version; no plugin errors; absent optional integrations do not break the shared UI. |
| Keyboard-only discovery | Open Dependency Versions, focus search, change provider/root filters, select rows, open source/details and return focus. | Controls have useful names; focus remains visible; all operations work without a pointer. |
| Narrow layout and scaling | Narrow tool window to about 400 logical pixels; test both scales/themes. | Search, filters, summary, details and essential actions remain reachable; long reasons wrap or scroll. |
| Observation expiry | Check, export JSON, wait through the original cache deadline, then inspect/export again. | Age is original observation age; export does not renew it; expiry is explained; stale observations cannot authorize edits. |
| Support report | Open **Preview Support Report…**, inspect the JSON, change filters or settings, then use **Save As…**. | Saved JSON matches the frozen preview. Bounds and redaction hold. Original observation timing remains. No checks or uploads start. |
| Coverage and empty results | Break catalog TOML; include unsupported declarations; filter to updates only; then fix the catalog. | Incomplete coverage stays visible even with zero matching updates; source and next action are available; a fresh check clears the corrected error. |
| Selection and rechecking | Select a subset; rapidly change mode/scope; cancel a check; allow older work to complete. | Selection matches exact targets; stale completions cannot enable Apply; cancelling does not start an unintended edit. |
| Apply and undo | Apply Maven, npm workspace and shared Gradle catalog changes; save; undo once and save. | Only reviewed owners change; grouped undo restores exact declaration bytes; lockfiles remain separate. |
| Input changes | Hold preview, edit a source/settings input externally, then Apply; repeat with unsaved editor input. | Stale plans are rejected atomically; current editor contents are preserved. |
| Maven inherited property | Update a parent-managed dependency through a new child property; reload; verify. | Exact controlling property is created; pre-import result is unknown; post-import effective version either matches or reports the actual mismatch. |
| Reload failure | Request reload with a deliberately broken/disconnected build. | Requested, failed/unknown import and version verification remain distinct. No verified-upgrade claim follows a request alone. |
| npm synchronization | Preview command, cancel before start; run a failing command; cancel during execution; run a successful disposable case. | Session records each outcome, changed/unknown lockfiles and partial-write risk; declaration undo never claims to undo native writes. |
| Session lifecycle | Apply twice; invoke a retained action from the first update; change an edited declaration; close/reopen project. | Outcomes stay attached to the right update; later edits invalidate verification; disposed project sessions are cleared. |
| Interaction cost | Use small and large fixtures, cold and warm checks, delayed registry and cancellation. | Record first visible result, review-ready time, table/filter response and typing latency; record fixture/archive/runtime with each value. |

At high zoom, use the **Show dependency filters** toolbar toggle to hide or
restore the four selector rows while keeping search visible. Verify that selected
filters survive this change and that it performs no queries. The clear icon has
the accessible name **Clear dependency filters** and the tooltip **Clear filters**.

## Measurement policy

First collect at least three runs per fixture and cold/warm condition. Preserve
individual values and describe the sampling method. Set regression budgets only
after that baseline exists; a single run is a smoke result, not a percentile
study. Filtering, selecting details and exporting cached observations must perform
zero native checks. Measure the atomic write separately from background checking.

## Evidence and release decision

Store a dated record under `docs/acceptance/` with the candidate identity and a
copy of the matrix populated for each IDE. Keep historical release records as
historical evidence. Do not transfer acceptance from an earlier ZIP to a changed
candidate or infer interactive success from unit/native integration tests.

Retain sanitized logs/screenshots or byte comparisons supporting each pass. Record
remaining blockers explicitly in the PR/release notes. This document defines the
acceptance procedure; it does not assert that any scenario has passed.
