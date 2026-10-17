package io.github.khopland.versionchecker

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.followup.BuildReloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList

class VersionUpdateNotificationsTest : BasePlatformTestCase() {
    fun testAppliedProviderSelectionOffersOnlyItsOwnExplicitFollowUp() {
        val captured = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { if (notification.title == "Versions updated") captured += notification }
        })
        notifyVersionUpdates(project, emptyList(), setOf("maven"))
        val notification = captured.single()
        assertEquals(listOf("View update session", "Save and reload Maven projects"), notification.actions.map { it.templatePresentation.text })
        assertTrue(notification.content.contains("Declaration versions changed"))
        notification.expire()
    }

    fun testSuccessfulChangesOfferHistoricalReviewWithoutIncludingDeclarationTextInNotification() {
        val captured = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { if (notification.title == "Versions updated") captured += notification }
        })
        notifyVersionUpdates(project, emptyList(), setOf("maven"), AppliedVersionChanges(listOf(
            AppliedVersionFile("/project/pom.xml", "private-before-contents", "private-after-contents"))))
        val notification = captured.single()
        assertEquals(listOf("Review changed files", "View update session", "Save and reload Maven projects"), notification.actions.map { it.templatePresentation.text })
        assertFalse(notification.content.contains("private-before"))
        assertFalse(notification.content.contains("private-after"))
        notification.expire()
    }

    fun testEntirelyOmittedReviewStillOffersClearGuidance() {
        val captured = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { if (notification.title == "Versions updated") captured += notification }
        })
        notifyVersionUpdates(project, emptyList(), changedFiles = AppliedVersionChanges(emptyList(), omittedTargetFiles = 2))
        val notification = captured.single()
        assertEquals(listOf("Review changed files", "View update session"), notification.actions.map { it.templatePresentation.text })
        assertTrue(notification.content.contains("2 targeted declaration files"))
        assertTrue(notification.content.contains("snapshot limits"))
        notification.expire()
    }

    fun testNotificationOffersRetainedSessionWithTheMatchingProviderAndHistoricalDiff() {
        val captured = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { if (notification.title == "Versions updated") captured += notification }
        })
        notifyVersionUpdates(project, emptyList(), setOf("maven"), AppliedVersionChanges(listOf(
            AppliedVersionFile("/project/pom.xml", "old declaration", "new declaration"))))
        val sessions = project.service<UpdateSessionService>()
        val originalToken = requireNotNull(sessions.latest())
        val original = requireNotNull(sessions.snapshot(originalToken))
        assertEquals(setOf("maven"), original.providers)
        assertTrue(original.summary().contains("1 changed declaration files captured"))
        assertTrue(original.summary().contains("Unknown. The expected effective dependency version has not been confirmed"))
        val diff = project.service<AppliedVersionChangesService>().captured(requireNotNull(original.diffToken))
        assertEquals("old declaration", diff!!.files.single().before)
        assertEquals("new declaration", diff.files.single().after)
        assertTrue(captured.single().actions.any { it.templatePresentation.text == "View update session" })

        notifyVersionUpdates(project, emptyList(), setOf("gradle"))
        assertFalse(originalToken == sessions.latest())
        assertEquals(setOf("maven"), sessions.snapshot(originalToken)!!.providers)
        assertEquals(setOf("gradle"), sessions.snapshot(requireNotNull(sessions.latest()))!!.providers)
        captured.forEach { it.expire() }
    }

    fun testFollowUpNotificationsExpireWithTheirOwnerAndRejectLatePublication() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = BuildReloadService(project, scope)
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
        try {
            val retained = group.createNotification("Follow-up test", "Pending action", NotificationType.INFORMATION)
            owner.publish(retained)
            Disposer.dispose(owner)
            assertTrue(retained.isExpired)
            val late = group.createNotification("Late follow-up test", "No action", NotificationType.INFORMATION)
            owner.publish(late)
            assertTrue(late.isExpired)
        } finally { if (!Disposer.isDisposed(owner)) Disposer.dispose(owner); scope.cancel() }
    }
}
