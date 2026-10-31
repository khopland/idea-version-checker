# User guide

Version Checker shows newer stable versions in Maven, Gradle, npm, pnpm, Yarn and Bun build files.
It uses IDEA's standard inspections, so hints show in the editor, the Problems view and **Alt+Enter**.
To install the plugin, see the [README](../README.md).

- [Check versions](#check-versions)
- [Review and apply updates](#review-and-apply-updates)
- [After an update](#after-an-update)
- [Settings](#settings)
- [Maven](#maven)
- [Gradle](#gradle)
- [npm, pnpm, Yarn and Bun](#npm-pnpm-yarn-and-bun)
- [Maven and Gradle wrappers](#maven-and-gradle-wrappers)
- [Troubleshooting](#troubleshooting)

## Check versions

The plugin checks a build file in the background when IDEA inspects it.
A hint shows the package, the update kind and the version change:

> junit:junit · patch update: 4.12 → 4.13.2

Deprecated and relocated packages are struck through.
Declarations that the plugin cannot update automatically show a hint with the reason.

| Task | Action |
| --- | --- |
| Check one file again | Right-click the build file. Select **Check This File for Updates**. |
| Check the whole project | Select **Tools → Versions → Check for Updates**. |
| See the status of the current file | Look at **Versions** in the status bar. Click it for the check actions and settings. |

A manual check ignores cached results and reads the repository metadata again.
Use it after a new version is published while the project is open.
The plugin saves the related build files before a manual check.

Results stay in memory for ten minutes.
A change to a build file, the build settings or the plugin settings removes the related results.
After you update one declaration, the hints for the other declarations stay visible while the check runs again.

The plugin shows stable versions only.
It skips alpha, beta, milestone, release candidate, preview and snapshot versions.
It compares with the declared version, not the installed or locked version.

## Review and apply updates

To update one declaration, put the caret on the hint and press **Alt+Enter**.

To update several declarations, select **Tools → Versions → Review Updates…**.
You can also right-click a build file and select **Review Updates…**.
The review window shows all proposed changes, with a text filter and sortable columns.

1. Select the scope: **Current File** or **Whole Project**.
2. Select the update mode:

   | Mode | Allowed updates |
   | --- | --- |
   | **Patch only** | Keep the current major and minor versions. |
   | **Minor + patch** | Keep the current major version. |
   | **Major + minor + patch** | Allow any newer stable version. |

3. Select the changes. All changes start selected.
4. Click **Update … selected declarations**.

The plugin applies all selected changes as one edit. Use **Edit → Undo** to revert it.

Each mode looks for the newest version inside its branch.
For example, **Patch only** finds the newest `1.2.x`, even when `2.0` exists.
In minor or major mode, **Check other targets** prepares patch and minor alternatives.
Select a row, then use **Target** to select a different version for it.

Some declarations show under **Needs review** instead of as changes.
Examples are shared versions whose users need different versions, version ranges and computed versions.
Each item shows the reason and opens the source.

Results expire. The window shows the time until the first result expires.
After that time, the update button is disabled. Click **Refresh** to check again.
If a build file or setting changes before you apply, the plugin rejects the update. Refresh and try again.

## After an update

After an update, the **Versions updated** notification offers one next step for each build system:

| Build system | Action | What it does |
| --- | --- | --- |
| Maven | **Reload Maven projects** | Saves the build files and starts a Maven reload. |
| Gradle | **Sync Gradle projects** | Saves the build files and starts a Gradle sync. |
| npm | **Update npm lockfile…** | Shows the `npm install --package-lock-only --ignore-scripts` command, and runs it after you confirm. |

**Review changed files** shows the build files before and after the update.

The plugin changes declarations only:

- It does not install packages or change `node_modules`.
- For pnpm, Yarn and Bun, run the package manager yourself to update the lockfile.
- For Gradle dependency locking, update the locks with your normal build.
- Lockfile changes are not part of the undoable edit. Review them in version control.

A reload request does not confirm that the reload succeeded. Look at the build tool window, then build and test.

## Settings

Open **Settings → Tools → Version Checker**.

| Setting | Use |
| --- | --- |
| **Enable version checks** | Turn the background checks on or off. |
| **Minimum release age (hours)** | Hide releases that are newer than this. See [minimum release age](#minimum-release-age). |
| **Mark dependencies as deprecated** | Show a deprecation hint for a package. Write one rule per line, for example `old.group:library = Use new.group:library`. |
| **Ignored versions** | The releases you hid. Remove a line to show the release again. |

The last two settings are in the **Dependency rules** group.
To change the warning level, or to turn off one inspection, use **Settings → Editor → Inspections**.
The inspections are in the **Maven**, **Gradle** and **npm** groups.

### Ignore a release

To hide one published version, put the caret on its hint.
Select **Alt+Enter → Ignore … in this project**.
Later releases and other packages still show.
The ignore applies to this project only.

### Minimum release age

Some repository managers hold new releases back for a time.
For example, a Sonatype Nexus Repository Firewall quarantine lists a new version but refuses its JAR.
Set **Minimum release age (hours)** to the quarantine time, for example `24`.
The plugin then suggests only versions that are older than that.
The default `0` suggests the newest version.

| Build system | Release time |
| --- | --- |
| Maven and the Maven wrapper | The `Last-Modified` time of the version's POM. |
| npm, pnpm, Yarn and Bun | The registry's `time` entry for the version. |
| Gradle wrapper | The release's build time on `services.gradle.org`. |
| Gradle libraries and plugins | Not supported. Gradle does not give publication times. |

The setting matches a time-based quarantine only.
Versions that a repository blocks for other reasons, for example security policy, still show.

### Shared project rules

To share rules with your team, commit a `.version-checker.json` file in the project root directory:

```json
{
  "schemaVersion": 1,
  "rules": [
    {
      "ecosystem": "maven",
      "package": "org.example:library",
      "ignoredVersions": [{ "version": "2.1.0", "reason": "Wait for compatibility tests", "until": "2026-12-01T00:00:00Z" }],
      "allowedUpdates": ["patch", "minor"]
    },
    { "ecosystem": "npm", "package": "@example/old-package", "deprecated": "Use @example/replacement" },
    { "ecosystem": "gradle", "package": "org.example:legacy", "deprecated": "Use org.example:replacement" }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `ecosystem` | `maven`, `gradle` or `npm`. |
| `package` | `group:artifact` for Maven and Gradle. The registry name for npm. Wildcards are not supported. |
| `ignoredVersions` | Exact versions to hide. `reason` and `until` are optional. `until` is an ISO-8601 time with an offset. |
| `deprecated` | The deprecation message. The package then needs a manual replacement. |
| `allowedUpdates` | Any of `patch`, `minor`, `major` and `other`. An empty list blocks all automatic updates for the package. |

Shared rules add to your local rules. A local setting cannot remove a shared rule.
If the file has an error, the plugin blocks updates and shows the error in the settings and the status bar.
Correct the file, then check again.

## Maven

The plugin checks imported Maven projects. Modules must be part of IDEA's Maven project model.

**Checked:**

- Dependencies and dependency management, including BOM imports, in the POM and active profiles.
- Build plugins in `build/plugins` and `build/pluginManagement/plugins`.
- Literal versions and simple version properties such as `${junit.version}`.
- Version properties that a child POM overrides for its parent.

**Quick fixes:**

- **Update version to …** changes a literal version. For a simple property, **Update &lt;property&gt; to …** changes the property.
  A shared property changes every dependency that uses it.
- For a version that a parent POM manages, **Override version locally with …** adds a version in the current POM.
  **Update … to … in pom.xml** changes the version in the parent POM instead.
- For a version that comes from a parent property, **Define &lt;property&gt; as … in this POM** adds the property to the child.

**Needs review:** composite expressions, version ranges, `LATEST` and `RELEASE`, properties with conflicting users, and plugins without a version.

**Not checked:** reporting plugins, build extensions, transitive dependencies and snapshot dependencies.

### Unnecessary overrides

The **Unnecessary Maven property or dependency version override** inspection finds overrides that you can remove:

- A property with the same value as the parent.
- A dependency version that equals the inherited version.
- An older override, when the parent gives a newer stable version.

Use **Alt+Enter** to remove the override. For a newer parent version, the action shows both versions.
Review the XML preview before you apply. Removing a property can change several dependencies.

### Repositories

The plugin uses IDEA's Maven configuration.
Maven applies your `settings.xml`, profiles, mirrors, credentials, proxies and local repository.
To use a custom `settings.xml`, set **Settings → Build, Execution, Deployment → Build Tools → Maven → User settings file**.

The plugin runs a small helper in Maven to read version metadata.
The helper does not download library JARs or resolve dependency trees.
Projects with Maven core extensions or custom Versions-plugin rules use the `versions-maven-plugin` goals instead.
Maven downloads that plugin on first use, so a private repository must allow or proxy it.

**Work offline** in the Maven settings stops remote checks.
Maven 4 needs IDEA 2026.2.3 or later.

### Deprecation and relocation

Maven has no general deprecation flag.
To mark a package as deprecated, add a rule under **Mark dependencies as deprecated**.
The plugin also reads [relocation declarations](https://maven.apache.org/guides/mini/guide-relocation.html) from POMs in the local repository.
If a relocation does not show, reload Maven to download the POMs, then check again.

## Gradle

The plugin checks linked Gradle projects. It uses IDEA's Gradle tooling, the project's Gradle JVM and the wrapper.

**Checked:**

- Libraries and BOMs in standard dependency configurations, for example `implementation`, `api`, `testImplementation`, `kapt` and `ksp`.
  `platform(...)` and `enforcedPlatform(...)` are supported.
- Plugins in `plugins {}` blocks: `id("x") version "1.0"`, `id 'x' version '1.0'` and `kotlin("jvm") version "1.0"`.
- The default catalog `gradle/libs.versions.toml`, and local TOML catalogs that the root settings file registers with a literal path.
  Libraries and plugins are both checked. A `version.ref` version is shared by all its users.

**Needs review:** dynamic versions, versions from variables or templates, map notation, classifiers, custom configurations, dependency substitutions and published catalogs.
A shared catalog version changes only when every library and plugin that uses it agrees on the same new version.

Gradle reads the versions through the build's own repositories, including credentials and content filters.
Plugin versions come from the `pluginManagement` repositories.
The check runs a short Gradle task. It configures the build, but it does not download library JARs or run build tasks.
Gradle offline mode stops remote checks.

Save the build files before a check. After an update, sync the Gradle project.

If a catalog has a TOML syntax error, the plugin cannot check that catalog.
A hint shows the error position. Correct the file, save it and check again.

Hints and ignore rules name plugins as `plugin:<id>`, for example `gradle plugin:org.jetbrains.kotlin.jvm = 2.1.0`.

## npm, pnpm, Yarn and Bun

Configure a local Node.js interpreter and the package manager in IDEA's JavaScript runtime settings.

The plugin selects the package manager from `packageManager`, `devEngines.packageManager` or the lockfile.
It reads versions with the package manager's own read-only command: `npm view`, `pnpm view`, `yarn info`, `yarn npm info` or `bun info`.
The package manager applies `.npmrc`, scoped registries and authentication.

**Checked:** `dependencies`, `devDependencies` and `optionalDependencies`, including scoped packages and aliases.
Exact versions and `^` or `~` selectors keep their operator. For example, `^1.2.3` becomes `^1.2.9`.

**Workspaces:** npm, Yarn and Bun use the `workspaces` field. pnpm uses `pnpm-workspace.yaml`.
In a workspace, **Alt+Enter** offers **Update locally to …** and **Update … across workspace to …**.

**Needs review:** complex ranges, tags, Git and file selectors, peer dependencies, and dependencies that a root override affects.
pnpm catalogs, resolution overrides and custom pnpm hooks also need review.

Registry deprecation notices show on the declared version.
Each update mode skips deprecated versions.

The plugin edits `package.json` only. For npm, use **Update npm lockfile…** in the notification.
For pnpm, Yarn and Bun, run the package manager yourself.

## Maven and Gradle wrappers

**Maven wrapper.** The plugin checks the `distributionUrl` in `.mvn/wrapper/maven-wrapper.properties`.
It reads the `org.apache.maven:apache-maven` versions through the project's Maven repositories and mirrors.
The update changes the version in the URL and keeps the repository host.

**Gradle wrapper.** The plugin checks the `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties`.
It reads the release list from `services.gradle.org`, through the IDE proxy settings.
Only the official `https://services.gradle.org/distributions/` URL is checked. A mirror URL shows a manual-review hint.
The update changes the properties file only.
To also update the wrapper scripts and JAR, run `./gradlew wrapper --gradle-version <version>`.

A wrapper with `distributionSha256Sum` needs review, because the checksum must change with the version.

## Troubleshooting

| Problem | Action |
| --- | --- |
| No hints show | Make sure the project is imported or linked, and **Enable version checks** is on. Look at **Versions** in the status bar. |
| The status bar shows **Versions: offline** | Turn off offline mode in the Maven or Gradle settings. |
| The status bar shows **Versions: save build files** | Save the build files. The check then starts. |
| A check failed | Click **Show Details** in the notification. Click **Retry Failed Checks** to check only the failed files again. The details are also in `idea.log`. |
| A quick fix did nothing | The file or the results changed. Click **Refresh This File** in the notification, then try again. |
| A new release does not show | Look at **Minimum release age** and **Ignored versions**. Then select **Check This File for Updates**. |
| The npm, pnpm, Yarn or Bun check fails | Look at the Node.js interpreter and the package manager in the JavaScript runtime settings. |

The plugin can load and unload without an IDE restart.
Unloading stops running checks and closes open review windows.
