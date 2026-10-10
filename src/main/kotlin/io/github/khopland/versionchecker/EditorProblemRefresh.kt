package io.github.khopland.versionchecker

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/** Refresh standard inspection highlighting after project settings change. */
internal fun refreshEditorProblems(project: Project, reason: Any) {
    DaemonCodeAnalyzer.getInstance(project).restart(reason)
}

/** Fixed windows combine completions; selected files can be promoted out of the slower batch. */
internal class FileProblemRefreshQueue(
    private val scope: CoroutineScope,
    private val windowMillis: Long = 200,
    private val activeWindowMillis: Long = 50,
    private val refresh: suspend (Set<String>) -> Unit,
) {
    private val lock = Any()
    private val paths = mutableSetOf<String>()
    private val activePaths = mutableSetOf<String>()
    private var worker: Job? = null
    private var activeWorker: Job? = null

    fun request(path: String, active: Boolean = false) = synchronized(lock) {
        if (active) {
            paths.remove(path)
            activePaths += path
            if (activeWorker == null) activeWorker = schedule(active = true)
        } else if (path !in activePaths) {
            paths += path
            if (worker == null) worker = schedule(active = false)
        }
    }

    private fun schedule(active: Boolean): Job {
        val queued = System.nanoTime()
        return scope.launch {
            delay((if (active) activeWindowMillis else windowMillis).milliseconds)
            val batch = synchronized(lock) {
                val pending = if (active) activePaths else paths
                pending.toSet().also {
                    pending.clear()
                    if (active) activeWorker = null else worker = null
                }
            }
            if (batch.isEmpty()) return@launch
            try {
                CheckPerformance.measure(CheckPerformance.Stage.HIGHLIGHT_RESTART, batch.size) { refresh(batch) }
            } finally { CheckPerformance.record(CheckPerformance.Stage.HIGHLIGHT_QUEUE, queued, batch.size) }
        }
    }
}

/** Repository results affect their source files; settings changes still refresh the whole project. */
@Service(Service.Level.PROJECT)
internal class FileProblemRefresh(private val project: Project, private val scope: CoroutineScope) {
    private val traces = mutableMapOf<String, Pair<CheckPerformance.Interaction, Long>>() // EDT only.
    private val queue = FileProblemRefreshQueue(scope) { paths ->
        val sources = withContext(Dispatchers.Default) {
            readAction {
                if (project.isDisposed) emptyList() else {
                    val psi = PsiManager.getInstance(project)
                    val roots = ProjectRootManager.getInstance(project).contentRoots
                    paths.mapNotNull { path ->
                        val file = LocalFileSystem.getInstance().findFileByPath(path)
                            ?: roots.firstNotNullOfOrNull { it.fileSystem.findFileByPath(path) }
                        file?.takeIf { it.isValid }?.let(psi::findFile)?.let { path to it }
                    }
                }
            }
        }
        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) {
                val daemon = DaemonCodeAnalyzer.getInstance(project)
                for ((path, source) in sources) {
                    if (source.isValid) {
                        val trace = traces.remove(path)
                        CheckPerformance.locally(trace?.first) {
                            trace?.let { CheckPerformance.record(CheckPerformance.Stage.HIGHLIGHT_QUEUE, it.second) }
                            CheckPerformance.measure(CheckPerformance.Stage.HIGHLIGHT_RESTART) { daemon.restart(source, this@FileProblemRefresh) }
                        }
                    }
                }
            }
        }
    }

    fun request(path: String) {
        scope.launch(Dispatchers.EDT + CheckPerformance.context()) {
            if (!project.isDisposed) {
                val active = FileEditorManager.getInstance(project).selectedFiles.any { it.path == path }
                CheckPerformance.current()?.let { traces.putIfAbsent(path, it to System.nanoTime()) }
                queue.request(path, active)
            }
        }
    }
}
