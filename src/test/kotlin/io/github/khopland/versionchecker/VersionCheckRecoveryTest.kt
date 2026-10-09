package io.github.khopland.versionchecker

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.UiInterceptors
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class VersionCheckRecoveryTest : BasePlatformTestCase() {
    private inner class Adapter(private val files: List<VirtualFile>, override val displayName: String) : BuildSystemAdapter {
        override val id = "recovery-${System.identityHashCode(this)}"
        override val capabilities = AdapterCapabilities()
        val errors = ConcurrentHashMap<String, String>()
        val checked = CopyOnWriteArrayList<String>()
        var metadataRefreshes = 0
        var offline = false
        var discoveryFailure: Exception? = null
        var beforeCheck: suspend (BuildSnapshot) -> Unit = {}
        override fun supports(project: Project, selection: BuildSelection) = selection.scope == UpdateScope.WHOLE_PROJECT || files.any { it.path == selection.currentFile }
        override fun isOffline(project: Project) = offline
        override fun snapshot(project: Project, file: VirtualFile) = BuildSnapshot(
            BuildContextId(id, file.parent.path, file.path), file.path, BuildFingerprint(mapOf(file.path to "1"), id),
            listOf(VersionDeclaration(DeclarationId(file.path, "library"), ArtifactId(id, "library"), "1.0", "1.0")))
        override fun isCurrent(project: Project, snapshot: BuildSnapshot) = true
        override suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot> {
            discoveryFailure?.let { throw it }
            return readAction { files.filter { selection.scope == UpdateScope.WHOLE_PROJECT || it.path == selection.currentFile }
                .map { snapshot(project, it) } }
        }
        override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
            checked += snapshot.sourceFile
            beforeCheck(snapshot)
            return UpdateReport(failure = errors[snapshot.sourceFile])
        }
        override fun invalidateMetadata(project: Project) { metadataRefreshes++ }
        override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>) = BulkUpdatePlan(emptyList(), emptyList())
    }

    private fun files(prefix: String, count: Int) = (1..count).map {
        myFixture.addFileToProject("$prefix/file-$it.build", "1.0").virtualFile
    }
    private fun notifications(): List<Notification> {
        val captured = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId != "Version Checker") return
                captured += notification
                Disposer.register(testRootDisposable) { notification.expire() }
            }
        })
        return captured
    }
    private fun invoke(notification: Notification, label: String) {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        val event = AnActionEvent.createFromDataContext("test", null, context)
        (notification.actions.single { it.templatePresentation.text == label } as NotificationAction).actionPerformed(event, notification)
    }

    fun testProjectFailuresProduceOneSummaryAndRetryOnlyFailedFilesAcrossProviders() {
        val firstFiles = files("recovery-first", 3)
        val secondFiles = files("recovery-second", 2)
        val first = Adapter(firstFiles, "First provider")
        val second = Adapter(secondFiles, "Second provider")
        val failed = setOf(firstFiles[0].path, firstFiles[1].path, secondFiles[0].path)
        firstFiles.take(2).forEach { first.errors[it.path] = "private registry authentication failed" }
        second.errors[secondFiles[0].path] = "private registry transport failed"
        listOf(first, second).forEach { BuildSystemAdapter.EP.point.registerExtension(it, testRootDisposable) }
        val notifications = notifications()
        val service = project.service<VersionCheckService>()
        val refresh = service.refresh(listOf(first, second))
        PlatformTestUtil.waitWithEventsDispatching("Project summary", { refresh.isCompleted && notifications.size == 1 }, 10)
        val notification = notifications.single()
        assertTrue(notification.content.contains("3 build files"))
        assertFalse(notification.content.contains("private registry"))
        val goodSnapshot = first.snapshot(project, firstFiles[2])
        val good = service.cachedResult(goodSnapshot, UpdateMode.MAJOR)!!
        first.errors.clear(); second.errors.clear()
        invoke(notification, "Retry Failed Checks")
        PlatformTestUtil.waitWithEventsDispatching("Only failed files retried", {
            failed.all { path -> (first.checked + second.checked).count { it == path } == 2 } &&
                firstFiles.take(2).all { service.cached(first.snapshot(project, it))?.successful == true } &&
                service.cached(second.snapshot(project, secondFiles[0]))?.successful == true
        }, 10)
        assertEquals(5, first.checked.size)
        assertEquals(3, second.checked.size)
        assertEquals(2, first.metadataRefreshes)
        assertEquals(2, second.metadataRefreshes)
        assertTrue("Successful results retain their preview identity", service.isCachedResultCurrent(goodSnapshot, UpdateMode.MAJOR, good))
        assertEquals(1, notifications.size)
        assertTrue(notification.isExpired)
    }

    fun testAutomaticFailuresShareOneNotificationAndKeepEveryRetryTarget() {
        val files = files("automatic-recovery", 3)
        val adapter = Adapter(files, "Automatic provider")
        files.forEach { adapter.errors[it.path] = "same registry error" }
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        val notifications = notifications()
        val service = project.service<VersionCheckService>()
        files.forEach { service.updates(adapter, adapter.snapshot(project, it)) }
        PlatformTestUtil.waitWithEventsDispatching("Automatic failures grouped", {
            files.all { service.cached(adapter.snapshot(project, it))?.successful == false } &&
                notifications.singleOrNull()?.content?.contains("3 build files") == true
        }, 10)
        adapter.errors.clear()
        invoke(notifications.single(), "Retry Failed Checks")
        PlatformTestUtil.waitWithEventsDispatching("Every failure retried", {
            files.all { service.cached(adapter.snapshot(project, it))?.successful == true }
        }, 10)
        assertEquals(6, adapter.checked.size)
        assertEquals(1, notifications.size)
    }

    fun testDiscoveryFailuresAndOfflineProvidersShareTheScanSummary() {
        val failed = Adapter(files("failed-discovery", 1), "Discovery provider").apply {
            discoveryFailure = IllegalStateException("native settings failure")
        }
        val offline = Adapter(files("offline-discovery", 1), "Offline provider").apply { this.offline = true }
        listOf(failed, offline).forEach { BuildSystemAdapter.EP.point.registerExtension(it, testRootDisposable) }
        val notifications = notifications()
        val refresh = project.service<VersionCheckService>().refresh(listOf(failed, offline))
        PlatformTestUtil.waitWithEventsDispatching("Discovery and offline summary", { refresh.isCompleted && notifications.size == 1 }, 10)
        assertTrue(notifications.single().content.contains("Could not discover"))
        assertTrue(notifications.single().content.contains("Offline provider"))
        assertFalse(notifications.single().content.contains("native settings failure"))
        assertTrue(failed.checked.isEmpty() && offline.checked.isEmpty())
        failed.discoveryFailure = null
        invoke(notifications.single(), "Retry Failed Checks")
        PlatformTestUtil.waitWithEventsDispatching("Failed provider rediscovered", { failed.checked.size == 1 }, 10)
        assertTrue("Offline providers aren't retry targets", offline.checked.isEmpty())
    }

    fun testCoroutineAndIdeaCancellationDoNotBecomeFailureNotifications() {
        val files = files("cancelled-recovery", 1)
        val adapter = Adapter(files, "Cancelled provider")
        val gate = CompletableDeferred<Unit>()
        adapter.beforeCheck = { gate.await() }
        val notifications = notifications()
        val service = project.service<VersionCheckService>()
        val refresh = service.refresh(listOf(adapter))
        try {
            PlatformTestUtil.waitWithEventsDispatching("Check starts", { adapter.checked.isNotEmpty() }, 10)
            refresh.cancel()
            PlatformTestUtil.waitWithEventsDispatching("Check cancelled", { refresh.isCompleted }, 10)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue(notifications.isEmpty())
            assertNull(service.cached(adapter.snapshot(project, files.single())))
        } finally { gate.complete(Unit) }
        adapter.discoveryFailure = ProcessCanceledException()
        val discovery = service.refresh(listOf(adapter))
        PlatformTestUtil.waitWithEventsDispatching("IDE cancellation propagates", { discovery.isCompleted }, 10)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(notifications.isEmpty())
    }

    fun testStaleNotificationsDeduplicateAndWaitForExplicitRefreshBeforeNativeWork() {
        val file = files("stale-recovery", 1).single()
        val adapter = Adapter(listOf(file), "Stale provider")
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        val notifications = notifications()
        val feedback = project.service<VersionCheckFeedback>()
        val target = VersionCheckTarget(adapter.id, file.path)
        feedback.staleFix(target); feedback.staleFix(target)
        PlatformTestUtil.waitWithEventsDispatching("Stale action appears", { notifications.size == 1 }, 10)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, notifications.size)
        assertTrue(adapter.checked.isEmpty())
        invoke(notifications.single(), "Refresh This File")
        PlatformTestUtil.waitWithEventsDispatching("Explicit refresh completes", {
            project.service<VersionCheckService>().cached(adapter.snapshot(project, file))?.successful == true
        }, 10)
        assertEquals(listOf(file.path), adapter.checked.toList())
        assertTrue(notifications.single().isExpired)
    }

    fun testPausedChecksDisableRecoveryWithoutExpiringTheNotificationOrInvalidatingMetadata() {
        val file = files("paused-recovery", 1).single()
        val adapter = Adapter(listOf(file), "Paused provider")
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        val notifications = notifications()
        project.service<VersionCheckFeedback>().staleFix(VersionCheckTarget(adapter.id, file.path))
        PlatformTestUtil.waitWithEventsDispatching("Recovery available", { notifications.size == 1 }, 10)
        val notification = notifications.single()
        val action = notification.actions.single() as NotificationAction
        val event = AnActionEvent.createFromDataContext("test", null,
            SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build())
        val settings = project.service<VersionCheckerSettings>().state
        settings.enabled = false
        try {
            action.update(event)
            assertFalse(event.presentation.isEnabled)
            action.actionPerformed(event, notification)
            assertFalse(notification.isExpired)
            assertTrue(adapter.checked.isEmpty())
            assertEquals(0, adapter.metadataRefreshes)
        } finally { settings.enabled = true }
        action.update(event)
        assertTrue(event.presentation.isEnabled)
        invoke(notification, "Refresh This File")
        PlatformTestUtil.waitWithEventsDispatching("Enabled recovery completes", {
            project.service<VersionCheckService>().cached(adapter.snapshot(project, file))?.successful == true
        }, 10)
    }

    fun testNativeFailureDetailsOpenOnDemandWithoutAnotherCheck() {
        val file = files("details-recovery", 1).single()
        val adapter = Adapter(listOf(file), "Details provider").apply { errors[file.path] = "native authentication details" }
        val notifications = notifications()
        val refresh = project.service<VersionCheckService>().refresh(listOf(adapter))
        PlatformTestUtil.waitWithEventsDispatching("Failure summary ready", { refresh.isCompleted && notifications.size == 1 }, 10)
        var shown = false
        UiInterceptors.registerPossible(testRootDisposable, object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
            override fun doIntercept(component: DialogWrapper) {
                fun text(container: java.awt.Container): String = container.components.joinToString("\n") {
                    when (it) {
                        is javax.swing.JTextArea -> it.text
                        is java.awt.Container -> text(it)
                        else -> ""
                    }
                }
                // Headless interception runs before DialogWrapper attaches its content pane.
                val panel = component.javaClass.getDeclaredMethod("createCenterPanel").apply { isAccessible = true }
                    .invoke(component) as java.awt.Container
                assertTrue(text(panel).contains("native authentication details"))
                shown = true
                component.close(DialogWrapper.OK_EXIT_CODE)
            }
        })
        invoke(notifications.single(), "Show Details")
        PlatformTestUtil.waitWithEventsDispatching("Details shown", { shown }, 10)
        assertEquals(1, adapter.checked.size)
    }
}
