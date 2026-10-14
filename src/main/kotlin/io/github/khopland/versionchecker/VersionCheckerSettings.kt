package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.VersionChangeKind

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.HideableDecorator
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

@Service(Service.Level.PROJECT)
@State(name = "VersionChecker", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class VersionCheckerSettings : PersistentStateComponent<VersionCheckerSettings.Options> {
    data class Options(
        var enabled: Boolean = true,
        var patchSeverity: VersionSeverity = VersionSeverity.WARNING,
        var minorSeverity: VersionSeverity = VersionSeverity.WARNING,
        var majorSeverity: VersionSeverity = VersionSeverity.WARNING,
        var otherSeverity: VersionSeverity = VersionSeverity.WARNING,
        var deprecatedSeverity: VersionSeverity = VersionSeverity.ERROR,
        var deprecatedDependencies: String = "",
        var scheduledChecks: Boolean = false,
        var checkIntervalMinutes: Int = 30,
        var ignoredVersions: String = "",
        var mavenPlatformFirst: Boolean = false,
        var mavenMetadataThreads: Int = 2,
        var mavenFastEditorChecks: Boolean = false,
        var mavenPersistentMetadata: Boolean = false
    )
    private var options = Options()
    override fun getState(): Options = options
    override fun loadState(state: Options) { options = state }
}

class VersionCheckerConfigurable(private val project: Project) : Configurable {
    private var enabled: JBCheckBox? = null
    private var scheduled: JBCheckBox? = null
    private var interval: JSpinner? = null
    private val severities = linkedMapOf<VersionChangeKind, ComboBox<VersionSeverity>>()
    private var deprecated: JBTextArea? = null
    private var ignored: JBTextArea? = null
    private var platformFirst: JBCheckBox? = null
    private var mavenThreads: JSpinner? = null
    private var fastMaven: JBCheckBox? = null
    private var persistentMaven: JBCheckBox? = null
    override fun getDisplayName(): String = "Version Checker"
    override fun createComponent(): JComponent {
        enabled = JBCheckBox("Enable version checks")
        val form = FormBuilder.createFormBuilder().addComponent(enabled!!)
        scheduled = JBCheckBox("Refresh periodically")
        scheduled!!.toolTipText = "Check repositories while this project is open. Dependency versions are never changed automatically."
        interval = JSpinner(SpinnerNumberModel(30, 1, 1440, 1))
        scheduled!!.addActionListener { updateScheduleControls() }
        enabled!!.addActionListener { updateScheduleControls() }
        form.addComponent(scheduled!!).addLabeledComponent("Refresh interval (minutes):", interval!!)
            .addComponent(TitledSeparator("Warnings"))
        for ((kind, label) in linkedMapOf(
            VersionChangeKind.PATCH to "Patch updates:",
            VersionChangeKind.MINOR to "Minor updates:",
            VersionChangeKind.MAJOR to "Major updates:",
            VersionChangeKind.OTHER to "Other updates:",
            VersionChangeKind.DEPRECATED to "Deprecated or relocated:"
        )) {
            val selector = ComboBox(VersionSeverity.entries.toTypedArray())
            severities[kind] = selector
            form.addLabeledComponent(label, selector)
        }
        deprecated = JBTextArea(4, 40).apply { accessibleContext.accessibleName = "Deprecated artifacts" }
        ignored = JBTextArea(4, 40).apply { accessibleContext.accessibleName = "Ignored published versions" }
        val rules = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Mark dependencies as deprecated:"))
            .addComponent(JBScrollPane(deprecated!!))
            .addComponent(JBLabel("One per line: org.example:library = reason or @scope/package = reason"))
            .addComponent(JBLabel("Ignored versions:"))
            .addComponent(JBScrollPane(ignored!!))
            .addComponent(JBLabel("One per line: maven org.example:library = 1.2.3 or npm @scope/package = 1.2.3"))
            .addComponent(JBLabel("Use gradle instead of maven for Gradle rules. Remove a line to restore a warning."))
        form.addComponent(collapsedSection("Dependency rules", rules.panel))

        platformFirst = JBCheckBox("Check Maven parents and Spring Boot BOMs first")
        platformFirst!!.toolTipText = "Show platform results before checking the remaining dependencies."
        fastMaven = JBCheckBox("Skip unused managed dependencies in editor checks")
        fastMaven!!.toolTipText = "Refresh and update previews still check all supported declarations."
        persistentMaven = JBCheckBox("Reuse Maven metadata across IDE sessions")
        persistentMaven!!.toolTipText = "Reuse cached repository results for up to ten minutes. Refresh always queries fresh results."
        mavenThreads = JSpinner(SpinnerNumberModel(2, 1, 4, 1)).apply {
            toolTipText = "Use fewer parallel requests for slow or rate-limited repositories."
        }
        val maven = FormBuilder.createFormBuilder()
            .addComponent(platformFirst!!)
            .addComponent(fastMaven!!)
            .addComponent(persistentMaven!!)
            .addLabeledComponent("Parallel metadata requests:", mavenThreads!!)
        form.addComponent(collapsedSection("Advanced Maven settings", maven.panel))
        return form.panel.also { reset() }
    }
    override fun isModified(): Boolean {
        val state = project.service<VersionCheckerSettings>().state
        return enabled?.isSelected != state.enabled ||
            scheduled?.isSelected != state.scheduledChecks || interval?.value != state.checkIntervalMinutes.coerceIn(1, 1440) ||
            severities.any { (kind, selector) -> selector.selectedItem != kind.severity(state) } ||
            deprecated?.text != state.deprecatedDependencies || ignored?.text != state.ignoredVersions ||
            platformFirst?.isSelected != state.mavenPlatformFirst || mavenThreads?.value != state.mavenMetadataThreads.coerceIn(1, 4) ||
            fastMaven?.isSelected != state.mavenFastEditorChecks || persistentMaven?.isSelected != state.mavenPersistentMetadata
    }
    override fun apply() {
        val state = project.service<VersionCheckerSettings>().state
        val persistenceChanged = state.mavenPersistentMetadata != persistentMaven!!.isSelected
        val disabling = state.enabled && !enabled!!.isSelected
        state.enabled = enabled!!.isSelected
        state.scheduledChecks = scheduled!!.isSelected
        state.checkIntervalMinutes = interval!!.value as Int
        state.patchSeverity = selected(VersionChangeKind.PATCH)
        state.minorSeverity = selected(VersionChangeKind.MINOR)
        state.majorSeverity = selected(VersionChangeKind.MAJOR)
        state.otherSeverity = selected(VersionChangeKind.OTHER)
        state.deprecatedSeverity = selected(VersionChangeKind.DEPRECATED)
        state.deprecatedDependencies = deprecated!!.text
        state.ignoredVersions = ignored!!.text
        state.mavenPlatformFirst = platformFirst!!.isSelected
        state.mavenMetadataThreads = mavenThreads!!.value as Int
        state.mavenFastEditorChecks = fastMaven!!.isSelected
        state.mavenPersistentMetadata = persistentMaven!!.isSelected
        if (disabling) project.service<VersionCheckService>().pause()
        if (persistenceChanged) io.github.khopland.versionchecker.core.BuildSystemAdapter.find("maven")?.invalidateMetadata(project)
        project.service<ScheduledVersionChecks>().configure()
        project.service<VersionCheckService>().statusChanged()
        refreshEditorProblems(project, this)
    }
    override fun reset() {
        val state = project.service<VersionCheckerSettings>().state
        enabled?.isSelected = state.enabled
        scheduled?.isSelected = state.scheduledChecks
        interval?.value = state.checkIntervalMinutes.coerceIn(1, 1440)
        updateScheduleControls()
        severities.forEach { (kind, selector) -> selector.selectedItem = kind.severity(state) }
        deprecated?.text = state.deprecatedDependencies
        ignored?.text = state.ignoredVersions
        platformFirst?.isSelected = state.mavenPlatformFirst
        mavenThreads?.value = state.mavenMetadataThreads.coerceIn(1, 4)
        fastMaven?.isSelected = state.mavenFastEditorChecks
        persistentMaven?.isSelected = state.mavenPersistentMetadata
    }
    private fun collapsedSection(title: String, content: JComponent): JPanel = JPanel().also { panel ->
        HideableDecorator(panel, title, false).apply {
            setContentComponent(content)
            setOn(false)
        }
    }

    private fun selected(kind: VersionChangeKind) = severities.getValue(kind).selectedItem as VersionSeverity
    private fun updateScheduleControls() {
        scheduled?.isEnabled = enabled?.isSelected == true
        interval?.isEnabled = enabled?.isSelected == true && scheduled?.isSelected == true
    }
    override fun disposeUIResources() { enabled = null; scheduled = null; interval = null; deprecated = null; ignored = null; platformFirst = null; mavenThreads = null; fastMaven = null; persistentMaven = null; severities.clear() }
}
