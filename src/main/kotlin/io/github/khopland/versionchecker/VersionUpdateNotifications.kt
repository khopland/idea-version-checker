package io.github.khopland.versionchecker

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

internal fun notifyVersionUpdates(project: Project, followUp: List<String>) {
    if (project.isDisposed || followUp.isEmpty()) return
    NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
        .createNotification("Versions updated", followUp.joinToString("\n"), NotificationType.INFORMATION)
        .notify(project)
}
