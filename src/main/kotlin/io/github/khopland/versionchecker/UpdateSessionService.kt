package io.github.khopland.versionchecker

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
import com.intellij.ui.components.JBScrollPane
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JTextArea

/** Presentation snapshots only; no edit authority, native provider types, or persistent history. */
internal data class UpdateSessionSnapshot(
    val token: Long,
    val diffToken: Long?,
    val declarationNotice: String,
    val providers: Set<String>,
    val followUps: Map<String, String> = emptyMap(),
    val verification: Map<String, String> = emptyMap(),
) {
    fun withoutCurrentEvidence(): UpdateSessionSnapshot = copy(verification = verification.mapValues {
        "Unknown. Captured declarations changed or evidence is unavailable. Previous observation: ${it.value}"
    })
    fun summary(): String = buildString {
        append("Update session #$token\n\nDeclarations\n$declarationNotice\n")
        for (provider in providers.sorted()) {
            append("\n$provider\n")
            append(followUps[provider] ?: "No follow-up requested in this session.")
            append("\nHistorical verification observation (not revalidated): ")
            append(verification[provider] ?: "Unknown. The expected effective dependency version has not been confirmed.")
            append('\n')
        }
        append("\nReopen this summary to see newly reported follow-up results. Verify again after any edit, undo or import/configuration change. This is a historical update summary. Subsequent edits, undo and external tool changes are not tracked. ")
        append("Cancellation does not undo declaration changes or lockfile writes. Inspect current files and the build tool results before retrying.")
    }
}

internal class UpdateSessionStore(private val limit: Int = 8) {
    init { require(limit > 0) }
    private val entries = linkedMapOf<Long, UpdateSessionSnapshot>()
    private var next = 0L
    @Synchronized fun start(diffToken: Long?, notice: String, providers: Set<String>): Long {
        while (entries.size >= limit) entries.remove(entries.keys.first())
        val token = ++next
        entries[token] = UpdateSessionSnapshot(token, diffToken, notice.take(4096), providers.take(16).map { it.take(128) }.toSet())
        return token
    }
    @Synchronized fun get(token: Long): UpdateSessionSnapshot? = entries[token]
    @Synchronized fun latest(): Long? = entries.keys.lastOrNull()
    @Synchronized fun record(token: Long, provider: String, message: String, verification: Boolean = false) {
        val current = entries[token] ?: return // An expired action must not modify a newer session.
        if (provider !in current.providers) return
        entries[token] = if (verification) current.copy(verification = current.verification + (provider to "Recorded at ${java.time.Instant.now()}: ${message.take(8000)}"))
            else current.copy(followUps = current.followUps + (provider to message.take(8192)))
    }
    @Synchronized fun clear() = entries.clear()
}

@Service(Service.Level.PROJECT)
internal class UpdateSessionService(private val project: Project) : Disposable {
    private val store = UpdateSessionStore()
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
    @Synchronized fun recordFollowUp(token: Long?, provider: String, message: String) {
        if (!disposed && !project.isDisposed && token != null) store.record(token, provider, message)
    }
    @Synchronized fun recordVerification(token: Long?, provider: String, message: String) {
        if (!disposed && !project.isDisposed && token != null) store.record(token, provider, message, verification = true)
    }
    @Synchronized fun latest(): Long? = if (disposed || project.isDisposed) null else store.latest()
    fun show(token: Long? = latest()) {
        if (project.isDisposed || disposed) return
        val snapshot = token?.let(store::get)?.let { retained ->
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
            if (current) retained else retained.withoutCurrentEvidence()
        }
        object : DialogWrapper(project) {
            init { title = "Version Update Session"; init() }
            override fun createCenterPanel(): JComponent = JBScrollPane(JTextArea(
                snapshot?.summary() ?: "No retained update session is available. Sessions expire after eight subsequent updates and are cleared when the project closes.",
                24, 84).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; caretPosition = 0
                accessibleContext.accessibleName = "Historical version update session summary"
                accessibleContext.accessibleDescription = "Declaration changes, provider follow-up outcomes and historical verification observations" })
            override fun createActions(): Array<Action> = buildList {
                snapshot?.diffToken?.let { diff -> add(object : DialogWrapperAction("Review declaration diff") {
                    override fun doAction(event: java.awt.event.ActionEvent?) {
                        project.service<AppliedVersionChangesService>().show(diff)
                    }
                }) }
                add(okAction)
            }.toTypedArray()
        }.show()
    }
    @Synchronized override fun dispose() { disposed = true; store.clear() }
}

class ShowUpdateSessionAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabled = event.project != null }
    override fun actionPerformed(event: AnActionEvent) { event.project?.service<UpdateSessionService>()?.show() }
}
