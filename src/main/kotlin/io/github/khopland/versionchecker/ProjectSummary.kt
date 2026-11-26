package io.github.khopland.versionchecker

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import io.github.khopland.versionchecker.core.DependencyReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.seconds

/**
 * When a project opens, checks every build file in the background and says how many versions are outdated or vulnerable,
 * with a link to the **Dependency Updates** tool window. It says so again only when the list has changed since the last time.
 */
class ProjectSummaryActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode || application.isHeadlessEnvironment) return
        if (!VersionCheckerSettings.getInstance().summaryOnOpen) return
        // Leave the first seconds after opening to indexing and the build tool import.
        delay(STARTUP_DELAY)
        val report = DependencyReport.getInstance(project)
        report.refresh()
        // The tool window may start a check of its own, which replaces this one, so wait for whichever finishes.
        val state = report.current.first { it.done && !it.running }
        ProjectSummary.notifyIfChanged(project, state)
    }

    private companion object {
        val STARTUP_DELAY = 5.seconds
    }
}

object ProjectSummary {
    private const val SHOWN = "io.github.khopland.versionChecker.summaryShown"

    class Counts(val outdated: Int, val vulnerable: Int, val files: Int)

    /** Outdated and vulnerable versions in [state]. Versions that could not be checked are left out. */
    fun counts(state: DependencyReport.State): Counts {
        val outdated = state.rows.filter { it.updates?.targets?.isEmpty == false }
        val vulnerable = state.rows.filter { it.vulnerable != null }
        return Counts(outdated.size, vulnerable.size, (outdated + vulnerable).map { it.file }.distinct().size)
    }

    fun text(counts: Counts): String? {
        val parts = listOfNotNull(
            counts.outdated.takeIf { it > 0 }?.let { "$it outdated ${StringUtil.pluralize("version", it)}" },
            counts.vulnerable.takeIf { it > 0 }?.let { "$it vulnerable" },
        )
        if (parts.isEmpty()) return null
        return parts.joinToString(", ") + " in ${counts.files} build ${StringUtil.pluralize("file", counts.files)}"
    }

    /** What the summary is about, so the same list is not announced at every start. */
    fun signature(state: DependencyReport.State): String = state.rows
        .filter { it.updates?.targets?.isEmpty == false || it.vulnerable != null }
        .map { row ->
            listOf(row.file.path, row.declaration.name, row.declaration.current, row.updates?.targets?.major.orEmpty(),
                row.vulnerable?.advisories?.map { it.displayId }?.sorted()?.joinToString(",").orEmpty()).joinToString("|")
        }
        .sorted()
        .joinToString("\n")
        .hashCode().toString()

    /** Shows the summary of [state] unless it is empty, the tool window is open, or the same list was shown before. Returns whether it did. */
    fun notifyIfChanged(project: Project, state: DependencyReport.State): Boolean {
        if (project.isDisposed) return false
        val properties = PropertiesComponent.getInstance(project)
        val signature = signature(state)
        val counts = counts(state)
        val text = text(counts)
        if (text == null || properties.getValue(SHOWN) == signature) {
            // An empty list is remembered too, so the next outdated version is announced.
            properties.setValue(SHOWN, signature)
            return false
        }
        properties.setValue(SHOWN, signature)
        if (ToolWindowManager.getInstance(project).getToolWindow(DependencyUpdatesToolWindowFactory.ID)?.isVisible == true) return false
        NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
            .createNotification("Dependency updates", text, if (counts.vulnerable > 0) NotificationType.WARNING else NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Show dependency updates") {
                ToolWindowManager.getInstance(project).getToolWindow(DependencyUpdatesToolWindowFactory.ID)?.activate(null)
            })
            .addAction(NotificationAction.createSimpleExpiring("Don't show again") {
                VersionCheckerSettings.getInstance().state.summaryOnOpen = false
            })
            .notify(project)
        return true
    }
}
