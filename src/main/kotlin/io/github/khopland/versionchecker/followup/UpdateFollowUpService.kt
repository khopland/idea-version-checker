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
import io.github.khopland.versionchecker.UpdateSessionService
import io.github.khopland.versionchecker.UpdateStepKind
import io.github.khopland.versionchecker.UpdateStepStatus
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

    fun previewNpmSync(sessionToken: Long? = null) {
        if (disposed || project.isDisposed || !active.compareAndSet(false, true)) return
        val sessions = project.service<UpdateSessionService>()
        val step = sessions.beginStep(sessionToken, "npm", UpdateStepKind.NPM_LOCKFILE_SYNC,
            "Preparing npm lockfile synchronization; command not yet started.")
        val commandRequested = AtomicBoolean(false)
        var workspaceRoot: Path? = null
        fun reportMessage(message: String, warning: Boolean, status: UpdateStepStatus = UpdateStepStatus.UNKNOWN, changes: NativeFileChanges? = null) {
            val attributed = NpmFollowUpPresentation.withWorkspace(message, workspaceRoot, project.basePath)
            if (!disposed && !project.isDisposed)
                sessions.recordStep(step, status, attributed, changes)
            result(attributed, warning)
        }
        fun report(outcome: NpmFollowUpOutcome, changes: NativeFileChanges? = null) {
            val status = when (outcome.phase) {
                NpmFollowUpOutcome.Phase.NOT_STARTED -> UpdateStepStatus.UNAVAILABLE
                NpmFollowUpOutcome.Phase.CANCELLED_BEFORE_START, NpmFollowUpOutcome.Phase.CANCELLED -> UpdateStepStatus.CANCELLED
                NpmFollowUpOutcome.Phase.COMPLETED -> UpdateStepStatus.COMPLETED
                NpmFollowUpOutcome.Phase.TIMED_OUT -> UpdateStepStatus.TIMED_OUT
                NpmFollowUpOutcome.Phase.FAILED -> if (outcome.exitCode == null) UpdateStepStatus.UNKNOWN else UpdateStepStatus.FAILED
            }
            val observed = changes?.copy(processTerminationKnown = outcome.phase == NpmFollowUpOutcome.Phase.COMPLETED || outcome.exitCode != null)
            reportMessage(outcome.message, outcome.warning, status, observed)
        }
        val job = scope.launch(Dispatchers.EDT) {
            var terminalReported = false
            try {
                if (!TrustedProjects.isProjectTrusted(project)) {
                    reportMessage("Trust the project before running npm lockfile synchronization. No command was started; dependency verification was not performed.", true, UpdateStepStatus.UNAVAILABLE)
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
                sessions.recordStep(step, UpdateStepStatus.PREPARING,
                    NpmFollowUpPresentation.withWorkspace("Preparing npm lockfile command; command not yet started.", directory, project.basePath))
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
                        val paths = NpmLockfileSync.outputNames.map(directory::resolve)
                        val hashes = NpmLockfileSync.capture(paths) { context.ensureActive() }
                        hashes to NativeFileChangeCapture.before(paths, expectedHashes = hashes.files,
                            checkCancellation = { context.ensureActive() })
                    }
                    Triple(before, command, outputs)
                }
                val (before, command, capturedOutputs) = prepared
                val (outputs, nativeBefore) = capturedOutputs
                var nativeChanges: NativeFileChanges? = null
                val preview = "Working directory: $directory\n\n${command.commandLineString}\n\n" +
                    "Uses IntelliJ's configured Node.js and npm. npm configuration and registry access still apply. " +
                    "Synchronizes the root lockfile, including workspace dependencies. Lifecycle scripts are disabled. " +
                    "Installed dependencies are not synchronized. Lockfile writes are separate from declaration undo. " +
                    "A separate historical lockfile diff will capture supported UTF-8 outputs within size limits. Concurrent external edits may be included. Review the resulting version control diff."
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
                            sessions.recordStep(step, UpdateStepStatus.RUNNING,
                                NpmFollowUpPresentation.withWorkspace(
                                    "npm lockfile synchronization command start requested. Completion and lockfile changes have not yet been observed. " +
                                        "Lockfile writes may occur. Dependency verification was not performed.", directory, project.basePath))
                            NpmRegistry.execute(command).exitCode
                        },
                        inspect = {
                            withContext(Dispatchers.IO) {
                                val context = currentCoroutineContext()
                                context.ensureActive()
                                LocalFileSystem.getInstance().refreshNioFiles(inputs, true, false, null)
                                val changed = outputs.changed {
                                    context.ensureActive()
                                    check(!disposed && !project.isDisposed) { "Project was disposed during output inspection" }
                                }
                                nativeChanges = nativeBefore.finish { context.ensureActive() }.copy(
                                    editorChangesObserved = readAction {
                                        val documents = FileDocumentManager.getInstance()
                                        NpmLockfileSync.outputNames.map(directory::resolve).any { path ->
                                            LocalFileSystem.getInstance().findFileByNioFile(path)?.let(documents::getCachedDocument)
                                                ?.let(documents::isDocumentUnsaved) == true
                                        }
                                    })
                                changed
                            }
                        },
                        isAlive = { !disposed && !project.isDisposed },
                        publish = { outcome -> report(outcome, nativeChanges ?: NativeFileChanges(captureUnavailable = true)); terminalReported = true },
                    )
                }
            } catch (_: CancellationException) {
                if (!terminalReported) report(NpmFollowUpOutcome(if (commandRequested.get()) NpmFollowUpOutcome.Phase.CANCELLED
                    else NpmFollowUpOutcome.Phase.CANCELLED_BEFORE_START),
                    if (commandRequested.get()) NativeFileChanges(captureUnavailable = true) else null)
            } catch (_: ProcessCanceledException) {
                if (!terminalReported) report(NpmFollowUpOutcome(if (commandRequested.get()) NpmFollowUpOutcome.Phase.CANCELLED
                    else NpmFollowUpOutcome.Phase.CANCELLED_BEFORE_START),
                    if (commandRequested.get()) NativeFileChanges(captureUnavailable = true) else null)
            } catch (_: Exception) {
                if (!terminalReported) report(NpmFollowUpOutcome(if (commandRequested.get()) NpmFollowUpOutcome.Phase.FAILED
                    else NpmFollowUpOutcome.Phase.NOT_STARTED),
                    if (commandRequested.get()) NativeFileChanges(captureUnavailable = true) else null)
            }
        }
        job.invokeOnCompletion {
            active.set(false)
            sessions.retirePendingStep(step, NpmFollowUpPresentation.withWorkspace(
                NpmFollowUpOutcome(if (commandRequested.get()) NpmFollowUpOutcome.Phase.CANCELLED
                    else NpmFollowUpOutcome.Phase.CANCELLED_BEFORE_START).message, workspaceRoot, project.basePath))
        }
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
