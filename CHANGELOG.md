# Maven Version Checker Changelog

## [Unreleased]

### Added

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

### Fixed

- Bulk checks no longer fail when a successful module produces no dependency-update report.
- Bulk check failures are logged and include a Show details action.
