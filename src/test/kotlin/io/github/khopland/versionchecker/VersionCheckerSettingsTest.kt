package io.github.khopland.versionchecker

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import com.intellij.util.xmlb.XmlSerializer
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JSpinner
import com.intellij.ui.components.JBCheckBox

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
            val schedule = components(panel).filterIsInstance<JBCheckBox>().single { "periodically" in it.text }
            val spinners = components(panel).filterIsInstance<JSpinner>()
            val interval = spinners.first()
            val mavenWorkers = spinners.last()
            val platformFirst = components(panel).filterIsInstance<JBCheckBox>().single { "BOMs" in it.text }
            val fastMaven = components(panel).filterIsInstance<JBCheckBox>().single { "faster Maven" in it.text }
            assertFalse(fastMaven.isSelected)
            assertFalse(platformFirst.isSelected)
            assertEquals(2, mavenWorkers.value)
            assertFalse(schedule.isSelected)
            assertFalse(interval.isEnabled)
            schedule.doClick()
            assertTrue(interval.isEnabled)
            interval.value = 15
            platformFirst.isSelected = true
            fastMaven.isSelected = true
            mavenWorkers.value = 4
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
            assertTrue(state.scheduledChecks)
            assertEquals(15, state.checkIntervalMinutes)
            assertTrue(state.mavenPlatformFirst)
            assertTrue(state.mavenFastEditorChecks)
            assertEquals(4, state.mavenMetadataThreads)
            assertFalse(configurable.isModified())
            selectors[2].selectedItem = VersionSeverity.INFORMATION
            configurable.reset()
            assertEquals(VersionSeverity.ERROR, selectors[2].selectedItem)
            assertFalse(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
            project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
            project.service<ScheduledVersionChecks>().configure()
        }
    }

    fun testSeverityPreferencesSurviveSerialization() {
        val state = VersionCheckerSettings.Options(patchSeverity = VersionSeverity.DISABLED,
            minorSeverity = VersionSeverity.INFORMATION, majorSeverity = VersionSeverity.ERROR,
            deprecatedSeverity = VersionSeverity.WARNING, deprecatedDependencies = "old:library = Retired",
            scheduledChecks = true, checkIntervalMinutes = 60, ignoredVersions = "maven g:a = 2.0",
            mavenPlatformFirst = true, mavenMetadataThreads = 4, mavenFastEditorChecks = true)
        assertEquals(state, XmlSerializer.deserialize(XmlSerializer.serialize(state), VersionCheckerSettings.Options::class.java))
    }
}
