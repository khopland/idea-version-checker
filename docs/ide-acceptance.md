# Packaged plugin acceptance

Run this matrix against the exact candidate archive on IDEA **2026.1.4** and
**2026.2.3**. Automated tests and Plugin Verifier are prerequisites; they do not
replace interactive acceptance. Use a fresh isolated IDE configuration, system,
plugins and log directory per target. Extract the candidate into an empty plugin
directory so an older JAR cannot remain beside it. Keep normal IDE profiles intact.

Record results in the pull request or release draft.
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
| Coverage and empty results | Break catalog TOML; include unsupported declarations; open **Review Updates…**; then fix the catalog. | The review shows incomplete coverage even with zero updates; a fresh check clears the corrected error. |
| Selection and rechecking | Select a subset; rapidly change mode/scope; cancel a check; allow older work to complete. | Selection matches exact targets; stale completions cannot enable Apply; cancelling does not start an unintended edit. |
| Apply and undo | Apply Maven, npm workspace and shared Gradle catalog changes; save; undo once and save. | Only reviewed owners change; grouped undo restores exact declaration bytes; lockfiles remain separate. |
| Input changes | Hold preview, edit a source/settings input externally, then Apply; repeat with unsaved editor input. | Stale plans are rejected atomically; current editor contents are preserved. |
| Maven inherited property | Update a parent-managed dependency through a new child property; reload. | Exact controlling property is created; after reload the Maven tool window shows the new effective version. |
| Reload failure | Request reload with a deliberately broken/disconnected build. | The reload notification reports the request or failure and points to the build tool window. No success claim follows a request alone. |
| npm synchronization | Preview command, cancel before start; run a failing command; cancel during execution; run a successful disposable case. | Each outcome is reported with changed/unknown lockfiles and partial-write risk; declaration undo never claims to undo native writes. |
| Interaction cost | Use small and large fixtures, cold and warm checks, delayed registry and cancellation. | Record first visible result, review-ready time, table/filter response and typing latency; record fixture/archive/runtime with each value. |

At high zoom, use the **Show dependency filters** toolbar toggle to hide or
restore the four selector rows while keeping search visible. Verify that selected
filters survive this change and that it performs no queries. The clear icon has
the accessible name **Clear dependency filters** and the tooltip **Clear filters**.

## Measurement policy

First collect at least three runs per fixture and cold/warm condition. Preserve
individual values and describe the sampling method. Set regression budgets only
after that baseline exists; a single run is a smoke result, not a percentile
study. Filtering and selecting details of cached observations must perform
zero native checks. Measure the atomic write separately from background checking.

## Evidence and release decision

Record the candidate identity and populated matrix in the pull request or release
draft. Keep supporting evidence in workflow artifacts or release attachments.
Do not transfer acceptance from an earlier ZIP to a changed
candidate or infer interactive success from unit/native integration tests.

Retain sanitized logs/screenshots or byte comparisons supporting each pass. Record
remaining blockers explicitly in the PR/release notes. This document defines the
acceptance procedure; it does not assert that any scenario has passed.
