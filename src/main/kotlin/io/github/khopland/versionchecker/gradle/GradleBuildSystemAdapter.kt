package io.github.khopland.versionchecker.gradle

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.*
import com.intellij.psi.PsiManager
import io.github.khopland.versionchecker.*
import io.github.khopland.versionchecker.core.*
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path

internal class GradleBuildSystemAdapter : BuildSystemAdapter {
    private companion object {
        // The Path getter was added in 2026.1; retain compatibility with 2025.3's String getter.
        val gradleHomeGetter = try {
            GradleProjectSettings::class.java.getMethod("getGradleHomePath")
        } catch (_: NoSuchMethodException) {
            GradleProjectSettings::class.java.getMethod("getGradleHome")
        }
    }

    override val id = "gradle"
    override val displayName = "Gradle"
    override val capabilities = AdapterCapabilities()
    override fun isOffline(project: Project) = GradleSettings.getInstance(project).isOfflineWork
    private fun supported(file: VirtualFile) = !file.isDirectory &&
        (file.name in setOf("build.gradle", "build.gradle.kts") || file.name == "libs.versions.toml" && file.parent.name == "gradle") &&
        generateSequence(file.parent) { it.parent }.none { it.name in excluded }
    private val excluded = setOf(".git", ".gradle", ".idea", "build", "out", "node_modules", "vendor")
    private fun roots(project: Project) = GradleSettings.getInstance(project).linkedProjectsSettings.mapNotNull { it.externalProjectPath }.sortedByDescending { it.length }
    private fun owner(project: Project, file: VirtualFile) = roots(project).firstOrNull { root ->
        if (file.name.endsWith(".toml")) {
            find(project, root)?.findFileByRelativePath("gradle/libs.versions.toml") == file
        } else {
            val directories = listOf(root) + GradleSettings.getInstance(project).getLinkedProjectSettings(root)?.modules.orEmpty()
            directories.any { directory -> find(project, directory)?.let { VfsUtilCore.isAncestor(it, file, false) } == true }
        }
    }
    private fun files(project: Project, root: String): List<VirtualFile> {
        val settings = GradleSettings.getInstance(project).getLinkedProjectSettings(root)
        val directories = (listOf(root) + settings?.modules.orEmpty()).distinct().sorted()
        return project.service<GradleProjectCache>().files(root, directories) { discoverFiles(project, directories) }
    }
    private fun discoverFiles(project: Project, directories: List<String>): List<VirtualFile> {
        val result = mutableSetOf<VirtualFile>()
        val roots = directories.mapNotNull { find(project, it) }.distinct()
        // Linked modules commonly sit inside the root; walking them again adds no inputs.
        for (file in roots.filter { candidate -> roots.none { it != candidate && VfsUtilCore.isAncestor(it, candidate, true) } }) {
            VfsUtilCore.iterateChildrenRecursively(file, { it.name !in excluded }) {
                if (!it.isDirectory && (it.name.endsWith(".gradle") || it.name.endsWith(".gradle.kts") || it.name.endsWith(".toml") || it.name in setOf("gradle.properties", "gradle-wrapper.properties", "gradle-wrapper.jar", "gradlew", "gradlew.bat", "gradle.lockfile") || it.path.contains("/gradle/dependency-locks/") || it.name == "verification-metadata.xml")) result += it
                true
            }
        }
        return result.toList()
    }
    private fun find(project: Project, path: String): VirtualFile? = LocalFileSystem.getInstance().findFileByPath(path)
        ?: ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { it.fileSystem.findFileByPath(path) }
    override fun supports(project: Project, selection: BuildSelection): Boolean = when (selection.scope) {
        UpdateScope.CURRENT_FILE -> selection.currentFile?.let { find(project, it) }?.let { supported(it) && owner(project, it) != null } == true
        UpdateScope.WHOLE_PROJECT -> roots(project).any { root -> files(project, root).any(::supported) }
    }
    override fun resolutionInputPaths(project: Project, selection: BuildSelection): Set<String> {
        val selectedRoots = if (selection.scope == UpdateScope.CURRENT_FILE)
            listOfNotNull(selection.currentFile?.let { find(project, it) }?.let { owner(project, it) }) else roots(project)
        return selectedRoots.flatMap { fingerprint(project, it).files.keys }.toSet()
    }

    override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? {
        return CheckPerformance.measure(CheckPerformance.Stage.GRADLE_SNAPSHOT) { snapshot(project, file, mutableMapOf(), mutableMapOf()) }
    }
    override fun inspectionSnapshot(project: Project, file: VirtualFile): BuildSnapshot? =
        snapshot(project, file, mutableMapOf(), mutableMapOf(), allowUnsaved = true)

    override fun canCheckInBackground(project: Project, snapshot: BuildSnapshot): Boolean =
        files(project, snapshot.context.root).none { FileDocumentManager.getInstance().isFileModified(it) }

    private fun snapshot(project: Project, file: VirtualFile, fingerprints: MutableMap<String, BuildFingerprint>, unsaved: MutableMap<String, Boolean>,
                         allowUnsaved: Boolean = false, inspectionFingerprints: MutableMap<String, BuildFingerprint> = mutableMapOf()): BuildSnapshot? {
        if (!supported(file)) return null
        val root = owner(project, file) ?: return null
        if (!allowUnsaved && unsaved.getOrPut(root) { files(project, root).any { FileDocumentManager.getInstance().isFileModified(it) } }) return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val fingerprint = fingerprints.getOrPut(root) { fingerprint(project, root) }
        val inspectionFingerprint = inspectionFingerprints.getOrPut(root) {
            fingerprint.copy(files = fingerprint.files + project.service<GradleProjectCache>().inspectionHashes(root, files(project, root)))
        }
        return BuildSnapshot(BuildContextId(id, root, file.path), file.path, fingerprint,
            GradleDeclarations.parse(file.path, psi.text).map { it.declaration }, inspectionFingerprint)
    }

    override fun retainInspectionReport(previous: BuildSnapshot, report: UpdateReport, current: BuildSnapshot): UpdateReport? {
        if (previous.context != current.context || previous.inspectionFingerprint == null ||
            previous.inspectionFingerprint != current.inspectionFingerprint) return null
        // Normalized build text is identical, so declarations retain their order even when offsets move.
        val declarations = previous.declarations.zip(current.declarations).filter { (old, new) ->
            old.copy(id = new.id) == new
        }.toMap()
        return report.copy(
            candidates = report.candidates.mapNotNull { candidate ->
                declarations[candidate.declaration]?.let { candidate.copy(declaration = it) }
            },
            notices = report.notices.mapNotNull { notice ->
                declarations[notice.declaration]?.let { notice.copy(declaration = it) }
            }
        )
    }
    private fun fingerprint(project: Project, root: String): BuildFingerprint {
        fun hash(bytes: ByteArray) = GradleProjectCache.hash(bytes)
        val settings = GradleSettings.getInstance(project)
        val linked = settings.getLinkedProjectSettings(root)
        val hashes = project.service<GradleProjectCache>().hashes(root, files(project, root)).toMutableMap()
        // These inputs can change outside IntelliJ's VFS, so always read their current contents.
        val home = Path.of(settings.serviceDirectoryPath ?: System.getenv("GRADLE_USER_HOME") ?: Path.of(System.getProperty("user.home"), ".gradle").toString())
        fun externalHash(path: Path): String {
            val saved = if (Files.exists(path)) hash(Files.readAllBytes(path)) else "missing"
            val documents = FileDocumentManager.getInstance()
            val unsaved = LocalFileSystem.getInstance().findFileByNioFile(path)?.let(documents::getCachedDocument)
                ?.takeIf(documents::isDocumentUnsaved)?.let { hash(it.text.toByteArray()) }
            return "$saved:$unsaved"
        }
        for (path in listOf(home.resolve("gradle.properties"), home.resolve("init.gradle"), home.resolve("init.gradle.kts"))) hashes[path.toString()] = externalHash(path)
        val init = home.resolve("init.d")
        if (Files.isDirectory(init)) Files.walk(init).use { paths -> paths.filter(Files::isRegularFile).forEach { hashes[it.toString()] = externalHash(it) } }
        val sdk = ProjectRootManager.getInstance(project).projectSdk
        val gradleHome = linked?.let { gradleHomeGetter.invoke(it)?.toString() }
        return BuildFingerprint(hashes, listOf(root, linked?.modules?.sorted(), linked?.gradleJvm, gradleHome, linked?.distributionType, sdk?.name, sdk?.homePath, settings.serviceDirectoryPath, settings.gradleVmOptions, settings.isOfflineWork, project.service<VersionCheckerSettings>().state.deprecatedDependencies, System.getenv().toSortedMap()).joinToString("|").let { hash(it.toByteArray()) })
    }
    override fun isCurrent(project: Project, snapshot: BuildSnapshot) = find(project, snapshot.sourceFile)?.let { owner(project, it) == snapshot.context.root && snapshot.fingerprint == fingerprint(project, snapshot.context.root) } == true
    private fun areCurrent(project: Project, snapshots: Collection<BuildSnapshot>): Boolean {
        val fingerprints = mutableMapOf<String, BuildFingerprint>()
        return snapshots.all { snapshot ->
            find(project, snapshot.sourceFile)?.let { owner(project, it) == snapshot.context.root &&
                snapshot.fingerprint == fingerprints.getOrPut(snapshot.context.root) { fingerprint(project, snapshot.context.root) } } == true
        }
    }
    override suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot> = readAction {
        val files = if (selection.scope == UpdateScope.CURRENT_FILE) listOfNotNull(selection.currentFile?.let { find(project, it) }) else roots(project).flatMap { files(project, it) }.distinct()
        val fingerprints = mutableMapOf<String, BuildFingerprint>()
        val unsaved = mutableMapOf<String, Boolean>()
        val inspectionFingerprints = mutableMapOf<String, BuildFingerprint>()
        files.mapNotNull { snapshot(project, it, fingerprints, unsaved, inspectionFingerprints = inspectionFingerprints) }
    }
    override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
        val policy = readAction { parseDeprecationPolicy(project.service<VersionCheckerSettings>().state.deprecatedDependencies) }
        val results = GradleVersionLookup.check(project, snapshot.copy(declarations = snapshot.declarations.filter { "${it.artifact.namespace}:${it.artifact.name}" !in policy }), mode)
        return UpdateReport(snapshot.declarations.mapNotNull { declaration ->
            val versions = results[declaration.id.location]?.versions.orEmpty().distinct()
            val latest = versions.singleOrNull() ?: return@mapNotNull null
            if (!GradleVersions.newer(declaration.baseline, latest)) return@mapNotNull null
            UpdateCandidate(declaration, latest, kind = GradleVersions.kind(declaration.baseline, latest))
        }, snapshot.declarations.mapNotNull { declaration ->
            policy["${declaration.artifact.namespace}:${declaration.artifact.name}"]?.let {
                UpdateNotice(declaration, NoticeKind.DEPRECATED, "${declaration.artifact.namespace}:${declaration.artifact.name} is deprecated: $it")
            } ?: results[declaration.id.location]?.reason?.let {
                UpdateNotice(declaration, NoticeKind.MANUAL_REVIEW, it)
            }
        })
    }
    override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan = readAction {
        check(areCurrent(project, reports.keys)) { "Gradle build files or settings changed. Run the check again." }
        val edits = mutableListOf<VersionEdit>(); val skipped = mutableListOf<String>()
        for ((snapshot, report) in reports) {
            val psi = find(project, snapshot.sourceFile)?.let { PsiManager.getInstance(project).findFile(it) } ?: error("Gradle build file is unavailable")
            val original = psi.text
            val declarations = GradleDeclarations.parse(snapshot.sourceFile, original)
            val candidates = report.candidates.associateBy { it.declaration.id }
            for ((range, consumers) in declarations.groupBy { it.range }) {
                val updates = consumers.map { candidates[it.declaration.id] }
                val location = "${psi.virtualFile.path}: " + consumers.joinToString {
                    "${it.declaration.artifact.namespace}:${it.declaration.artifact.name} (${it.declaration.id.location})"
                }
                if (range != null && consumers.all { it.reason == null } && updates.all { it != null } && updates.map { it!!.version }.distinct().size == 1) {
                    edits += GradleVersionEdit(psi, range, updates.first()!!.version, location, original)
                } else if (consumers.any { it.reason != null } || updates.any { it != null }) {
                    skipped += "$location: ${consumers.mapNotNull { it.reason }.firstOrNull() ?: "Shared version has conflicting or unchanged consumers"}"
                }
            }
            skipped += report.notices.map { "${it.declaration.id.file}: ${it.message}" }
        }
        BulkUpdatePlan(edits.sortedByDescending { (it as GradleVersionEdit).range.startOffset }, skipped, { areCurrent(project, reports.keys) }, if (edits.isEmpty()) emptyList() else listOf("Reload the Gradle project in IntelliJ after updating versions. Update dependency locks yourself if the build uses locking."))
    }
}
