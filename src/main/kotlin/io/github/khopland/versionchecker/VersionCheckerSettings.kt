package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.BuildSystemAdapter
import com.intellij.util.xmlb.annotations.OptionTag

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

private const val MAX_RELEASE_AGE_HOURS = 24 * 365

@Service(Service.Level.PROJECT)
@State(name = "VersionChecker", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class VersionCheckerSettings : PersistentStateComponent<VersionCheckerSettings.Options> {
    data class Options(
        var enabled: Boolean = true,
        var deprecatedDependencies: String = "",
        var ignoredVersions: String = "",
        // Stored under its original name so existing quarantine settings keep working.
        @get:OptionTag("mavenMinimumReleaseAgeHours")
        var minimumReleaseAgeHours: Int = 0
    )
    private var options = Options()
    override fun getState(): Options = options
    override fun loadState(state: Options) { options = state }
}

class VersionCheckerConfigurable(private val project: Project) : Configurable {
    private var enabled: JBCheckBox? = null
    private var deprecated: JBTextArea? = null
    private var ignored: JBTextArea? = null
    private var releaseAge: JSpinner? = null
    override fun getDisplayName(): String = "Version Checker"
    override fun createComponent(): JComponent {
        enabled = JBCheckBox("Enable version checks").apply { name = "versionChecker.enabled" }
        releaseAge = JSpinner(SpinnerNumberModel(0, 0, MAX_RELEASE_AGE_HOURS, 1)).apply {
            name = "versionChecker.minimumReleaseAge"
            toolTipText = "Suggest only versions published at least this many hours ago, for example to match a repository " +
                "quarantine period. Applies to Maven, npm, pnpm, Yarn and Bun. 0 suggests the newest version."
        }
        val form = FormBuilder.createFormBuilder().addComponent(enabled!!)
            .addLabeledComponent("Minimum release age (hours):", releaseAge!!)
        form.addComponent(JBLabel("Change how version hints are highlighted under Settings → Editor → Inspections."))
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
        form.addComponent(JBLabel("Shared rules: $PROJECT_POLICY_FILE in the project root; workspace rules add restrictions."))
        form.addComponent(JBLabel("Shared policy is edited in its file. These settings never create or change it."))
        projectPolicy(project).shared.diagnostic?.let { diagnostic ->
            form.addComponent(JBLabel(diagnostic).apply { accessibleContext.accessibleName = "Shared policy error" })
        }
        return form.panel.also { reset() }
    }
    override fun isModified(): Boolean {
        val state = project.service<VersionCheckerSettings>().state
        return enabled?.isSelected != state.enabled ||
            deprecated?.text != state.deprecatedDependencies || ignored?.text != state.ignoredVersions ||
            releaseAge?.value != state.minimumReleaseAgeHours.coerceIn(0, MAX_RELEASE_AGE_HOURS)
    }
    override fun apply() {
        val state = project.service<VersionCheckerSettings>().state
        val releaseAgeChanged = state.minimumReleaseAgeHours != releaseAge!!.value as Int
        val disabling = state.enabled && !enabled!!.isSelected
        state.enabled = enabled!!.isSelected
        state.deprecatedDependencies = deprecated!!.text
        state.ignoredVersions = ignored!!.text
        state.minimumReleaseAgeHours = releaseAge!!.value as Int
        if (disabling) project.service<VersionCheckService>().pause()
        // Cached metadata stays valid (npm keys release times separately); only the selected versions change.
        if (releaseAgeChanged) BuildSystemAdapter.EP.extensionList.forEach {
            project.service<VersionCheckService>().invalidateResults(it.id)
        }
        project.service<VersionCheckService>().statusChanged()
        refreshEditorProblems(project, this)
    }
    override fun reset() {
        val state = project.service<VersionCheckerSettings>().state
        enabled?.isSelected = state.enabled
        deprecated?.text = state.deprecatedDependencies
        ignored?.text = state.ignoredVersions
        releaseAge?.value = state.minimumReleaseAgeHours.coerceIn(0, MAX_RELEASE_AGE_HOURS)
    }
    private fun collapsedSection(title: String, content: JComponent): JPanel = panel {
        collapsibleGroup(title) {
            row { cell(content).align(AlignX.FILL) }
        }.expanded = false
    }

    override fun disposeUIResources() { enabled = null; deprecated = null; ignored = null; releaseAge = null }
}
