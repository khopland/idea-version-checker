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
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
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
        var checkIntervalMinutes: Int = 30
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
    override fun getDisplayName(): String = "Version Checker"
    override fun createComponent(): JComponent {
        enabled = JBCheckBox("Check for newer dependency and build-plugin versions")
        val form = FormBuilder.createFormBuilder().addComponent(enabled!!)
        scheduled = JBCheckBox("Check repositories periodically while this project is open")
        interval = JSpinner(SpinnerNumberModel(30, 1, 1440, 1))
        scheduled!!.addActionListener { updateScheduleControls() }
        enabled!!.addActionListener { updateScheduleControls() }
        form.addComponent(scheduled!!).addLabeledComponent("Check interval (minutes):", interval!!)
            .addComponent(JBLabel("Scheduled checks refresh editor warnings and do not change dependency versions."))
        for ((kind, label) in linkedMapOf(
            VersionChangeKind.PATCH to "Patch updates:",
            VersionChangeKind.MINOR to "Minor updates:",
            VersionChangeKind.MAJOR to "Major updates:",
            VersionChangeKind.OTHER to "Other version changes:",
            VersionChangeKind.DEPRECATED to "Deprecated / relocated:"
        )) {
            val selector = ComboBox(VersionSeverity.entries.toTypedArray())
            severities[kind] = selector
            form.addLabeledComponent(label, selector)
        }
        deprecated = JBTextArea(5, 50)
        form.addComponent(JBLabel("Explicitly deprecated dependencies or plugins (one artifact identifier = reason per line):"))
            .addComponent(JBScrollPane(deprecated!!))
            .addComponent(JBLabel("Use the build system’s artifact identifier, such as org.example:library or @scope/package."))
        return form.panel.also { reset() }
    }
    override fun isModified(): Boolean {
        val state = project.service<VersionCheckerSettings>().state
        return enabled?.isSelected != state.enabled ||
            scheduled?.isSelected != state.scheduledChecks || interval?.value != state.checkIntervalMinutes.coerceIn(1, 1440) ||
            severities.any { (kind, selector) -> selector.selectedItem != kind.severity(state) } ||
            deprecated?.text != state.deprecatedDependencies
    }
    override fun apply() {
        val state = project.service<VersionCheckerSettings>().state
        state.enabled = enabled!!.isSelected
        state.scheduledChecks = scheduled!!.isSelected
        state.checkIntervalMinutes = interval!!.value as Int
        state.patchSeverity = selected(VersionChangeKind.PATCH)
        state.minorSeverity = selected(VersionChangeKind.MINOR)
        state.majorSeverity = selected(VersionChangeKind.MAJOR)
        state.otherSeverity = selected(VersionChangeKind.OTHER)
        state.deprecatedSeverity = selected(VersionChangeKind.DEPRECATED)
        state.deprecatedDependencies = deprecated!!.text
        project.service<ScheduledVersionChecks>().configure()
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
    }
    private fun selected(kind: VersionChangeKind) = severities.getValue(kind).selectedItem as VersionSeverity
    private fun updateScheduleControls() {
        scheduled?.isEnabled = enabled?.isSelected == true
        interval?.isEnabled = enabled?.isSelected == true && scheduled?.isSelected == true
    }
    override fun disposeUIResources() { enabled = null; scheduled = null; interval = null; deprecated = null; severities.clear() }
}
