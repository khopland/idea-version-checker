# Version Checker Changelog

## [Unreleased]

### Added

- **Review Dependency Updates…** in supported build files' editor context menu opens a searchable preview with checkboxes, update mode and scope controls, cached-result age, and Refresh. Empty previews retain these controls, and skipped items have a separate Needs review section.

### Changed

- npm inspections publish finished packages while slower queries continue. Aliases retain their shared lookup, and one package failure preserves other successful hints while rejecting a complete bulk preview. Early results retain freshness, cancellation and refresh guards.
- Bulk previews reuse current, unexpired results for the requested update mode. Refresh still forces native revalidation. Selected edits retain all input and shared-consumer guards, and expired or replaced results require another preview before applying.
- Bulk apply preserves valid warnings while rechecking changed declarations, and shows npm/Gradle synchronization guidance only for selected build systems.
- Current-file refreshes can run between whole-project module checks. Manual checks and selected-file inspections take priority over queued background checks, and superseded automatic inspections are cancelled.
- Completed checks refresh selected build files after a 50 ms batch window, while other files keep the 200 ms window. Promoted files are removed from the slower batch.
- Maven inspections share one snapshot for version updates, relocations and quick-fix construction, retaining fresh validation before edits. Gradle inspections index candidates and notices, and npm inspections group workspace consumers by artifact once per pass.

### Fixed

- Keep Maven, npm and Gradle warnings for unchanged packages and other build files visible while rechecking after a version edit, so additional updates remain available immediately. Gradle inspections also retain actionable warnings during unsaved version edits while native checks wait for save.

## [1.2.0] - 2026-10-09

### Added

- Dynamic plugin support, with packaged-plugin unload/reload tests covering service disposal, cancelled checks and timers, open previews, and class-loader release.
- **Refresh Dependency Versions** in supported build files' editor context menu, plus clearer **Refresh Current File** and **Refresh Whole Project** menu actions.
- Cancellable status-bar progress for manual refreshes and automatic inspection checks, with whole-project refresh progress advancing by build file.
- Optional periodic repository checks in project settings, with a configurable 1–1440 minute interval (30 minutes by default). Checks refresh highlighting while the project is open and skip offline providers, unsaved build files, indexing and active checks.

### Changed

- Maven dependency goals receive exact inclusion filters from supported declarations, reducing inherited lookups whose results cannot be reported for the selected POM.
- Compatible npm checks share lazy Node/npm runtime resolution while active, including version-manager shims. Runtime probes are cancellable, refreshes resolve again, and completed scans retain no runtime session.
- Build and release validation run authenticated native Maven, npm and Gradle integration tests, with a pinned Node/npm fixture runtime.
- npm retrieves stable versions and their deprecation notices together, selecting candidates locally and retaining the older query path for incompatible responses.
- Maven discovery and bulk-preview validation capture shared imported-POM/settings fingerprints once per read pass, reusing ancestor configuration stamps while revalidating all inputs before applying edits. Unsaved settings and `.mvn` configuration changes now reject stale previews too.
- npm discovery and manifest hashes stay warm across unrelated source edits; manifest content and structure changes still invalidate workspace ownership and prepared edits. Hashes track saved bytes and unsaved text separately.
- npm workspace manifests share successful raw metadata and in-flight requests with bounded memory, configuration isolation and refresh generations; report expiry retains the original metadata freshness deadline.
- Maven goals and retrieval fallbacks reuse one captured set of coordinates, config properties, explicit profiles and effective repository data per scan.
- Optional performance debug traces and authenticated request-count benchmarks cover Maven filtering and the combined npm query.
- Bulk-update previews use non-modal windows owned by the plugin service. Unloading closes them and cancels pending responses before any edits can be applied.
- Maven goals share one fresh server embedder per POM and skip dependency, plugin or parent categories with no supported declarations.
- Independent npm packages are queried concurrently with a limit of four, sharing metadata and deprecation queries for aliases of the same package.
- Gradle checks cache build-file discovery and content hashes, traverse nested linked modules once, and share a root fingerprint during discovery and bulk validation. Edits, file moves and linked settings still invalidate stale results and previews.
- Completed checks combine nearby highlighting updates and restart inspections only for affected build files, reducing repeated project-wide analysis across Maven, npm and Gradle.

### Fixed

- Maven property quick fixes and bulk edits select the unique active profile owner, including overrides of root dependency properties. Ambiguous active owners require review; shared edits cannot target a shadowed default property.
- Scheduled npm checks skip workspaces with unsaved applicable registry or runtime configuration, including ancestor inputs, and resume after save without saving documents from the timer.
- Gradle stable qualifiers are compared without case differences, JRE/Android variants remain isolated, and service-pack releases advance numerically even with mixed-case qualifiers. Equivalent versions and reverse service-pack updates are not offered.
- Gradle settings fingerprints use the supported installation-path API on IntelliJ 2026.1 while retaining compatibility with 2025.3.
- npm and Gradle document listeners use their cache service as the disposable parent, so listeners are removed on plugin unload as well as project close.
- Refreshes pick up newly published releases despite Maven's daily metadata cache or npm's cached registry metadata. Maven refreshes only the selected artifacts' effective repository metadata timestamps, retaining downloaded artifacts and local-install metadata.
- Refresh progress now covers the actual repository checks and resulting inspection updates, instead of finishing after background checks were merely queued.

## [1.1.0] - 2026-10-07

### Added

- Maven `<parent>` version updates for parents outside the workspace. Reactor and snapshot parents are left unchanged.
- Initial Gradle support for linked Groovy/Kotlin build files and default local version catalogs, through IntelliJ’s Gradle tooling and project repository configuration.
- Gradle inspections, version quick fixes, scoped bulk previews, shared-catalog conflict checks, and authenticated repository integration coverage.

### Fixed

- Maven bulk updates skip shared properties referenced in XML attributes, including uses in unselected modules.
- Maven metadata fallbacks only use the current project's effective release repositories and mirrors.
- npm workspace glob matching recognizes zero-level globstars and uses minimatch semantics for inclusions and exclusions.
- Local Maven literal, property, plugin and parent quick fixes reject stale coordinates, declaration context and Maven configuration.
- npm project detection accepts object and array forms of `devEngines.packageManager`; mixed, empty or malformed manager declarations remain excluded.
- npm quick fixes show the lockfile and installed-dependency synchronization reminder after a successful edit.
- npm discovery and manifest fingerprints are cached between changes, bulk validation shares workspace fingerprints, and edits to independent npm projects no longer invalidate each other.
- Maven checks no longer fail when the resolver cannot order an artifact's versions ("Comparison method violates its general contract"), for example when a locally installed `0-SNAPSHOT` meets branch-named snapshots. That artifact is resolved from the metadata Maven downloaded, and the remaining dependencies are still checked.

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
