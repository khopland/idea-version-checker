package io.github.khopland.versionchecker

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

@Service(Service.Level.PROJECT)
@State(name = "MavenVersionChecker", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class VersionCheckerSettings : PersistentStateComponent<VersionCheckerSettings.Options> {
    data class Options(
        var enabled: Boolean = true,
        var patchSeverity: DependencySeverity = DependencySeverity.WARNING,
        var minorSeverity: DependencySeverity = DependencySeverity.WARNING,
        var majorSeverity: DependencySeverity = DependencySeverity.WARNING,
        var otherSeverity: DependencySeverity = DependencySeverity.WARNING,
        var deprecatedSeverity: DependencySeverity = DependencySeverity.ERROR,
        var deprecatedDependencies: String = ""
    )
    private var options = Options()
    override fun getState(): Options = options
    override fun loadState(state: Options) { options = state }
}

class VersionCheckerConfigurable(private val project: Project) : Configurable {
    private var enabled: JBCheckBox? = null
    private val severities = linkedMapOf<DependencyChangeKind, ComboBox<DependencySeverity>>()
    private var deprecated: JBTextArea? = null
    override fun getDisplayName(): String = "Maven Version Checker"
    override fun createComponent(): JComponent {
        enabled = JBCheckBox("Check for newer Maven dependency versions")
        val form = FormBuilder.createFormBuilder().addComponent(enabled!!)
        for ((kind, label) in linkedMapOf(
            DependencyChangeKind.PATCH to "Patch updates:",
            DependencyChangeKind.MINOR to "Minor updates:",
            DependencyChangeKind.MAJOR to "Major updates:",
            DependencyChangeKind.OTHER to "Other version changes:",
            DependencyChangeKind.DEPRECATED to "Deprecated / relocated dependencies:"
        )) {
            val selector = ComboBox(DependencySeverity.entries.toTypedArray())
            severities[kind] = selector
            form.addLabeledComponent(label, selector)
        }
        deprecated = JBTextArea(5, 50)
        form.addComponent(JBLabel("Explicitly deprecated dependencies (one groupId:artifactId = reason per line):"))
            .addComponent(JBScrollPane(deprecated!!))
            .addComponent(JBLabel("Maven relocation notices are detected from dependency POMs already downloaded by Maven."))
        return form.panel.also { reset() }
    }
    override fun isModified(): Boolean {
        val state = project.service<VersionCheckerSettings>().state
        return enabled?.isSelected != state.enabled ||
            severities.any { (kind, selector) -> selector.selectedItem != kind.severity(state) } ||
            deprecated?.text != state.deprecatedDependencies
    }
    override fun apply() {
        val state = project.service<VersionCheckerSettings>().state
        state.enabled = enabled!!.isSelected
        state.patchSeverity = selected(DependencyChangeKind.PATCH)
        state.minorSeverity = selected(DependencyChangeKind.MINOR)
        state.majorSeverity = selected(DependencyChangeKind.MAJOR)
        state.otherSeverity = selected(DependencyChangeKind.OTHER)
        state.deprecatedSeverity = selected(DependencyChangeKind.DEPRECATED)
        state.deprecatedDependencies = deprecated!!.text
        refreshEditorProblems(project, this)
    }
    override fun reset() {
        val state = project.service<VersionCheckerSettings>().state
        enabled?.isSelected = state.enabled
        severities.forEach { (kind, selector) -> selector.selectedItem = kind.severity(state) }
        deprecated?.text = state.deprecatedDependencies
    }
    private fun selected(kind: DependencyChangeKind) = severities.getValue(kind).selectedItem as DependencySeverity
    override fun disposeUIResources() { enabled = null; deprecated = null; severities.clear() }
}
