# Version Checker

Version Checker is an IntelliJ IDEA plugin that shows newer stable versions in your build files.
It works with Maven, Gradle, npm, pnpm, Yarn and Bun.

> junit:junit · patch update: 4.12 → 4.13.2

Use **Alt+Enter** to update one declaration.
Use **Review Updates…** to select several updates for a file or the whole project.

## Install

Use **IntelliJ IDEA 2026.1.4 through 2026.2.x**.

1. Open **Settings → Plugins → Marketplace**.
2. Search for **Version Checker**.
3. Install the plugin.

Each build system needs its bundled IDE plugin and an imported project:

| Build system | Setup |
| --- | --- |
| Maven | Enable Java and Maven. Import the Maven project. Maven 4 needs IDEA 2026.2.3 or later. |
| Gradle | Enable Gradle. Link the Gradle project. |
| npm, pnpm, Yarn, Bun | Enable JavaScript and TypeScript. Configure a local Node.js interpreter and the package manager. |

## Use

The plugin checks a build file when IDEA inspects it.
Newer versions show as warnings on the declared version.

| Task | Action |
| --- | --- |
| Update one version | Put the caret on the warning. Press **Alt+Enter**. |
| Check a file again | Right-click the build file. Select **Check This File for Updates**. |
| Check the whole project | Select **Tools → Versions → Check for Updates**. |
| Select several updates | Select **Tools → Versions → Review Updates…**, or right-click a build file. |
| See the check status | Click **Versions** in the status bar. |

**Review Updates…** shows all proposed changes in one window.
Select the update mode: **Patch only**, **Minor + patch** or **Major + minor + patch**.
Select the changes, then click **Update selected declarations**.
The plugin applies them as one edit that you can undo.

After an update, a notification offers the next step:
**Reload Maven projects**, **Sync Gradle projects** or **Update npm lockfile…**.
The plugin changes declarations only. It does not change lockfiles or installed packages.

An available update is not a compatibility guarantee. Build and test after you update.

## What is checked

| Build system | Declarations |
| --- | --- |
| Maven | Dependencies, dependency management, BOMs and build plugins. Literal versions and simple version properties, including properties from a parent POM. |
| Gradle | Libraries, BOMs and plugins in Groovy and Kotlin build scripts. Libraries and plugins in local version catalogs. |
| npm, pnpm, Yarn, Bun | `dependencies`, `devDependencies` and `optionalDependencies`, including workspaces and aliases. `^` and `~` selectors keep their operator. |
| Maven wrapper | The Maven version in `.mvn/wrapper/maven-wrapper.properties`. |
| Gradle wrapper | The Gradle version in `gradle/wrapper/gradle-wrapper.properties`, for the official download URL. |

Some declarations show as **Needs review** instead of an automatic update.
Examples are version ranges, computed versions and shared versions whose users do not agree.
The [user guide](docs/user-guide.md) lists the rules for each build system.

## Settings

Open **Settings → Tools → Version Checker**.

- **Minimum release age (hours):** hide releases that are newer than this. Set it to your repository quarantine, for example 24.
- **Dependency rules:** mark packages as deprecated, and see the versions you ignored.

To hide one release, use **Alt+Enter → Ignore … in this project**.
To change the warning level, use **Settings → Editor → Inspections**.

Checks use your existing repository configuration: Maven settings and mirrors, `.npmrc`, and Gradle repositories.
You do not need to enter repository credentials in the plugin.
Offline mode in Maven or Gradle stops remote checks.

## Documentation

- [User guide](docs/user-guide.md): checks, reviews and the rules for each build system.
- [Development](docs/development.md): build, test and try the plugin from source.
- [Architecture](docs/architecture.md): how the code is organized.
- [Releasing](docs/releasing.md) and [packaged plugin acceptance](docs/ide-acceptance.md).

## License

Copyright 2026 Kristoffer Larsen Hopland.
Licensed under the [Apache License, Version 2.0](LICENSE).
