# Version Checker

An IntelliJ IDEA plugin that highlights newer stable dependency versions in Maven, npm and Gradle projects. Maven build plugins are supported too.

Updates appear as standard IDEA inspections, with hover details, Problems entries and **Alt+Enter** quick fixes:

> Newer version of junit:junit is available: 4.12 → 4.13.2

Update one declaration or review a batch of updates for the current file or whole project.

## Install

Requires **IntelliJ IDEA 2026.1.4 through 2026.2.x** and the relevant IDE plugins:

| Project | Required plugins and setup |
| --- | --- |
| Maven | Java and Maven; import the Maven project. Maven 4 requires IDEA 2026.2.3 or later. |
| npm | JavaScript and TypeScript; configure a local Node.js interpreter and npm. |
| Gradle | Gradle; link/import the Gradle project. |

Open **Settings → Plugins → Marketplace**, search for **Version Checker**, and install it.

To install a local build, run `./gradlew buildPlugin` with Java 21 or later, then select the ZIP in `build/distributions/` through **Settings → Plugins → gear → Install Plugin from Disk**.

## Check and update versions

Checks run in the background when IDEA inspects supported build files.

- **Update one version:** use **Alt+Enter** on a highlighted declaration.
- **Refresh:** choose **Tools → Check Versions → Refresh Current File / Refresh Whole Project** to query fresh metadata.
- **Review a batch:** right-click a build file and choose **Review Dependency Updates…**, or use **Tools → Update Versions → Current File / Whole Project**.
- **Keep results open:** use **View → Tool Windows → Dependency Versions** to inspect cached checks, search packages and paths, combine build-system/root/outcome/change filters, inspect selected-row details, open source files, or export the displayed rows in their sort order as JSON. Use **Refresh Whole Project** and **Review Updates…** there for fresh checks and an editable review.
- **Check status:** click the **Versions** status-bar item for the current file's state and actions.

Bulk previews let you choose an update mode:

| Mode | Allowed updates |
| --- | --- |
| Patch only | Keep the current major and minor versions. |
| Minor + patch | Keep the current major version. |
| Major + minor + patch | Any newer stable version. |

Select the changes you want, then click **Update selected declarations**. Changes apply in one undoable command. Shared versions are updated only when their consumers agree; conflicts and unsupported declarations appear under **Needs review**. Changed build inputs or expired results require a fresh preview. Select a change to see its captured declaration, target, consumers and build context. Select a **Needs review** item to see its reason and open its source when available.

Refresh and review actions save relevant build inputs before checking. Version choices use declared versions, rather than installed or locked versions; an available update does not guarantee compatibility.

## Supported declarations

| Build system | Automatic updates |
| --- | --- |
| Maven | Dependencies, dependency management/BOMs and build plugins in imported POMs and active profiles. Literal versions and simple local properties have quick fixes; parent-managed versions can offer local overrides or updates to an imported parent. |
| npm | `dependencies`, `devDependencies` and `optionalDependencies`, including scoped packages, aliases and npm workspaces. Exact versions and simple `^`/`~` selectors retain their operator. |
| Gradle | Fixed stable library/BOM versions in supported Groovy/Kotlin dependency calls and the default local `gradle/libs.versions.toml` catalog, including simple `version.ref` entries. |

Complex Maven expressions, npm ranges and peer dependencies, and Gradle dynamic versions, script properties, plugin versions and custom catalogs require manual review. pnpm, Yarn and Bun projects are excluded from npm checks.

**After applying updates**, the notification offers **Save and reload Maven/Gradle projects** for the providers changed. npm updates offer **Preview npm lockfile sync**: choose a supported package.json and confirm the configured command and workspace root. This runs `npm install --package-lock-only --ignore-scripts` with audit, funding and update notices disabled. It checks saved inputs again before execution and reports changed lockfiles, cancellation or failure. Review the version control diff; lockfile writes are separate from declaration undo. Installed npm dependencies, Gradle locks and build verification still need their usual native workflow.

The tool window and JSON export contain cached observations, which expire and may lag current build inputs. Opening or filtering them does not run native checks. Exports contain displayed rows, sanitized details and project-relative source paths; external paths are redacted. Refresh and review before editing.

The tool-window coverage summary stays visible when filters hide incomplete results. It distinguishes checked observations and updates from skipped/unsupported, failed, pending, unknown and manual-review results. Counts refer to cached observations: separate build contexts or update modes can describe the same physical declaration. Zero updates does not mean complete coverage. Select a row for its source, diagnostic, coverage scope and suggested next action; declarations the parser does not recognize cannot be counted individually.

See the [detailed guide](docs/user-guide.md) for complete scope, shared-property rules, workspace updates and Maven platform reviews.

## Settings and repositories

Configure **Settings → Tools → Version Checker** to enable or disable checks, set severity by update type, manage deprecated dependencies and ignored versions, or enable periodic checks. Periodic checking is off by default; its default interval is 30 minutes. Updates default to Warning; deprecated or relocated artifacts default to Error. Set **Advanced Maven settings → Minimum release age (hours)** to suggest only Maven versions published at least that long ago, for example to match a 24-hour Nexus quarantine; see [minimum release age](docs/user-guide.md#minimum-release-age).

Use **Alt+Enter → Ignore … in this project** to hide one published version. Restore it by removing its entry from **Dependency rules → Ignored versions** in settings. Individual inspections can also be disabled under **Settings → Editor → Inspections → Maven / npm / Gradle**.

Checks use each build system's existing repository and authentication configuration: IDEA's Maven settings, npm's `.npmrc`, or the linked Gradle build. No separate repository credentials are needed. Offline settings are respected. See the [repository and caching details](docs/user-guide.md#maven-repositories-and-settingsxml).

## Development

Use Java 21 or later; the Gradle wrapper uses a Java 21 toolchain. Java and Maven can be selected through SDKMAN.

```bash
./gradlew runIde
./gradlew test buildPlugin verifyPluginProjectConfiguration
./gradlew verifyPlugin
```

Compatibility checks target IDEA 2026.1.4 and 2026.2.3. Native integration tests are opt-in locally; commands and test coverage are in the [verification guide](docs/user-guide.md#development-and-verification). See the [release instructions](docs/releasing.md) and [1.3.0 acceptance record](docs/release-acceptance-1.3.0/README.md) for delivery and validation details.

Demo projects: [Maven](src/test/resources/maven-demo/README.md), [npm](src/test/resources/npm-demo/README.md), [Gradle](src/test/resources/gradle-demo/README.md). Copy a demo to the Git-ignored `examples/` directory before trying updates.

See the [improvement roadmap](docs/improvement-roadmap.html) and [packaged IDE acceptance matrix](docs/ide-acceptance.md) for the next delivery and validation work.

See the [architecture guide](docs/architecture.md), [performance measurements](docs/performance.md), and the [release guide](docs/releasing.md) for further development details.

## License

Copyright 2026 Kristoffer Larsen Hopland. Licensed under the [Apache License, Version 2.0](LICENSE).

After applying an inherited Maven property override, use **Save and reload Maven projects**, wait for Maven resolution, then choose **Verify Maven effective versions** in the update notification. Verification compares each checked dependency's expected version with a subsequently resolved native Maven model. It reports confirmed, mismatch, or unknown separately from declaration edits and reload requests. It does not run tests or establish compatibility. Plugins, parent POMs and absent or unresolved dependencies remain unknown; other update paths do not yet capture verification targets. Verification waits for the native full Maven import request to complete and requires identical physical input content before and after that request. Unrelated resolution events invalidate its evidence. Changed or undone declaration snapshots, physical parent/config/settings edits (including changes without a VFS event), and changed Maven settings or profiles invalidate the captured evidence. Re-run the review after further edits.
