# Version Checker

An IntelliJ IDEA plugin that highlights newer stable dependency versions in Maven, npm and Gradle projects. Maven build plugins are supported too.

Updates appear as standard IDEA inspections, with hover details, Problems entries and **Alt+Enter** quick fixes:

> Newer version of junit:junit is available: 4.12 → 4.13.2

Update one declaration or review a batch of updates for the current file or whole project.

## Install

Requires **IntelliJ IDEA 2025.3.6.1 or later** and the relevant IDE plugins:

| Project | Required plugins and setup |
| --- | --- |
| Maven | Java and Maven; import the Maven project. |
| npm | JavaScript and TypeScript; configure a local Node.js interpreter and npm. |
| Gradle | Gradle; link/import the Gradle project. |

Open **Settings → Plugins → Marketplace**, search for **Version Checker**, and install it.

To install a local build, run `./gradlew buildPlugin` with Java 21 or later, then select the ZIP in `build/distributions/` through **Settings → Plugins → gear → Install Plugin from Disk**.

## Check and update versions

Checks run in the background when IDEA inspects supported build files.

- **Update one version:** use **Alt+Enter** on a highlighted declaration.
- **Refresh:** choose **Tools → Check Versions → Refresh Current File / Refresh Whole Project** to query fresh metadata.
- **Review a batch:** right-click a build file and choose **Review Dependency Updates…**, or use **Tools → Update Versions → Current File / Whole Project**.
- **Check status:** click the **Versions** status-bar item for the current file's state and actions.

Bulk previews let you choose an update mode:

| Mode | Allowed updates |
| --- | --- |
| Patch only | Keep the current major and minor versions. |
| Minor + patch | Keep the current major version. |
| Major + minor + patch | Any newer stable version. |

Select the changes you want, then click **Update selected declarations**. Changes apply in one undoable command. Shared versions are updated only when their consumers agree; conflicts and unsupported declarations appear under **Needs review**. Changed build inputs or expired results require a fresh preview.

Refresh and review actions save relevant build inputs before checking. Version choices use declared versions, rather than installed or locked versions; an available update does not guarantee compatibility.

## Supported declarations

| Build system | Automatic updates |
| --- | --- |
| Maven | Dependencies, dependency management/BOMs and build plugins in imported POMs and active profiles. Literal versions and simple local properties have quick fixes; parent-managed versions can offer local overrides or updates to an imported parent. |
| npm | `dependencies`, `devDependencies` and `optionalDependencies`, including scoped packages, aliases and npm workspaces. Exact versions and simple `^`/`~` selectors retain their operator. |
| Gradle | Fixed stable library/BOM versions in supported Groovy/Kotlin dependency calls and the default local `gradle/libs.versions.toml` catalog, including simple `version.ref` entries. |

Complex Maven expressions, npm ranges and peer dependencies, and Gradle dynamic versions, script properties, plugin versions and custom catalogs require manual review. pnpm, Yarn and Bun projects are excluded from npm checks.

**After npm updates**, use IntelliJ or npm to synchronize installed dependencies and lockfiles. **After Gradle updates**, reload the project and synchronize dependency locks if used. The checker edits declarations only.

See the [detailed guide](docs/user-guide.md) for complete scope, shared-property rules, workspace updates and Maven platform reviews.

## Settings and repositories

Configure **Settings → Tools → Version Checker** to enable or disable checks, set severity by update type, manage deprecated dependencies and ignored versions, or enable periodic checks. Periodic checking is off by default; its default interval is 30 minutes. Updates default to Warning; deprecated or relocated artifacts default to Error.

Use **Alt+Enter → Ignore … in this project** to hide one published version. Restore it by removing its entry from **Ignored published versions** in settings. Individual inspections can also be disabled under **Settings → Editor → Inspections → Maven / npm / Gradle**.

Checks use each build system's existing repository and authentication configuration: IDEA's Maven settings, npm's `.npmrc`, or the linked Gradle build. No separate repository credentials are needed. Offline settings are respected. See the [repository and caching details](docs/user-guide.md#maven-repositories-and-settingsxml).

## Development

Use Java 21 or later; the Gradle wrapper uses a Java 21 toolchain. Java and Maven can be selected through SDKMAN.

```bash
./gradlew runIde
./gradlew test buildPlugin verifyPluginProjectConfiguration
./gradlew verifyPlugin
```

Compatibility checks target IDEA 2025.3.6.1 and 2026.1.4. Native integration tests are opt-in locally; commands and test coverage are in the [verification guide](docs/user-guide.md#development-and-verification).

Demo projects: [Maven](src/test/resources/maven-demo/README.md), [npm](src/test/resources/npm-demo/README.md), [Gradle](src/test/resources/gradle-demo/README.md). Copy a demo to the Git-ignored `examples/` directory before trying updates.

See [performance measurements](docs/performance.md) and the [release guide](docs/releasing.md) for further development details.

## License

Copyright 2026 Kristoffer Larsen Hopland. Licensed under the [Apache License, Version 2.0](LICENSE).
