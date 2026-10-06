# Gradle demo

From the repository root, create a local copy and add the repository's wrapper:

```bash
cp -R src/test/resources/gradle-demo examples/gradle-demo
cp gradlew gradlew.bat examples/gradle-demo/
cp -R gradle/wrapper examples/gradle-demo/gradle/
```

If a local demo already exists, use it instead of copying over your edits. Open the copied `settings.gradle.kts` as a Gradle project in IntelliJ and import it using Java 21 or later.

The root has literal Kotlin dependencies, `library` has Groovy catalog references, and `nested:app` has a literal BOM plus a catalog consumer. The shared Jackson catalog version should produce one edit only when both libraries have the same update target. Use Alt+Enter on the version in the catalog to update it; references in a build file are not separate editable versions.

Try Tools → Check Versions and Tools → Update Versions in all three modes, for the current file and whole project. Reload Gradle after editing versions. Catalog changes affect all consumers; Current File bulk updates do not change other build files. Maven settings.xml does not configure these lookups: Gradle uses the repositories in this fixture's settings script.
