# Version Checker guide

For installation and a quick start, see the [README](../README.md).

An IntelliJ IDEA plugin that shows newer stable Maven dependency and build-plugin versions in `pom.xml`, npm dependency versions in `package.json`, and Gradle dependency and plugin versions in build scripts and local version catalogs through **standard IDEA inspections**.

Maven's stable-candidate policy excludes alpha, beta, milestone/M, RC/CR, EA/EAP, dev, preview and snapshot qualifiers after a dot or hyphen, regardless of case, with optional numeric suffixes. Stable qualifiers such as Final/GA, service packs and vendor suffixes remain eligible. The same policy applies to native metadata and legacy Maven reports; it is a version-name filter, not a compatibility assessment.

For example, a dependency using `junit:junit:4.12` gets this inspection message:

> Newer version of junit:junit is available: 4.12 → 4.13.2

Use **Alt+Enter → Update version to 4.13.2** to update a literal version. For a simple `${junit.version}` reference declared in the same POM, the quick fix updates the property instead. A shared property change affects every dependency using it.

Version properties also show diagnostics under `<properties>`, including child overrides used by dependencies or plugins declared in a parent POM. Checks resolve those declarations with the child's effective properties, even when the child has no dependency declaration. The property quick fix updates the child override when its consumers agree on an update; conflicting or unchanged consumers require review. Inherited property consumers appear under **Needs review** in bulk previews.

For a dependency whose version is managed by a parent POM in this project, Alt+Enter offers two choices:

- **Override version locally with 4.13.2:** add or replace `<version>` on this dependency in the current POM.
- **Update parent version to 4.13.2 in …/pom.xml:** change the controlling version or property in the imported parent, including across multiple parent levels. Shared consumers inherit that change.

The parent choice is available only when the controlling declaration can be identified in a non-ignored project POM. A local override remains available for externally managed versions with a known baseline. These choices belong to Version Checker's version inspection; IntelliJ's built-in vulnerability inspection has its own fixes.

## Install and use

Requires IntelliJ IDEA **2026.1.4 through 2026.2.x**. Enable the bundled Java and Maven plugins for Maven projects, the bundled Gradle plugin for Gradle projects, or the JavaScript and TypeScript plugin for npm projects. The build targets 2026.1.4.

The plugin supports dynamic loading and unloading, allowing IDEA to enable, disable or update it without a restart when the IDE permits it. Unloading cancels background checks and closes pending update previews. Previews use non-modal windows; build-file changes still invalidate a preview before it can apply edits.

1. In IDEA, open **Settings → Plugins → Marketplace**, search for **Version Checker**, and install it. For a local build, run `./gradlew buildPlugin` using Java 21 or later; Gradle uses a Java 21 toolchain.
2. If installing a local build, use **Settings → Plugins → gear → Install Plugin from Disk** and select the ZIP in `build/distributions/`.
3. Open and import a Maven project. Checks start in the background when IDEA inspects an imported POM.
4. Right-click the POM and choose **Check This File for Updates**, or use **Tools → Versions → Check for Updates** for all imported modules.

Version checks show cancellable progress in IDEA's bottom status bar. A manual refresh bypasses the checker's cached results, queries repository metadata again, and refreshes the inspection highlighting when results arrive. Use this after publishing a new library version while the project is open. Right-click a supported build file in the editor and choose **Check This File for Updates**, or find the refresh actions through **Find Action**.

Squiggles appear only for supported newer stable versions; republishing an existing version or a snapshot does not create a version-update warning.

The inspection appears under **Settings → Editor → Inspections → Maven → Newer Maven dependency or plugin version available**. You can disable the whole inspection there. Change its severity on the same inspections page. Editor diagnostics do not change Maven's build result.

## Severity and deprecated dependencies

General controls and warning levels appear first. **Minimum release age** sits under the enable checkbox. Expand **Dependency rules** to edit deprecation and ignore rules.

Each inspection has one severity, set under **Settings → Editor → Inspections**, and it applies to patch, minor, major and other version changes alike. The default is Warning. Deprecated and relocated artifacts are struck through. To hide editor messages, disable the inspection. Bulk updates stay available either way.

Maven has no general deprecation flag for libraries or plugins. To explicitly mark one deprecated for your project, add a line in **Settings → Tools → Version Checker → Dependency rules → Mark dependencies as deprecated**:

```text
old.group:library = Use new.group:library instead
```

These notices appear even without a newer version. The checker also reads [Maven relocation declarations](https://maven.apache.org/guides/mini/guide-relocation.html) from dependency POMs already downloaded into the project's Maven local repository. Reload Maven to download missing dependency POMs, then refresh the checks. No deprecation is inferred from a library's age or a major-version update. Deprecated/relocated coordinates are listed for manual review during bulk updates, since migration may require coordinate and API changes.

## Shared project rules

Commit an optional `.version-checker.json` in the IDE project's root directory to share dependency rules:

```json
{
  "schemaVersion": 1,
  "rules": [
    {
      "ecosystem": "maven",
      "package": "org.example:library",
      "ignoredVersions": [{ "version": "2.1.0", "reason": "Await compatibility testing", "until": "2026-12-01T00:00:00Z" }],
      "allowedUpdates": ["patch", "minor"]
    },
    {
      "ecosystem": "npm",
      "package": "@example/old-package",
      "deprecated": "Use @example/replacement instead"
    },
    {
      "ecosystem": "gradle",
      "package": "org.example:legacy",
      "deprecated": "Use org.example:replacement instead"
    }
  ]
}
```

Each rule matches one exact ecosystem and package: Maven/Gradle use `group:artifact`; npm uses the registry package name, including an alias's target package. Wildcards, duplicate rules, duplicate JSON fields and unknown fields are rejected. `schemaVersion` must be the integer `1` and `rules` must be an array. No file means the existing workspace settings continue to apply.

`ignoredVersions` contains exact published versions and an optional non-empty `reason`, shown with shared policy provenance. An optional `until` is an ISO-8601 timestamp with a UTC offset; the ignore ends at that instant. Omit `until` for an indefinite ignore. `deprecated` supplies a non-empty reason and requires manual replacement review. `allowedUpdates` accepts any subset of `patch`, `minor`, `major`, `other`; an empty array blocks every automatic version update for that package. Unknown/nonstandard version changes require `other`. The checker filters the versions available in the selected check mode; it does not query additional versions to find an allowed replacement when a suggested release is blocked.

Workspace ignores and deprecations add restrictions. Removing a local rule cannot override a shared rule, and a shared deprecation reason takes precedence over a local reason for the same package. Warnings and dependency review details identify shared rule provenance. Enable/disable and release-age preferences stay local. **Ignore published version** also stays local; settings never create, migrate or rewrite the shared file.

Unsaved shared-file edits take effect in the IDE and retire prepared updates. Changes to shared rules invalidate cached checks, and applying an update captures fresh physical policy input, including a file created or deleted outside the IDE. Policies with malformed JSON or unsupported schema/fields block edits; Settings → Tools → Version Checker and the version status/preview show the diagnostic. Fix the file and refresh checks to continue. A prepared selection also expires when one of its ignore timestamps passes. Parsed policy is reused by file/document stamps; fresh pre-write validation still rejects external writes with unchanged timestamps. Policies are limited to 1 MiB.

## IDEA highlighting and InlineProblems

Messages use IDEA's standard inspection highlighting, hover tooltips, Problems view, and Alt+Enter quick fixes. Configure their severity, or disable the inspection, under **Settings → Editor → Inspections → Maven**. You can disable automatic checking altogether under **Settings → Tools → Version Checker**.

The **Versions** status-bar item explains the selected build file's check state: unchecked, queued, checking, checked, updates available, needs review, offline, save required, paused or failed. Click it for **Check This File for Updates**, **Review Updates…** and settings. Refresh saves documents before checking; disable the build system's offline setting first when required. “Checked” covers supported declarations only. Partial and retained hints cannot mark a file fully checked, and expired results return to unchecked. Displaying the status does not start repository queries. You can hide the item through IDEA's status-bar widget settings.

Project refresh failures produce one summary with **Show Details** and **Retry Failed Checks**. Retry saves documents, refreshes metadata once per affected build system, and checks only the failed files; successful files keep their cached results. If discovery itself failed, retry rediscovers that build system. Automatic failures share one notification per build system. Cancelled work does not produce a failure notification. Recovery notifications expire after a minute or on plugin/project disposal.

When a quick fix rejects changed files or settings, it leaves the files untouched and offers **Refresh This File**. Repeated rejected fixes in the same file share one recovery message. Refresh requires enabled checks; opening details or rejecting a stale fix does not start a repository query.

If installed, [InlineProblems](https://github.com/0verEngineer/InlineProblems) can render these standard inspection messages using its own display. No dependency on that plugin is required.

## Bulk updates and submodules

Right-click a supported build file in the editor and choose **Review Updates…** to review that file. The review starts in **Major + minor + patch** mode. You can change scope and update mode inside the preview. **Tools → Versions → Review Updates…** also opens the preview. Shared actions select the adapters for the current file or whole project. Each action checks dependencies and build plugins together and shows one combined preview. Maven, npm and Gradle are implemented adapters; a mixed project gets one combined preview. Current File checks and edits only the imported POM in the editor. Whole Project includes all imported, non-ignored Maven modules, including nested submodules. Both offer:

- **Patch only:** keep the current major and minor version numbers.
- **Minor + patch:** keep the current major version number.
- **Major + minor + patch:** allow any newer stable version.

The check queries the newest version within the selected scope; it does not simply discard a latest version outside that scope. Restricted modes require numeric major/minor prefixes.

For a minor or major review, choose **Check other targets** to prepare the highest eligible patch and minor alternatives. Select one row, then use **Target** to choose among its checked versions. Each option retains its own native checks, shared-consumer agreement, input validation and expiry. You can mix patch and major choices across declarations in one undoable update. The list contains checked branch targets, rather than every historical release; a shared owner with conflicting consumers may have no alternative. Ordinary previews query only their selected mode until you request other targets. Changing scope, mode or refreshing discards the prepared choices; changed targets require selection again.

A preview lists changes in sortable rows with checkboxes and a text filter. All proposed changes start selected. Filtering keeps selections and counts selected rows hidden by the filter; **Clear selection** removes every selection, and **Select visible** selects the filtered rows. **Update selected declarations** applies one undoable command. Compatible dependency/plugin updates sharing a property become one indivisible edit affecting every listed consumer. It skips inherited/composite version declarations and shared properties with conflicting, unchanged, unselected, or inherited uses. Shared-property safety checks cover all imported POMs even in Current File mode. These cases appear under **Needs review**.

Previews reuse current, unexpired results for the selected mode and show their age. A countdown uses the earliest actual result expiry, including any loaded alternative targets; it does not estimate validity from observation age. At that deadline Apply is disabled, while the source details, diff, filters and selections remain available. Use **Refresh** to check again. The countdown itself does not query repositories or renew results. When any loaded deadline is unavailable the preview says **expiry unknown**, and provider validation still controls whether an edit can apply.

**Refresh** forces another native check. A cached major result cannot answer a patch request. Applying revalidates all relevant build inputs and rejects expired, refreshed or replaced results before editing, including a click queued before the expiry deadline. Imported POM, relevant Maven configuration and deprecation-policy changes invalidate a preview, including unsaved settings and ancestor `.mvn` edits. The plugin saves updated files, keeps remaining valid hints available and rechecks changed declarations.

Successful module checks with an empty or absent Maven report count as having no updates and do not abort the scan. Failures are logged to `idea.log`; a **Show details** notification action displays the cause without changing any versions.

Submodules must be imported into IDEA's Maven project model. Maven ignores and offline mode are respected. Parent-managed dependency versions and properties can also be updated from the child with the parent quick fix; bulk updates take a conservative approach to properties inherited across modules.

Routine hints show the package, update kind and declared version change. **Alt+Enter → Ignore … in this project** dismisses one exact published version from hints and bulk review. npm aliases share the registry package's ignore. Other packages, later releases and replacement notices remain visible. To restore an update, remove its line from **Settings → Tools → Version Checker → Dependency rules → Ignored versions**. Ignores are stored in this project's local workspace settings.

Refresh and preview actions save relevant build inputs, including required sibling declarations and configuration, while preserving unrelated unsaved editing. Applying a preview saves only the selected edit files. Custom build scripts can read inputs outside the supported adapters' tracked files; save those explicitly before native resolution.

## After applying updates

After a successful declaration edit, **Versions updated → Review changed files** opens a read-only comparison of the text captured immediately before and after that update. Multiple edits to one declaration file appear in one comparison. These are historical snapshots: later editing, saving, reformatting or undo does not change the captured diff. Lockfiles and native build-tool writes are not included.

Snapshots remain only in bounded project memory and may be unavailable after eviction or project closure. Large updates can omit files; the review reports omissions rather than presenting its subset as complete. The review action never applies or restores content. Use normal IDE undo to revert declaration edits and version control to inspect subsequent native changes.

The same notification offers one next step per build system that changed: **Reload Maven projects**, **Sync Gradle projects** or **Update npm lockfile…**. The Maven and Gradle actions save build files and ask the IDE to reload. A requested reload does not mean synchronization succeeded; check the build tool window and run the relevant build or tests.

**Update npm lockfile…** asks for a supported `package.json`, shows the workspace root and command, and checks saved inputs again before it starts. It reports completion, cancellation, timeout or failure, and lists the lockfiles that changed. Cancellation or timeout does not confirm the process stopped, so writes may continue. Review the lockfile diff in version control before retrying.

## Override an inherited Maven version property

For a dependency controlled by a property in your Maven parent chain, **Alt+Enter → Define &lt;property&gt; as … in this POM** adds that exact property under the child POM's `<properties>`. For example, a parent declaration using `${junit.version}` can be overridden by adding `<junit.version>4.13.2</junit.version>` locally. The action follows exact references and aliases to the controlling literal property; it never guesses the property name from an artifact or a matching version number. Existing local properties keep their normal update action. The parent and dependency version references remain unchanged.

The action's XML preview lists checked artifacts separately from unused managed entries whose metadata changes without a version check. It also works for a dependency inherited from the parent when the child has no dependency declaration; unused parent management catalogs do not expand editor checks. All actual used or resolved shared consumers must have an agreed candidate version; unused ordinary dependency-management entries need no extra query. BOM imports and plugin-management references remain subject to candidate agreement. An unchecked, ignored or conflicting active consumer, a separately overridden dependency version, a non-version property use, an ambiguous parent profile, or a `.mvn` property override requires manual review. Creation is also withheld when this POM has imported descendant modules, including ignored modules, because those modules would inherit the override. Properties inside an **imported BOM** have their own interpolation context and cannot be overridden this way; review an explicit `dependencyManagement` entry instead.

Apply uses the IDE's undo command and rejects changed results, POMs, parent source files or Maven settings. Save and reload Maven, then inspect its effective dependency versions. A newer available version is not evidence that it fixes a vulnerability or remains compatible with Spring Boot's tested dependency set. Prefer a parent upgrade when appropriate. Use the separate **Verify Maven versions** follow-up to compare captured dependency targets with a subsequent import; automatic vulnerability checks are not provided.

## Remove unnecessary Maven overrides

The **Unnecessary Maven property or dependency version override** inspection checks overrides against the current parent chain.
It appears under **Settings → Editor → Inspections → Maven** and is enabled by default.
It uses locally available parent POMs without requesting repository metadata.

The inspection reports these cases:

- A literal property has the same value as the nearest parent definition.
- A version property has an older version than the parent and has recognized dependency or plugin version consumers.
- An explicit dependency version matches the inherited version, or the parent provides a newer stable version.

Use **Alt+Enter** on the highlighted property or version.
For equal values, select **Remove redundant property override** or **Remove redundant dependency version override**.
For newer parent versions, the action shows both versions.
For example, **Remove dependency version override and use parent version 2.0 (was 1.0)** removes `<version>1.0</version>`.
The dependency then inherits version `2.0`.
Removing a version property can upgrade several consumers.
Review the XML preview before applying the change.

Dependencies controlled by parent management keep their declarations, scopes, and exclusions in the child POM.
For a dependency declared directly in the parent, the fix removes the simple child override as a whole entry.
For simple dependency-management overrides, the fix removes the whole entry to restore parent management.
Whole-entry removal requires both declarations to contain only coordinates and a version.
The fix supports undo and rejects changed child or parent POMs.
Save and reload Maven after cleanup. Test the resulting dependency versions for compatibility.

Profile overrides, imported BOMs, unresolved coordinates, composite expressions, and version ranges require manual review.
Newer property values with non-version uses also require manual review.
Customized inherited dependency entries, customized management entries, and Maven configuration property overrides require manual review.
Ignored versions and shared dependency rules also apply to newer parent versions.
Older parent versions do not produce a cleanup suggestion.
These inspection results are separate from cached repository update results and bulk update previews.

## Maven repositories and settings.xml

The plugin uses IDEA's existing Maven server rather than querying a public dependency search service. Maven handles the effective repositories, active settings profiles, mirrors, server credentials, encrypted credentials, proxies, and local repository using IDEA's Maven configuration.

Configure **Settings → Build, Execution, Deployment → Build Tools → Maven → User settings file** if you use a custom `settings.xml`. Otherwise Maven uses its default settings location. A mirror with `mirrorOf="*"` is applied by Maven, including for the checker's own Maven goal. No separate repository or credential configuration is needed in the plugin.

Maven 4 support is tested with **Maven 4.0.0-rc-7 on IntelliJ IDEA 2026.2.3**. Use IDEA 2026.2.3 or later for Maven 4; older IDE Maven servers can fail before a check starts. Both POM model 4.0.0 and 4.1.0, including `<subprojects>`, have repository integration coverage.

On supported Maven 3 and Maven 4 runtimes (Maven 3.6.3 or later, Java 17 or later), checks execute a small metadata helper bundled inside the IDEA plugin. It is installed under its content-derived version in Maven's effective local repository. The helper asks Maven Resolver for batches of artifact version histories using the current project's effective repositories. It does not resolve library dependency trees or download library JARs. Plugin histories use Maven's plugin repositories; promising plugin candidates have their POM prerequisites checked against the project's Maven runtime. Repository errors produce a notification. IDEA's **Work offline** setting disables remote checks.

Other runtimes, configured core/build extensions and custom Versions-plugin rules retain the read-only `display-dependency-updates` and [display-plugin-updates](https://www.mojohaus.org/versions/versions-maven-plugin/display-plugin-updates-mojo.html) fallback from `org.codehaus.mojo:versions-maven-plugin:2.21.0`. Maven downloads those goals and their dependencies through configured plugin repositories on first use. Private repositories must allow or proxy these fallback artifacts. Both paths exclude prereleases, downgrades and plugin updates requiring a newer Maven runtime.

Declaration results are cached for ten minutes and invalidated by POM saves, Maven model changes, settings changes in IDEA, or refresh. The native helper also shares bounded, in-memory version histories across modules with matching resolution contexts and across Patch, Minor and Major modes. Reuse retains the original metadata expiry. Contexts include ordered effective repositories and mirrors, settings and credential-file content, profiles, local repository, runtime, environment and `.mvn` configuration; repository IDs alone never authorize sharing. After changing `settings.xml` externally, reload Maven or refresh to replace displayed results. Checks inspect saved POM contents and only apply when the current version still matches.

### Minimum release age

Some repository managers hold new releases back for a while. A Sonatype Nexus Repository Firewall quarantine, for example, still lists a new version in `maven-metadata.xml` and serves its POM, but refuses its JAR. To suggest only versions you can download, set **Minimum release age (hours)** under **Settings → Tools → Version Checker** to the quarantine period, for example `24`. The default `0` suggests the newest version.

The release time of a version is the `Last-Modified` time of its POM, which Maven keeps as the file time in the local repository. When an artifact's metadata `lastUpdated` timestamp is older than the minimum age, every listed version is old enough and nothing more is read. Otherwise the checker reads the POM of the newest candidate (a few kilobytes, or nothing when it is already in the local repository). If that version is too new, it tries the next older one. Results are cached with the version history. Changing the setting reselects versions from cached metadata.

A repository that sends no `Last-Modified` header makes a version count as published when Maven first downloaded its POM, so the delay is never shortened. The setting matches a time-based quarantine only; versions blocked for other reasons, such as security or license policy, are still suggested. The Versions-goal fallback applies the same rule through the bundled helper.

For npm, pnpm, Yarn and Bun, the release time is the registry's per-version `time` entry. The checker reads it with one extra read-only registry query per package, only while a minimum age is set. Versions without a release time count as too new. If the registry returns no release times at all, the package is reported as failed rather than up to date; check the registry configuration or set the age to 0.

Gradle checks do not use this setting yet: Gradle's resolver does not expose per-version publication times.

After a version edit, existing warnings for unchanged Maven coordinates stay visible in the same POM and other imported POMs while the new check runs. Their quick fixes remain available, so you can update additional dependencies without waiting. Warnings for edited or removed declarations disappear immediately; settings changes and explicit refreshes discard retained results.

Manual refreshes start one metadata generation for the requested scan. Every helper cache miss forces metadata revalidation through a cloned Maven Resolver session, including under daily update policies. Editor checks, refreshes and previews share a short-lived embedder pool for compatible lookup phases; it is released on completion or cancellation after any shared workers finish. Shared histories prepare version ordering and branches once. Checks publish a small first batch and yield between completed goals to waiting selected-file or interactive checks. Failures retry independently while successful sibling histories remain reusable. The fallback continues to expire only selected remote metadata timestamps and uses a fresh per-POM session.

Maven checks always cover every supported declaration. Each metadata goal resolves up to four repository files at once, matching Maven's own default, and the next batch's goal starts while the current batch is published.

Full results from Refresh stay visible until they expire or are invalidated, including failed audits. Preview guards track global Maven settings, security files and their relocation targets, including unsaved changes; changing those inputs requires another audit before applying edits.

## Maven scope

- Maven dependencies and dependency management, including BOM version declarations, in imported POMs and active profiles.
- Maven build plugins under `build/plugins` and `build/pluginManagement/plugins`, including active profiles. An omitted plugin group defaults to `org.apache.maven.plugins`.
- Stable versions, including major upgrades, using Maven's version ordering. Common prerelease qualifiers and snapshots are excluded.
- Literal versions and properties. Simple local properties have a quick fix. Inherited dependencies offer a local override and, when the controlling declaration is in an imported project POM, a parent update. Composite expressions require manual review.
- Each module is checked in its own Maven repository context. Reactor-only dependencies have no remote upgrade result unless the artifact is also published.

Reporting plugins, build extensions, plugin-contained dependencies, transitive-only dependencies, snapshot dependencies, version ranges, and `LATEST`/`RELEASE` declarations are outside this release's scope. Plugins without an explicit or managed version are not pinned automatically; edit inherited versions at their source. Remote Maven environments such as WSL/SSH are not supported by the report-file transport in this version.

## npm projects

Configure a **local Node.js interpreter and npm** in IntelliJ's JavaScript runtime settings, then open a project containing `package.json`. Checks start when IDEA inspects the manifest. The inspection is under **Settings → Editor → Inspections → npm → Newer npm dependency version available**. The same **Tools → Versions → Check for Updates** and **Review Updates…** actions work for npm, including Current File and Whole Project.

Registry checks execute only read-only `npm view` commands through IntelliJ's configured runtime. npm evaluates project/user `.npmrc`, environment settings, scoped registries and authentication. Workspace members query from their workspace root so they use its registry configuration. Projects declaring pnpm, Yarn or Bun use the matching adapter and installed CLI. Configure that manager in IntelliJ’s JavaScript runtime settings. An explicit npm `packageManager` takes precedence over leftover lockfiles. `devEngines.packageManager` supports both an object and an array of objects; every entry must name npm. Empty, mixed-manager or malformed declarations are excluded. npm does not need to install dependencies to check versions.

Independent npm packages are checked concurrently, with at most four package queries active at once. Aliases and member manifests share raw metadata within one workspace and resolution configuration. Successful metadata is retained for up to ten minutes with bounded memory; manual refreshes, scheduled scans and bulk previews start a fresh generation. Selector edits reuse the raw response while prepared edits still require current manifest fingerprints. One `npm view` response supplies published stable versions and their deprecation notices; incompatible responses fall back to separate queries. Queries use npm's `--prefer-online` option to revalidate cached registry metadata, while npm retains control of authentication and offline settings.

After a selector edit, warnings and quick fixes for unchanged npm declarations remain available in the manifest and workspace members while checks run. Registry, runtime, workspace and package-manager configuration changes discard retained warnings.

Automatic updates cover `dependencies`, `devDependencies` and `optionalDependencies`, including scoped packages and npm aliases. Exact versions and simple caret/tilde selectors retain their operator: `^1.2.3` becomes `^1.2.9`, and `npm:@scope/package@~1.2.3` retains the alias. Each update mode finds the highest published stable, non-deprecated version in its numeric branch, even if the registry's `latest` tag points to an older version. For `0.x` packages, minor mode can cross minor branches; it does not guarantee compatibility.

When a dependency can be updated in multiple manifests within one npm workspace, **Alt+Enter** offers **Update locally** for the selected declaration and **Update across workspace** for its supported declarations in the root and member packages. The workspace option includes aliases of the same registry package and preserves each selector's operator and dependency section. It leaves newer versions, peer declarations and unsupported selectors for review, and excludes independent projects and nested workspaces. npm packages keep their own dependency declarations; changing only the root declaration does not update a member's version. Current File bulk updates still edit only the selected manifest.

Comparisons use the **declared version or range floor**, not the installed or locked version. Registry deprecation notices refer to that baseline, and the project deprecation field accepts npm names such as `@scope/package = Use another package`. Explicitly retired packages require replacement review. Complex ranges, tags, Git/file selectors, local workspace dependencies and peer compatibility are listed as needing review. Peer declarations, overrides, scripts and the package's own version are not updated.

Root overrides that could conflict with an update put the affected dependency under **Needs review** and block its local, workspace and bulk edits. Review the dependency and override together before changing them. Safe root `$dependency` references follow their own declaration and remain unchanged; references to another dependency and overrides affecting workspace members require review.

The preview applies only `package.json` string edits in one undoable command, preserving declaration categories. Changes to manifests, workspace membership, npm configuration, package-manager selection, runtime selection or deprecation policy invalidate a prepared preview. Workspace discovery and manifest hashes stay warm while ordinary source files are edited. Manifest content changes rebuild workspace ownership and hashes; manifest/directory structure changes and project-root changes rebuild discovery. Saved bytes and unsaved manifest text are tracked separately, and unchanged files retain their cached hashes. Fingerprints cover the relevant workspace and configuration; edits to an independent npm project do not invalidate its siblings. Generated/vendor directories such as `node_modules`, `dist`, `build`, `vendor` and `.yarn` are excluded below the selected IDE content root. The root itself and its enclosing directories can have those names. Declared workspace members imported as separate content roots still share their npm root and overrides; off-project manifests are not scanned. If only a member is imported and its actual npm workspace root is outside project content, import the workspace root before checking; the checker withholds results rather than discarding the parent overrides or registry context.

**Applying version edits changes declarations only.** The update notification offers **Update npm lockfile…** as a separate, explicit operation. Choose a supported `package.json`, review the workspace root and command, and confirm to run IntelliJ's configured npm with `--package-lock-only --ignore-scripts` and audit, funding and update notices disabled. The plugin checks project trust, saved inputs, workspace membership and existing lockfiles again before execution. Installed dependencies are not synchronized. Native lockfile writes are separate from declaration undo; inspect the resulting version control diff, including after cancellation or failure.

A notification shows this reminder after successful bulk, local or workspace npm updates. Selective bulk updates show it only when npm declarations were selected. A stale quick fix that makes no edit does not show the reminder.

## pnpm, Yarn and Bun projects

Configure a local Node.js interpreter and the installed package manager in IntelliJ's JavaScript runtime settings. Explicit `packageManager` and `devEngines.packageManager` declarations select the adapter; conflicting or malformed declarations and ambiguous manager files are excluded. Yarn Classic and modern Yarn are supported. Checks do not download a missing package manager.

Registry checks run `pnpm view`, Yarn Classic `yarn info`, modern `yarn npm info`, or `bun info` at the workspace root. Each CLI owns scoped registry and authentication settings. The same patch/minor/major checks, cached results, local/workspace quick fixes and batch previews apply to supported `package.json` declarations. Yarn's `npm:^1.2.3` protocol retains its prefix. Shared catalog references, peer compatibility and complex selectors require manual review.

pnpm workspace membership comes from `pnpm-workspace.yaml`, including inclusion/exclusion patterns. Yarn and Bun use `package.json` workspaces. Unsaved or saved manager-configuration changes invalidate prepared updates. The checker conservatively leaves projects with resolution overrides or custom pnpm hooks for manual review. pnpm configuration that can bootstrap packages or runtimes is rejected before a CLI starts.

Updates change manifest strings only, in one undoable command. Lockfiles and installed dependencies remain unchanged. After reviewing edits, run the matching package manager yourself; **Update npm lockfile…** is offered only for npm projects. Demos are in `src/test/resources/node-managers-demo/`.

## Gradle projects

Link/import the Gradle project in IntelliJ and enable the bundled Gradle plugin. The same **Tools → Versions → Check for Updates** and **Review Updates…** actions work for `build.gradle`, `build.gradle.kts`, the default local `gradle/libs.versions.toml` catalog, and named local TOML catalogs registered in root settings. The inspection is under **Settings → Editor → Inspections → Gradle → Newer Gradle dependency version available**, with its own inspection severity and Alt+Enter version fixes.

Checks run a temporary metadata inspection task through IntelliJ's Gradle tooling integration, using the linked project's selected distribution/wrapper, Gradle JVM and Gradle user home. Gradle evaluates project/settings repositories, credentials, content filters and user init scripts. The checker adds no public repository. Gradle offline mode disables remote checks. Checks configure the build and can download Gradle/build plugins as part of configuration; the inspection task resolves dependency metadata without downloading library JARs or running application build tasks.

The initial scope covers fixed numeric stable library/BOM coordinates in dependency calls, literal catalog library versions, and simple catalog `version.ref` entries. Declared versions are the comparison baseline; installed, locked or platform-selected versions are not treated as declarations. Gradle selects the latest stable candidate inside the requested patch/minor/major branch, preserving release suffixes such as `jre` and `android`. Catalog entries are checked in the repository contexts of linked subprojects whose source configurations declare the same coordinates and baseline. This matching retains the existing coordinate-based fallback: it does not establish which catalog alias produced a dependency. Entries with no matching source configuration have no automatic update. Identical coordinates and declared versions in distinct physical catalog files require manual review, so a consumed alias cannot authorize an unused second catalog owner. Conflicting target versions between consumer repository contexts also require manual review.

Named catalogs support literal registrations inside `dependencyResolutionManagement { versionCatalogs { ... } }` in `settings.gradle` or `settings.gradle.kts`, for example `create("tools") { from(files("versions/tools.toml")) }`. The path must be relative, and the TOML file must exist inside the linked build and outside generated folders. Several catalog names can refer to one physical file; its aliases and shared versions still produce one edit. Unregistered TOML files are not dependency catalogs. Missing, external, published, computed, conditional or customized registrations appear under **Needs review** in settings and block automatic edits until input ownership can be established. A file shared by independently linked builds also requires review.

A catalog version shared by several libraries or plugins gets one edit only when every consumer has an update to the same version. Unchanged, unsupported or conflicting consumers prevent the shared edit. Current File edits only the selected build file or catalog; a catalog edit affects every consumer of that shared version.

Malformed typed catalog fields appear under **Needs review** without hiding valid sibling declarations. Malformed library or plugin consumers block edits to shared versions whose ownership cannot be established. Independent valid declarations can still be updated.

Plugin versions are checked in `plugins {}` blocks (`id("x") version "1.0"`, `id 'x' version '1.0'` and `kotlin("jvm") version "1.0"`) and in the catalog's `[plugins]` table. Gradle resolves each plugin's `<id>.gradle.plugin` marker through the build's `pluginManagement` repositories, using the same stable-version and update-mode rules as libraries. Plugins without a version, plugin versions from variables or templates, and plugins inside `buildscript {}` are not edited. Ignore rules and messages name plugins as `plugin:<id>`, for example `gradle plugin:org.jetbrains.kotlin.jvm = 2.1.0`.

Invalid TOML syntax, including duplicate keys, makes the whole catalog unavailable for dependency checking. A file-level **Needs review** item opens the syntax error location, and an inspection explains the missing coverage. No catalog versions can be edited until the syntax is corrected. Correct and save the catalog, then refresh the check to restore normal coverage.

Automatic script edits cover standard Java dependency configurations, test-fixture configurations, kapt/ksp and core-library desugaring, with `platform` and `enforcedPlatform` wrappers. Unknown or custom configuration calls, user helper calls and feature wrappers such as `testFixtures` require manual review. Catalog consumers requesting capabilities or features also block automatic catalog edits. Both the current declaration and the exact proposed target are checked in copies of every matching original resolvable configuration, retaining attributes, constraints and resolution rules. Substitutions, target rejection and selection of a different version require manual review, including rules that affect only the proposed version.

Dynamic versions, prereleases, interpolated/shared build-script properties, map notation, dependency customization blocks, constraints, published or programmatically constructed catalogs, convention plugins and dependency substitutions are outside the initial automatic-edit scope. Dependency declarations made for subprojects from another project's build script are not automatically followed back to their source. Declarations without a known source owner, including catalog entries without a matching source configuration, need review and do not count as checked. Project deprecation rules support Gradle `group:artifact` coordinates; there is no inferred Gradle deprecation status.

Classifier and `@extension` string coordinates, Groovy named or bracket maps, and Kotlin `mapOf` declarations appear under **Needs review** with a source anchor. Recognizing these forms does not authorize automatic edits. Arbitrary helper calls, computed map objects and `add(configuration, map)` remain outside this added coverage.

Generated-directory exclusions apply below linked Gradle roots and imported module roots. An enclosing directory or explicitly imported root named `build` or `vendor` remains checkable; generated descendants remain excluded.

Save Gradle build files before checking. Results and previews track build scripts, catalogs, properties, wrapper and lock/verification files, buildSrc sources/resources, literal included builds, user Gradle properties/init scripts, linked project settings, JVM selection and deprecation policy. Unsaved edits or relevant configuration changes invalidate the preview. Changed or added build logic rejects held edits. Computed included-build paths and external/computed applied scripts require manual review because their input coverage cannot be established. Arbitrary executable build code can read additional inputs beyond static discovery. Updates are one undoable command that changes version text only. **Reload the Gradle project afterward; synchronize dependency locks yourself if the build uses locking.**

Version edits keep warnings and quick fixes for unchanged Gradle declarations visible across the linked build, including while edits are unsaved. Native checks wait until build files are saved. Repository, build configuration and catalog-consumer changes discard retained warnings; shared catalog fixes still require every consumer to agree.

Build-file discovery and hashes are shared across checks for a linked Gradle build. Nested linked modules are traversed once, and repeated checks reuse cached directory lists and file hashes between changes. User-home properties and init scripts are read fresh to detect changes outside IntelliJ. Completed Maven, npm and Gradle checks combine nearby highlighting updates and refresh their affected build files.

The [Gradle demo](../src/test/resources/gradle-demo/README.md) includes Groovy and Kotlin scripts, a shared catalog, a BOM, and nested subprojects.

## Other ecosystems

The shared adapter model, coordinator, cache and edit bridge support Maven, npm and Gradle. pnpm, Yarn and Bun use the shared manifest pipeline with manager-specific discovery and native registry commands.

## Development and verification

For hands-on testing, the [Maven demo template](../src/test/resources/maven-demo/README.md) contains intentionally outdated dependencies and build plugins, local and shared version properties, managed versions, and nested modules. On a fresh checkout, create a local copy from the repository root:

```bash
mkdir -p examples
cp -R src/test/resources/maven-demo examples/maven-demo
```

Open `examples/maven-demo/pom.xml` as a separate IDEA project. The local `examples` directory is ignored by Git, so dependency updates in the demo do not alter the committed test fixtures. If the demo already exists, use that copy; copying again would not reset it cleanly. The template instructions describe inspection highlighting, bulk update modes, and an optional repository mirror settings file.

Java and Maven can be selected through SDKMAN. The Gradle wrapper builds the plugin; Maven is only needed separately if you want to reproduce its goal from a terminal.

The shared coordinator owns scheduling and result caching; build-system adapters own discovery, native checks and edit preparation. npm separates workspace ownership and package-manager selection in `NpmWorkspaces` from registry/runtime fingerprints in `NpmBuildInputs`. `NpmProjectCache` caches those inputs without depending on the adapter. Metadata reuse tracks resolution configuration, while edit safety also tracks workspace manifests. Project deprecation rules share a parser in `core`, with ecosystem-specific name validation in the adapters.

Maven/npm performance changes restrict dependency goals to supported snapshot coordinates, reuse Maven scan inputs, combine npm metadata queries and share responses across workspace manifests. See [performance checks](performance.md) for tracing, benchmark commands, and measurement limits.

```bash
./gradlew runIde
./gradlew test buildPlugin verifyPluginProjectConfiguration
./gradlew verifyPlugin
```

Compatibility checks target IDEA 2026.1.4 and 2026.2.3. Verifier compatibility and headless integration tests do not replace native workflow checks. Use the [packaged plugin acceptance procedure](ide-acceptance.md) for the selected archive.

Build and release CI enable the native Maven/npm/Gradle integration tests; local runs can opt in using the flags below. Build CI runs distribution packaging, tests, Maven 4 regressions and compatibility verification concurrently; the release draft still waits for all four checks. The test task builds the distributable ZIP. Lifecycle tests load that ZIP with IDEA's real plugin class loader, exercise repeated unload/reload cycles, and check service disposal, cancellation of active checks and timers, closure of open previews, and class-loader collection. Dialog tests use the platform's headless UI interception; native window behavior can be checked with `runIde`.

Local scaling tests exercise discovery, stale-result validation and bulk application with 101 Maven POMs, Gradle build files or npm manifests and 10,000 declarations. They print timings without depending on machine-specific time limits:

```bash
./gradlew test --tests '*SnapshotScalingTest'
```

Dense Gradle application also checks 10,000 selected replacements in one file, including mixed replacement lengths, one PSI commit and one-command undo. Separate cases check caret and range preservation on unchanged text:

```bash
./gradlew test --tests '*GradleDocumentUpdateTest'
```

Parser and IntelliJ platform tests cover stable-version filtering, wrapped reports, current-POM/project scopes, plugin Maven prerequisites, Maven configuration properties, stale previews, shared properties, dependency/plugin selection, and POM quick fixes.

The locally opt-in integration tests start IDEA's real Maven server with both an authenticated local Maven repository and the five-project demo reactor. They verify the repository profile, mirror, credentials, all three bulk-update modes for dependencies and plugins, current-POM isolation, nested modules, inspection highlighting, and a property quick fix. The Versions goals and demo metadata may be downloaded from Maven Central during these tests:

```bash
./gradlew test -PmavenIntegration=true --tests '*MavenSettingsIntegrationTest'
# Check native effective POMs after quick-fix and bulk updates, including newly defined inherited properties.
./gradlew test -PmavenIntegration=true --tests '*MavenInheritedVersionFixTest'
# Check override cleanup against native effective POMs.
./gradlew test -PmavenIntegration=true --tests '*MavenOverrideInspectionTest'
# Include the full 100-coordinate, 50/250 ms latency benchmark (slower; opt-in).
./gradlew test -PmavenIntegration=true -PmavenLatencyBenchmark=true \
  --tests '*MavenSettingsIntegrationTest.testBatchedMetadataSharesOneHundredArtifactsAcrossOneHundredModulesAndModes'
# Maven 4 regressions execute on IDEA 2026.2.3; provide an extracted Maven 4 distribution.
./gradlew testMaven4 -Pmaven4Home=/path/to/apache-maven-4.0.0-rc-7
```

The Maven sharing regression retains 100 modules and 10,000 declarations. Its default concurrency check uses 32 coordinates at 50 ms with both two and four workers, asserting early publication, overlap and the worker limit. The opt-in latency benchmark above expands that check to 100 coordinates at both 50 and 250 ms.

The npm integration test uses IntelliJ's configured Node/npm with an authenticated local registry. It covers scoped registries, workspaces, aliases, all update modes, deprecation highlighting and manifest-only application, and verifies that no tarballs are downloaded, no `node_modules` directory is created and lockfiles remain unchanged:

```bash
./gradlew test -PnpmIntegration=true --tests '*NpmRegistryIntegrationTest'
```

A [small npm workspace demo](../src/test/resources/npm-demo/README.md) is also available for hands-on inspection and preview checks.

The optional Gradle integration test runs IntelliJ's real Gradle tooling with the project wrapper and an authenticated local Maven repository declared in settings. It covers nested Groovy/Kotlin projects, shared catalog versions, all update modes, current-file isolation, inspections/quick fixes, authentication failures, and unchanged locks with no dependency JAR downloads:

```bash
./gradlew test -PgradleIntegration=true --tests '*GradleRepositoryIntegrationTest'
```

## Releasing

See the [release pipeline guide](releasing.md) for Marketplace credentials, version preparation, signing, prereleases, and recovery.

## License

Copyright 2026 Kristoffer Larsen Hopland. Licensed under the [Apache License, Version 2.0](../LICENSE). A copy is included in the plugin JAR under `META-INF/LICENSE`.
