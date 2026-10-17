package io.github.khopland.versionchecker

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationAction
import com.intellij.openapi.components.service
import io.github.khopland.versionchecker.followup.UpdateFollowUpService
import io.github.khopland.versionchecker.followup.BuildReloadService
import io.github.khopland.versionchecker.core.BuildSystemAdapter
import com.intellij.openapi.project.Project

internal fun notifyVersionUpdates(project: Project, followUp: List<String>, buildSystems: Set<String> = emptySet(),
                                 changedFiles: AppliedVersionChanges? = null) {
    if (project.isDisposed || followUp.isEmpty() && buildSystems.isEmpty() && changedFiles == null) return
    val captureNotice = when {
        changedFiles?.captureUnavailable == true -> "Historical file snapshots could not be captured. Inspect the changed files or use local history."
        (changedFiles?.omittedTargetFiles ?: 0) > 0 -> "${changedFiles!!.omittedTargetFiles} targeted declaration files were omitted from the captured review because of snapshot limits."
        else -> ""
    }
    val content = listOf(followUp.joinToString("\n").ifEmpty { "Declaration versions changed. Choose a follow-up action below." }, captureNotice)
        .filter { it.isNotEmpty() }.joinToString("\n")
    val notification = NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
        .createNotification("Versions updated", content, NotificationType.INFORMATION)
    val reviewToken = changedFiles?.let { project.service<AppliedVersionChangesService>().retain(it) }
    if (reviewToken != null) notification.addAction(NotificationAction.createSimple("Review changed files") {
        if (!project.isDisposed) project.service<AppliedVersionChangesService>().show(reviewToken)
    })
    val systems = buildSystems + buildSet {
        if (followUp.any { it.startsWith("npm updates") }) add("npm")
        if (followUp.any { it.startsWith("Reload the Gradle") }) add("gradle")
    }
    val sessionToken = project.service<UpdateSessionService>().start(reviewToken, changedFiles, systems)
    if (sessionToken != null) notification.addAction(NotificationAction.createSimple("View update session") {
        if (!project.isDisposed) project.service<UpdateSessionService>().show(sessionToken)
    })
    if (reviewToken != null && changedFiles.mavenVerificationTargets.isNotEmpty()) {
        notification.addAction(NotificationAction.createSimple("Verify Maven effective versions") {
            if (!project.isDisposed) {
                val captured = project.service<AppliedVersionChangesService>().captured(reviewToken)
                if (captured != null) BuildSystemAdapter.find("maven")?.verifyUpdatedVersions(project, captured) { message ->
                    project.service<UpdateSessionService>().recordVerification(sessionToken, "maven", message)
                } else project.service<UpdateSessionService>().recordVerification(sessionToken, "maven",
                    "Unknown. Historical evidence expired; apply a fresh reviewed update to capture verification evidence.")
            }
        })
    }
    for ((id, label) in listOf("maven" to "Maven", "gradle" to "Gradle")) {
        if (id in systems && BuildSystemAdapter.find(id) != null) {
            notification.addAction(NotificationAction.createSimple("Save and reload $label projects") {
                if (!project.isDisposed) project.service<BuildReloadService>().reload(id, sessionToken)
            })
        }
    }
    if ("npm" in systems && BuildSystemAdapter.find("npm") != null) notification.addAction(NotificationAction.createSimple("Preview npm lockfile sync") {
        if (!project.isDisposed && BuildSystemAdapter.find("npm") != null)
            project.service<UpdateFollowUpService>().previewNpmSync(sessionToken)
    })
    project.service<BuildReloadService>().publish(notification)
}
