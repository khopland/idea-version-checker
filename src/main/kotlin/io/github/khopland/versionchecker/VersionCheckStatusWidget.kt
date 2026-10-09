package io.github.khopland.versionchecker

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import io.github.khopland.versionchecker.core.BuildSelection
import io.github.khopland.versionchecker.core.BuildSystemAdapter
import io.github.khopland.versionchecker.core.UpdateScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.MouseEvent
import kotlin.time.Duration.Companion.nanoseconds

class VersionCheckStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = "VersionChecker.Status"
    override fun getDisplayName() = "Dependency version checks"
    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget = VersionCheckStatusWidget(project, scope)
}

/** Event-driven, read-only status. Snapshot work stays off the UI thread; no periodic repository checks. */
internal class VersionCheckStatusWidget(project: Project, parentScope: CoroutineScope) :
    EditorBasedWidget(project), StatusBarWidget.TextPresentation {
    private val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.Default + SupervisorJob(parentScope.coroutineContext[Job]))
    private val requests = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var disposed = false
    @Volatile private var currentFile: VirtualFile? = null
    @Volatile internal var status: VersionCheckStatus? = null
        private set

    override fun ID() = "VersionChecker.Status"
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getText() = status?.text.orEmpty()
    override fun getTooltipText() = status?.tooltip.orEmpty()
    override fun getAlignment() = 0f
    override fun getClickConsumer() = Consumer<MouseEvent> { event ->
        val file = currentFile?.takeIf { it.isValid } ?: return@Consumer
        if (disposed || project.isDisposed) return@Consumer
        val actions = DefaultActionGroup().apply {
            val manager = ActionManager.getInstance()
            add(manager.getAction("VersionChecker.RefreshCurrentFile"))
            add(manager.getAction("VersionChecker.ReviewCurrentFile"))
            addSeparator()
            add(object : AnAction("Version Checker Settings…") {
                override fun actionPerformed(event: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "Version Checker")
                }
            })
        }
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file).build()
        JBPopupFactory.getInstance().createActionGroupPopup("Dependency versions", actions, context,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true).show(RelativePoint(event))
    }

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        val connection = project.messageBus.connect(this)
        connection.subscribe(VersionCheckStatusListener.TOPIC, VersionCheckStatusListener { path ->
            if (path == null || path == currentFile?.path) requestUpdate()
        })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                // Never leave the preceding editor's result visible while the new snapshot is read.
                status = null
                currentFile = null
                statusBar.updateWidget(ID())
                requestUpdate()
            }
        })
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) { requestUpdate() }
        })
        connection.subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun enteredDumbMode() { requestUpdate() }
            override fun exitDumbMode() { requestUpdate() }
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) { requestUpdate() }
            })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) { requestUpdate() }
        }, this)
        scope.launch {
            requests.receiveAsFlow().collectLatest {
                // A short pause folds completion/VFS/document bursts into one background read.
                delay(50)
                val file = withContext(Dispatchers.EDT) {
                    if (disposed || project.isDisposed) null else FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                }
                val result = try {
                    file?.let { readAction { evaluate(it) } }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cancelled: ProcessCanceledException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Logger.getInstance(VersionCheckStatusWidget::class.java).warn("Could not read dependency check status", failure)
                    VersionCheckStatus(CheckPhase.UNAVAILABLE)
                }
                withContext(Dispatchers.EDT) {
                    if (!disposed && !project.isDisposed &&
                        FileEditorManager.getInstance(project).selectedFiles.firstOrNull() == file) {
                        currentFile = file
                        status = result
                        statusBar.updateWidget(ID())
                    }
                }
                // Only a report's original deadline wakes the widget; ordinary idle time does no work.
                result?.expiresAtNanos?.let { deadline ->
                    delay((deadline - System.nanoTime()).coerceAtLeast(1).nanoseconds)
                    requestUpdate()
                }
            }
        }
        requestUpdate()
    }

    private fun evaluate(file: VirtualFile): VersionCheckStatus? {
        if (!file.isValid || disposed || project.isDisposed) return null
        if (DumbService.isDumb(project)) return if (file.name in setOf("pom.xml", "package.json", "build.gradle", "build.gradle.kts", "libs.versions.toml"))
            VersionCheckStatus(CheckPhase.INDEXING) else null
        val adapters = BuildSystemAdapter.matching(project, BuildSelection(UpdateScope.CURRENT_FILE, file.path))
        if (adapters.isEmpty()) return null
        if (!project.service<VersionCheckerSettings>().state.enabled) return VersionCheckStatus(CheckPhase.PAUSED)
        val statuses = adapters.map { adapter ->
            if (adapter.isOffline(project)) VersionCheckStatus(CheckPhase.OFFLINE)
            else adapter.inspectionSnapshot(project, file)?.let { project.service<VersionCheckService>().status(adapter, it) }
                ?: VersionCheckStatus(CheckPhase.UNAVAILABLE)
        }
        return statuses.firstOrNull { it.phase != CheckPhase.CHECKED } ?: VersionCheckStatus(CheckPhase.CHECKED,
            statuses.sumOf { it.updates }, statuses.sumOf { it.notices }, statuses.mapNotNull { it.expiresAtNanos }.minOrNull())
    }

    internal fun requestUpdate() { if (!disposed) requests.trySend(Unit) }

    override fun dispose() {
        disposed = true
        requests.close()
        scope.cancel()
        currentFile = null
        status = null
        super.dispose()
    }
}
