package io.github.khopland.versionchecker

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import com.intellij.util.xmlb.XmlSerializer
import java.awt.Container
import javax.swing.JComboBox

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
            selectors[0].selectedItem = DependencySeverity.DISABLED
            selectors[2].selectedItem = DependencySeverity.ERROR
            components(panel).filterIsInstance<JBTextArea>().single().text = "old:library = Use new:library"
            assertTrue(configurable.isModified())
            configurable.apply()
            val state = project.service<VersionCheckerSettings>().state
            assertEquals(DependencySeverity.DISABLED, state.patchSeverity)
            assertEquals(DependencySeverity.WARNING, state.minorSeverity)
            assertEquals(DependencySeverity.ERROR, state.majorSeverity)
            assertEquals("old:library = Use new:library", state.deprecatedDependencies)
            assertFalse(configurable.isModified())
            selectors[2].selectedItem = DependencySeverity.INFORMATION
            configurable.reset()
            assertEquals(DependencySeverity.ERROR, selectors[2].selectedItem)
            assertFalse(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
            project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
        }
    }

    fun testSeverityPreferencesSurviveSerialization() {
        val state = VersionCheckerSettings.Options(patchSeverity = DependencySeverity.DISABLED,
            minorSeverity = DependencySeverity.INFORMATION, majorSeverity = DependencySeverity.ERROR,
            deprecatedSeverity = DependencySeverity.WARNING, deprecatedDependencies = "old:library = Retired")
        assertEquals(state, XmlSerializer.deserialize(XmlSerializer.serialize(state), VersionCheckerSettings.Options::class.java))
    }
}
