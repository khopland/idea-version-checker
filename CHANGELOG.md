# Version Checker Changelog

## [Unreleased]

### Added

- Two quick-fix choices for inherited Maven dependency versions: override locally or update the controlling literal/property in an imported parent POM, with stale-context protection.

- npm dependency inspections, range-preserving quick fixes and all three bulk-update modes for package.json and workspaces.
- Local or workspace-wide npm quick fixes, preserving per-declaration selectors and applying workspace changes in one undoable command.
- Read-only npm registry checks through IntelliJ's local Node/npm configuration, including scoped registries, authentication, aliases and deprecation notices.
- Manifest-only npm updates; lockfiles and installation remain with IntelliJ/npm. Peer compatibility and unsupported selectors are listed for review.
- Package-manager detection, npm stale-preview protection and authenticated registry integration tests.

- Shared build-system adapter model and coordinator; Maven discovery, metadata lookup and XML edits are isolated in the Maven adapter.
- Generic artifact/declaration identities, context-isolated caching, refresh generations and a format-independent edit bridge.
- Optional Maven registration and contract tests using a non-Maven provider.

- Maven dependency version inspection with configurable severity and literal/property update quick fixes.
- Background checks through IDEA's Maven server, respecting Maven settings, mirrors and authentication.
- Cached per-POM results, offline support, and a Tools action to refresh version checks.
- Report parsing, POM quick fix tests, and an optional authenticated settings/mirror integration test.
- Native IDEA inspection highlighting and standard inspection support for InlineProblems.
- Bulk update previews for patch-only, minor + patch, and major + minor + patch updates across imported modules.
- Shared-property conflict detection and stale-preview protection for bulk edits.
- An extension assessment for Gradle and npm/pnpm/Bun support.
- Independent severity settings for patch, minor, major, other version changes, and deprecated/relocated dependencies; updates default to yellow warnings.
- Explicit project deprecation rules and relocation notices read from Maven's downloaded dependency POMs.
- Separate Current File and Whole Project actions for checks and bulk updates.
- Build-plugin version inspections, quick fixes and all three bulk-update modes, including `pluginManagement` and active profiles.
- Plugin checks respect Maven plugin repositories and runtime prerequisites; restricted modes query candidates within the selected version branch.
- Shared-property safety checks across imported modules when updating only the current POM.

### Fixed

- Shared actions, settings and notification names are build-system neutral; Current File and Whole Project automatically select matching adapters.
- Removed artifact roles from the shared model; Maven keeps its dependency/plugin classification private.
- Removed unreleased compatibility aliases and plugin-only bulk planning. Combined plans retain every provider’s stale-preview guard.

- Maven dependency and build-plugin updates now share one action group and preview; compatible shared properties are edited once.
- Stale in-flight results are discarded after refresh, and prepared edits are invalidated by changes to imported POMs or Maven configuration.

- Bulk checks no longer fail when a successful module produces no dependency-update report.
- Bulk check failures are logged and include a Show details action.
