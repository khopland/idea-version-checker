# Version Checker

An IntelliJ IDEA plugin that highlights outdated dependency versions in build files and updates them by patch, minor or major version.

> junit:junit 4.12 → 4.12.1 (patch) · 4.13.2 (minor) · 5.0.0 (major)

## Install

Use IntelliJ IDEA 2026.1.4 through 2026.2.x. Open **Settings → Plugins → Marketplace** and search for **Version Checker**.

## Use

| Task | Action |
| --- | --- |
| Update one version | Put the caret on the warning and press **Alt+Enter**. Pick the patch, minor or major update. |
| Update every version in a file | **Tools → Versions → Update This File → Patch / Minor / Major**, or the editor and Project view context menus. |
| Update every build file in the project | **Tools → Versions → Update All Files → Patch / Minor / Major**. |
| Check again now | **Tools → Versions → Check Now**. Results otherwise stay cached for 30 minutes. After that, and after a restart, the plugin asks the repository whether its stored copy is still current, so an unchanged document is not downloaded again. |

Each mode picks the newest version in its branch. **Patch** finds the newest `1.2.x` even when `2.0` exists. **Minor** stays on the same major version.
A bulk update is one edit, across all files. **Edit → Undo** reverts all of it.
The plugin only edits declarations. It does not change lockfiles, `node_modules` or downloaded artifacts.
Reload Maven or Gradle afterwards, then build and test: a newer version is not a compatibility guarantee.

Only stable versions are suggested, without alpha, beta, milestone, RC, preview or snapshot versions.
If you use a prerelease, its final release counts as newer: `4.0.0-rc-5` is offered `4.0.0`.

A version is read as numbers, an optional prerelease marker and an optional variant. The variant is kept:

| Version | Updates to |
| --- | --- |
| `33.0.0-jre` | Newer `-jre` versions, never `-android`. |
| `2.3-groovy-3.0` (Spock) | Newer `-groovy-3.0` versions, never `-groovy-4.0`. |
| `5.2.9.RELEASE`, `5.6.15.Final` | Plain newer versions. `.RELEASE`, `.Final` and `.GA` are not variants. |
| `1.2.3-1` | `1.2.3-2`: a number right after the version is a build number. |

Versions that are not semver use the same rule: the first number is major, the second minor, and everything after is patch.
A two-part `1.2` has minor updates, a date version like `20231013` only has major updates, and a calendar version like `2024.1.0` treats the year as major.
Versions that do not start with a number, like `r09`, are not suggested.

## What is checked

| Build system | Declarations |
| --- | --- |
| Maven | `pom.xml`: dependencies, dependency management and BOMs, build plugins, plugin management, extensions, profiles and an external parent. Literal versions and `${property}` versions. The fix changes the property where it is set, also in a parent POM in the project. |
| npm | `package.json`: `dependencies`, `devDependencies` and `optionalDependencies`. Exact, `^` and `~` versions keep their operator. Works for npm, pnpm, Yarn and Bun projects. Registry deprecations show in the message. |
| Gradle | Build scripts: `"group:artifact:version"` strings, `id("x") version "v"` and `kotlin("jvm") version "v"`. `libs.versions.toml`: `[libraries]` and `[plugins]`, with literal versions or `version.ref`. |
| Wrappers | `distributionUrl` in `.mvn/wrapper/maven-wrapper.properties` and `gradle/wrapper/gradle-wrapper.properties`. |

A version that several declarations share, like a Maven property or a catalog `version.ref`, changes only when they all want the same version.

Not checked: version ranges and tags, computed versions, transitive and peer dependencies, lockfiles, and Gradle versions from variables.

## Redundant Maven overrides

The **Redundant Maven override** inspection works offline and reports:

- a dependency `<version>` that equals the managed version from a parent or BOM, or is older than it,
- a dependency management entry that repeats the parent module's version,
- a property with the same value as in the parent, or an older value,
- a version property that no POM in the project or parent chain uses.

Use **Alt+Enter** to remove one, or **Fix all** in the file.

## Settings

**Settings → Tools → Version Checker**:

- **Check versions in build files**: turn the checks on or off.
- **Minimum release age (hours)**: hide Maven, Gradle and wrapper versions published less recently than this, for example `24` to match a Nexus quarantine. It does not apply to npm: a Nexus quarantine already leaves held-back versions out of the npm metadata, while Maven metadata still lists them.

Severity and per-inspection on/off are under **Settings → Editor → Inspections → Version Checker**.

## Repositories and credentials

The plugin reads repository metadata over HTTP, using the IDE proxy settings. It does not start Maven, Node or Gradle.

| Build system | Repositories | Credentials |
| --- | --- | --- |
| Maven | The repositories IDEA imported for the module, with mirrors applied. Maven Central if the project is not imported. **Work offline** stops checks. | `<server>` entries in your `settings.xml`, matched by repository or mirror id: `username` and `password`, or `configuration/httpHeaders`. `${env.NAME}` works. Encrypted `{…}` passwords are decrypted with the master password in `~/.m2/settings-security.xml`, like Maven 3. Maven 4's new encrypted format is not supported. |
| npm | `registry` and `@scope:registry` from `.npmrc` files in the package directory and above, the user `.npmrc` and `npm_config_registry`. With a `.yarnrc.yml` (Yarn 2+), `npmRegistryServer` and `npmScopes` from `.yarnrc.yml` instead. | `//host/path/:_authToken`, `_auth`, or `username` and `_password` in `.npmrc`. For Yarn 2+, `npmAuthToken` or `npmAuthIdent` in `.yarnrc.yml`, per scope, per registry or for all. `${NAME}` works. |
| Gradle | Maven Central (Google's repository for `androidx`, `com.android` and similar groups) and the Gradle Plugin Portal. | None. |

When a repository fails for several versions in a file, for example without VPN, the file shows one warning that says how many versions were not checked.

Release times for the minimum release age come from the POM's `Last-Modified` header (Maven and Gradle) and `services.gradle.org` (Gradle wrapper).
A version without a known release time is hidden while the setting is above zero.

## Development

```bash
./gradlew runIde                        # A sandbox IDE with the plugin.
./gradlew test                          # Unit and platform tests, with no network.
./gradlew test -Plive=true --tests '*LiveRepositoriesTest'   # Against the public registries.
./gradlew buildPlugin verifyPlugin      # The ZIP in build/distributions, and binary compatibility.
```

The demo projects in `src/test/resources` have outdated versions on purpose. Copy one to the Git-ignored `examples/` directory and open the copy.

| Package | Contents |
| --- | --- |
| `core` | Version ordering and targets, the HTTP client, the cached checker, the annotator and inspection, quick fixes and bulk updates. |
| `repo` | The Maven-layout repository lookup used by Maven, Gradle and the Maven wrapper. |
| `maven`, `npm`, `gradle`, `wrapper` | One `DeclarationProvider` each: find versions in a file, and say how to look them up and edit them. |

## License

Copyright 2026 Kristoffer Larsen Hopland. Licensed under the [Apache License, Version 2.0](LICENSE).
