package io.github.khopland.versionchecker.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import io.github.khopland.versionchecker.VersionCheckerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Build files changed, or the cached versions were dropped, so a list of outdated versions is out of date too. */
fun interface VersionsChangedListener {
    fun versionsChanged()

    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC = Topic(VersionsChangedListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** Every outdated version in the project, for the **Dependency Updates** tool window. */
@Service(Service.Level.PROJECT)
class DependencyReport(private val project: Project, private val scope: CoroutineScope) {
    /**
     * One version to show: outdated, deprecated, or not checked. [line] counts from 1.
     * [source] follows later edits to the file, so it still finds the declaration before the next check.
     */
    class Row(val declaration: Declaration, val file: VirtualFile, val line: Int, val outcome: Outcome, val fixes: Map<UpdateKind, String>,
              val source: OpenFileDescriptor) {
        val updates get() = outcome as? Outcome.Updates
        val failure get() = (outcome as? Outcome.Failed)?.message

        /** The newest version that changes the [kind] number, as the editor warning lists it. */
        fun offered(kind: UpdateKind): String? {
            val targets = updates?.targets ?: return null
            return UpdateKind.entries.mapNotNull { targets[it] }.distinct().firstOrNull { Versions.kind(declaration.current, it) == kind }
        }

        /** Whether an update writes [version]. A shared version that its users disagree on cannot change. */
        fun canWrite(version: String) = version in fixes.values

        /** The same text as the editor warning. */
        val message: String get() =
            failure?.let { "Could not check ${declaration.name}: $it" } ?: VersionAnnotator.message(declaration, updates!!, fixes).orEmpty()
    }

    /** [checked] versions in the build [files]. [running] keeps the previous rows until the check finishes. */
    data class State(
        val rows: List<Row> = emptyList(),
        val checked: Int = 0,
        val files: Set<VirtualFile> = emptySet(),
        val running: Boolean = false,
        val done: Boolean = false,
        val disabled: Boolean = false,
    )

    private val state = MutableStateFlow(State())
    private var job: Job? = null

    val current: StateFlow<State> get() = state

    /** Checks every build file again. A check that is still running stops. */
    @Synchronized
    fun refresh(): Job {
        val previous = job
        return scope.launch {
            previous?.cancelAndJoin()
            if (!VersionCheckerSettings.getInstance().enabled) {
                state.value = State(disabled = true)
                return@launch
            }
            state.update { it.copy(running = true) }
            try {
                state.value = check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Could not check the project", e)
                state.update { it.copy(running = false, done = true) }
            }
        }.also { job = it }
    }

    private suspend fun check(): State {
        val checked = Updater.check(project, null)
        val rows = readAction {
            checked.declarations.mapNotNull { declaration ->
                val outcome = checked.outcomes[declaration] ?: return@mapNotNull null
                val shown = when (outcome) {
                    is Outcome.Failed -> true
                    is Outcome.Updates -> !outcome.targets.isEmpty || outcome.deprecated != null
                }
                if (!shown) return@mapNotNull null
                val file = checked.files.getValue(declaration)
                val line = FileDocumentManager.getInstance().getDocument(file)
                    ?.let { it.getLineNumber(declaration.range.startOffset.coerceIn(0, it.textLength)) + 1 } ?: 1
                Row(declaration, file, line, outcome, checked.fixes[declaration].orEmpty(), OpenFileDescriptor(project, file, declaration.range.startOffset))
            }
        }.sortedWith(compareBy<Row>({ it.failure != null }, { it.file.path }, { it.line }))
        return State(rows, checked.declarations.size, checked.scanned.toSet(), done = true)
    }

    /** Calls [listener] on the EDT with every new state, until [parent] is disposed. */
    fun watch(parent: Disposable, listener: (State) -> Unit) {
        val watcher = scope.launch(Dispatchers.EDT) { state.collect(listener) }
        Disposer.register(parent) { watcher.cancel() }
    }

    companion object {
        private val LOG = logger<DependencyReport>()

        fun getInstance(project: Project): DependencyReport = project.service()
    }
}
