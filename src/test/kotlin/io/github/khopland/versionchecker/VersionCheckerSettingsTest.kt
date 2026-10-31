package io.github.khopland.versionchecker

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import com.intellij.util.xmlb.XmlSerializer
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JSpinner

class VersionCheckerSettingsTest : BasePlatformTestCase() {
    fun testSettingsPageAppliesAndResetsIndependentSeverityChoices() {
        val configurable = VersionCheckerConfigurable(project)
        try {
            val panel = configurable.createComponent()
            fun components(container: Container): List<java.awt.Component> = container.components.flatMap {
                listOf(it) + if (it is Container) components(it) else emptyList()
            }
            val selectors = components(panel as Container).filterIsInstance<JComboBox<*>>()
            assertEquals(5, selectors.size)
            assertFalse(configurable.isModified())
            val releaseAge = components(panel).filterIsInstance<JSpinner>().single { it.name == "versionChecker.minimumReleaseAge" }
            assertEquals(0, releaseAge.value)
            releaseAge.value = 48
            selectors[0].selectedItem = VersionSeverity.DISABLED
            selectors[2].selectedItem = VersionSeverity.ERROR
            components(panel).filterIsInstance<JBTextArea>().single { it.accessibleContext.accessibleName == "Deprecated artifacts" }.text = "old:library = Use new:library"
            components(panel).filterIsInstance<JBTextArea>().single { it.accessibleContext.accessibleName == "Ignored published versions" }.text = "npm alpha = 1.2.3"
            assertTrue(configurable.isModified())
            configurable.apply()
            val state = project.service<VersionCheckerSettings>().state
            assertEquals(VersionSeverity.DISABLED, state.patchSeverity)
            assertEquals(VersionSeverity.WARNING, state.minorSeverity)
            assertEquals(VersionSeverity.ERROR, state.majorSeverity)
            assertEquals("old:library = Use new:library", state.deprecatedDependencies)
            assertEquals("npm alpha = 1.2.3", state.ignoredVersions)
            assertEquals(48, state.minimumReleaseAgeHours)
            assertFalse(configurable.isModified())
            selectors[2].selectedItem = VersionSeverity.INFORMATION
            configurable.reset()
            assertEquals(VersionSeverity.ERROR, selectors[2].selectedItem)
            assertFalse(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
            project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
        }
    }

    fun testSeverityPreferencesSurviveSerialization() {
        val state = VersionCheckerSettings.Options(patchSeverity = VersionSeverity.DISABLED,
            minorSeverity = VersionSeverity.INFORMATION, majorSeverity = VersionSeverity.ERROR,
            deprecatedSeverity = VersionSeverity.WARNING, deprecatedDependencies = "old:library = Retired",
            ignoredVersions = "maven g:a = 2.0",
            minimumReleaseAgeHours = 72)
        assertEquals(state, XmlSerializer.deserialize(XmlSerializer.serialize(state), VersionCheckerSettings.Options::class.java))
    }

    fun testMinimumReleaseAgeKeepsItsOriginalStoredName() {
        val element = XmlSerializer.serialize(VersionCheckerSettings.Options(minimumReleaseAgeHours = 24))
        assertTrue(com.intellij.openapi.util.JDOMUtil.write(element).contains("name=\"mavenMinimumReleaseAgeHours\" value=\"24\""))
        val stored = com.intellij.openapi.util.JDOMUtil.load(
            "<Options><option name=\"mavenMinimumReleaseAgeHours\" value=\"36\" /></Options>")
        assertEquals(36, XmlSerializer.deserialize(stored, VersionCheckerSettings.Options::class.java).minimumReleaseAgeHours)
    }
}
