# Maven Version Checker demo

A standalone Maven project with intentionally old dependency and build-plugin versions. Requires Java 21 or newer and Maven. The reactor has five projects: the root, two direct modules, a nested aggregator and its child module.

## Open in IntelliJ IDEA

1. Install the plugin ZIP from `../../build/distributions/` using **Settings → Plugins → gear → Install Plugin from Disk**, then restart if prompted. Alternatively, open this project in the development IDE launched with `./gradlew runIde` from the plugin repository.
2. Open this directory's **root `pom.xml` as a project**. Reload all Maven projects and wait for dependency import to finish. Use Java 21 or newer as the project SDK and Maven runner JDK.
3. Open a POM and choose **Tools → Check Maven Versions → Current POM**, or **Whole Project** to check all imported modules. Keep Maven online. Checks run in the background, so messages can take a little time to appear on the first run.

## Things to try

| POM | Scenario | Expected behavior |
| --- | --- | --- |
| `literal-dependencies/pom.xml` | Guava `32.1.1-jre` and SLF4J `1.7.30` | Standard IDEA inspection warnings on literal versions; Alt+Enter updates the version. |
| `property-dependencies/pom.xml` | Commons Lang `3.12.0` through a local property | Alt+Enter changes the local property rather than replacing `${commons-lang3.version}`. |
| `property-dependencies/pom.xml` | Jackson Core and Databind share `2.15.2` | Bulk updates group the property edit when both dependencies agree on a target. Conflicting targets are skipped and explained in the preview. |
| Root `pom.xml` | Managed Commons IO `2.11.0` and JUnit `4.12` | Managed versions can be updated where they are declared. |
| Root `pom.xml` | Compiler plugin `3.12.0` and Surefire plugin `3.2.1` in `pluginManagement` | Warnings on plugin versions; Alt+Enter updates the compiler literal or the local `maven-surefire.version` property. |
| `nested/managed-dependencies/pom.xml` | Dependencies inherit versions through two parent levels | Warnings appear on the dependency declarations. Edit the version in the root POM; the child has no local version quick fix. |

Hover over a highlighted dependency to read its standard IDEA inspection message, or use the Problems view. These messages are also available to the InlineProblems plugin. The plugin's settings are under **Settings → Tools → Maven Version Checker**. Try changing major updates to Error or disabling patch messages; bulk updates remain available.

To test a red deprecation notice, add `org.slf4j:slf4j-api = Demo policy: migrate to a maintained version` to the explicitly deprecated dependencies field. This is a demo policy you supply, not a claim that SLF4J itself is deprecated. The dependency then displays a deprecation notice and is skipped for manual review during bulk updates. Remove the rule to resume the normal update demo.

Choose **Tools → Update Maven Dependencies**, then **Current POM** or **Whole Project**, and inspect the preview before applying:

- **Patch Only** keeps the current major and minor numbers. Guava stays within `32.1.x`; SLF4J stays within `1.7.x`; Jackson stays within `2.15.x`.
- **Minor + Patch** keeps the major number. Guava stays within `32.x`; SLF4J stays within `1.x`; Jackson stays within `2.x`.
- **Major + Minor + Patch** allows newer major versions too. Review these changes before applying them.

Try the same scopes and modes under **Tools → Update Maven Build Plugins**. With the root POM open, **Current POM → Patch Only** can update Compiler to `3.12.1` and Surefire to `3.2.5`. These versions are declared in the root's `pluginManagement`; child POMs inherit them. Minor and major modes can offer newer plugin versions compatible with the project's Maven runtime.

Targets depend on the versions your configured repository currently provides. There may be no candidate for a particular artifact in a restricted mode. Current POM edits only the POM in the editor; Whole Project includes all imported modules, including the nested child. A single editor Undo restores an applied bulk update. Reload Maven after edits and refresh the checks.

## Build and check repository settings

From this directory:

```sh
mvn test
```

That uses your existing Maven settings. To try the supplied public repository mirror without changing `~/.m2/settings.xml`:

```sh
mvn -s settings-demo.xml test
```

In IDEA, select this `settings-demo.xml` under **Settings → Build, Execution, Deployment → Build Tools → Maven → User settings file**, reload Maven and refresh version checks. The example mirrors repositories to Maven Central; you can substitute a repository you use. It does not modify your global settings.

The dependency goal used by the plugin can also be run manually:

```sh
mvn -s settings-demo.xml org.codehaus.mojo:versions-maven-plugin:2.21.0:display-dependency-updates \
  -DprocessDependencyManagement=true -DallowSnapshots=false \
  '-Dmaven.version.ignore=(?i).*[.-](alpha|beta|milestone|rc|cr|ea|preview|snapshot|m)[.-]?[0-9]*([.-].*)?' \
  -DallowMajorUpdates=false -DallowMinorUpdates=false
```

This reports patch updates without editing POMs. Remove `-DallowMinorUpdates=false` for minor + patch, and remove both restrictions for major + minor + patch.

For an unrestricted build-plugin report, run:

```sh
mvn -s settings-demo.xml org.codehaus.mojo:versions-maven-plugin:2.21.0:display-plugin-updates \
  -DprocessUnboundPlugins=true -DallowSnapshots=false \
  '-Dmaven.version.ignore=(?i).*[.-](alpha|beta|milestone|rc|cr|ea|preview|snapshot|m)[.-]?[0-9]*([.-].*)?'
```

Unlike the dependency goal, this goal has no `allowMajorUpdates`/`allowMinorUpdates` controls. The checker applies version-branch filters when running restricted plugin update modes.

The old versions are intentional. If you want to repeat the demo, use Undo immediately after updates or restore the original versions listed above (the root JUnit version is `4.12`).
