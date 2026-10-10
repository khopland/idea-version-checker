package io.github.khopland.versionchecker

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.npm.NpmViewFailure
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList

class PreviewFailureRecoveryTest : BasePlatformTestCase() {
    private lateinit var feedback: VersionCheckFeedback
    private val captured = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        feedback = VersionCheckFeedback(project, scope)
        Disposer.register(testRootDisposable, feedback)
        Disposer.register(testRootDisposable) { scope.cancel() }
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "Version Checker") captured += notification
            }
        })
    }

    private fun notification(): Notification {
        PlatformTestUtil.waitWithEventsDispatching("Preview failure offered", { captured.size == 1 }, 10)
        return captured.single()
    }

    private fun event() = AnActionEvent.createFromDataContext("test", null,
        SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build())

    fun testAuthenticationAdviceDoesNotExposeNativeOutputAndRetryRunsOnce() {
        var retries = 0
        feedback.previewFailure("npm", NpmViewFailure("E401", "secret-native-output")) { retries++ }
        val notification = notification()
        assertTrue(notification.content.contains("registry access was denied"))
        assertTrue(notification.content.contains(".npmrc"))
        assertTrue(notification.content.contains("No versions were changed"))
        assertFalse(notification.content.contains("NpmViewFailure"))
        assertFalse(notification.content.contains("secret-native-output"))
        assertEquals(listOf("Retry Preview", "Show Details"), notification.actions.map { it.templatePresentation.text })
        val action = notification.actions.first() as NotificationAction
        action.actionPerformed(event(), notification)
        action.actionPerformed(event(), notification)
        assertEquals(1, retries)
        assertTrue(notification.isExpired)
    }

    fun testPausedChecksKeepRecoveryAvailableUntilReenabled() {
        var retries = 0
        feedback.previewFailure("Test", IllegalStateException("private-native-cause")) { retries++ }
        val notification = notification()
        assertFalse(notification.content.contains("private-native-cause"))
        assertFalse(notification.content.contains("IllegalStateException"))
        val action = notification.actions.first() as NotificationAction
        val event = event()
        val settings = project.service<VersionCheckerSettings>().state
        settings.enabled = false
        try {
            action.update(event)
            assertFalse(event.presentation.isEnabled)
            action.actionPerformed(event, notification)
            assertEquals(0, retries)
            assertFalse(notification.isExpired)
        } finally { settings.enabled = true }
        action.update(event)
        assertTrue(event.presentation.isEnabled)
        action.actionPerformed(event, notification)
        assertEquals(1, retries)
    }

    fun testDisposalExpiresPreviewRecoveryAndRejectsLateRetry() {
        var retries = 0
        feedback.previewFailure("npm", NpmViewFailure("ENOTFOUND", "native cause")) { retries++ }
        val notification = notification()
        Disposer.dispose(feedback)
        assertTrue(notification.isExpired)
        (notification.actions.first() as NotificationAction).actionPerformed(event(), notification)
        assertEquals(0, retries)
        feedback.previewFailure("npm", NpmViewFailure("E401", "late cause")) { retries++ }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, captured.size)
    }
}
