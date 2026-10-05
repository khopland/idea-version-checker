# Extending beyond Maven

The current release implements Maven. Gradle and npm/pnpm/Bun support is feasible, but needs separate adapters for discovery, version comparison, repository configuration, and edits. The standard inspection approach, settings, update-mode selection, and preview flow can be reused.

See [the shared build-system API proposal](build-system-api.md) for the concrete adapter boundary, current-code mapping, update/lockfile contracts and migration sequence.

## Gradle

Use IDEA's linked Gradle projects and the project's Gradle wrapper. Execute a temporary inspection task/init script through Gradle's tooling integration so repository credentials, repository content filters, settings-level repositories, and init scripts are evaluated by Gradle. Maven `settings.xml` is not the repository configuration for Gradle builds.

Start with dependency versions in `gradle/libs.versions.toml` and literal coordinates in `build.gradle` / `build.gradle.kts`. Version catalogs centralize declarations, while Gradle resolution can select different versions because of platforms and constraints. Follow each result back to the declaration that actually controls it. Treat rich constraints, dynamic versions, shared properties, convention plugins, and externally published catalogs as separate cases.

An initial Gradle adapter should support linked subprojects and version catalogs, with private-repository integration tests. Extend shared-property and constrained-version editing after that.

Sources: [Gradle dependency management](https://docs.gradle.org/current/userguide/dependency_management_basics.html), [version catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html), [repository protocols and credentials](https://docs.gradle.org/current/userguide/supported_repository_protocols.html).

## npm and pnpm

Discover package roots and workspaces, and identify the package manager from `packageManager` and its lockfile. Use the selected manager's configuration to query versions, preserving project/user `.npmrc`, scope-specific registries, and registry-scoped credentials.

`npm outdated --json` and `pnpm outdated --format json` provide machine-readable checks; pnpm supports recursive workspace checks. Current, wanted/compatible, and latest versions have different meanings. For patch/minor selection, query the available version set and apply SemVer rules rather than treating a caret range as “minor only,” especially for versions below 1.0.

Update both the version declaration and the package manager's lockfile through that package manager. Preserve range operators, aliases, workspace dependencies, and pnpm catalogs. Check dependencies, devDependencies, and optionalDependencies first; peerDependencies need a distinct compatibility policy.

Sources: [npm outdated](https://docs.npmjs.com/cli/v11/commands/npm-outdated/), [npm registry selection](https://docs.npmjs.com/using-npm/registry.html/), [npmrc](https://docs.npmjs.com/cli/v8/configuring-npm/npmrc/), [pnpm outdated](https://pnpm.io/cli/outdated), [pnpm update](https://pnpm.io/cli/update).

## Bun

Bun shares the npm package ecosystem and `package.json`, but has its own lockfile and configuration precedence. Support both `.npmrc` and `bunfig.toml`, plus environment/CLI overrides, through Bun rather than assuming npm's configuration fully describes the project.

`bun outdated` distinguishes installed, compatible-update, and latest versions. Its documented output is a table, so validate a structured API/metadata query for the installed Bun version before relying on a stable parser. Apply selected versions with Bun and let Bun regenerate its lockfile. Account for workspace and catalog declarations.

Sources: [Bun outdated](https://bun.sh/docs/pm/cli/outdated), [Bun update](https://bun.sh/docs/pm/cli/update), [Bun npmrc support and configuration precedence](https://bun.sh/docs/pm/npmrc).

## Suggested order

1. Keep Maven's checked behavior and test suite as the reference implementation.
2. Separate dependency results and version-declaration editing from Maven XML classes; keep repository operations inside each tool's adapter.
3. Add Gradle version catalogs and literal dependencies, including subprojects.
4. Add npm/pnpm declarations and workspace checks with package-manager-controlled lockfile updates.
5. Add Bun using the same package metadata model, with its own configuration and lockfile operations.

These adapters are an extension plan, not functionality included in this release.
