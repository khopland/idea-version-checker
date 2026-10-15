package io.github.khopland.versionchecker

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationAction
import com.intellij.openapi.components.service
import io.github.khopland.versionchecker.followup.UpdateFollowUpService
import io.github.khopland.versionchecker.core.BuildSystemAdapter
import com.intellij.openapi.project.Project

internal fun notifyVersionUpdates(project: Project, followUp: List<String>, buildSystems: Set<String> = emptySet()) {
    if (project.isDisposed || followUp.isEmpty() && buildSystems.isEmpty()) return
    val notification = NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
        .createNotification("Versions updated", followUp.joinToString("\n"), NotificationType.INFORMATION)
    val systems = buildSystems + buildSet {
        if (followUp.any { it.startsWith("npm updates") }) add("npm")
        if (followUp.any { it.startsWith("Reload the Gradle") }) add("gradle")
    }
    for ((id, label) in listOf("maven" to "Maven", "gradle" to "Gradle")) {
        if (id in systems && BuildSystemAdapter.find(id) != null) {
            notification.addAction(NotificationAction.createSimple("Reload $label project") {
                if (!project.isDisposed) BuildSystemAdapter.find(id)?.reloadProject(project)
            })
        }
    }
    if ("npm" in systems && BuildSystemAdapter.find("npm") != null) notification.addAction(NotificationAction.createSimple("Preview npm lockfile sync") {
        if (!project.isDisposed && BuildSystemAdapter.find("npm") != null)
            project.service<UpdateFollowUpService>().previewNpmSync()
    })
    notification.notify(project)
}
