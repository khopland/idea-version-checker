# Vulnerabilities

The **Vulnerable dependency version** inspection reports declared versions with known vulnerabilities from [OSV.dev](https://osv.dev), which includes the GitHub Advisory Database.

> CVE-2021-44228 (critical): Remote code injection in Log4j. Fixed in 2.15.0

It is off by default. Turn on **Check for known vulnerabilities** in **Settings → Tools → Version Checker**.

## What is sent

The check sends the names and versions of the declared Maven, Gradle and npm dependencies to `api.osv.dev`: those in open build files, and in other build files that share a version with them, such as a parent POM property. The **Dependency Updates** tool window sends those of every build file in the project.
In Maven, npm, pnpm, Yarn and Bun projects it also sends the packages they bring in, as resolved by the last Maven import or the lockfile, which can include internal packages from private repositories. Modules of the Maven project are not sent.
Credentials, file names and repository URLs are not sent. A project set up for [Nexus IQ](#nexus-iq) sends them to its Nexus IQ server instead, and nothing to osv.dev. Nothing is sent while the setting is off.

## Declared dependencies

- An npm dependency in `dependencies`, `devDependencies` or `optionalDependencies` is checked at the version the lockfile installs, so `^1.2.3` with 1.4.0 installed is checked as 1.4.0. Without a lockfile, or when the lockfile is older than the declared version, the declared version is checked, which for `^1.2.3` is 1.2.3. Catalog entries are checked at the declared version.
- Several advisories show as one warning with their IDs and the highest severity. The ID is the CVE when there is one, otherwise the GitHub (`GHSA-…`) or OSV ID.
- The severity is GitHub's rating, or else computed from the advisory's CVSS 4.0 or 3.x vector, the newest one first.
- Critical and high severity show as errors, unless you set another level for the inspection. The error also offers the patch, minor and major updates, because it hides the outdated warning, its deprecation note and a moved-dependency notice on the same version.
- **Alt+Enter** offers **Update to … (fixes N vulnerabilities)**: the lowest stable version above the checked one, with the same variant, that no reported advisory affects. Like the other fixes, it changes a shared Maven property or catalog `version.ref`, and only to a version that every user of it has and that fixes all of them. The patch, minor and major fixes say `still vulnerable` when the new version is affected too.
- `<!--suppress VulnerableDependency -->`, `//noinspection VulnerableDependency` or `#noinspection VulnerableDependency` keeps a version, as for outdated versions.
- Results stay in memory for 30 minutes. **Check Now** asks again. Advisory details stay on disk until OSV.dev reports a change, or for 30 days unused. An advisory that could not be read shows by its ID, without a fix, and is asked for again after a minute.

## In the Dependency Updates tool window

While the setting is on, the tool window also lists every vulnerable version in the project, the worst first, with two more columns: **Vulnerabilities**, the highest severity and the number of advisories, and **Safe**, the version the fix writes.
**Update → Safe Versions** writes the safe version of the selected rows, or of all rows, in one undoable edit. Versions that share a Maven property or a catalog `version.ref` get the highest safe version any of them needs.
A version kept by a suppress comment for outdated versions still shows when it is vulnerable. Use `VulnerableDependency` to keep it out.
Transitive dependencies are not listed.

## Transitive dependencies

The **Vulnerable transitive dependency** inspection warns on a declared dependency that brings in a vulnerable package, with the shortest path to it:

> Brings in com.fasterxml.jackson.core:jackson-databind 2.9.0 via org.example:json: CVE-2019-12384 (high): Polymorphic typing. Fixed in 2.9.9.1

The warning is on the artifact or package name, so an error on the dependency's own version does not hide it. It stays a warning at the default level, since the version is not set in the file and the code may never reach the package. A package the file declares, or a POM manages, is checked by **Vulnerable dependency version** instead, and its own dependencies show under it.

### Maven

The dependency tree of the last Maven import, so reload the project after changing dependencies. Dependencies inherited from a parent POM show in the parent. **Alt+Enter** offers **Pin … to … in dependency management**: the lowest stable version above the resolved one that no reported advisory affects, added to `<dependencyManagement>` in this POM. It is not offered for an artifact with a classifier or a type other than `jar`, which a plain entry does not match. `<!--suppress VulnerableTransitiveDependency -->` above the `<artifactId>` keeps a dependency, and **Alt+Enter → Suppress for tag** puts it there.

### npm, pnpm, Yarn and Bun

The packages the lockfile installs, so install after changing dependencies. Open `package.json` files are checked again when the lockfile changes. A workspace package uses the lockfile of the workspace root.

| Lockfile | Read |
| --- | --- |
| `package-lock.json` | Lockfile version 2 or 3, from npm 7 and later. |
| `pnpm-lock.yaml` | Version 6 or 9, from pnpm 8 and later. |
| `yarn.lock` | Yarn 1, and Yarn 2 and later. |
| `bun.lock` | The text lockfile of Bun 1.2 and later. The binary `bun.lockb` is not read. |

With several lockfiles in one folder, `packageManager` in `package.json` says which one is read, or else the newest one.
Yarn 1 does not list its workspaces, so every `package.json` below a Yarn 1 lockfile uses it, and a dependency is found by the exact range `package.json` declares.
Git, file, link, tarball and patched dependencies have no registry version and are not checked.

**Alt+Enter** offers **Override …** for npm and pnpm, not for Yarn or Bun: an override in the root of the workspace, scoped to the vulnerable version's major, such as `"qs@^6": "^6.2.4"`, so copies of other majors keep their version. The value is a range from the safe version, so newer copies in the range keep their version. When copies of one package need different safe versions, the override offers the highest.

The fix is not offered when:

- the safe version has another major,
- the version is `0.0.x`, where each patch is its own range,
- the package is already overridden in the file the fix would write to,
- the root `package.json` depends on the package directly, which npm refuses.

Where the override goes:

| Package manager | File | Why |
| --- | --- | --- |
| npm | `overrides` in `package.json` | |
| pnpm 11 and later | `overrides` in `pnpm-workspace.yaml` | It reads only that file. |
| pnpm before 10.5.1 | `pnpm.overrides` in `package.json` | The only place it reads. |
| pnpm 10.5.1 to 10.x, or an unknown version | Where the project already keeps overrides, otherwise `package.json` | pnpm 10 ignores the workspace file's overrides while `package.json` has some, and a workspace file with only overrides makes pnpm 9 fail to install. |

The pnpm version comes from `packageManager`, or else from the last install in `node_modules`.
`package.json` cannot have comments, so turn the inspection off instead of suppressing it.

## Nexus IQ

With a [Nexus IQ server](https://help.sonatype.com/en/iq-server.html) (Sonatype Lifecycle), the check can follow your organization's policies and waivers instead of OSV.dev, so the editor shows only the vulnerable dependencies that still need attention.
Open **Settings → Tools → Version Checker → Nexus IQ** and enter the server URL and your user name and password or user token. **Test Connection** checks them.
The server is stored in `.idea/version-checker.xml`, so you can share it with the team. The user name and password stay in the IDE password safe.

Each build file finds its application, the first of:

1. `clm.applicationId` in the POM or a parent POM, or `<applicationId>` in the configuration of `clm-maven-plugin`, as the [Sonatype CLM for Maven](https://help.sonatype.com/en/sonatype-clm-for-maven.html) plugin reads them.
2. The **Application ID** in the settings, for the whole project.
3. The first of these that Nexus IQ has an application for: the artifact ID of the POM and of its parents in the project, or the `name` of the `package.json` and those above it in the project, with and without the `@scope/`.

Without one, the file shows which names were tried.

- The declared dependencies, and the [transitive](#transitive-dependencies) packages they bring in, are evaluated against the policies of the application, through the component evaluation API. **Check for known vulnerabilities** must be on.
- A vulnerability shows when a policy violation names it and the violation is neither waived nor legacy. Vulnerabilities that no policy flags, and those Nexus IQ marks as not applicable, are left out.
- The message names the policy, as in `CVE-2021-44228 (critical): Security-Critical policy. Fixed in 2.17.1`. The fix is the lowest version from or above the one Nexus IQ suggests without policy violations.
- **Alt+Enter → Open … in Nexus IQ to waive** opens the application's newest scan report in the browser, where you can waive the violation. The report shows the last scan of the application, so a dependency changed since then may not be in it yet.
- Results stay in memory for 30 minutes, as for OSV.dev. **Check Now** asks again, for example after you waive a violation.
- To see what Nexus IQ answers, add `#io.github.khopland.versionchecker.vuln.NexusIqSource` in **Help → Diagnostic Tools → Debug Log Settings** and look in `idea.log`. Credentials are not logged.

## Limits

Only declared dependencies are checked otherwise, including npm, Bun and pnpm catalog entries. Transitive dependencies of Gradle projects, npm `overrides` and `resolutions`, `packageManager`, Gradle plugins, the wrappers, toolchain files and GitHub Actions are not, so this does not replace a full scan of the resolved dependencies, such as `npm audit`, OWASP Dependency-Check or a Nexus IQ or Snyk scan.
IntelliJ IDEA Ultimate's built-in **Package Checker** plugin may report the same vulnerabilities; turn one of them off if you see both.
