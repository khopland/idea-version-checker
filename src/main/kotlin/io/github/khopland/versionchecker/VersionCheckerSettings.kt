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
import io.github.khopland.versionchecker.core.VersionsChangedListener
import io.github.khopland.versionchecker.core.VulnerabilityService
import io.github.khopland.versionchecker.vuln.NexusIqSettings

@Service
@State(name = "VersionChecker", storages = [Storage("version-checker.xml")])
class VersionCheckerSettings : SimplePersistentStateComponent<VersionCheckerSettings.State>(State()) {
    class State : BaseState() {
        var minReleaseAgeHours by property(0)
        var checkVulnerabilities by property(false)
    }

    val minReleaseAgeHours get() = state.minReleaseAgeHours.coerceAtLeast(0)
    /** Off by default: the check sends dependency names and versions to osv.dev, or the project's Nexus IQ server. */
    val checkVulnerabilities get() = state.checkVulnerabilities

    companion object {
        fun getInstance(): VersionCheckerSettings = ApplicationManager.getApplication().service()
    }
}

class VersionCheckerConfigurable : BoundConfigurable("Version Checker") {
    private val state get() = VersionCheckerSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        row("Minimum release age:") {
            intTextField(0..100_000).columns(6).bindIntText(state::minReleaseAgeHours)
            label("hours")
        }.rowComment("Hide Maven, Gradle, wrapper and npm versions published less than this long ago, for example to match a Nexus quarantine, or to wait until a hijacked release has been found and removed. 0 shows the newest version.")
        row { checkBox("Check for known vulnerabilities (sends dependency names and versions to osv.dev)").bindSelected(state::checkVulnerabilities) }
            .rowComment("Declared Maven, Gradle and npm dependencies, and in Maven, npm, pnpm, Yarn and Bun projects the packages they bring in, as the last Maven import or the lockfile resolved them. A project with a Nexus IQ server under <b>Nexus IQ</b> sends them there instead.")
    }

    override fun apply() {
        super.apply()
        refreshAll()
    }
}

/** Forget cached versions, highlight open build files again and check the Dependency Updates list again. */
fun refreshAll() {
    VersionService.getInstance().clear()
    VulnerabilityService.getInstance().clear()
    ProjectManager.getInstance().openProjects.forEach {
        NexusIqSettings.getInstance(it).clear()
        DaemonCodeAnalyzer.getInstance(it).restart("Version Checker settings changed")
        it.messageBus.syncPublisher(VersionsChangedListener.TOPIC).versionsChanged()
    }
}
