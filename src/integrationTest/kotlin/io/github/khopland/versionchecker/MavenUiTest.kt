package io.github.khopland.versionchecker

import com.intellij.driver.sdk.getNotifications
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Runs the plugin in the sandbox IDE against the Maven demo. Versions come from Maven Central. */
class MavenUiTest {
    @Test
    fun warningsShowOnOutdatedVersions() = runWithDemo("maven-warnings", "maven-demo") {
        assertTrue(isPluginLoaded("io.github.khopland.version-checker"), "The plugin is loaded")
        val pom = openDocument("literal-dependencies/pom.xml")
        val guava = waitForHighlight(pom, "a Guava warning") { it.message().startsWith("com.google.guava:guava 32.1.1-jre → ") }
        println("Guava: ${guava.message()}")
        assertTrue(guava.message().substringAfter(" → ").split(" · ").all { it.substringBefore(" ").endsWith("-jre") }, guava.message())
        val slf4j = waitForHighlight(pom, "an SLF4J warning") { it.message().startsWith("org.slf4j:slf4j-api 1.7.30 → ") }
        println("SLF4J: ${slf4j.message()}")
        assertTrue("(patch)" in slf4j.message() && "(major)" in slf4j.message(), slf4j.message())
    }

    @Test
    fun altEnterUpdatesALiteralAndAProperty() = runWithDemo("maven-alt-enter", "maven-demo") {
        val literal = openDocument("literal-dependencies/pom.xml")
        val slf4j = waitForHighlight(literal, "an SLF4J warning") { it.message().startsWith("org.slf4j:slf4j-api 1.7.30") }
        val patch = slf4j.target("patch")!!
        val offered = intentionsAt("1.7.30")
        println("Alt+Enter on SLF4J: $offered")
        assertTrue("Update to $patch (patch)" in offered && "Update all versions in this file…" in offered, offered.toString())
        applyIntention("1.7.30", "Update to $patch (patch)")
        waitFor("SLF4J is updated", 10.seconds) { "<version>$patch</version>" in literal.getText() }

        val properties = openDocument("property-dependencies/pom.xml")
        val jackson = waitForHighlight(properties, "a Jackson warning") { it.message().startsWith("com.fasterxml.jackson.core:jackson-core 2.15.2") }
        val minor = jackson.target("minor")!!
        applyIntention("{jackson.version}</version>", "Update jackson.version to $minor (minor)")
        // The fix edits the property, so both Jackson dependencies change.
        waitFor("jackson.version is updated", 10.seconds) { "<jackson.version>$minor</jackson.version>" in properties.getText() }
        assertEquals(2, Regex("""\$\{jackson\.version}""").findAll(properties.getText()).count())
    }

    @Test
    fun updateAllFilesChangesEveryBuildFile() = runWithDemo("maven-update-all", "maven-demo") {
        val root = openDocument("pom.xml")
        waitForHighlight(root, "a JUnit warning") { it.message().startsWith("junit:junit 4.12") }
        val literal = openDocument("literal-dependencies/pom.xml")
        waitForHighlight(literal, "a Guava warning") { it.message().startsWith("com.google.guava:guava") }
        val before = root.getText() to literal.getText()

        invokeAction("VersionChecker.UpdateAll.Minor")
        val notification = waitFor("the update notification", 2.minutes, getter = {
            getNotifications(singleProject()).firstOrNull { it.getTitle().startsWith("Updated ") }
        }, checker = { it != null })!!
        println("Notification: ${notification.getTitle()} — ${notification.getContent()}")
        assertTrue(root.getText() != before.first && literal.getText() != before.second, "Both POMs changed")
        assertTrue("<version>4.12</version>" !in root.getText(), "JUnit moved to a newer 4.x")
        assertTrue("32.1.1-jre" !in literal.getText() && "-jre" in literal.getText(), "Guava stayed on -jre")
    }

    @Test
    fun redundantOverrideIsReported() = runWithDemo("maven-redundant", "maven-demo", prepare = { demo ->
        // The root manages JUnit 4.12. Repeat that version, and add a property nothing uses.
        val child = demo.resolve("nested/managed-dependencies/pom.xml")
        val text = child.toFile().readText()
        child.toFile().writeText(text
            .replace("<artifactId>junit</artifactId>", "<artifactId>junit</artifactId>\n            <version>4.12</version>")
            .replace("<artifactId>managed-dependencies</artifactId>",
                "<artifactId>managed-dependencies</artifactId>\n    <properties><unused.version>1.0</unused.version></properties>"))
    }) {
        val child = openDocument("nested/managed-dependencies/pom.xml")
        waitForHighlight(child, "the managed-version warning") { it.message() == "Version 4.12 is already managed" }
        waitForHighlight(child, "the unused-property warning") { it.message() == "Property unused.version is not used" }
    }
}
