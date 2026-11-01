# Gradle demo

Kotlin and Groovy build scripts, a version catalog and a BOM, with old versions.

```bash
mkdir -p examples
cp -R src/test/resources/gradle-demo examples/gradle-demo
cp gradlew gradlew.bat examples/gradle-demo/ && cp -R gradle/wrapper examples/gradle-demo/gradle/
```

Open `examples/gradle-demo` in the sandbox IDE from `./gradlew runIde`.
Warnings show on literal versions in the scripts and in `gradle/libs.versions.toml`.
In the catalog, a `version.ref` shows the warning on the reference, and the fix changes the `[versions]` entry. The shared Jackson version changes only for modes where both libraries agree.
