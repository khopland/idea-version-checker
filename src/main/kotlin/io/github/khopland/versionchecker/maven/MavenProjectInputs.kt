package io.github.khopland.versionchecker.maven

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.khopland.versionchecker.CheckPerformance
import io.github.khopland.versionchecker.core.BuildFingerprint
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A read-pass input view, never retained across repository work or preview application. */
internal class MavenProjectInputs(private val manager: MavenProjectsManager, private val external: External? = null,
                                 private val layoutIsCurrent: () -> Boolean = { true }) {
    internal data class External(val fingerprints: Map<Path, String>, val inventory: MavenInputInventory)

    companion object {
        /** External configuration can change without a VFS event, size change or new timestamp. */
        fun savedDiskFingerprint(path: Path): String =
            savedDiskFingerprint(path) { com.intellij.openapi.progress.ProgressManager.checkCanceled() }

        private fun savedDiskFingerprint(path: Path, checkCancellation: () -> Unit): String {
            checkCancellation()
            if (!Files.exists(path)) return "missing"
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkCancellation()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        /** Only immutable paths cross the disk phase; current IDE state is overlaid afterward. */
        suspend fun <T> capture(manager: MavenProjectsManager, readFingerprint: ((Path) -> String)? = null,
                                action: (MavenProjectInputs) -> T): T = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            repeat(3) {
                val layout = readAction { MavenInputInventory.layout(manager) }
                val inventory = MavenInputInventory.discover(layout) { context.ensureActive() }
                val external = External(inventory.externalPaths.associateWith { path ->
                    context.ensureActive()
                    readFingerprint?.invoke(path) ?: savedDiskFingerprint(path) { context.ensureActive() }
                }, inventory)
                context.ensureActive()
                val captured = readAction {
                    if (layout != MavenInputInventory.layout(manager)) null else Captured(action(MavenProjectInputs(manager, external) { layout == MavenInputInventory.layout(manager) }))
                }
                if (captured != null) return@withContext captured.value
            }
            error("Maven input paths changed during capture. Run the check again.")
        }
        private data class Captured<T>(val value: T)
    }
    val projects: List<MavenProject> = manager.nonIgnoredProjects.toList()
    private val byPath = projects.associateBy { it.path }
    private val diskFingerprints = mutableMapOf<Path, String>()
    private val fingerprints = mutableMapOf<Path, BuildFingerprint>()
    private val inspectionFingerprints = mutableMapOf<Path, BuildFingerprint>()
    private val readPass by lazy { MavenInputInventory.ReadPass(manager) }
    private val configuration by lazy {
        val policy = io.github.khopland.versionchecker.projectPolicy(manager.project).deprecationFingerprint
        "${manager.generalSettings.hashCode()}:${manager.explicitProfiles.hashCode()}:$policy"
    }
    private val shared by lazy {
        CheckPerformance.measure(CheckPerformance.Stage.MAVEN_PROJECT_INPUTS, projects.size) {
            val documents = FileDocumentManager.getInstance()
            // Include unselected modules: shared-property ownership depends on their saved/unsaved POMs.
            val files = projects.associate { pom ->
                pom.path to "${pom.file.modificationStamp}:${documents.getCachedDocument(pom.file)?.takeIf { documents.isDocumentUnsaved(it) }?.modificationStamp}"
            }.toMutableMap()
            (external?.inventory?.sharedPaths ?: readPass.sharedPaths).forEach { files[it.toString()] = diskFingerprint(it) }
            BuildFingerprint(files, "${manager.modificationTracker.modificationCount}:$configuration")
        }
    }

    fun find(path: String): MavenProject? = byPath[path]

    /** Overlay live imports/documents/settings without reusing the preparation read-pass caches. */
    fun currentView(): MavenProjectInputs? = if (layoutIsCurrent())
        MavenProjectInputs(manager, external, layoutIsCurrent) else null

    fun fingerprint(project: MavenProject): BuildFingerprint {
        val directory = project.file.toNioPath().parent
        return fingerprints.getOrPut(directory) {
            val files = shared.files.toMutableMap()
            val inputs = external?.inventory?.projectInputs(project) ?: readPass.projectInputs(project)
            val distribution = inputs.distribution
            inputs.paths.forEach { files[it.toString()] = diskFingerprint(it) }
            BuildFingerprint(files, "${shared.configuration}:${distribution.home}:${distribution.version}")
        }
    }

    /** POM edits trigger a new check but need not hide warnings for unchanged coordinates. */
    fun inspectionFingerprint(project: MavenProject): BuildFingerprint =
        inspectionFingerprints.getOrPut(project.file.toNioPath().parent) {
            BuildFingerprint(fingerprint(project).files.filterKeys { it !in byPath },
                "$configuration:${project.activatedProfilesIds.hashCode()}")
        }

    private fun diskFingerprint(path: Path): String = diskFingerprints.getOrPut(path) {
        val saved = external?.fingerprints?.getValue(path.toAbsolutePath().normalize()) ?: savedDiskFingerprint(path)
        val documents = FileDocumentManager.getInstance()
        val document = LocalFileSystem.getInstance().findFileByNioFile(path.toAbsolutePath())
            ?.let(documents::getCachedDocument)?.takeIf { documents.isDocumentUnsaved(it) }
        "$saved:${document?.modificationStamp}"
    }
}
