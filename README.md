# Maven Version Checker

An IntelliJ IDEA plugin that marks Maven dependencies with newer stable versions as **errors in `pom.xml`**.

For example, a dependency using `junit:junit:4.12` gets this inspection message:

> Newer version of junit:junit is available: 4.12 → 4.13.2

Use **Alt+Enter → Update version to 4.13.2** to update a literal version. For a simple `${junit.version}` reference declared in the same POM, the quick fix updates the property instead. A shared property change affects every dependency using it.

## Install and use

Requires IntelliJ IDEA **2025.3 or later** with its bundled Java and Maven plugins enabled. The build targets 2025.3.6.1.

1. Build with `./gradlew buildPlugin` using Java 21 or later. Gradle uses a Java 21 toolchain.
2. In IDEA, open **Settings → Plugins → gear → Install Plugin from Disk** and select `build/distributions/version-checker-1.0.0-SNAPSHOT.zip`.
3. Open and import a Maven project. Checks start in the background when IDEA inspects an imported POM.
4. Use **Tools → Check Maven Dependency Versions** to save files and refresh checks for all imported modules.

The inspection appears under **Settings → Editor → Inspections → Maven → Newer Maven dependency version available**. Its default severity is Error; you can change or disable it there. These are editor inspection errors; the plugin does not change Maven's build result.

## Maven repositories and settings.xml

The plugin uses IDEA's existing Maven server rather than querying a public dependency search service. Maven handles the effective repositories, active settings profiles, mirrors, server credentials, encrypted credentials, proxies, and local repository using IDEA's Maven configuration.

Configure **Settings → Build, Execution, Deployment → Build Tools → Maven → User settings file** if you use a custom `settings.xml`. Otherwise Maven uses its default settings location. A mirror with `mirrorOf="*"` is applied by Maven, including for the checker's own Maven goal. No separate repository or credential configuration is needed in the plugin.

Checks execute the read-only `org.codehaus.mojo:versions-maven-plugin:2.21.0:display-dependency-updates` goal. Maven downloads this goal and its dependencies through the configured plugin repositories on first use. A private repository must allow or proxy these artifacts. Repository errors produce a notification and no dependency errors. IDEA's **Work offline** setting disables remote checks.

Results are cached for ten minutes and invalidated by POM saves, Maven model changes, settings changes in IDEA, or the refresh action. After changing `settings.xml` externally, reload the Maven project or use the refresh action. Checks inspect saved POM contents; version results only apply when the current version still matches.

## First version scope

- Maven dependencies and dependency management, including BOM version declarations, in imported POMs and active profiles.
- Stable versions, including major upgrades, using Maven's version ordering. Common prerelease qualifiers and snapshots are excluded.
- Literal versions and properties. Simple local properties have a quick fix; inherited, composite, and externally managed versions must be edited at their source.
- Each module is checked in its own Maven repository context. Reactor-only dependencies have no remote upgrade result unless the artifact is also published.

Gradle, build plugins, transitive-only dependencies, snapshot dependencies, version ranges, and `LATEST`/`RELEASE` declarations are outside this release's scope. Remote Maven environments such as WSL/SSH are not supported by the report-file transport in this version.

## Development and verification

Java and Maven can be selected through SDKMAN. The Gradle wrapper builds the plugin; Maven is only needed separately if you want to reproduce its goal from a terminal.

```bash
./gradlew runIde
./gradlew test buildPlugin verifyPluginProjectConfiguration
./gradlew verifyPlugin
```

Parser and IntelliJ platform tests cover stable-version filtering, wrapped reports, stale results, dependency selection, and POM quick fixes.

The optional integration test starts IDEA's real Maven server and an authenticated local Maven repository. Its custom `settings.xml` supplies an active repository profile, a mirror, and server credentials. The test verifies the stable update is retrieved through that mirror. The Versions goal may be downloaded from Maven Central during this test:

```bash
./gradlew test -PmavenIntegration=true --tests '*MavenSettingsIntegrationTest'
```
