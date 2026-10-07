# Version Checker Changelog

## Unreleased

### Added

- Maven `<parent>` version updates for parents outside the workspace. Reactor and snapshot parents are left unchanged.

### Fixed

- Maven bulk updates skip shared properties referenced in XML attributes, including uses in unselected modules.
- Maven metadata fallbacks only use the current project's effective release repositories and mirrors.
- npm workspace glob matching recognizes zero-level globstars and uses minimatch semantics for inclusions and exclusions.
- Local Maven literal, property, plugin and parent quick fixes reject stale coordinates, declaration context and Maven configuration.
- npm project detection accepts object and array forms of `devEngines.packageManager`; mixed, empty or malformed manager declarations remain excluded.
- npm quick fixes show the lockfile and installed-dependency synchronization reminder after a successful edit.
- npm discovery and manifest fingerprints are cached between changes, bulk validation shares workspace fingerprints, and edits to independent npm projects no longer invalidate each other.
- Maven checks no longer fail when the resolver cannot order an artifact's versions ("Comparison method violates its general contract"), for example when a locally installed `0-SNAPSHOT` meets branch-named snapshots. That artifact is resolved from the metadata Maven downloaded, and the remaining dependencies are still checked.

- Initial Gradle support for linked Groovy/Kotlin build files and default local version catalogs, through IntelliJ’s Gradle tooling and project repository configuration.
- Gradle inspections, version quick fixes, scoped bulk previews, shared-catalog conflict checks, and authenticated repository integration coverage.
- Maven `<parent>` version updates for parents outside the workspace. Reactor and snapshot parents are left unchanged.

## [1.0.0] - 2026-10-06

### Added

- Maven dependency and build-plugin inspections, including managed versions, active profiles and local version properties.
- Read-only checks through IntelliJ's Maven configuration, respecting repositories, settings, mirrors, credentials and offline mode.
- npm dependency inspections for exact, caret and tilde selectors, scoped packages and aliases, with authenticated registry and workspace support through IntelliJ's local Node/npm runtime.
- Quick fixes for Maven literals and properties, local overrides or parent edits for inherited Maven dependencies, and local or workspace-wide npm declaration updates.
- Current File and Whole Project checks and bulk-update previews for patch, minor or major updates, including combined Maven/npm projects.
- Undoable edits, shared-property conflict checks and rejection of stale results or previews when build files or relevant configuration change.
- Configurable severity, project deprecation rules, Maven relocation notices and npm registry deprecation messages.
- npm lockfile synchronization reminders in bulk previews and after applying updates.
- Apache 2.0 license, included in the distributed plugin JAR.
- Custom plugin icon combining an update arrow and a checkmark.

### Scope

- Requires IntelliJ IDEA 2025.3.6.1 or later; verified against 2025.3.6.1 and 2026.1.4.
- npm updates edit package.json declarations. Synchronize lockfiles and installed dependencies afterward through IntelliJ or npm.
- Complex npm selectors, local/workspace dependencies and peer compatibility require manual review.
- Gradle, pnpm, Yarn, Bun and remote Maven/Node runtimes are outside this release's scope.
