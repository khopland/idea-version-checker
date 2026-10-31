package io.github.khopland.versionchecker

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import com.intellij.util.xmlb.XmlSerializer
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JSpinner

class VersionCheckerSettingsTest : BasePlatformTestCase() {
    fun testSettingsPageAppliesAndResets() {
        val configurable = VersionCheckerConfigurable(project)
        try {
            val panel = configurable.createComponent()
            fun components(container: Container): List<java.awt.Component> = container.components.flatMap {
                listOf(it) + if (it is Container) components(it) else emptyList()
            }
            assertTrue(components(panel as Container).filterIsInstance<JComboBox<*>>().isEmpty())
            assertFalse(configurable.isModified())
            val releaseAge = components(panel).filterIsInstance<JSpinner>().single { it.name == "versionChecker.minimumReleaseAge" }
            assertEquals(0, releaseAge.value)
            releaseAge.value = 48
            val deprecated = components(panel).filterIsInstance<JBTextArea>().single { it.accessibleContext.accessibleName == "Deprecated artifacts" }
            deprecated.text = "old:library = Use new:library"
            components(panel).filterIsInstance<JBTextArea>().single { it.accessibleContext.accessibleName == "Ignored published versions" }.text = "npm alpha = 1.2.3"
            assertTrue(configurable.isModified())
            configurable.apply()
            val state = project.service<VersionCheckerSettings>().state
            assertEquals("old:library = Use new:library", state.deprecatedDependencies)
            assertEquals("npm alpha = 1.2.3", state.ignoredVersions)
            assertEquals(48, state.minimumReleaseAgeHours)
            assertFalse(configurable.isModified())
            releaseAge.value = 12
            deprecated.text = ""
            assertTrue(configurable.isModified())
            configurable.reset()
            assertEquals(48, releaseAge.value)
            assertEquals("old:library = Use new:library", deprecated.text)
            assertFalse(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
            project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
        }
    }

    fun testPreferencesSurviveSerialization() {
        val state = VersionCheckerSettings.Options(deprecatedDependencies = "old:library = Retired",
            ignoredVersions = "maven g:a = 2.0", minimumReleaseAgeHours = 72)
        assertEquals(state, XmlSerializer.deserialize(XmlSerializer.serialize(state), VersionCheckerSettings.Options::class.java))
    }

    fun testRemovedSeverityOptionsAreIgnoredWhenLoading() {
        val stored = com.intellij.openapi.util.JDOMUtil.load(
            "<Options><option name=\"patchSeverity\" value=\"ERROR\" /><option name=\"ignoredVersions\" value=\"npm a = 1\" /></Options>")
        assertEquals("npm a = 1", XmlSerializer.deserialize(stored, VersionCheckerSettings.Options::class.java).ignoredVersions)
    }

    fun testMinimumReleaseAgeKeepsItsOriginalStoredName() {
        val element = XmlSerializer.serialize(VersionCheckerSettings.Options(minimumReleaseAgeHours = 24))
        assertTrue(com.intellij.openapi.util.JDOMUtil.write(element).contains("name=\"mavenMinimumReleaseAgeHours\" value=\"24\""))
        val stored = com.intellij.openapi.util.JDOMUtil.load(
            "<Options><option name=\"mavenMinimumReleaseAgeHours\" value=\"36\" /></Options>")
        assertEquals(36, XmlSerializer.deserialize(stored, VersionCheckerSettings.Options::class.java).minimumReleaseAgeHours)
    }
}
