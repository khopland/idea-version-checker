# Version Checker Changelog

## [Unreleased]

### Added

- pnpm, Yarn Classic/modern Yarn and Bun adapters using their native read-only metadata commands.
- pnpm YAML workspace discovery and manager-specific configuration validation; updates edit manifests only.

### Changed

- One **Tools → Versions** menu with **Check for Updates** and **Review Updates…**, replacing the separate Check Versions and Update Versions menus (eight items). The editor context menu offers **Check This File for Updates** and **Review Updates…**. Reviews open for the current build file, or the whole project when no supported build file is selected, in **Major + minor + patch** mode; scope and mode can be changed in the preview.
- Maven checks are faster for everyone: each metadata goal resolves four repository files at once (Maven's default, was two), and the next batch's goal starts while the current batch is published.

### Removed

- Advanced Maven tuning settings: parallel metadata requests, parents/Spring Boot BOMs first, fast editor checks and persistent metadata across IDE sessions. Maven checks always cover every supported declaration and keep metadata in memory. **Minimum release age** moves to a **Maven** section.
- **Tools → Update Versions → Maven Platform** and the preview's Maven Platform scope. Use **Current File** or **Whole Project**.
- Periodic background refresh. Use **Refresh Current File** or **Refresh Whole Project** when needed.

### Fixed

- Run Maven metadata and Versions goals in cancellable Java processes using IDEA's selected Maven and import JDK. Remove experimental progress APIs.
- Replace deprecated IntelliJ API calls and generated interface bridges. Remove redundant experimental write-intent wrappers from document saving.

## [1.4.0] - 2026-10-22

### Added
- Maven inspections identify unnecessary property and dependency version overrides when the parent provides the same or a newer stable version. Alt+Enter removes the override, preserves dependency configuration, shows version changes and XML previews, and rejects changed POMs.

- Dependency Versions offers a read-only support-report preview and explicit JSON Save As. The bounded snapshot includes safe version identifiers, allowlisted scalar settings, policy status/counts and displayed cached outcomes with original timing; logs, credentials, policy contents and machine identities are excluded, and reporting starts no checks or uploads.
- Batch reviews can explicitly load the selected npm target's published engine/peer requirements and known repository/homepage links using the workspace's configured npm context. Aliases retain actual registry identity, stale requests are discarded, and unknown requirements never change edit eligibility or imply compatibility.

- Dependency Versions offers **Copy Markdown** and **Export Markdown…** for shareable summaries of displayed, filtered observations in their sort order. Summaries sanitize and escape details, redact external paths, retain original observation timing, and state coverage and verification limits without starting checks or renewing cached results.
- Update sessions retain typed per-attempt follow-up states and refresh while open. Explicit npm lockfile sync offers a separate bounded read-only historical diff, with omitted-file, concurrent-edit and unknown-termination disclosures; native writes remain separate from declaration undo.

- Maven dependency quick fixes and selected bulk edits capture expected effective versions for **Verify Maven versions**, retaining each imported shared consumer POM and attributing local overrides to their child only. Confirmation requires a subsequent full import with unchanged saved inputs; parent/plugin versions, vulnerability checks and runtime compatibility remain outside verification.
- Optional schema-versioned `.version-checker.json` shares exact Maven/npm/Gradle dependency rules, expiring ignored releases, reasoned deprecations and allowed update levels. Workspace rules add restrictions without overriding shared policy; diagnostics and policy provenance stay visible, and unsaved/external changes retire prepared updates. Personal settings remain local and the plugin never creates or rewrites the policy file.
- Gradle supports named local TOML catalogs registered through literal root settings `create("name") { from(files("path.toml")) }` declarations. Multiple names retain one physical edit, native consumer repository checks and stale-settings guards. Matching coordinates and versions in distinct catalog files require review because alias provenance cannot be proven. Missing, external, computed, published or customized registrations and catalogs shared by independently linked builds remain explicit manual review.
- Batch reviews can explicitly check patch/minor alternatives and choose a checked target per dependency, property or catalog owner. Mixed target choices preserve native validation, result expiry, shared consumers and one undoable update.

- Successful declaration updates offer a read-only historical diff of the actual edited files, including grouped edits and newly defined Maven properties. Snapshots are bounded in project memory; omitted or evicted content is reported explicitly and native lockfile writes remain separate.

- Maven **Define inherited property locally** quick fix creates the exact controlling parent version property in the child POM, with an XML preview, affected artifacts, undo and stale-input guards. It supports actual inherited dependencies without child declarations, requires agreed checks for active shared consumers while listing unused managed metadata separately without extra queries, and leaves BOM property overrides and imported descendant subtrees for manual review. Suggested versions indicate availability, not verified vulnerability fixes.

- Maven 4 support tested with 4.0.0-rc-7 on IDEA 2026.2.3, including POM model 4.1.0. Metadata responses and Versions reports retain the selected POM's results when Maven also executes child projects; fallback refreshes use Maven's effective mirror IDs, and plugin prerequisites no longer depend on the removed legacy container.

- Dependency Versions tool window keeps cached checks visible with outcome/change filters, source navigation, explicit refresh/review actions and sanitized JSON export.
- Batch reviews show captured versions, consumers and build context; Needs review entries provide explanations and source navigation when available.
- Successful declaration edits offer explicit Maven/Gradle save-and-reload actions and a previewed npm lockfile-only synchronization command. npm scripts are disabled; inputs are revalidated and results report changed lockfiles and possible partial writes separately from declaration undo.
- Optional Maven **Minimum release age (hours)** suggests only versions published at least that long ago, for example to match a repository quarantine such as Sonatype Nexus Repository Firewall. Metadata older than the minimum age needs no extra request; otherwise candidate POM `Last-Modified` times are read newest first and cached. It defaults to off and applies to both the native helper and the Versions-goal fallback.

### Changed

- Remove obsolete plans, performance reports and dated acceptance evidence. Keep current guides and record candidate checks in the pull request or release draft.
- Share dependency-review JSON row construction with support reports, removing redundant serialization and parsing while preserving sanitization, timing and output limits.

- Update reviews display the earliest actual result lease deadline and disable Apply at expiry, preserving read-only details and selections for Refresh. The presentation timer performs no repository queries, stops on disposal, and rejects stale queued clicks without estimating deadlines from observation age.

- Build reload follow-ups distinguish saving, requesting, requested, cancelled, unavailable and failed outcomes without claiming synchronization or dependency verification. npm lockfile follow-ups report timeout separately, bound post-cancellation inspection, and disclose unconfirmed process termination and potentially incomplete file observations.

- Cached dependency review rows preserve original observation age and expiry from cache entries and progress. JSON export schema 2 records relative observation age and expiry status separately from file creation time, with explicit unknown timing and no edit authority or absolute monotonic timestamps.
- Dependency Versions uses a compact IDE toolbar, literal package/path search and combined build-system/root/outcome/change filters. A details pane exposes full selected-row context; selection and sorting survive result updates, and JSON export follows the displayed sort order.

- New builds require IntelliJ IDEA 2026.1.4 and support the 2026.1/2026.2 release branches. Compile against 2026.1.4 and verify against 2026.1.4 and 2026.2.3; drop the 2025.3 target and use the 2026 Gradle plugin ID (`com.intellij.gradle`).

- Check execution, queueing and result publication are owned by a lifecycle-aware executor; disposed executors reject new work and late results.
- Maven saving and captured fingerprints share external-input inventory policy while preserving selected-module configuration and all imported POM ownership checks.
- Bulk Maven updates support safe exact local property alias chains through the same usage/ownership policy as local fixes, requiring agreed results for all imported consumer contexts.
- npm configuration capture, saving, unsaved checks and freshness contexts derive tracked paths from one typed inventory.
- Provider edit capture and final input validation use distinct named types through adapters, quick fixes and bulk plans.
- Preview rechecks use a dialog-owned request controller for cancellation, latest-result delivery and failure recovery.
- Maven inspection and bulk updates share consumer agreement and captured XML edits; shared local property edits also validate transitive property uses in the active profile context.
- Gradle inspection and bulk updates share preparation for physical version targets, catalog consumers and blockers, rejecting inconsistent, duplicate and non-newer candidates.
- npm local, workspace and bulk edits share provider-owned preparation and physical declaration validation. Unsupported or inconsistent supplied baselines remain review-only.
- Gradle project saving, unsaved detection and physical freshness validation share a typed input inventory, including canonical module aliases and unobserved physical roots.
- Shared version editing distinguishes result authorization, background input capture and final validation with named guard types while preserving provider callback contracts.

- Gradle metadata checks use a packaged probe script with JSON configuration and a versioned response, preserving native ownership and target validation.
- Build with Kotlin 2.4.21 and verify the Gradle 9.8.1 distribution against its published SHA-256 checksum.

### Fixed

- Invalid TOML syntax in Gradle version catalogs produces a file-level Needs review entry and inspection with source navigation instead of an empty successful check. Catalogs with syntax errors cannot produce edits; correcting the syntax restores normal declaration coverage.
- npm updates require manual review when root overrides could conflict with a changed dependency, including workspace consumers and cross-referenced specifications. Safe root `$dependency` references remain intact.
- Gradle update validation tracks buildSrc sources and resources and literal included builds. Changed or added build logic rejects held edits; computed included-build paths and untracked applied scripts require manual review.
- Malformed typed Gradle catalog entries no longer suppress valid siblings. Invalid entries and uncertain shared-version owners remain noneditable.
- npm and Gradle folder exclusions respect selected project roots, so an enclosing or imported root named build or vendor no longer hides legitimate declarations. Generated descendants remain excluded.
- Gradle classifier, extension and common map declarations appear under Needs review instead of disappearing from coverage.
- Gradle update severity classification handles numeric version components larger than a 32-bit integer consistently with version ordering.

## [1.3.0] - 2026-10-12

### Added

- JetBrains Marketplace error and UI freeze reporting through the IDE's built-in reporter, respecting users' error-reporting settings.
- Failed version-update previews offer a fresh retry that preserves the selected mode, scope and file. npm registry failures explain authentication, offline-cache, network and certificate recovery without exposing native output in the notification.
- Maven metadata worker control (1–4, default 2), optional parents/Spring Boot BOM priority, and optional fast editor checks with explicit partial coverage. Refreshes and Current File / Whole Project previews perform full supported-declaration audits.
- **Maven Platform** update previews follow imported parent chains to the editable parent/BOM owners, check platform declarations before explicit overrides, and show their actual source files. This explicit narrower scope has separate result identity and cannot replace or authorize a full audit.
- Optional local persistence of fresh Maven histories and plugin prerequisites across IDE sessions. The bounded store retains original age/expiry, bypasses old generations on Refresh, and falls back to native resolution after corruption, expiry or incompatible clocks. It defaults to off.
- Ignore one exact published artifact/version in the current project through Alt+Enter, with reversible workspace settings and matching bulk-review filtering. Other releases and replacement notices remain visible; changed ignore policy rejects older prepared edits.
- Anonymous performance interaction IDs and monotonic endpoints connect native setup/query work, result acceptance, displayed previews, changed editor text and painted visible diagnostics. A sanitized trace report computes median/p95 and partitions overlapping stages.

- A current-file **Versions** status-bar item distinguishes queued/running checks, current results, failures, offline mode and required saves, with direct Refresh, Review and settings actions. It reads existing results in the background and does not trigger repository queries.
- **Review Dependency Updates…** in supported build files' editor context menu opens a searchable preview with checkboxes, update mode and scope controls, cached-result age, and Refresh. Empty previews retain these controls, and skipped items have a separate Needs review section.

### Changed

- Review Apply enters a write-safe IDE context before changing documents, including clicks through accessibility. Repeated pending clicks apply once; closing or refreshing the review cancels a queued Apply.
- Inspection input captures and fresh quick-fix/review validation read external Maven, npm and Gradle configuration in cancellable background work. Final edits recheck current documents, imports, settings and result authorization inside their undoable command.
- Selected-file saving acquires write-intent access explicitly, including review clicks through accessibility on newer IDEs. Follow-up failures after a successful edit explain that the editor text changed.
- Unsupported or unavailable inspection snapshots do not trigger repeated background captures and highlighting restarts; Gradle checks file ownership before preparing inspection inputs.
- Gradle discovery recognizes imported module paths that alias a linked root through a symlink, preventing duplicated declarations and review rows while preserving the linked root's file paths.

- Status and review show explicit checked, skipped, ignored, manual-review and failed declaration coverage, including partial progress and Maven narrow scopes. Shared Maven consumers count once in whole-project totals; unsupported empty reviews explain their exclusions.

- Sanitize credentials, URL queries and local paths in displayed/copied failure details; show native npm recovery guidance in status and reject held bulk updates while checks are paused.

- Version review keeps one window during mode/scope changes and Refresh, preserving filters, sorting and choices for unchanged targets. New targets require selection; pending/replaced results cannot authorize older plans. Rejected applies and failed rechecks recover in the same window.
- Review rows can open their source declaration and show a read-only before/after diff. npm bulk edits preserve original spacing and share the captured-text validation used by their previews.
- Gradle background discovery, result validation and preview preparation capture user-home properties and init scripts outside IDE read locks, sharing one capture across linked builds and preserving unsaved-input guards.
- Gradle metadata checks index requested source owners and configurations once and reuse identical probes within each source and mode. A development-only `versionchecker.gradleScanAggregation` JVM switch enables bounded checks across compatible files in one linked build; it defaults to off while native UI validation continues.
- Gradle bulk updates validate captured text once per file and apply selected replacements in a bulk document update with one PSI commit. Combined plans preserve file batching, caret and unchanged ranges, and one-command undo, including dense files with different-length replacements.
- Supported Maven 3/Java 17+ checks use a bundled metadata helper with batched Resolver requests, bounded shared histories across modules/update modes, cached plugin prerequisites and short-lived editor/refresh/preview sessions. Automatic checks reuse one embedder pool across their metadata batches; each helper invocation reuses repository setup and one secured XML parser. Fresh generations force remote revalidation; context changes isolate repository and credential results. Custom extensions/rules and unsupported runtimes retain the Versions-goal fallback.
- Shared Maven histories prepare version ordering and numeric branches once. Plugin candidates use the same index and descend through prerequisite checks without repeated list shifting. Traces distinguish local index preparation from candidate-POM prerequisite goals.
- Maven checks publish a small first metadata batch, then use larger batches. Between completed goals, background checks hand off to waiting selected-file or interactive checks while preserving native serialization and shared metadata.
- Routine hints lead with artifact, update kind and declared version change. Maven shared version actions include known version-declaration and POM counts.
- Refresh, preview and retry save required build inputs rather than all open documents; bulk apply saves only selected edit files.
- Preview controls have keyboard mnemonics, and Space toggles sorted/filtered highlighted rows while preserving hidden selections and repeated keyboard selection.

- Project refresh failures share one summary with native details and a retry limited to failed files. Automatic failures share a notification per build system. Cancelled checks are excluded, successful cached results survive retry, and actionable notifications expire on plugin unload or project disposal.
- npm inspections publish finished packages while slower queries continue. Aliases retain their shared lookup, and one package failure preserves other successful hints while rejecting a complete bulk preview. Early results retain freshness, cancellation and refresh guards.
- Maven inspections publish completed dependency, plugin and parent results incrementally, checking dependencies first by default. Failed batches preserve other hints but prevent a complete bulk preview; cancellation stops work once no shared consumer remains.
- Bulk previews reuse current, unexpired results for the requested update mode. Refresh still forces native revalidation. Selected edits retain all input and shared-consumer guards, and expired or replaced results require another preview before applying.
- Bulk apply preserves valid warnings while rechecking changed declarations, and shows npm/Gradle synchronization guidance only for selected build systems.
- Current-file refreshes can run between whole-project module checks. Manual checks and selected-file inspections take priority over queued background checks, and superseded automatic inspections are cancelled.
- Completed checks refresh selected build files after a 50 ms batch window, while other files keep the 200 ms window. Promoted files are removed from the slower batch.
- Maven inspections share one snapshot for version updates, relocations and quick-fix construction, retaining fresh validation before edits. Gradle inspections index candidates and notices, and npm inspections group workspace consumers by artifact once per pass.

### Fixed

- Reject stale version edits when tracked build files change on disk before IntelliJ notices them, including same-size/same-timestamp changes. Fresh source validation runs in the background before the atomic edit.
- Large update reviews refresh their summary after Swing updates row mappings, avoiding sorter warnings when replacing the visible declarations.
- Gradle validates proposed versions in every matching source configuration before offering an automatic edit. Target-only substitutions, rejected versions and conflicting constraints require review; declarations without a known source owner no longer count as checked.
- Maven excludes development (`dev`) and early access (`eap`) qualifiers from stable candidates in both native metadata and legacy reports, including case and separator variants.
- Held inspection fixes remain usable when unrelated partial results arrive. Replacing any contributing declaration result still rejects local or shared edits; refresh, cancellation, expiry, eviction and final-result replacement retain their freshness guards.
- Maven edit and result guards detect external settings, relocated credentials and ancestor configuration changes even when file size and timestamp are unchanged. Gradle bulk validation shares one fresh user-home input capture across linked builds.
- Maven, npm and Gradle inspection fixes now share preview freshness checks: expired, refreshed, replaced or evicted version results require a fresh choice. Retained warnings keep their original metadata deadline, and stale authorization rejects before provider input reads.
- Refresh, preview and retry collect build-input save paths in cancellable background work before saving relevant documents on the UI thread. Save preparation no longer hashes npm/Gradle inputs, and Gradle user-init/Maven credential-relocation discovery runs outside IDE read locks.
- Bound complete and incremental result storage and clean up expired source/generation bookkeeping. Evicted results cannot be restored by late checks or authorize an older preview; provider refresh and service disposal release retained state.
- Versions status counts now use the same exact-version ignore policy as editor hints and bulk previews, including incremental results. Removing an ignore restores counts from cached metadata without another repository query; notices retain their visibility and original expiry.
- Preview selectors use the supported renderer API, and diagnostic refresh resolves PSI files in a cancellable background read action before restarting highlighting on the UI thread, removing deprecated API calls flagged by current IDEA verification.
- Fast Maven editor scope preserves completed and failed full-audit status until expiry or invalidation. Global settings, credential files and relocated credential targets now participate in preview/input guards, including unsaved changes.
- Gradle preview rows and shared-version review messages identify their dependency coordinates, allowing package-name filtering while retaining declaration and catalog-alias distinctions.
- Rebuilt previews have independent performance interactions, and bulk Apply timing includes plan and writable-file preparation. Trace reports distinguish owned native invocations from background overlap and expose incomplete samples.
- Preview mode/scope selectors display readable labels, and Tab/Shift+Tab leave the change table. Update actions precede exact-release Ignore actions in Alt+Enter.
- Preview mode/scope changes wait for dropdown selection to finish, preserving keyboard navigation and keeping Enter out of the editor. Pending choices cannot apply an older plan; empty previews use readable mode labels.
- Maven version diagnostics also highlight local properties and child overrides used by parent dependency/plugin declarations, even without a child dependency declaration. Property fixes edit the child override and require compatible updates across its consumers.
- Stale Maven, npm and Gradle quick fixes explain rejected edits and offer **Refresh This File**, preserving changed text and atomic workspace/catalog edits. Repeated rejection messages are grouped by file, and recovery actions respect disabled checks.
- Keep Maven, npm and Gradle warnings for unchanged packages and other build files visible while rechecking after a version edit, so additional updates remain available immediately. Gradle inspections also retain actionable warnings during unsaved version edits while native checks wait for save.
- Maven checks no longer report "no updates" when the resolver cannot order an artifact's versions (for example a locally installed `0-SNAPSHOT` next to branch-named snapshots) and IDEA reports the aborted Versions goal as successful. Every affected artifact is excluded after the first failure, so the goal reruns once instead of once per artifact, and the excluded artifacts are resolved from freshly read repository metadata.

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
