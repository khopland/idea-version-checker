package io.github.khopland.versionchecker

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.dom.MavenDomUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class VersionCheckService(private val project: Project, private val scope: CoroutineScope) {
    private data class Fingerprint(val pomStamp: Long, val modelStamp: Long, val settingsHash: Int, val generation: Long)
    private data class Entry(val fingerprint: Fingerprint, val expiresAt: Long, val updates: Map<DependencyVersion, String>,
                             val relocations: Map<DependencyVersion, String> = emptyMap())
    private val cache = ConcurrentHashMap<String, Entry>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val generation = AtomicLong()
    // Avoid competing for a Maven embedder while checking several modules.
    private val scanMutex = Mutex()
    private val log = Logger.getInstance(VersionCheckService::class.java)

    internal fun relocations(mavenProject: MavenProject): Map<DependencyVersion, String> =
        cache[mavenProject.path]?.relocations.orEmpty()

    fun updates(mavenProject: MavenProject): Map<DependencyVersion, String> {
        if (!project.service<VersionCheckerSettings>().state.enabled) return emptyMap()
        val manager = MavenProjectsManager.getInstance(project)
        if (manager.generalSettings.isWorkOffline || manager.isIgnored(mavenProject)) return emptyMap()
        val fingerprint = Fingerprint(mavenProject.file.modificationStamp,
            manager.modificationTracker.modificationCount, manager.generalSettings.hashCode(), generation.get())
        val entry = cache[mavenProject.path]
        if (entry == null || entry.fingerprint != fingerprint || entry.expiresAt < System.nanoTime()) {
            schedule(manager, mavenProject, fingerprint)
        }
        return entry?.takeIf { it.fingerprint == fingerprint }?.updates.orEmpty()
    }

    internal suspend fun checkNow(mavenProject: MavenProject, mode: UpdateMode): Map<DependencyVersion, String> =
        scanMutex.withLock {
            val manager = MavenProjectsManager.getInstance(project)
            check(!manager.generalSettings.isWorkOffline) { "Maven is offline" }
            MavenVersionLookup.check(manager, mavenProject, mode)
        }

    fun refresh() {
        generation.incrementAndGet()
        cache.clear()
        val manager = MavenProjectsManager.getInstance(project)
        if (manager.generalSettings.isWorkOffline) {
            notify("Maven is offline. Disable Work offline in Maven settings to check remote versions.", NotificationType.INFORMATION)
            return
        }
        manager.nonIgnoredProjects.forEach { updates(it) }
        restartInspections()
    }

    private fun schedule(manager: MavenProjectsManager, mavenProject: MavenProject, fingerprint: Fingerprint) {
        if (!pending.add(mavenProject.path)) return
        scope.launch(Dispatchers.IO) {
            try {
                scanMutex.withLock {
                    if (project.isDisposed || manager.generalSettings.isWorkOffline) return@withLock
                    val updates = MavenVersionLookup.check(manager, mavenProject)
                    val relocations = readRelocations(mavenProject)
                    cache[mavenProject.path] = Entry(fingerprint, System.nanoTime() + TimeUnit.MINUTES.toNanos(10), updates, relocations)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.warn("Dependency version check failed for ${mavenProject.path}", failure)
                cache[mavenProject.path] = Entry(fingerprint, System.nanoTime() + TimeUnit.MINUTES.toNanos(1), emptyMap())
                notify("Could not check dependency versions for ${mavenProject.file.name}. Check Maven settings and repository access, then use Tools → Check Maven Dependency Versions to retry.", NotificationType.WARNING)
            } finally {
                pending.remove(mavenProject.path)
                restartInspections()
            }
        }
    }

    internal suspend fun readRelocations(mavenProject: MavenProject): Map<DependencyVersion, String> {
        val dependencies = readAction {
            val file = PsiManager.getInstance(project).findFile(mavenProject.file) ?: return@readAction emptyList()
            val model = MavenDomUtil.getMavenDomProjectModel(file) ?: return@readAction emptyList()
            val analysis = MavenDependencyAnalysis(model, mavenProject, emptyMap())
            PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).mapNotNull(analysis::coordinate).distinct()
        }
        return buildMap {
            for (dependency in dependencies) {
                try {
                    MavenRelocation.read(mavenProject.localRepositoryPath, dependency)?.let { put(dependency, it) }
                } catch (failure: Exception) {
                    log.debug("Could not read relocation metadata for $dependency", failure)
                }
            }
        }
    }

    private fun restartInspections() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) refreshEditorProblems(project, this)
        }
    }

    private fun notify(message: String, type: NotificationType) {
        if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("Maven Version Checker")
            .createNotification("Maven Version Checker", message, type).notify(project)
    }
}
