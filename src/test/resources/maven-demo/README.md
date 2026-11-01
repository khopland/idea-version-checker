# Maven demo

A Maven reactor with old dependency and plugin versions: the root, two modules, a nested aggregator and its child. Needs Java 21 and Maven.

1. Copy it from the repository root: `mkdir -p examples && cp -R src/test/resources/maven-demo examples/maven-demo`.
2. Start the sandbox IDE with `./gradlew runIde`, open `examples/maven-demo/pom.xml` as a project and let Maven import it.
3. Open a POM. Warnings show on outdated versions after a short check.

| POM | Try |
| --- | --- |
| `literal-dependencies/pom.xml` | Guava `32.1.1-jre` and SLF4J `1.7.30`. **Alt+Enter** offers patch, minor and major updates. Guava stays a `-jre` version. |
| `property-dependencies/pom.xml` | Commons Lang through `${commons-lang3.version}`: the fix edits the property. Jackson Core and Databind share `2.15.2`: a fix shows only for the modes where both want the same version. |
| Root `pom.xml` | Managed Commons IO and JUnit, and plugins in `pluginManagement`. |
| `nested/managed-dependencies/pom.xml` | Versions inherited through two parents. Add `<version>` equal to the managed version to see **Redundant Maven override**. |

Then try **Tools → Versions → Update All Files → Minor**, and **Edit → Undo** to revert it in one step.
Set **Settings → Tools → Version Checker → Minimum release age** to see recent releases disappear.
