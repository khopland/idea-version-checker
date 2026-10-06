# Version Checker 1.0.0 release verification

Verified locally on 6 October 2026, on macOS arm64.

## Release artifact

- Version: `1.0.0`.
- Archive: `build/distributions/version-checker-1.0.0.zip`.
- SHA-256: `513a1e7f4dc927f7b6ca7076fb2238631185b0e0081668836251011a38f30faa`.
- Minimum IDE build: `253.33813.55` (IDEA 2025.3.6.1).
- The packaged descriptor contains the finalized 1.0.0 change notes.
- `META-INF/LICENSE` in the plugin JAR matches the repository's Apache 2.0 license.
- The custom 40×40 SVG icon is packaged under `META-INF/pluginIcon.svg`, with four pixels of transparent padding. It was rendered and visually checked at 40, 80 and 120 pixels on light and dark backgrounds; the same asset works on both themes.

## Build and behavior checks

The following commands passed:

```sh
./gradlew test buildPlugin verifyPluginProjectConfiguration
./gradlew verifyPlugin
./gradlew test -PmavenIntegration=true -PnpmIntegration=true \
  --tests '*MavenSettingsIntegrationTest' --tests '*NpmRegistryIntegrationTest'
./gradlew getChangelog --project-version=1.0.0 --no-header \
  --output-file=build/release-notes/1.0.0.md
```

- The final combined run passed all 99 tests (96 default tests and three integration tests), using the merged Kotlin 2.4.20 build configuration. Default coverage includes Maven multi-edit undo, npm workspace undo, stale-preview rejection and Maven coroutine cancellation.
- All three integration tests passed. They exercise authenticated Maven/npm access, update modes, current-file isolation, npm manifest application without lockfile changes, and cancellation of a running npm query.
- npm plan checks confirm that the synchronization reminder survives mixed-plan combination, is deduplicated, and is absent when no npm edits are prepared.
- Plugin Verifier reports compatibility with IDEA 2025.3.6.1 and 2026.1.4. It reports two deprecated API usages and seven experimental API usages on each build; no blocking compatibility problems were reported.

## Installed ZIP startup checks

Each check extracted the release ZIP into a separate plugins directory and launched a real headless IDE with fresh configuration, system and log directories. A temporary verification plugin checked Version Checker's loaded version, adapter and inspection registration, all eight actions, its project services, and construction/reset of its settings component. The verification plugin is separate from the release ZIP.

| IDE | Maven enabled, JavaScript disabled | npm enabled, Maven/Java disabled | Both enabled | Maven/Java/JavaScript disabled |
| --- | --- | --- | --- | --- |
| 2025.3.6.1 | Passed | Blocked by subscription availability | Not run: same subscription requirement | Passed |
| 2026.1.4 | Passed | Passed | Passed | Passed |

The fresh, unlicensed IDEA 2025.3.6.1 profile disabled its Ultimate module and therefore its JavaScript plugin. Its npm startup check found no npm adapter, as expected when that optional dependency cannot load. npm behavior on this build passed the IntelliJ integration fixtures, but the installed ZIP's npm and mixed startup checks still need a licensed profile. The user accepted documenting this limitation.

These are automated startup and platform behavior checks. Manual visual review of the preview/notification and clicking through the IDE's Install Plugin from Disk flow were not verified. A graphical disposable IDE was launched, but the computer-use tool could not select that JVM's window, so it was closed without changing the user's IDE.

Local startup logs and the temporary verification harness are retained under `build/reports/release-smoke/`. Bundled plugins unavailable in an unlicensed profile produced unrelated loading messages; the successful checks explicitly verified Version Checker's own registrations and services.

## Release preparation

The build workflow reads the finalized changelog entry for the release version. The publication workflow builds the tagged source without rewriting its changelog or opening a changelog PR. The release preparation is committed with the custom icon. No tag or Marketplace publication is created by these local checks; the existing remote snapshot release draft is unchanged.
