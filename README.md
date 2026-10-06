# Version Checker

An IntelliJ IDEA plugin that shows newer stable Maven dependency and build-plugin versions in `pom.xml` and npm dependency versions in `package.json` through **standard IDEA inspections**.

For example, a dependency using `junit:junit:4.12` gets this inspection message:

> Newer version of junit:junit is available: 4.12 → 4.13.2

Use **Alt+Enter → Update version to 4.13.2** to update a literal version. For a simple `${junit.version}` reference declared in the same POM, the quick fix updates the property instead. A shared property change affects every dependency using it.

## Install and use

Requires IntelliJ IDEA **2025.3 or later**. Enable the bundled Java and Maven plugins for Maven projects, or the JavaScript and TypeScript plugin for npm projects. The build targets 2025.3.6.1.

1. Build with `./gradlew buildPlugin` using Java 21 or later. Gradle uses a Java 21 toolchain.
2. In IDEA, open **Settings → Plugins → gear → Install Plugin from Disk** and select `build/distributions/version-checker-1.0.0-SNAPSHOT.zip`.
3. Open and import a Maven project. Checks start in the background when IDEA inspects an imported POM.
4. Use **Tools → Check Versions → Current File** to refresh the POM in the editor, or **Whole Project** for all imported modules.

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

Choose **Tools → Update Versions**, then **Current File** or **Whole Project**. Shared actions select the adapters for the current file or whole project. Each action checks dependencies and build plugins together and shows one combined preview. Maven and npm are implemented adapters; a mixed project gets one combined preview. Current File checks and edits only the imported POM in the editor. Whole Project includes all imported, non-ignored Maven modules, including nested submodules. Both offer:

- **Patch only:** keep the current major and minor version numbers.
- **Minor + patch:** keep the current major version number.
- **Major + minor + patch:** allow any newer stable version.

The check queries the newest version within the selected scope; it does not simply discard a latest version outside that scope. Restricted modes require numeric major/minor prefixes.

A preview lists each POM/property change before applying one undoable command. The plugin saves the changed files and refreshes checks. Compatible dependency/plugin updates sharing a property become one edit. It skips inherited/composite version declarations and shared properties with conflicting, unchanged, unselected, or inherited uses. Shared-property safety checks cover all imported POMs even in Current File mode. These cases appear in the preview as needing review. A preview becomes invalid if an imported POM, relevant Maven configuration, or deprecation policy changes before applying it.

Successful module checks with an empty or absent Maven report count as having no updates and do not abort the scan. Failures are logged to `idea.log`; a **Show details** notification action displays the cause without changing any versions.

Submodules must be imported into IDEA's Maven project model. Maven ignores and offline mode are respected. Parent-managed properties can still be updated individually with Alt+Enter at their declaration; bulk updates take a conservative approach to properties inherited across modules.

## Maven repositories and settings.xml

The plugin uses IDEA's existing Maven server rather than querying a public dependency search service. Maven handles the effective repositories, active settings profiles, mirrors, server credentials, encrypted credentials, proxies, and local repository using IDEA's Maven configuration.

Configure **Settings → Build, Execution, Deployment → Build Tools → Maven → User settings file** if you use a custom `settings.xml`. Otherwise Maven uses its default settings location. A mirror with `mirrorOf="*"` is applied by Maven, including for the checker's own Maven goal. No separate repository or credential configuration is needed in the plugin.

Checks execute the read-only `display-dependency-updates` and `display-plugin-updates` goals from `org.codehaus.mojo:versions-maven-plugin:2.21.0`. Maven downloads these goals and their dependencies through the configured plugin repositories on first use. Build-plugin checks also use Maven's effective plugin repositories, settings profiles and mirrors. A private repository must allow or proxy these artifacts. Repository errors produce a notification. IDEA's **Work offline** setting disables remote checks.

The [plugin update goal](https://www.mojohaus.org/versions/versions-maven-plugin/display-plugin-updates-mojo.html) uses different scope controls from the dependency goal. The checker limits candidate versions before querying each major/minor branch, then excludes prereleases, downgrades and updates requiring a newer Maven runtime than the project uses.

Results are cached for ten minutes and invalidated by POM saves, Maven model changes, settings changes in IDEA, or the refresh action. After changing `settings.xml` externally, reload the Maven project or use the refresh action. Checks inspect saved POM contents; version results only apply when the current version still matches.

## Maven scope

- Maven dependencies and dependency management, including BOM version declarations, in imported POMs and active profiles.
- Maven build plugins under `build/plugins` and `build/pluginManagement/plugins`, including active profiles. An omitted plugin group defaults to `org.apache.maven.plugins`.
- Stable versions, including major upgrades, using Maven's version ordering. Common prerelease qualifiers and snapshots are excluded.
- Literal versions and properties. Simple local properties have a quick fix; inherited, composite, and externally managed versions must be edited at their source.
- Each module is checked in its own Maven repository context. Reactor-only dependencies have no remote upgrade result unless the artifact is also published.

Gradle, reporting plugins, build extensions, plugin-contained dependencies, transitive-only dependencies, snapshot dependencies, version ranges, and `LATEST`/`RELEASE` declarations are outside this release's scope. Plugins without an explicit or managed version are not pinned automatically; edit inherited versions at their source. Remote Maven environments such as WSL/SSH are not supported by the report-file transport in this version.

## npm projects

Configure a **local Node.js interpreter and npm** in IntelliJ's JavaScript runtime settings, then open a project containing `package.json`. Checks start when IDEA inspects the manifest. The inspection is under **Settings → Editor → Inspections → npm → Newer npm dependency version available**. The same **Tools → Check Versions** and **Tools → Update Versions** actions work for npm, including Current File and Whole Project.

Registry checks execute only read-only `npm view` commands through IntelliJ's configured runtime. npm evaluates project/user `.npmrc`, environment settings, scoped registries and authentication. Workspace members query from their workspace root so they use its registry configuration. Projects declaring pnpm, Yarn or Bun through `packageManager`, `devEngines.packageManager`, or their manager files are excluded. An explicit npm `packageManager` takes precedence over leftover lockfiles. npm does not need to install dependencies to check versions.

Automatic updates cover `dependencies`, `devDependencies` and `optionalDependencies`, including scoped packages and npm aliases. Exact versions and simple caret/tilde selectors retain their operator: `^1.2.3` becomes `^1.2.9`, and `npm:@scope/package@~1.2.3` retains the alias. Each update mode finds the highest published stable, non-deprecated version in its numeric branch, even if the registry's `latest` tag points to an older version. For `0.x` packages, minor mode can cross minor branches; it does not guarantee compatibility.

Comparisons use the **declared version or range floor**, not the installed or locked version. Registry deprecation notices refer to that baseline, and the project deprecation field accepts npm names such as `@scope/package = Use another package`. Explicitly retired packages require replacement review. Complex ranges, tags, Git/file selectors, local workspace dependencies and peer compatibility are listed as needing review. Peer declarations, overrides, scripts and the package's own version are not updated.

The preview applies only `package.json` string edits in one undoable command, preserving declaration categories. Changes to manifests, workspace membership, npm configuration, package-manager selection, runtime selection or deprecation policy invalidate a prepared preview. Generated/vendor directories such as `node_modules`, `dist`, `build`, `vendor` and `.yarn` are excluded.

**Lockfiles and installed dependencies are left to IntelliJ or npm.** After applying manifest edits, use IntelliJ's package-manager action or run npm yourself to synchronize them. The checker never installs packages or regenerates lockfiles.

## Other ecosystems

The shared adapter model, coordinator, cache and edit bridge support Maven and npm. Gradle, pnpm and Bun still require their own discovery, repository and editing adapters. See [implementation notes](docs/adapter-implementation.md) and [the extension assessment](docs/ecosystem-support.md).

## Development and verification

For hands-on testing, the [Maven demo template](src/test/resources/maven-demo/README.md) contains intentionally outdated dependencies and build plugins, local and shared version properties, managed versions, and nested modules. On a fresh checkout, create a local copy from the repository root:

```bash
mkdir -p examples
cp -R src/test/resources/maven-demo examples/maven-demo
```

Open `examples/maven-demo/pom.xml` as a separate IDEA project. The local `examples` directory is ignored by Git, so dependency updates in the demo do not alter the committed test fixtures. If the demo already exists, use that copy; copying again would not reset it cleanly. The template instructions describe inspection highlighting, bulk update modes, and an optional repository mirror settings file.

Java and Maven can be selected through SDKMAN. The Gradle wrapper builds the plugin; Maven is only needed separately if you want to reproduce its goal from a terminal.

```bash
./gradlew runIde
./gradlew test buildPlugin verifyPluginProjectConfiguration
./gradlew verifyPlugin
```

Compatibility checks target IDEA 2025.3.6.1 and 2026.1.4.

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
