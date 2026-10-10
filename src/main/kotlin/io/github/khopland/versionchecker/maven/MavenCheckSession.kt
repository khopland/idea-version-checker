package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.CheckPerformance
import org.jetbrains.idea.maven.project.MavenEmbeddersManager
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenEmbedderWrapper
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.nio.file.Path

/** One fresh embedder per scan, shared by its goals, without changing IDEA's import settings. */
internal suspend fun <T> withMavenCheckSession(
    manager: MavenProjectsManager, project: MavenProject, configuration: String? = null,
    action: suspend (MavenEmbedderWrapper) -> T
): T {
    currentCoroutineContext()[MavenScanSession]?.let { session ->
        if (configuration != null) return session.use(manager, project, configuration, action)
    }
    val embedders = MavenEmbeddersManager(manager.project)
    try {
        val embedder = CheckPerformance.measure(CheckPerformance.Stage.MAVEN_SESSION) {
            embedders.getEmbedder(project, MavenEmbeddersManager.FOR_DEPENDENCIES_RESOLVE)
        }
        try {
            return action(embedder)
        } finally {
            embedders.release(embedder)
        }
    } finally {
        embedders.reset()
    }
}

/** A short-lived pool belonging to one refresh/preview, never IDEA's import manager. */
internal class MavenScanSession : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MavenScanSession>
    private val lock = Any()
    private val mutex = Mutex()
    private var references = 1
    private var closed = false
    private var pool: MavenEmbeddersManager? = null
    private var configuration: String? = null
    private var repository: Path? = null

    fun retain() = synchronized(lock) { check(!closed) { "Maven scan session is closed" }; references++ }

    /** Called inside use's serialized action; only successful settings loads are retained. */
    suspend fun repository(load: suspend () -> Path): Path = repository ?: load().also { repository = it }

    suspend fun <T> use(manager: MavenProjectsManager, project: MavenProject, configuration: String,
                        action: suspend (MavenEmbedderWrapper) -> T): T {
        retain()
        try {
            return mutex.withLock {
                if (this.configuration != configuration) {
                    pool?.reset()
                    pool = MavenEmbeddersManager(manager.project)
                    this.configuration = configuration
                    repository = null
                }
                val pool = pool!!
                val embedder = CheckPerformance.measure(CheckPerformance.Stage.MAVEN_SESSION) {
                    pool.getEmbedder(project, MavenEmbeddersManager.FOR_DEPENDENCIES_RESOLVE)
                }
                try { action(embedder) } finally { pool.release(embedder) }
            }
        } finally { release() }
    }

    fun release() = synchronized(lock) {
        references--
        if (references == 0) { closed = true; pool?.reset(); pool = null }
    }
}

internal suspend fun <T> withMavenScanSession(action: suspend () -> T): T {
    if (currentCoroutineContext()[MavenScanSession] != null) return action()
    val session = MavenScanSession()
    try { return withContext(session) { action() } } finally { session.release() }
}
