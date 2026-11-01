package io.github.khopland.versionchecker

import com.intellij.driver.sdk.getHighlights
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.waitFor
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Runs the plugin in the sandbox IDE against the npm and Gradle demos. Versions come from npmjs.org and Maven Central. */
class NpmAndGradleUiTest {
    @Test
    fun npmWarningsKeepTheOperator() = runWithDemo("npm-warnings", "npm-demo") {
        val app = openDocument("packages/app/package.json")
        waitForHighlight(app, "a lodash warning") { it.message().startsWith("lodash 4.17.19 → ") }
        val react = waitForHighlight(app, "a react warning") { it.message().startsWith("react 18.2.0 → ") }
        println("react: ${react.message()}")
        // Local packages, peer dependencies and ranges get no warning from the plugin. Other IDE warnings, such as
        // "package is not installed", are not the plugin's.
        val ours = getHighlights(app, singleProject()).map { it.message() }.filter { " → " in it || it.startsWith("Could not check") }
        assertTrue(ours.none { it.startsWith("@demo/library ") || it.startsWith("react-dom ") || it.startsWith("@types/node ") }, ours.toString())
        val minor = react.target("minor")!!
        applyIntention("^18.2.0", "Update to $minor (minor)")
        waitFor("react keeps its ^", 10.seconds) { "\"react\": \"^$minor\"" in app.getText() }
    }

    @Test
    fun anUnreachableNpmRegistryGivesOneWarningForTheFile() = runWithDemo("npm-unreachable", "npm-demo", prepare = { demo ->
        demo.resolve(".npmrc").toFile().writeText("registry=http://127.0.0.1:9/\n")
    }) {
        val app = openDocument("packages/app/package.json")
        val messages = waitFor("a file-level warning", 2.minutes, getter = { fileLevelMessages("Could not check") }, checker = { it.isNotEmpty() })
        println("File-level: $messages")
        assertTrue(Regex("""Could not check \d+ versions: 127\.0\.0\.1: could not connect""").matches(messages.single()), messages.toString())
        // A per-version warning would read "Could not check <package>: …".
        val perVersion = getHighlights(app, singleProject()).map { it.message() }
            .filter { it.startsWith("Could not check ") && !Regex("""Could not check \d+ versions: .*""").matches(it) }
        assertTrue(perVersion.isEmpty(), "No warning on each version: $perVersion")
    }

    @Test
    fun yarnrcChoosesTheRegistry() = runWithDemo("npm-yarnrc", "npm-demo", prepare = { demo ->
        demo.resolve(".yarnrc.yml").toFile().writeText("npmRegistryServer: \"http://127.0.0.1:9\"\n")
    }) {
        val app = openDocument("packages/app/package.json")
        val messages = waitFor("a file-level warning", 2.minutes, getter = { fileLevelMessages("Could not check") }, checker = { it.isNotEmpty() })
        println("File-level with .yarnrc.yml: $messages")
        assertTrue("127.0.0.1" in messages.single(), messages.toString())
    }

    @Test
    fun gradleCatalogWarningsSitOnTheReference() = runWithDemo("gradle-catalog", "gradle-demo", prepare = { demo ->
        // Without a settings script the IDE does not import the Gradle build, which is not needed here.
        demo.resolve("settings.gradle.kts").toFile().delete()
    }) {
        val catalog = openDocument("gradle/libs.versions.toml")
        val core = waitForHighlight(catalog, "a jackson-core warning") { it.message().startsWith("com.fasterxml.jackson.core:jackson-core 2.16.0 → ") }
        println("Catalog: ${core.getText()} => ${core.message()}")
        assertTrue(core.getText() == "jackson", "The warning is on the version.ref, not on the [versions] entry")
        val script = openDocument("build.gradle.kts")
        waitForHighlight(script, "a Guava warning") { it.message().startsWith("com.google.guava:guava 32.1.1-jre → ") }
    }
}
