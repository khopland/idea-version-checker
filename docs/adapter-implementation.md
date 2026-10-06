# Build-system adapter implementation

Maven and npm are implemented behind internal adapters. Dependencies and build plugins now use **Tools → Update Versions → Current File / Whole Project**, with the three existing update modes. The shared menus discover matching adapters automatically; Whole Project combines their plans into one atomic preview. Legacy Maven and plugin-only action aliases have been removed.

## Boundaries

| Layer | Location | Responsibility |
| --- | --- | --- |
| Immutable model | `core/BuildModels.kt` | Artifact namespace, context, declaration identity, selector/baseline/resolved version, snapshots, candidates, notices and explicit failures. |
| Internal provider contract | `core/BuildSystemAdapter.kt` | Discovery, capabilities, snapshot validity, repository checks and preparation of edits. |
| Shared result cache | `core/VersionResultCache.kt` | Context/provider isolation, expiry and refresh generations. |
| Shared coordinator | `VersionCheckService.kt` | Background work, cancellation, serialization per provider, stale-result rejection and refresh. |
| Shared UI and edit bridge | `BulkUpdateService.kt`, `BulkUpdatePlan.kt`, action classes | Combined preview, scope/mode selection, read-only file preparation and validation before one undoable command. |
| npm implementation | `npm/` | Manifest/workspace discovery, npm-selected registry queries, SemVer selection, deprecation metadata and JSON string edits. |
| Maven implementation | `maven/` | Native Maven server, settings/repositories, report parsing, version ordering/classification, relocation metadata, XML analysis and shared-property ownership. |

The model contains no IntelliJ or Maven types. The provider and edit bridges use IntelliJ platform types; the shared coordinator and UI do not import Maven classes or XML tags. Maven inspection registration is in `META-INF/version-checker-maven.xml`, loaded through an optional Maven dependency. The extension point is internal and is not a supported third-party API.

## Current contract

`BuildSystemAdapter` exposes a provider ID, a display name and supported update modes, plus:

- `supports` and `isOffline` for action availability.
- `snapshot` and `discover` for one build file or the selected project scope.
- `isCurrent` to validate source/configuration fingerprints.
- `check` to return declaration-specific candidates and notices.
- `prepareUpdates` to translate results into a generic `BulkUpdatePlan`.

Candidates keep the proposed version and replacement selector separate. Declarations keep the original selector, comparison baseline and optional resolved version separate, so the npm adapter does not represent `^1.2.3` as an installed version. Maven retains its existing numeric update-mode rules in `MavenVersionSemantics`.

The background cache stores complete major-mode checks for each context. Restricted bulk queries go directly through `checkNow` and are not mixed into that cache. Cache identity includes provider/root/resolution context; validity includes file/configuration fingerprints and declaration selectors/baselines. Current-file and provider-wide refresh generations prevent earlier checks from publishing stale results. Refreshing one provider preserves other providers' results.

Maven snapshots cover all imported POMs because shared-property checks include unselected consumers. They also track Maven settings, ancestor `.mvn` configuration and the deprecation policy. Prepared previews validate this snapshot and every target before making any edits. Unsaved changes in another imported POM invalidate the preview even when its target versions have not changed.

## npm behavior

npm checks use IntelliJ's configured local Node.js interpreter and npm package manager. Workspace members share their owning root's registry configuration while retaining declaration and cache identities per manifest. Only members declared in the workspace patterns share its local package names; excluded and independent nested projects retain their own contexts. Other managers are detected from explicit declarations or manager files and excluded.

Only `npm view` is executed. Version selection uses the published version set rather than capping results at the `latest` tag. Exact, caret and tilde selectors and npm aliases retain their syntax. Supported dependency sections are `dependencies`, `devDependencies` and `optionalDependencies`; peer compatibility, workspace/local dependencies and unsupported selectors require review. Installed versions are not inferred from range floors. Deprecation checks use the declared baseline and exclude deprecated candidates.

Apply edits only manifest strings through the shared native PSI bridge. Lockfiles and dependency installation are handled by IntelliJ/npm afterward. This deliberately narrows the original proposal's staged lockfile contract for the current implementation. Fingerprints include project manifests, ancestor npm configuration, manager markers, user/custom npm configuration, runtime/environment and project deprecation policy. Checks and prepared plans are rejected when that context changes.

## Verification and next stage

The Maven integration fixtures exercise the combined actions against an authenticated settings-profile mirror and a nested reactor. Contract tests use a non-Maven provider to exercise the real coordinator, plus cache tests for independent repository contexts, selector/resolved-version separation, failure expiry and in-flight refresh rejection.

Gradle, pnpm and Bun adapters are still to be implemented. Staged external lockfile preparation/apply strategies and richer unsupported/incompatible outcomes also remain future work; the current edit bridge applies native PSI edits. The next adapter should start with Gradle local version catalogs and literal declarations, with a repository-query prototype and private-repository fixtures before enabling automatic updates. npm registry integration fixtures cover authentication, scoped packages, workspaces and manifest-only updates without installation.

The original [API proposal](build-system-api.md) remains the broader migration plan.
