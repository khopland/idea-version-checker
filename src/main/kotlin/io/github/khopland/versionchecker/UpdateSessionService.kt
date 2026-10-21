package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.BuildSystemAdapter
import io.github.khopland.versionchecker.followup.BuildReloadService
import io.github.khopland.versionchecker.followup.UpdateFollowUpService
import io.github.khopland.versionchecker.followup.NativeFileChanges
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import java.time.Instant
import javax.swing.Action
import javax.swing.JComponent

internal enum class UpdateStepKind { NATIVE_RELOAD, NPM_LOCKFILE_SYNC, VERSION_OBSERVATION }
internal enum class UpdateStepStatus { PREPARING, RUNNING, REQUESTED, COMPLETED, OBSERVED, FAILED, CANCELLED, TIMED_OUT, UNKNOWN, UNAVAILABLE }
internal data class UpdateStepToken(val session: Long, val step: Long)
internal data class UpdateSessionStep(
    val id: Long, val provider: String, val kind: UpdateStepKind, val status: UpdateStepStatus,
    val message: String, val updatedAt: Instant = Instant.now(), val nativeChanges: NativeFileChanges? = null,
)

/** Presentation snapshots only; no edit authority, native provider types, or persistent history. */
internal data class UpdateSessionSnapshot(
    val token: Long,
    val diffToken: Long?,
    val declarationNotice: String,
    val providers: Set<String>,
    val steps: List<UpdateSessionStep> = emptyList(),
    val verification: Map<String, String> = emptyMap(),
) {
    fun withoutCurrentEvidence(): UpdateSessionSnapshot = copy(
        verification = verification.mapValues {
            "Unknown. Captured declarations changed or evidence is unavailable. Previous observation: ${it.value}"
        },
        steps = steps.map { step ->
            if (step.kind == UpdateStepKind.VERSION_OBSERVATION && step.status == UpdateStepStatus.OBSERVED)
                step.copy(status = UpdateStepStatus.UNKNOWN,
                    message = "Unknown. Captured declarations changed or evidence is unavailable. Previous observation: ${step.message}")
            else step
        })
    fun summary(): String = buildString {
        append("Update session #$token\n\nDeclarations\n$declarationNotice\n")
        for (provider in providers.sorted()) {
            append("\n$provider\n")
            val providerSteps = steps.filter { it.provider == provider }
            if (providerSteps.isEmpty()) append("No follow-up requested in this session.")
            for (step in providerSteps) {
                append("Step #${step.id} · ${step.kind.name.lowercase().replace('_', ' ')} · ${step.status.name.lowercase().replace('_', ' ')} · ${step.updatedAt}\n")
                append(step.message).append('\n')
                step.nativeChanges?.let { append(it.description()).append('\n') }
            }
            append("\nHistorical verification observation (not revalidated): ")
            append(verification[provider] ?: "Unknown. The expected effective dependency version has not been confirmed.")
            append('\n')
        }
        append("\nThis summary refreshes while open. Verify again after any edit, undo or import/configuration change. This is a historical update summary. Subsequent edits, undo and external tool changes are not tracked. ")
        append("These follow-ups do not run builds or tests. Cancellation does not undo declaration changes or lockfile writes. Inspect current files and the build tool results before retrying.")
    }
}

internal class UpdateSessionStore(private val limit: Int = 8, private val nativeCharacters: Long = 2_000_000) {
    init { require(limit > 0 && nativeCharacters > 0) }
    private val entries = linkedMapOf<Long, UpdateSessionSnapshot>()
    private var next = 0L
    private var nextStep = 0L
    @Synchronized fun start(diffToken: Long?, notice: String, providers: Set<String>): Long {
        while (entries.size >= limit) entries.remove(entries.keys.first())
        val token = ++next
        entries[token] = UpdateSessionSnapshot(token, diffToken, notice.take(4096), providers.take(16).map { it.take(128) }.toSet())
        return token
    }
    @Synchronized fun get(token: Long): UpdateSessionSnapshot? = entries[token]
    @Synchronized fun latest(): Long? = entries.keys.lastOrNull()
    @Synchronized fun begin(token: Long, provider: String, kind: UpdateStepKind, message: String): UpdateStepToken? {
        val current = entries[token] ?: return null
        if (provider !in current.providers) return null
        val step = UpdateSessionStep(++nextStep, provider, kind, UpdateStepStatus.PREPARING, message.take(8192))
        entries[token] = current.copy(steps = (current.steps + step).takeLast(16))
        return UpdateStepToken(token, step.id)
    }
    @Synchronized fun update(token: UpdateStepToken, status: UpdateStepStatus, message: String, nativeChanges: NativeFileChanges? = null) {
        val current = entries[token.session] ?: return
        if (current.steps.none { it.id == token.step }) return
        val captured = nativeChanges?.copy(files = java.util.Collections.unmodifiableList(nativeChanges.files.toList()),
            omitted = java.util.Collections.unmodifiableList(nativeChanges.omitted.toList()))
        entries[token.session] = current.copy(steps = current.steps.map { step ->
            if (step.id == token.step) step.copy(status = status, message = message.take(8192), updatedAt = Instant.now(), nativeChanges = captured ?: step.nativeChanges) else step
        })
        // Evict historical text only; retain the typed outcome and explicit omission notice.
        while (entries.values.sumOf { session -> session.steps.sumOf { it.nativeChanges?.characters ?: 0 } } > nativeCharacters) {
            val oldest = entries.values.first { session -> session.steps.any { (it.nativeChanges?.characters ?: 0) > 0 } }
            val id = oldest.steps.first { (it.nativeChanges?.characters ?: 0) > 0 }.id
            entries[oldest.token] = oldest.copy(steps = oldest.steps.map { step ->
                if (step.id == id) step.copy(nativeChanges = step.nativeChanges!!.copy(files = emptyList(), captureUnavailable = true)) else step
            })
        }
    }
    @Synchronized fun retirePending(token: UpdateStepToken, message: String) {
        val step = entries[token.session]?.steps?.firstOrNull { it.id == token.step } ?: return
        if (step.status in setOf(UpdateStepStatus.PREPARING, UpdateStepStatus.RUNNING))
            update(token, UpdateStepStatus.CANCELLED, message)
    }
    @Synchronized fun observe(token: Long, provider: String, message: String) {
        val current = entries[token] ?: return
        if (provider !in current.providers) return
        entries[token] = current.copy(verification = current.verification +
            (provider to "Recorded at ${Instant.now()}: ${message.take(8000)}"))
    }
    @Synchronized fun clear() = entries.clear()
}

@Service(Service.Level.PROJECT)
internal class UpdateSessionService(private val project: Project) : Disposable {
    private val store = UpdateSessionStore()
    private val dialogs = linkedSetOf<DialogWrapper>()
    @Volatile private var disposed = false
    @Synchronized fun start(diffToken: Long?, changes: AppliedVersionChanges?, providers: Set<String>): Long? {
        if (disposed || project.isDisposed) return null
        val notice = when {
            changes?.captureUnavailable == true -> "Declaration changes were applied; historical snapshots could not be captured."
            changes == null -> "Declaration changes were applied; no historical file snapshots are available."
            else -> "${changes.files.size} changed declaration files captured; ${changes.omittedTargetFiles} targeted files omitted by snapshot limits."
        }
        return store.start(diffToken, notice, providers)
    }
    @Synchronized fun beginStep(token: Long?, provider: String, kind: UpdateStepKind, message: String): UpdateStepToken? =
        if (disposed || project.isDisposed || token == null) null else store.begin(token, provider, kind, message)
    @Synchronized fun recordStep(token: UpdateStepToken?, status: UpdateStepStatus, message: String, nativeChanges: NativeFileChanges? = null) {
        if (!disposed && !project.isDisposed && token != null) store.update(token, status, message, nativeChanges)
    }
    @Synchronized fun retirePendingStep(token: UpdateStepToken?, message: String) {
        if (!disposed && !project.isDisposed && token != null) store.retirePending(token, message)
    }
    @Synchronized fun recordVerification(token: Long?, provider: String, message: String) {
        if (!disposed && !project.isDisposed && token != null) store.observe(token, provider, message)
    }
    @Synchronized fun latest(): Long? = if (disposed || project.isDisposed) null else store.latest()
    @Synchronized internal fun snapshot(token: Long): UpdateSessionSnapshot? =
        if (disposed || project.isDisposed) null else store.get(token)
    fun showNativeChanges(token: Long) {
        if (disposed || project.isDisposed) return
        val steps = snapshot(token)?.steps.orEmpty().filter { it.kind == UpdateStepKind.NPM_LOCKFILE_SYNC }
        val requests = nativeRequests(project, token, steps)
        val details = steps.mapNotNull { it.nativeChanges?.description() }
        if (requests.isEmpty()) {
            Messages.showInfoMessage(project, details.joinToString("\n\n").ifEmpty {
                "No historical npm lockfile snapshots are retained for this session. Native writes remain separate from declaration undo."
            }, "Native File Review")
        } else {
            DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain(requests), com.intellij.diff.DiffDialogHints.DEFAULT)
        }
    }
    fun show(token: Long? = latest()) {
        if (project.isDisposed || disposed) return
        fun presented(retained: UpdateSessionSnapshot): UpdateSessionSnapshot {
            val captures = retained.diffToken?.let { project.service<AppliedVersionChangesService>().captured(it) }
            val current = captures != null && !captures.captureUnavailable && captures.omittedTargetFiles == 0 &&
                captures.files.isNotEmpty() && ApplicationManager.getApplication().runReadAction<Boolean> {
                    val documents = FileDocumentManager.getInstance()
                    captures.files.all { captured ->
                        LocalFileSystem.getInstance().findFileByPath(captured.path)?.let { file ->
                            documents.getCachedDocument(file)?.let { document ->
                                document.textLength == captured.after.length && document.immutableCharSequence.toString() == captured.after
                            } == true
                        } == true
                    }
                }
            return if (current) retained else retained.withoutCurrentEvidence()
        }
        val snapshot = token?.let(::snapshot)?.let(::presented)
        val mavenActions = MavenSessionFollowUp.availability(snapshot?.providers.orEmpty(),
            BuildSystemAdapter.find("maven") != null,
            snapshot?.diffToken?.let { project.service<AppliedVersionChangesService>().captured(it) })
        val dialog = object : DialogWrapper(project) {
            init { title = "Version Update Session"; isModal = false; init() }
            override fun createCenterPanel(): JComponent = UpdateSessionSummaryPanel(disposable,
                { token?.let(this@UpdateSessionService::snapshot) }, ::presented)
            override fun createActions(): Array<Action> = buildList {
                snapshot?.diffToken?.let { diff -> add(object : DialogWrapperAction("Review declaration diff") {
                    override fun doAction(event: java.awt.event.ActionEvent?) {
                        project.service<AppliedVersionChangesService>().show(diff)
                    }
                }) }
                if (snapshot != null && mavenActions.reload) add(object : DialogWrapperAction("Reload Maven") {
                    override fun doAction(event: java.awt.event.ActionEvent?) {
                        if (!project.isDisposed) project.service<BuildReloadService>().reload("maven", snapshot.token)
                    }
                })
                if (snapshot != null && "gradle" in snapshot.providers && BuildSystemAdapter.find("gradle") != null)
                    add(object : DialogWrapperAction("Reload Gradle") {
                        override fun doAction(event: java.awt.event.ActionEvent?) {
                            if (!project.isDisposed && BuildSystemAdapter.find("gradle") != null)
                                project.service<BuildReloadService>().reload("gradle", snapshot.token)
                        }
                    })
                if (snapshot?.diffToken != null && mavenActions.verify) add(object : DialogWrapperAction("Verify Maven versions") {
                    override fun doAction(event: java.awt.event.ActionEvent?) {
                        MavenSessionFollowUp.verify(project, snapshot.diffToken, snapshot.token)
                    }
                })
                if (snapshot != null && "npm" in snapshot.providers && BuildSystemAdapter.find("npm") != null)
                    add(object : DialogWrapperAction("Preview npm lockfile sync") {
                        override fun doAction(event: java.awt.event.ActionEvent?) {
                            if (!project.isDisposed && BuildSystemAdapter.find("npm") != null)
                                project.service<UpdateFollowUpService>().previewNpmSync(snapshot.token)
                        }
                    })
                if (snapshot != null && "npm" in snapshot.providers) add(object : DialogWrapperAction("Review npm lockfile diff") {
                    override fun doAction(event: java.awt.event.ActionEvent?) { showNativeChanges(snapshot.token) }
                })
                add(okAction)
            }.toTypedArray()
        }
        synchronized(this) {
            if (disposed || project.isDisposed) { dialog.close(DialogWrapper.CANCEL_EXIT_CODE); return }
            dialogs.add(dialog)
        }
        Disposer.register(dialog.disposable) { synchronized(this) { dialogs.remove(dialog) } }
        dialog.show()
    }
    companion object {
        internal fun nativeRequests(project: Project, token: Long, steps: List<UpdateSessionStep>): List<SimpleDiffRequest> {
            val factory = DiffContentFactory.getInstance()
            return steps.flatMap { step -> step.nativeChanges?.let { changes ->
                val limits = buildList {
                    if (changes.omitted.isNotEmpty()) add("${changes.omitted.size} files omitted; see session summary")
                    if (changes.captureUnavailable) add("capture incomplete")
                    if (changes.editorChangesObserved) add("unsaved editor changes excluded")
                    if (!changes.processTerminationKnown) add("process termination unknown; later writes may be missing")
                }.joinToString("; ").let { if (it.isEmpty()) "" else "; $it" }
                changes.files.map { file ->
                    val type = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                        .getFileTypeByFileName(file.path.substringAfterLast('/'))
                    SimpleDiffRequest("Observed npm lockfile changes · session #$token step #${step.id}: ${file.path} (historical; concurrent writes may be included$limits)",
                        factory.create(project, file.before.orEmpty(), type), factory.create(project, file.after.orEmpty(), type),
                        if (file.before == null) "Before command (file absent)" else "Before command (captured)",
                        if (file.after == null) "After inspection (file absent)" else "After inspection (captured)").apply {
                        putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
                    }
                }
            }.orEmpty() }
        }
    }
    override fun dispose() {
        val retained = synchronized(this) {
            disposed = true
            store.clear()
            dialogs.toList().also { dialogs.clear() }
        }
        val close = Runnable { retained.forEach { if (!it.isDisposed) it.close(DialogWrapper.CANCEL_EXIT_CODE) } }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) close.run() else application.invokeLater(close, com.intellij.openapi.application.ModalityState.any())
    }
}

class ShowUpdateSessionAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabled = event.project != null }
    override fun actionPerformed(event: AnActionEvent) { event.project?.service<UpdateSessionService>()?.show() }
}
