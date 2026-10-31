# Development

Use Java 21 or later. The Gradle wrapper uses a Java 21 toolchain.
You can select Java and Maven through SDKMAN.

## Build and run

```bash
./gradlew runIde                                   # Start a sandbox IDE with the plugin.
./gradlew test buildPlugin verifyPluginProjectConfiguration
./gradlew verifyPlugin                             # Check binary compatibility with the target IDEs.
```

`buildPlugin` writes the ZIP to `build/distributions/`.
To install it, open **Settings → Plugins → gear → Install Plugin from Disk**.

Compatibility checks target IDEA 2026.1.4 and 2026.2.3.
Before a release, also run the [packaged plugin acceptance](ide-acceptance.md) on the exact ZIP.

## Try the demos

The demo projects contain outdated dependencies on purpose.
Copy a demo to the Git-ignored `examples/` directory, then open the copy as a separate project.
The copy keeps your updates out of the committed test fixtures.

```bash
mkdir -p examples
cp -R src/test/resources/maven-demo examples/maven-demo
```

| Demo | Contents |
| --- | --- |
| [Maven](../src/test/resources/maven-demo/README.md) | Dependencies, build plugins, local and shared properties, managed versions and nested modules. |
| [npm](../src/test/resources/npm-demo/README.md) | A small npm workspace. |
| [Gradle](../src/test/resources/gradle-demo/README.md) | Groovy and Kotlin scripts, a shared catalog, a BOM and nested projects. |
| `src/test/resources/node-managers-demo/` | pnpm, Yarn and Bun projects. |

## Tests

`./gradlew test` runs the unit and IntelliJ platform tests.
The test task also builds the plugin ZIP. Lifecycle tests load that ZIP and unload and reload it several times.

The native integration tests use real build tools and local test repositories. Enable them with flags:

| Flag | Test class | Needs |
| --- | --- | --- |
| `-PmavenIntegration=true` | `MavenSettingsIntegrationTest`, and native cases in `MavenInheritedVersionFixTest` and `MavenOverrideInspectionTest` | IDEA's bundled Maven. Can download the Versions plugin from Maven Central. |
| `-PgradleIntegration=true` | `GradleRepositoryIntegrationTest` | The project's Gradle wrapper. |
| `-PnpmIntegration=true` | `NpmRegistryIntegrationTest` | A local Node.js and npm. |
| `-PnodeIntegration=true` | `NodeRegistryIntegrationTest` | `VERSIONCHECKER_PNPM_PATH`, `VERSIONCHECKER_YARN_CLASSIC_PATH`, `VERSIONCHECKER_YARN_MODERN_PATH`, `VERSIONCHECKER_BUN_PATH` and `VERSIONCHECKER_PNPM_LEGACY_PATH`. |

```bash
./gradlew test -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true
./gradlew test -PmavenIntegration=true --tests '*MavenSettingsIntegrationTest'
```

The integration tests do not download library JARs or npm tarballs, and they do not change lockfiles.

Maven 4 tests run on IDEA 2026.2.3. Give the path to an extracted Maven 4 distribution:

```bash
./gradlew testMaven4 -Pmaven4Home=/path/to/apache-maven-4.0.0-rc-7
```

To run the full Maven latency benchmark (100 coordinates at 50 and 250 ms), add `-PmavenLatencyBenchmark=true`.
Without the flag, the test checks 32 coordinates at 50 ms.

## CI

The Build workflow runs these jobs at the same time: Build, Test, Maven integration, Maven 4 integration and Verify plugin.
The release draft waits for all of them.
Each test job uploads its reports as a workflow artifact.

## Writing

Follow the [writing rules](writing.md) for documentation, comments and user messages.
