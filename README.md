# Version Checker

An IntelliJ IDEA plugin that shows newer stable Maven dependency and build-plugin versions in `pom.xml`, npm dependency versions in `package.json`, and Gradle dependency versions in build scripts and local version catalogs through **standard IDEA inspections**.

For example, a dependency using `junit:junit:4.12` gets this inspection message:

> Newer version of junit:junit is available: 4.12 → 4.13.2

Use **Alt+Enter → Update version to 4.13.2** to update a literal version. For a simple `${junit.version}` reference declared in the same POM, the quick fix updates the property instead. A shared property change affects every dependency using it.

For a dependency whose version is managed by a parent POM in this project, Alt+Enter offers two choices:

- **Override version locally with 4.13.2:** add or replace `<version>` on this dependency in the current POM.
- **Update parent version to 4.13.2 in …/pom.xml:** change the controlling version or property in the imported parent, including across multiple parent levels. Shared consumers inherit that change.

The parent choice is available only when the controlling declaration can be identified in a non-ignored project POM. A local override remains available for externally managed versions with a known baseline. These choices belong to Version Checker's version inspection; IntelliJ's built-in vulnerability inspection has its own fixes.

## Install and use

Requires IntelliJ IDEA **2025.3.6.1 or later**. Enable the bundled Java and Maven plugins for Maven projects, the bundled Gradle plugin for Gradle projects, or the JavaScript and TypeScript plugin for npm projects. The build targets 2025.3.6.1.

The plugin supports dynamic loading and unloading, allowing IDEA to enable, disable or update it without a restart when the IDE permits it. Unloading cancels background checks and scheduled timers and closes pending update previews. Previews use non-modal windows; build-file changes still invalidate a preview before it can apply edits.

1. In IDEA, open **Settings → Plugins → Marketplace**, search for **Version Checker**, and install it. For a local build, run `./gradlew buildPlugin` using Java 21 or later; Gradle uses a Java 21 toolchain.
2. If installing a local build, use **Settings → Plugins → gear → Install Plugin from Disk** and select the ZIP in `build/distributions/`.
3. Open and import a Maven project. Checks start in the background when IDEA inspects an imported POM.
4. Use **Tools → Check Versions → Refresh Current File** to refresh the POM in the editor, or **Refresh Whole Project** for all imported modules.

Version checks show cancellable progress in IDEA's bottom status bar. A manual refresh bypasses the checker's cached results, queries repository metadata again, and refreshes the inspection highlighting when results arrive. Use this after publishing a new library version while the project is open. Right-click a supported build file in the editor and choose **Refresh Dependency Versions**, or find the refresh actions through **Find Action**.

For periodic checks, enable **Check repositories periodically while this project is open** under **Settings → Tools → Version Checker**. The default interval is 30 minutes, configurable from 1 minute to 24 hours; scheduling is off by default. Checks run after each interval without saving files or changing versions. They skip offline build systems, affected unsaved build files, indexing, and checks already in progress. The timer stops when the project closes or checking is disabled, and resumes with the saved settings when the project opens again. Squiggles appear only for supported newer stable versions; republishing an existing version or a snapshot does not create a version-update warning.

The inspection appears under **Settings → Editor → Inspections → Maven → Newer Maven dependency or plugin version available**. You can disable the whole inspection there. Configure severity by update type under **Settings → Tools → Version Checker**. Editor diagnostics do not change Maven's build result.

## Severity and deprecated dependencies

The settings page has independent **Warning (yellow)**, **Error (red)**, **Information**, and **Disabled** selectors for patch, minor, major, other version changes, and deprecated/relocated artifacts. These settings apply to dependencies and build plugins. All version updates default to Warning, including major upgrades. Deprecated/relocated artifacts default to Error. Disabling a severity hides editor messages for that category while leaving bulk updates available.

Maven has no general deprecation flag for libraries or plugins. To explicitly mark one deprecated for your project, add a line in the settings page's deprecated dependencies or plugins field:

```text
old.group:library = Use new.group:library instead
```

These notices appear even without a newer version. The checker also reads [Maven relocation declarations](https://maven.apache.org/guides/mini/guide-relocation.html) from dependency POMs already downloaded into the project's Maven local repository. Reload Maven to download missing dependency POMs, then refresh the checks. No deprecation is inferred from a library's age or a major-version update. Deprecated/relocated coordinates are listed for manual review during bulk updates, since migration may require coordinate and API changes.

## IDEA highlighting and InlineProblems

Messages use IDEA's standard inspection highlighting, hover tooltips, Problems view, and Alt+Enter quick fixes. Configure their severity under **Settings → Tools → Version Checker**. You can disable automatic checking altogether there, or disable the inspection under **Settings → Editor → Inspections → Maven**.

If installed, [InlineProblems](https://github.com/0verEngineer/InlineProblems) can render these standard inspection messages using its own display. No dependency on that plugin is required.

## Bulk updates and submodules

Choose **Tools → Update Versions**, then **Current File** or **Whole Project**. Shared actions select the adapters for the current file or whole project. Each action checks dependencies and build plugins together and shows one combined preview. Maven, npm and Gradle are implemented adapters; a mixed project gets one combined preview. Current File checks and edits only the imported POM in the editor. Whole Project includes all imported, non-ignored Maven modules, including nested submodules. Both offer:

- **Patch only:** keep the current major and minor version numbers.
- **Minor + patch:** keep the current major version number.
- **Major + minor + patch:** allow any newer stable version.

The check queries the newest version within the selected scope; it does not simply discard a latest version outside that scope. Restricted modes require numeric major/minor prefixes.

A preview lists each POM/property change before applying one undoable command. The plugin saves the changed files and refreshes checks. Compatible dependency/plugin updates sharing a property become one edit. It skips inherited/composite version declarations and shared properties with conflicting, unchanged, unselected, or inherited uses. Shared-property safety checks cover all imported POMs even in Current File mode. These cases appear in the preview as needing review. A preview becomes invalid if an imported POM, relevant Maven configuration, or deprecation policy changes before applying it. Unsaved settings and ancestor `.mvn` configuration edits invalidate it too.

Successful module checks with an empty or absent Maven report count as having no updates and do not abort the scan. Failures are logged to `idea.log`; a **Show details** notification action displays the cause without changing any versions.

Submodules must be imported into IDEA's Maven project model. Maven ignores and offline mode are respected. Parent-managed dependency versions and properties can also be updated from the child with the parent quick fix; bulk updates take a conservative approach to properties inherited across modules.

## Maven repositories and settings.xml

The plugin uses IDEA's existing Maven server rather than querying a public dependency search service. Maven handles the effective repositories, active settings profiles, mirrors, server credentials, encrypted credentials, proxies, and local repository using IDEA's Maven configuration.

Configure **Settings → Build, Execution, Deployment → Build Tools → Maven → User settings file** if you use a custom `settings.xml`. Otherwise Maven uses its default settings location. A mirror with `mirrorOf="*"` is applied by Maven, including for the checker's own Maven goal. No separate repository or credential configuration is needed in the plugin.

Checks execute the read-only `display-dependency-updates` and `display-plugin-updates` goals from `org.codehaus.mojo:versions-maven-plugin:2.21.0`. Maven downloads these goals and their dependencies through the configured plugin repositories on first use. Build-plugin checks also use Maven's effective plugin repositories, settings profiles and mirrors. A private repository must allow or proxy these artifacts. Repository errors produce a notification. IDEA's **Work offline** setting disables remote checks.

The [plugin update goal](https://www.mojohaus.org/versions/versions-maven-plugin/display-plugin-updates-mojo.html) uses different scope controls from the dependency goal. The checker limits candidate versions before querying each major/minor branch, then excludes prereleases, downgrades and updates requiring a newer Maven runtime than the project uses.

Results are cached for ten minutes and invalidated by POM saves, Maven model changes, settings changes in IDEA, or the refresh action. After changing `settings.xml` externally, reload the Maven project or use the refresh action. Checks inspect saved POM contents; version results only apply when the current version still matches.

Each Maven scan captures its coordinates, Maven config properties and explicit profiles once, shares one fresh server embedder across its dependency, plugin and parent goals, and skips artifact categories absent from the POM. Effective repository information is evaluated once and reused during the scan. Before querying, it expires the selected artifacts' metadata update timestamps for the effective repositories and mirrors. Maven then refreshes the metadata even under a daily cache policy; cached artifact files and local-install metadata are retained.

## Maven scope

- Maven dependencies and dependency management, including BOM version declarations, in imported POMs and active profiles.
- Maven build plugins under `build/plugins` and `build/pluginManagement/plugins`, including active profiles. An omitted plugin group defaults to `org.apache.maven.plugins`.
- Stable versions, including major upgrades, using Maven's version ordering. Common prerelease qualifiers and snapshots are excluded.
- Literal versions and properties. Simple local properties have a quick fix. Inherited dependencies offer a local override and, when the controlling declaration is in an imported project POM, a parent update. Composite expressions require manual review.
- Each module is checked in its own Maven repository context. Reactor-only dependencies have no remote upgrade result unless the artifact is also published.

Reporting plugins, build extensions, plugin-contained dependencies, transitive-only dependencies, snapshot dependencies, version ranges, and `LATEST`/`RELEASE` declarations are outside this release's scope. Plugins without an explicit or managed version are not pinned automatically; edit inherited versions at their source. Remote Maven environments such as WSL/SSH are not supported by the report-file transport in this version.

## npm projects

Configure a **local Node.js interpreter and npm** in IntelliJ's JavaScript runtime settings, then open a project containing `package.json`. Checks start when IDEA inspects the manifest. The inspection is under **Settings → Editor → Inspections → npm → Newer npm dependency version available**. The same **Tools → Check Versions** and **Tools → Update Versions** actions work for npm, including Current File and Whole Project.

Registry checks execute only read-only `npm view` commands through IntelliJ's configured runtime. npm evaluates project/user `.npmrc`, environment settings, scoped registries and authentication. Workspace members query from their workspace root so they use its registry configuration. Projects declaring pnpm, Yarn or Bun through `packageManager`, `devEngines.packageManager`, or their manager files are excluded. An explicit npm `packageManager` takes precedence over leftover lockfiles. `devEngines.packageManager` supports both an object and an array of objects; every entry must name npm. Empty, mixed-manager or malformed declarations are excluded. npm does not need to install dependencies to check versions.

Independent npm packages are checked concurrently, with at most four package queries active at once. Aliases and member manifests share raw metadata within one workspace and resolution configuration. Successful metadata is retained for up to ten minutes with bounded memory; manual refreshes, scheduled scans and bulk previews start a fresh generation. Selector edits reuse the raw response while prepared edits still require current manifest fingerprints. One `npm view` response supplies published stable versions and their deprecation notices; incompatible responses fall back to separate queries. Queries use npm's `--prefer-online` option to revalidate cached registry metadata, while npm retains control of authentication and offline settings.

Automatic updates cover `dependencies`, `devDependencies` and `optionalDependencies`, including scoped packages and npm aliases. Exact versions and simple caret/tilde selectors retain their operator: `^1.2.3` becomes `^1.2.9`, and `npm:@scope/package@~1.2.3` retains the alias. Each update mode finds the highest published stable, non-deprecated version in its numeric branch, even if the registry's `latest` tag points to an older version. For `0.x` packages, minor mode can cross minor branches; it does not guarantee compatibility.

When a dependency can be updated in multiple manifests within one npm workspace, **Alt+Enter** offers **Update locally** for the selected declaration and **Update across workspace** for its supported declarations in the root and member packages. The workspace option includes aliases of the same registry package and preserves each selector's operator and dependency section. It leaves newer versions, peer declarations and unsupported selectors for review, and excludes independent projects and nested workspaces. npm packages keep their own dependency declarations; changing only the root declaration does not update a member's version. Current File bulk updates still edit only the selected manifest.

Comparisons use the **declared version or range floor**, not the installed or locked version. Registry deprecation notices refer to that baseline, and the project deprecation field accepts npm names such as `@scope/package = Use another package`. Explicitly retired packages require replacement review. Complex ranges, tags, Git/file selectors, local workspace dependencies and peer compatibility are listed as needing review. Peer declarations, overrides, scripts and the package's own version are not updated.

The preview applies only `package.json` string edits in one undoable command, preserving declaration categories. Changes to manifests, workspace membership, npm configuration, package-manager selection, runtime selection or deprecation policy invalidate a prepared preview. Workspace discovery and manifest hashes stay warm while ordinary source files are edited. Manifest content changes rebuild workspace ownership and hashes; manifest/directory structure changes and project-root changes rebuild discovery. Saved bytes and unsaved manifest text are tracked separately, and unchanged files retain their cached hashes. Fingerprints cover the relevant workspace and configuration; edits to an independent npm project do not invalidate its siblings. Generated/vendor directories such as `node_modules`, `dist`, `build`, `vendor` and `.yarn` are excluded.

**Lockfiles and installed dependencies are left to IntelliJ or npm.** After applying manifest edits, use IntelliJ's package-manager action or run npm yourself to synchronize them. The checker never installs packages or regenerates lockfiles.

The bulk-update preview includes this reminder, and a notification repeats it after successful bulk, local or workspace npm updates. A stale quick fix that makes no edit does not show the reminder.

## Gradle projects

Link/import the Gradle project in IntelliJ and enable the bundled Gradle plugin. The same **Tools → Check Versions** and **Tools → Update Versions** actions work for `build.gradle`, `build.gradle.kts`, and the default local `gradle/libs.versions.toml` catalog. The inspection is under **Settings → Editor → Inspections → Gradle → Newer Gradle dependency version available**, with the shared severity settings and Alt+Enter version fixes.

Checks run a temporary metadata inspection task through IntelliJ's Gradle tooling integration, using the linked project's selected distribution/wrapper, Gradle JVM and Gradle user home. Gradle evaluates project/settings repositories, credentials, content filters and user init scripts. The checker adds no public repository. Gradle offline mode disables remote checks. Checks configure the build and can download Gradle/build plugins as part of configuration; the inspection task resolves dependency metadata without downloading library JARs or running application build tasks.

The initial scope covers fixed numeric stable library/BOM coordinates in dependency calls, literal catalog library versions, and simple catalog `version.ref` entries. Declared versions are the comparison baseline; installed, locked or platform-selected versions are not treated as declarations. Gradle selects the latest stable candidate inside the requested patch/minor/major branch, preserving release suffixes such as `jre` and `android`. Catalog entries are checked in the repository contexts of linked subprojects that directly declare their coordinates and baseline. Unused catalog entries have no automatic update.

A catalog version shared by several libraries gets one edit only when every library has an update to the same version. Unchanged, unsupported or conflicting consumers prevent the shared edit. References also used by catalog plugins require manual review. Current File edits only the selected build file or catalog; a catalog edit affects every consumer of that shared version.

Automatic script edits cover standard Java dependency configurations, test-fixture configurations, kapt/ksp and core-library desugaring, with `platform` and `enforcedPlatform` wrappers. Unknown or custom configuration calls, user helper calls and feature wrappers such as `testFixtures` require manual review. Catalog consumers requesting capabilities or features also block automatic catalog edits. Substitutions are checked in copies of the original resolvable configurations and require manual review, including substitutions to local projects or rules targeting a specific version.

Dynamic versions, prereleases, interpolated/shared build-script properties, map notation, dependency customization blocks, constraints, plugin versions, custom/published catalogs, convention plugins and dependency substitutions are outside the initial automatic-edit scope. Dependency declarations made for subprojects from another project's build script are not automatically followed back to their source. Project deprecation rules support Gradle `group:artifact` coordinates; there is no inferred Gradle deprecation status.

Save Gradle build files before checking. Results and previews track build scripts, catalogs, properties, wrapper and lock/verification files, user Gradle properties/init scripts, linked project settings, JVM selection and deprecation policy. Unsaved edits or relevant configuration changes invalidate the preview. Updates are one undoable command that changes version text only. **Reload the Gradle project afterward; synchronize dependency locks yourself if the build uses locking.**

Build-file discovery and hashes are shared across checks for a linked Gradle build. Nested linked modules are traversed once, and repeated checks reuse cached directory lists and file hashes between changes. User-home properties and init scripts are read fresh to detect changes outside IntelliJ. Completed Maven, npm and Gradle checks combine nearby highlighting updates and refresh their affected build files.

The [Gradle demo](src/test/resources/gradle-demo/README.md) includes Groovy and Kotlin scripts, a shared catalog, a BOM, and nested subprojects.

## Other ecosystems

The shared adapter model, coordinator, cache and edit bridge support Maven, npm and Gradle. pnpm and Bun still require their own discovery, repository and editing adapters.

## Development and verification

For hands-on testing, the [Maven demo template](src/test/resources/maven-demo/README.md) contains intentionally outdated dependencies and build plugins, local and shared version properties, managed versions, and nested modules. On a fresh checkout, create a local copy from the repository root:

```bash
mkdir -p examples
cp -R src/test/resources/maven-demo examples/maven-demo
```

Open `examples/maven-demo/pom.xml` as a separate IDEA project. The local `examples` directory is ignored by Git, so dependency updates in the demo do not alter the committed test fixtures. If the demo already exists, use that copy; copying again would not reset it cleanly. The template instructions describe inspection highlighting, bulk update modes, and an optional repository mirror settings file.

Java and Maven can be selected through SDKMAN. The Gradle wrapper builds the plugin; Maven is only needed separately if you want to reproduce its goal from a terminal.

Maven/npm performance changes restrict dependency goals to supported snapshot coordinates, reuse Maven scan inputs, combine npm metadata queries and share responses across workspace manifests. See [performance measurements and tracing](docs/performance.md) for request-count benchmarks, timing limits and the remaining work.

```bash
./gradlew runIde
./gradlew test buildPlugin verifyPluginProjectConfiguration
./gradlew verifyPlugin
```

Compatibility checks target IDEA 2025.3.6.1 and 2026.1.4.

The test task builds the distributable ZIP. Lifecycle tests load that ZIP with IDEA's real plugin class loader, exercise repeated unload/reload cycles, and check service disposal, cancellation of active checks and timers, closure of open previews, and class-loader collection. Dialog tests use the platform's headless UI interception; native window behavior can be checked with `runIde`.

Local scaling tests exercise discovery, stale-result validation and bulk application with 101 Maven POMs, Gradle build files or npm manifests and 10,000 declarations. They print timings without depending on machine-specific time limits:

```bash
./gradlew test --tests '*SnapshotScalingTest'
```

Parser and IntelliJ platform tests cover stable-version filtering, wrapped reports, current-POM/project scopes, plugin Maven prerequisites, Maven configuration properties, stale previews, shared properties, dependency/plugin selection, and POM quick fixes.

The optional integration tests start IDEA's real Maven server with both an authenticated local Maven repository and the five-project demo reactor. They verify the repository profile, mirror, credentials, all three bulk-update modes for dependencies and plugins, current-POM isolation, nested modules, configurable inspection severity, and a property quick fix. The Versions goals and demo metadata may be downloaded from Maven Central during these tests:

```bash
./gradlew test -PmavenIntegration=true --tests '*MavenSettingsIntegrationTest'
```

The npm integration test uses IntelliJ's configured Node/npm with an authenticated local registry. It covers scoped registries, workspaces, aliases, all update modes, deprecation highlighting and manifest-only application, and verifies that no tarballs are downloaded, no `node_modules` directory is created and lockfiles remain unchanged:

```bash
./gradlew test -PnpmIntegration=true --tests '*NpmRegistryIntegrationTest'
```

A [small npm workspace demo](src/test/resources/npm-demo/README.md) is also available for hands-on inspection and preview checks.

The optional Gradle integration test runs IntelliJ's real Gradle tooling with the project wrapper and an authenticated local Maven repository declared in settings. It covers nested Groovy/Kotlin projects, shared catalog versions, all update modes, current-file isolation, inspections/quick fixes, authentication failures, and unchanged locks with no dependency JAR downloads:

```bash
./gradlew test -PgradleIntegration=true --tests '*GradleRepositoryIntegrationTest'
```

## Releasing

See the [release pipeline guide](docs/releasing.md) for Marketplace credentials, version preparation, signing, prereleases, and recovery.

## License

Copyright 2026 Kristoffer Larsen Hopland. Licensed under the [Apache License, Version 2.0](LICENSE). A copy is included in the plugin JAR under `META-INF/LICENSE`.
