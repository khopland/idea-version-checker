package io.github.khopland.versionchecker

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
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

/** A fixed window combines nearby completions without postponing highlighting during a long scan. */
internal class FileProblemRefreshQueue(
    private val scope: CoroutineScope,
    private val windowMillis: Long = 200,
    private val refresh: suspend (Set<String>) -> Unit,
) {
    private val lock = Any()
    private val paths = mutableSetOf<String>()
    private var worker: Job? = null

    fun request(path: String) = synchronized(lock) {
        paths += path
        if (worker == null) {
            val queued = System.nanoTime()
            worker = scope.launch {
                delay(windowMillis.milliseconds)
                val batch = synchronized(lock) {
                    paths.toSet().also { paths.clear(); worker = null }
                }
                try {
                    CheckPerformance.measure(CheckPerformance.Stage.HIGHLIGHT_RESTART, batch.size) { refresh(batch) }
                } finally { CheckPerformance.record(CheckPerformance.Stage.HIGHLIGHT_QUEUE, queued, batch.size) }
            }
        }
    }
}

/** Repository results affect their source files; settings changes still refresh the whole project. */
@Service(Service.Level.PROJECT)
internal class FileProblemRefresh(private val project: Project, scope: CoroutineScope) {
    private val queue = FileProblemRefreshQueue(scope) { paths ->
        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) ReadAction.run<RuntimeException> {
                val psi = PsiManager.getInstance(project)
                val daemon = DaemonCodeAnalyzer.getInstance(project)
                for (path in paths) {
                    val file = LocalFileSystem.getInstance().findFileByPath(path)
                        ?: ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { it.fileSystem.findFileByPath(path) }
                    file?.takeIf { it.isValid }?.let(psi::findFile)?.let { daemon.restart(it, this@FileProblemRefresh) }
                }
            }
        }
    }

    fun request(path: String) { queue.request(path) }
}
