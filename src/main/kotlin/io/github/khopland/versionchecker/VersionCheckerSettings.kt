package io.github.khopland.versionchecker

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.khopland.versionchecker.core.VersionService

@Service
@State(name = "VersionChecker", storages = [Storage("version-checker.xml")])
class VersionCheckerSettings : SimplePersistentStateComponent<VersionCheckerSettings.State>(State()) {
    class State : BaseState() {
        var enabled by property(true)
        var minReleaseAgeHours by property(0)
    }

    val enabled get() = state.enabled
    val minReleaseAgeHours get() = state.minReleaseAgeHours.coerceAtLeast(0)

    companion object {
        fun getInstance(): VersionCheckerSettings = ApplicationManager.getApplication().service()
    }
}

class VersionCheckerConfigurable : BoundConfigurable("Version Checker") {
    private val state get() = VersionCheckerSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        row { checkBox("Check versions in build files").bindSelected(state::enabled) }
        row("Minimum release age:") {
            intTextField(0..100_000).columns(6).bindIntText(state::minReleaseAgeHours)
            label("hours")
        }.rowComment("Hide versions published less than this long ago, for example to match a repository quarantine. 0 shows the newest version.")
    }

    override fun apply() {
        super.apply()
        refreshAll()
    }
}

/** Forget cached versions and highlight open build files again. */
fun refreshAll() {
    VersionService.getInstance().clear()
    ProjectManager.getInstance().openProjects.forEach { DaemonCodeAnalyzer.getInstance(it).restart("Version Checker settings changed") }
}
