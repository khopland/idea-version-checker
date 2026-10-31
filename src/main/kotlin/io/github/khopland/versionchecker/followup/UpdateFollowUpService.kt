package io.github.khopland.versionchecker.followup

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.withBackgroundProgress
import io.github.khopland.versionchecker.npm.NpmBuildInputs
import io.github.khopland.versionchecker.npm.NpmRegistry
import io.github.khopland.versionchecker.npm.NpmWorkspaces
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
internal class UpdateFollowUpService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    @Volatile private var disposed = false
    override fun dispose() { disposed = true }
    private val active = AtomicBoolean(false)

    fun previewNpmSync() {
        if (disposed || project.isDisposed || !active.compareAndSet(false, true)) return
        val commandRequested = AtomicBoolean(false)
        var workspaceRoot: Path? = null
        fun reportMessage(message: String, warning: Boolean) =
            result(NpmFollowUpPresentation.withWorkspace(message, workspaceRoot, project.basePath), warning)
        fun report(outcome: NpmFollowUpOutcome) = reportMessage(outcome.message, outcome.warning)
        val job = scope.launch(Dispatchers.EDT) {
            var terminalReported = false
            try {
                if (!TrustedProjects.isProjectTrusted(project)) {
                    reportMessage("Trust the project before running npm lockfile synchronization. No command was started.", true)
                    return@launch
                }
                val selected = FileChooser.chooseFile(
                    FileChooserDescriptorFactory.createSingleFileDescriptor("json")
                        .withTitle("Choose package.json for npm lockfile synchronization"), project, null
                ) ?: run {
                    report(NpmFollowUpOutcome(NpmFollowUpOutcome.Phase.CANCELLED_BEFORE_START))
                    return@launch
                }
                val workspace = readAction {
                    require(selected.name == "package.json" && NpmWorkspaces.supportsManifest(project, selected)) {
                        "Choose a supported npm package.json inside this project. Yarn, pnpm and Bun projects are not supported."
                    }
                    NpmWorkspaces.resolve(project, selected)
                }
                val directory = Path.of(workspace.root.path)
                workspaceRoot = directory
                val inputs = (workspace.manifests.map { Path.of(it.path) } +
                    listOf(directory.resolve(".npmrc"), directory.resolve("package.json")) +
                    NpmLockfileSync.outputNames.map(directory::resolve)).distinct()
                requireNoUnsavedFiles(inputs)
                val prepared = withBackgroundProgress(project, "Preparing npm lockfile command", cancellable = true) {
                    val before = NpmBuildInputs.capture(project, { setOf(workspace.root.path) }) {
                        NpmBuildInputs.fingerprint(project, workspace, it)
                    }
                    val command = NpmRegistry.lockfileCommand(project, directory)
                    val outputs = withContext(Dispatchers.IO) {
                        val context = currentCoroutineContext()
                        NpmLockfileSync.capture(NpmLockfileSync.outputNames.map(directory::resolve)) { context.ensureActive() }
                    }
                    Triple(before, command, outputs)
                }
                val (before, command, outputs) = prepared
                val preview = "Working directory: $directory\n\n${command.commandLineString}\n\n" +
                    "Uses IntelliJ's configured Node.js and npm. npm configuration and registry access still apply. " +
                    "Synchronizes the root lockfile, including workspace dependencies. Lifecycle scripts are disabled. " +
                    "Installed dependencies are not synchronized. Lockfile writes are separate from declaration undo. " +
                    "Review the resulting version control diff."
                if (Messages.showOkCancelDialog(project, preview, "Preview npm lockfile synchronization",
                        "Synchronize lockfile", "Cancel", Messages.getQuestionIcon()) != Messages.OK) {
                    report(NpmFollowUpOutcome(NpmFollowUpOutcome.Phase.CANCELLED_BEFORE_START))
                    return@launch
                }
                requireNoUnsavedFiles(inputs)
                withBackgroundProgress(project, "Synchronizing npm lockfile", cancellable = true) {
                    check(!disposed && !project.isDisposed && TrustedProjects.isProjectTrusted(project)) { "Project trust or lifecycle changed after preview." }
                    val currentWorkspace = readAction {
                        check(selected.isValid && NpmWorkspaces.supportsManifest(project, selected))
                        NpmWorkspaces.resolve(project, selected)
                    }
                    check(currentWorkspace.root.path == workspace.root.path &&
                        currentWorkspace.manifests.map { it.path }.toSet() == workspace.manifests.map { it.path }.toSet()) {
                        "npm workspace membership changed after preview. Preview again."
                    }
                    val current = NpmBuildInputs.capture(project, { setOf(currentWorkspace.root.path) }) {
                        NpmBuildInputs.fingerprint(project, currentWorkspace, it)
                    }
                    check(before == current) { "Files or npm settings changed after preview. Preview again." }
                    withContext(Dispatchers.IO) {
                        val context = currentCoroutineContext()
                        check(outputs.isCurrent { context.ensureActive() }) { "Lockfiles changed after preview. Preview again." }
                    }
                    NpmFollowUpExecution.run(
                        execute = {
                            commandRequested.set(true)
                            NpmRegistry.execute(command).exitCode
                        },
                        inspect = {
                            withContext(Dispatchers.IO) {
                                val context = currentCoroutineContext()
                                context.ensureActive()
                                LocalFileSystem.getInstance().refreshNioFiles(inputs, true, false, null)
                                outputs.changed {
                                    context.ensureActive()
                                    check(!disposed && !project.isDisposed) { "Project was disposed during output inspection" }
                                }
                            }
                        },
                        isAlive = { !disposed && !project.isDisposed },
                        publish = { outcome -> report(outcome); terminalReported = true },
                    )
                }
            } catch (_: CancellationException) {
                if (!terminalReported) report(NpmFollowUpOutcome(if (commandRequested.get()) NpmFollowUpOutcome.Phase.CANCELLED
                    else NpmFollowUpOutcome.Phase.CANCELLED_BEFORE_START))
            } catch (_: ProcessCanceledException) {
                if (!terminalReported) report(NpmFollowUpOutcome(if (commandRequested.get()) NpmFollowUpOutcome.Phase.CANCELLED
                    else NpmFollowUpOutcome.Phase.CANCELLED_BEFORE_START))
            } catch (_: Exception) {
                if (!terminalReported) report(NpmFollowUpOutcome(if (commandRequested.get()) NpmFollowUpOutcome.Phase.FAILED
                    else NpmFollowUpOutcome.Phase.NOT_STARTED))
            }
        }
        job.invokeOnCompletion { active.set(false) }
    }

    private fun requireNoUnsavedFiles(paths: Collection<Path>) {
        val documents = FileDocumentManager.getInstance()
        val unsaved = documents.unsavedDocuments.mapNotNull { documents.getFile(it)?.path }.toSet()
        val roots = paths.filter { it.fileName.toString() == "package.json" }.mapNotNull { it.parent }.toSet()
        check(paths.none { it.toString() in unsaved } && roots.none {
            NpmBuildInputs.hasUnsavedResolutionInputs(project, it.toString(), unsaved)
        }) { "Save manifest, npm configuration and lockfiles before synchronizing." }
    }

    private fun result(message: String, failed: Boolean) {
        if (!disposed && !project.isDisposed) project.service<BuildReloadService>().publish(NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
            .createNotification("npm lockfile synchronization", message,
                if (failed) NotificationType.WARNING else NotificationType.INFORMATION))
    }
}
