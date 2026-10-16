package io.github.khopland.versionchecker

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/** Notification actions retain tokens only; event-log notifications cannot retain unbounded file text. */
internal class AppliedVersionChangesStore(private val records: Int = 8, private val characters: Long = 2_000_000,
                                         private val filesPerRecord: Int = 50) {
    init { require(records > 0 && characters > 0 && filesPerRecord > 0) }
    private val entries = linkedMapOf<Long, AppliedVersionChanges>()
    private var next = 0L
    @Synchronized fun retain(changes: AppliedVersionChanges): Long? {
        val empty = changes.files.isEmpty() && changes.omittedTargetFiles == 0 && !changes.captureUnavailable
        if (empty || changes.files.size > filesPerRecord || changes.textCharacters > characters) return null
        while (entries.size >= records || entries.values.sumOf { it.textCharacters } + changes.textCharacters > characters)
            entries.remove(entries.keys.first())
        return (++next).also { entries[it] = changes }
    }
    @Synchronized fun get(token: Long): AppliedVersionChanges? = entries[token]
    @Synchronized fun clear() = entries.clear()
}

@Service(Service.Level.PROJECT)
internal class AppliedVersionChangesService(private val project: Project) : Disposable {
    private val store = AppliedVersionChangesStore()
    @Volatile private var disposed = false
    @Synchronized fun retain(changes: AppliedVersionChanges): Long? = if (disposed || project.isDisposed) null else store.retain(changes)
    fun show(token: Long) {
        if (disposed || project.isDisposed) return
        val changes = store.get(token)
        if (changes == null) {
            Messages.showInfoMessage(project, "This captured update review is no longer available. Inspect the current build files or use the IDE's local history.", "Version Update Review")
            return
        }
        if (changes.files.isEmpty()) {
            Messages.showInfoMessage(project,
                if (changes.captureUnavailable) "The declaration update was applied, but its historical file snapshots could not be captured. Inspect current build files or use the IDE's local history."
                else "${changes.omittedTargetFiles} targeted declaration files exceeded the snapshot limits. No historical file text is retained for this update. Inspect current build files or use the IDE's local history.",
                "Version Update Review")
            return
        }
        DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain(requests(project, changes)), com.intellij.diff.DiffDialogHints.DEFAULT)
    }
    @Synchronized override fun dispose() { disposed = true; store.clear() }

    companion object {
        internal fun requests(project: Project, changes: AppliedVersionChanges): List<SimpleDiffRequest> {
            val factory = DiffContentFactory.getInstance()
            val omissions = if (changes.omittedTargetFiles == 0) "" else
                "; ${changes.omittedTargetFiles} additional targeted files omitted by snapshot limits"
            return changes.files.map { file ->
                val type = FileTypeManager.getInstance().getFileTypeByFileName(file.path.substringAfterLast('/'))
                SimpleDiffRequest("Captured version update: ${file.path} (historical snapshots, not current files)$omissions",
                    factory.create(project, file.before, type), factory.create(project, file.after, type),
                    "Before declaration update (captured)", "After declaration update (captured)").apply {
                    putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
                }
            }
        }
    }
}
