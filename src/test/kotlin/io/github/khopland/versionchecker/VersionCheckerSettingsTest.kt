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
            selectors[0].selectedItem = VersionSeverity.DISABLED
            selectors[2].selectedItem = VersionSeverity.ERROR
            components(panel).filterIsInstance<JBTextArea>().single().text = "old:library = Use new:library"
            assertTrue(configurable.isModified())
            configurable.apply()
            val state = project.service<VersionCheckerSettings>().state
            assertEquals(VersionSeverity.DISABLED, state.patchSeverity)
            assertEquals(VersionSeverity.WARNING, state.minorSeverity)
            assertEquals(VersionSeverity.ERROR, state.majorSeverity)
            assertEquals("old:library = Use new:library", state.deprecatedDependencies)
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
            deprecatedSeverity = VersionSeverity.WARNING, deprecatedDependencies = "old:library = Retired")
        assertEquals(state, XmlSerializer.deserialize(XmlSerializer.serialize(state), VersionCheckerSettings.Options::class.java))
    }
}
