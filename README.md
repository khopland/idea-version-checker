# Version Checker

Version Checker is an IntelliJ IDEA plugin for Maven, npm, pnpm, Yarn, Bun, and Gradle projects.
It shows newer stable dependency versions through standard IDEA inspections.
It also checks Maven build plugins.

> Newer version of junit:junit is available: 4.12 → 4.13.2

Use **Alt+Enter** to update one declaration.
Use an update preview to select several changes for a file or project.

## Install

Use **IntelliJ IDEA 2026.1.4 through 2026.2.x** with these plugins and settings:

| Build system | Required setup |
| --- | --- |
| Maven | Enable Java and Maven. Import the Maven project. Maven 4 requires IDEA 2026.2.3 or later. |
| npm, pnpm, Yarn, Bun | Enable JavaScript and TypeScript. Configure a local Node.js interpreter and the matching installed package manager. |
| Gradle | Enable Gradle. Import the Gradle project. |

1. Open **Settings → Plugins → Marketplace**.
2. Search for **Version Checker**.
3. Install the plugin.

To install a local build, run `./gradlew buildPlugin` with Java 21 or later.
Open **Settings → Plugins → gear → Install Plugin from Disk**.
Select the ZIP file in `build/distributions/`.

## Check and update versions

IDEA starts background checks when it inspects supported build files.

| Task | Action |
| --- | --- |
| Update one version | Use **Alt+Enter** on a highlighted declaration. |
| Check a file again | Right-click the build file. Select **Check This File for Updates**. |
| Check the project again | Select **Tools → Versions → Check for Updates**. |
| Select several updates | Right-click a build file. Select **Review Updates…**. |
| Review project updates | Select **Tools → Versions → Review Updates…**. |
| Examine cached results | Open **View → Tool Windows → Dependency Versions**. |
| Examine check status | Click **Versions** in the status bar. |

Refresh and review actions save the relevant build inputs before a check.
Checks compare declared versions with published versions.
An available update does not confirm compatibility.

Update previews offer these modes:

| Mode | Allowed updates |
| --- | --- |
| Patch only | Keep the current major and minor versions. |
| Minor + patch | Keep the current major version. |
| Major + minor + patch | Allow any newer stable version. |

Select the changes. Click **Update selected declarations**.
The plugin applies the changes in one command that you can undo.
A shared version changes only when its consumers agree.
Conflicts and unsupported declarations appear under **Needs review**.
If build inputs change or results expire, prepare a new preview.
The preview shows the earliest result expiry and disables Apply when that deadline passes.
Use **Refresh** to check again.

## Supported declarations

| Build system | Automatic updates |
| --- | --- |
| Maven | Dependencies, dependency management, BOMs, and build plugins in imported POMs and active profiles. Supports literal versions and simple local properties. Parent-managed versions can offer local overrides or imported-parent updates. |
| npm, pnpm, Yarn, Bun | `dependencies`, `devDependencies`, and `optionalDependencies`. Supports scoped packages, aliases, and npm workspaces. Exact versions and simple `^` or `~` selectors keep their operator. |
| Gradle | Fixed stable library and BOM versions in supported Groovy and Kotlin dependency calls. Supports the default local `gradle/libs.versions.toml` catalog, literal named local TOML catalog registrations in root settings, and simple `version.ref` entries. |

These declarations require manual review:

- Maven expressions that use more than a simple property reference.
- npm ranges and peer dependencies.
- Gradle dynamic versions, script properties, plugin versions, and published or programmatically constructed catalogs.

pnpm uses `pnpm-workspace.yaml`; Yarn and Bun use `package.json` workspaces.
Checks use each manager’s native registry commands. Shared catalogs, resolution overrides and custom hooks require manual review.
See the [user guide](docs/user-guide.md) for declaration limits and shared-version rules.

Maven inspections also flag unnecessary property and dependency version overrides when the parent provides the same or a newer version.
Use **Alt+Enter** to remove an override and inherit the parent value.
For newer parent versions, the action shows the version change.
See [Maven override cleanup](docs/user-guide.md#remove-unnecessary-maven-overrides) for supported declarations.

## After an update

Maven and Gradle notifications offer **Save and reload … projects**.
Check the native build tool window for the reload result.
Update Gradle dependency locks through your normal build process.

For pnpm, Yarn and Bun, run the matching package manager yourself after reviewing manifest edits.

npm notifications offer **Preview npm lockfile sync**.
Select a supported `package.json`. Confirm the workspace root and command.
The command uses `npm install --package-lock-only --ignore-scripts`.
It disables audit, funding, and update notices.
The plugin checks saved inputs again before it starts the command.

Examine the version control diff after lockfile changes, cancellation, or failure.
Lockfile changes are separate from declaration undo.
Use your normal build process to update installed dependencies and run tests.

Maven dependency quick fixes and selected bulk updates capture expected versions for **Verify Maven effective versions**.
Supported edits include literal versions, shared properties and managed versions, local overrides, and inherited-property definitions.
Shared edits retain each recognized consumer POM; local overrides belong to their child.
Save and reload Maven, wait for import completion, then verify against the resolved dependency model.
See [Maven verification](docs/user-guide.md#maven-effective-version-verification) for its limits.

Use **View update session** in the notification to examine declaration changes and subsequent action results.
You can also select **Tools → View Latest Version Update Session…**.
Opening a summary does not reload or verify the project.
See [update sessions](docs/user-guide.md#update-sessions) for retention and validity rules.

## Cached results

The **Dependency Versions** tool window can search, filter, and sort cached observations.
Opening the window or changing filters does not start a native check.
Cached results can expire or refer to earlier build inputs.
Refresh and review before you edit versions.

The coverage summary stays visible when filters hide incomplete results.
Zero updates does not confirm complete coverage.
Separate build contexts or update modes can describe the same physical declaration.
The parser cannot count declarations that it does not recognize.

See the [tool window guide](docs/user-guide.md#dependency-versions-tool-window) for details.

## Settings and repositories

Open **Settings → Tools → Version Checker** to configure checks and dependency rules.
Version updates are shown as warnings, and deprecated or relocated artifacts are struck through.
Change the severity of each inspection under **Settings → Editor → Inspections**.

Use **Minimum release age (hours)** to exclude recent Maven, npm, pnpm, Yarn and Bun releases.
For example, use 24 hours to match a repository quarantine.
See [minimum release age](docs/user-guide.md#minimum-release-age).

Use **Alt+Enter → Ignore … in this project** to hide one published version.
To restore it, remove its entry from **Dependency rules → Ignored versions**.
Disable individual inspections under **Settings → Editor → Inspections → Maven / npm / Gradle**.

Checks use the existing repository and authentication configuration for each build system.
They use IDEA's Maven settings, npm's `.npmrc`, or the linked Gradle build.
They respect offline settings. No separate repository credentials are necessary.
See [repository settings](docs/user-guide.md#maven-repositories-and-settingsxml).

## Development

Use Java 21 or later. The Gradle wrapper uses a Java 21 toolchain.
Select Java and Maven through SDKMAN if necessary.

```bash
./gradlew runIde
./gradlew test buildPlugin verifyPluginProjectConfiguration
./gradlew verifyPlugin
```

Compatibility checks target IDEA 2026.1.4 and 2026.2.3.
Native integration tests require explicit selection in local runs.
See the [verification guide](docs/user-guide.md#development-and-verification) for commands.

Copy a demo to the Git-ignored `examples/` directory before you try updates:
[Maven](src/test/resources/maven-demo/README.md),
[npm](src/test/resources/npm-demo/README.md), or
[Gradle](src/test/resources/gradle-demo/README.md).

See the [documentation index](docs/README.md) for current guides.

Development references:

- [Architecture](docs/architecture.md)
- [Performance measurements](docs/performance.md)
- [Release instructions](docs/releasing.md)
- [Packaged IDE acceptance matrix](docs/ide-acceptance.md)
- [Writing rules](docs/writing.md)

## License

Copyright 2026 Kristoffer Larsen Hopland.
Licensed under the [Apache License, Version 2.0](LICENSE).
