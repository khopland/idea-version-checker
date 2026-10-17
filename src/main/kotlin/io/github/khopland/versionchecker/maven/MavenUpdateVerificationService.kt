package io.github.khopland.versionchecker.maven

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import io.github.khopland.versionchecker.*
import kotlinx.coroutines.*
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.MavenDomProjectProcessorUtils
import org.jetbrains.idea.maven.project.*
import java.nio.file.Path
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

/** Native resolution evidence is bound to the exact saved inputs prepared for an explicit reload. */
@Service(Service.Level.PROJECT)
internal class MavenUpdateVerificationService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private data class Context(val layout: MavenInputInventory.Layout, val parents: Set<String>,
                               val settings: MavenGeneralSettings, val profiles: Set<String>, val disabledProfiles: Set<String>,
                               val documents: Map<String, String>)
    private data class Inputs(val context: Context, val disk: Map<String, String>, val documents: Map<String, String>, val normalizedPoms: Map<String, String>)
    private data class Resolution(val generation: Long, val inputs: Inputs?, val versions: Map<String, Set<String>>)
    private val manager = MavenProjectsManager.getInstance(project)
    private val resolutions = linkedMapOf<String, Resolution>()
    private var generation = 0L
        private var disposed = false

    init {
        manager.addProjectsTreeListener(object : MavenProjectsTree.Listener {
            override fun projectResolved(projectWithChanges: Pair<MavenProject, MavenProjectChanges>,
                                         nativeMavenProject: org.jetbrains.idea.maven.server.NativeMavenProjectHolder?) {
                ApplicationManager.getApplication().runReadAction {
                    synchronized(this@MavenUpdateVerificationService) {
                        if (disposed || project.isDisposed) return@runReadAction
                        val pom = projectWithChanges.first
                        val versions = if (pom.hasReadingErrors() || pom.hasUnresolvedArtifacts()) emptyMap() else
                            pom.dependencies.groupBy { "${it.groupId}:${it.artifactId}" }
                                .mapValues { (_, artifacts) -> artifacts.map { it.version }.toSet() }
                        while (resolutions.size >= 128 && pom.path !in resolutions) resolutions.remove(resolutions.keys.first())
                        resolutions[pom.path] = Resolution(++generation, null, versions)
                    }
                }
            }
        }, this)
    }

    @Synchronized fun generation(): Long = generation

    /** Only completion of this exact native full-import request can authorize a model snapshot. */
    internal suspend fun reloadAndCapture(reload: (suspend () -> Unit)? = null) {
        synchronized(this) { resolutions.clear() }
        val before = try { captureInputs() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (reload != null) reload() else manager.updateAllMavenProjects(
            org.jetbrains.idea.maven.buildtool.MavenSyncSpec.full("Version Checker effective-version verification"))
        val completedGeneration = generation()
        val after = try { captureInputs() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        readAction {
            synchronized(this) {
                if (disposed || before == null || before != after || generation != completedGeneration) return@readAction
                // An unrelated or late resolution during capture invalidates evidence; its event
                // can never be relabeled with a newer request's input snapshot.
                for (pom in manager.nonIgnoredProjects.take(128)) {
                    val versions = if (pom.hasReadingErrors() || pom.hasUnresolvedArtifacts()) emptyMap() else
                        pom.dependencies.groupBy { "${it.groupId}:${it.artifactId}" }
                            .mapValues { (_, artifacts) -> artifacts.map { it.version }.toSet() }
                    resolutions[pom.path] = Resolution(++generation, before, versions)
                }
            }
        }
    }

    fun verify(changes: AppliedVersionChanges, publish: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val message = try {
                withBackgroundProgress(project, "Verifying Maven effective versions", cancellable = true) { evaluate(changes) }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "Unknown: Maven verification inputs could not be read. Reload and verify again." }
            withContext(Dispatchers.EDT) {
                if (!project.isDisposed) {
                    publish(message)
                    Messages.showInfoMessage(project, message, "Maven Effective Versions")
                }
            }
        }
    }

    internal suspend fun evaluate(changes: AppliedVersionChanges): String {
        val current = captureInputs()
        return readAction {
            synchronized(this) {
                if (disposed) return@readAction "Unknown: this Maven verification session has expired."
                val capturesCurrent = !changes.captureUnavailable && changes.omittedTargetFiles == 0 && changes.files.isNotEmpty() &&
                    changes.files.all { captured ->
                        val file = LocalFileSystem.getInstance().findFileByPath(captured.path)
                        file != null && FileDocumentManager.getInstance().getDocument(file)?.text == captured.after
                    }
                val results = changes.mavenVerificationTargets.map { target ->
                    val resolution = resolutions[target.pomPath]
                    // A resolution prepared before the edit cannot match its saved after-text, even if
                    // its event arrives after application. Stamps alone cannot prove this correlation.
                    val savedAfterMatches = changes.files.all { captured ->
                        resolution?.inputs?.normalizedPoms?.get(captured.path) == hash(captured.after.toByteArray(Charsets.UTF_8))
                    }
                    val fresh = current != null && current.context == context() &&
                        current.documents == documentStamps(current.disk.keys) && capturesCurrent &&
                        resolution?.inputs == current && savedAfterMatches
                    val result = if (resolution == null || resolution.inputs == null)
                        EffectiveVersionResult(EffectiveVersionStatus.UNKNOWN,
                            "No resolution with captured reload inputs. Use Save and reload Maven projects, wait for completion, then verify again.")
                    else compareEffectiveVersion(target, resolution.generation, fresh,
                        resolution.versions["${target.groupId}:${target.artifactId}"])
                    "${result.status.name.lowercase().replaceFirstChar { it.uppercase() }}: ${target.groupId}:${target.artifactId} (${target.pomPath}) — ${result.detail}"
                }
                (listOf("Maven effective-version verification (imported dependency model; does not run tests):") + results).joinToString("\n")
            }
        }
    }

    /** Physical input content is read only on IO, outside every read action, including unchanged mtimes. */
    private suspend fun captureInputs(): Inputs? = withContext(Dispatchers.IO) {
        val before = readAction { if (disposed || project.isDisposed) null else context() } ?: return@withContext null
        val coroutine = currentCoroutineContext()
        val inventory = MavenInputInventory.discover(before.layout) { coroutine.ensureActive() }
        val paths = inventory.resolutionInputPaths() + before.parents
        val documents = readAction { documentStamps(paths) }
        val charsets = readAction { (before.layout.poms + before.parents).associateWith {
            LocalFileSystem.getInstance().findFileByPath(it)?.charset ?: Charsets.UTF_8
        } }
        val normalized = mutableMapOf<String, String>()
        val disk = paths.associateWith { path ->
            coroutine.ensureActive()
            if (path in charsets && Files.exists(Path.of(path)) && Files.size(Path.of(path)) <= 2_000_000) {
                val bytes = Files.readAllBytes(Path.of(path))
                normalized[path] = hash(String(bytes, charsets.getValue(path)).removePrefix("\uFEFF")
                    .replace("\r\n", "\n").replace('\r', '\n').toByteArray(Charsets.UTF_8))
                hash(bytes)
            } else MavenProjectInputs.savedDiskFingerprint(Path.of(path))
        }
        val after = readAction { if (disposed || project.isDisposed) null else context() }
        val afterDocuments = readAction { documentStamps(paths) }
        if (before != after || documents != afterDocuments || documents.values.any { it.endsWith(":unsaved") }) null
        else Inputs(before, disk, documents, normalized)
    }

    /** IDE-only context capture; physical files are fingerprinted separately on IO. */
    private fun context(): Context {
        val layout = MavenInputInventory.layout(manager)
        val parents = manager.nonIgnoredProjects.flatMap { pom ->
            val file = PsiManager.getInstance(project).findFile(pom.file)
            val model = file?.let(MavenDomUtil::getMavenDomProjectModel)
            model?.let { MavenDomProjectProcessorUtils.collectParentProjects(it) }
                .orEmpty().mapNotNull { it.xmlTag?.containingFile?.virtualFile?.path }
        }.toSet()
        val stamps = documentStamps(layout.poms + parents)
        return Context(layout, parents, manager.generalSettings.clone(), manager.explicitProfiles.enabledProfiles.toSet(),
            manager.explicitProfiles.disabledProfiles.toSet(), stamps)
    }

    private fun documentStamps(paths: Collection<String>): Map<String, String> {
        val documents = FileDocumentManager.getInstance()
        return paths.associateWith { path ->
            val file = LocalFileSystem.getInstance().findFileByPath(path)
            val document = file?.let(documents::getCachedDocument)
            "${file?.modificationStamp}:${document?.modificationStamp}" +
                if (document != null && documents.isDocumentUnsaved(document)) ":unsaved" else ""
        }
    }

    @Synchronized override fun dispose() { disposed = true; resolutions.clear() }

    private fun hash(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
