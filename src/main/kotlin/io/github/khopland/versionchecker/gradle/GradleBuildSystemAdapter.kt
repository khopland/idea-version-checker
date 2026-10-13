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
import java.io.IOException
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class GradleBuildSystemAdapter(
    private val externalInputBytes: (Path) -> ByteArray = Files::readAllBytes
) : BuildSystemAdapter {
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
    override fun invalidateMetadata(project: Project) = project.service<GradleProjectCache>().invalidateMetadata()
    override suspend fun <T> withScan(project: Project, snapshots: List<BuildSnapshot>, mode: UpdateMode,
                                    action: suspend () -> T): T {
        if (!java.lang.Boolean.getBoolean("versionchecker.gradleScanAggregation")) return action()
        val policy = readAction { parseDeprecationPolicy(project.service<VersionCheckerSettings>().state.deprecatedDependencies) }
        val eligible = snapshots.map { snapshot ->
            snapshot.copy(declarations = snapshot.declarations.filter { "${it.artifact.namespace}:${it.artifact.name}" !in policy })
        }
        return withGradleScanSession(project, eligible, mode, action)
    }
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
        val directories = (listOf(root) + settings?.modules.orEmpty().sorted()).distinct()
        return project.service<GradleProjectCache>().files(root, directories) { discoverFiles(project, directories) }
    }
    private fun discoverFiles(project: Project, directories: List<String>): List<VirtualFile> {
        val result = mutableSetOf<VirtualFile>()
        // Imported module paths can use a canonical location while the linked root uses
        // a symlink (for example /tmp and /private/tmp on macOS). Keep the user's root
        // namespace, but compare VFS-known link targets before walking it again.
        val roots = directories.mapNotNull { find(project, it) }.distinctBy { it.canonicalPath ?: it.path }
        val locations = roots.associateWith { it.canonicalPath ?: it.path }
        // Linked modules commonly sit inside the root; walking them again adds no inputs.
        for (file in roots.filter { candidate -> roots.none { parent ->
            parent != candidate && locations.getValue(candidate).startsWith(locations.getValue(parent).trimEnd('/') + "/")
        } }) {
            VfsUtilCore.iterateChildrenRecursively(file, { it.name !in excluded }) {
                if (!it.isDirectory && (it.name.endsWith(".gradle") || it.name.endsWith(".gradle.kts") || it.name.endsWith(".toml") || it.name in setOf("gradle.properties", "gradle-wrapper.properties", "gradle-wrapper.jar", "gradlew", "gradlew.bat", "gradle.lockfile") || it.path.contains("/gradle/dependency-locks/") || it.name == "verification-metadata.xml")) result += it
                true
            }
        }
        return result.distinctBy { it.canonicalPath ?: it.path }
    }
    private fun find(project: Project, path: String): VirtualFile? = LocalFileSystem.getInstance().findFileByPath(path)
        ?: ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { it.fileSystem.findFileByPath(path) }
    override fun supports(project: Project, selection: BuildSelection): Boolean = when (selection.scope) {
        UpdateScope.CURRENT_FILE -> selection.currentFile?.let { find(project, it) }?.let { supported(it) && owner(project, it) != null } == true
        UpdateScope.WHOLE_PROJECT -> roots(project).any { root -> files(project, root).any(::supported) }
        UpdateScope.MAVEN_PLATFORM -> false
    }
    override suspend fun resolutionInputPaths(project: Project, selection: BuildSelection): Set<String> {
        val (inputs, home) = readAction {
            val selectedRoots = if (selection.scope == UpdateScope.CURRENT_FILE)
                listOfNotNull(selection.currentFile?.let { find(project, it) }?.let { owner(project, it) }) else roots(project)
            val paths = selectedRoots.flatMap { files(project, it).map { file -> file.path } }.toSet()
            val home = Path.of(GradleSettings.getInstance(project).serviceDirectoryPath ?: System.getenv("GRADLE_USER_HOME")
                ?: Path.of(System.getProperty("user.home"), ".gradle").toString())
            paths to home
        }
        return withContext(Dispatchers.IO) {
            val paths = inputs.toMutableSet()
            listOf("gradle.properties", "init.gradle", "init.gradle.kts").forEach { paths += home.resolve(it).toString() }
            val init = home.resolve("init.d")
            val context = currentCoroutineContext()
            if (Files.isDirectory(init)) Files.walk(init).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    context.ensureActive()
                    val path = iterator.next()
                    if (Files.isRegularFile(path)) paths += path.toString()
                }
            }
            paths
        }
    }

    override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? {
        return CheckPerformance.measure(CheckPerformance.Stage.GRADLE_SNAPSHOT) { snapshot(project, file, mutableMapOf(), mutableMapOf()) }
    }
    override fun inspectionSnapshot(project: Project, file: VirtualFile): BuildSnapshot? =
        snapshot(project, file, mutableMapOf(), mutableMapOf(), allowUnsaved = true)

    override suspend fun prepareInspectionSnapshot(project: Project, file: VirtualFile): () -> BuildSnapshot? =
        withExternalInputs(project) { external ->
            val home = externalHome(project)
            val capture = { if (project.isDisposed || !file.isValid || home != externalHome(project)) null else
                snapshot(project, file, mutableMapOf(), mutableMapOf(), allowUnsaved = true, externalHashes = external) }
            capture
        }

    override fun canCheckInBackground(project: Project, snapshot: BuildSnapshot): Boolean =
        files(project, snapshot.context.root).none { FileDocumentManager.getInstance().isFileModified(it) }

    private fun snapshot(project: Project, file: VirtualFile, fingerprints: MutableMap<String, BuildFingerprint>, unsaved: MutableMap<String, Boolean>,
                         allowUnsaved: Boolean = false, inspectionFingerprints: MutableMap<String, BuildFingerprint> = mutableMapOf(),
                         externalHashes: Map<String, String>? = null): BuildSnapshot? {
        if (!supported(file)) return null
        val root = owner(project, file) ?: return null
        if (!allowUnsaved && unsaved.getOrPut(root) { files(project, root).any { FileDocumentManager.getInstance().isFileModified(it) } }) return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val fingerprint = fingerprints.getOrPut(root) { fingerprint(project, root, externalHashes) }
        val inspectionFingerprint = inspectionFingerprints.getOrPut(root) {
            fingerprint.copy(files = fingerprint.files + project.service<GradleProjectCache>().inspectionHashes(root, files(project, root)))
        }
        val parsed = GradleDeclarations.parse(file.path, psi.text)
        return BuildSnapshot(BuildContextId(id, root, file.path), file.path, fingerprint,
            parsed.map { it.declaration }, inspectionFingerprint, skippedDeclarations = parsed.filter {
                it.declaration.baseline.isEmpty()
            }.associate { it.declaration.id to (it.reason ?: "Unsupported Gradle version selector needs manual review.") })
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
            },
            coverage = report.coverage?.let { outcomes ->
                declarations.mapNotNull { (old, new) -> outcomes[old.id]?.let { new.id to it } }.toMap() + current.skippedCoverage()
            }
        )
    }
    private fun externalHome(project: Project): Path = Path.of(GradleSettings.getInstance(project).serviceDirectoryPath
        ?: System.getenv("GRADLE_USER_HOME") ?: Path.of(System.getProperty("user.home"), ".gradle").toString())

    private fun readExternalInputs(home: Path, checkCancellation: () -> Unit): Map<String, String> {
        val hashes = mutableMapOf<String, String>()
        fun capture(path: Path) {
            checkCancellation()
            hashes[path.toString()] = if (Files.exists(path)) GradleProjectCache.hash(externalInputBytes(path)) else "missing"
        }
        for (name in listOf("gradle.properties", "init.gradle", "init.gradle.kts")) capture(home.resolve(name))
        val init = home.resolve("init.d")
        if (Files.isDirectory(init)) Files.walk(init).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                checkCancellation()
                val path = iterator.next()
                if (Files.isRegularFile(path)) capture(path)
            }
        }
        return hashes
    }

    private data class InputCapture<T>(val value: T)

    /** Capture disk inputs once per operation, then overlay the latest editor/settings state. */
    private suspend fun <T> withExternalInputs(project: Project, action: (Map<String, String>) -> T): T =
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            repeat(3) {
                context.ensureActive()
                val home = readAction { externalHome(project) }
                val hashes = readExternalInputs(home) { context.ensureActive() }
                context.ensureActive()
                val captured = readAction {
                    if (home != externalHome(project)) null else InputCapture(action(hashes))
                }
                if (captured != null) return@withContext captured.value
            }
            error("Gradle user home changed during input capture. Run the check again.")
        }

    private fun fingerprint(project: Project, root: String, externalHashes: Map<String, String>? = null): BuildFingerprint {
        fun hash(bytes: ByteArray) = GradleProjectCache.hash(bytes)
        val settings = GradleSettings.getInstance(project)
        val linked = settings.getLinkedProjectSettings(root)
        val hashes = project.service<GradleProjectCache>().hashes(root, files(project, root)).toMutableMap()
        // These inputs can change outside IntelliJ's VFS, so always read their current contents.
        val external = externalHashes ?: readExternalInputs(externalHome(project)) { com.intellij.openapi.progress.ProgressManager.checkCanceled() }
        for ((path, saved) in external) {
            val documents = FileDocumentManager.getInstance()
            val unsaved = LocalFileSystem.getInstance().findFileByNioFile(Path.of(path))?.let(documents::getCachedDocument)
                ?.takeIf(documents::isDocumentUnsaved)?.let { hash(it.text.toByteArray()) }
            hashes[path] = "$saved:$unsaved"
        }
        val sdk = ProjectRootManager.getInstance(project).projectSdk
        val gradleHome = linked?.let { gradleHomeGetter.invoke(it)?.toString() }
        return BuildFingerprint(hashes, listOf(root, linked?.modules?.sorted(), linked?.gradleJvm, gradleHome, linked?.distributionType, sdk?.name, sdk?.homePath, settings.serviceDirectoryPath, settings.gradleVmOptions, settings.isOfflineWork, project.service<VersionCheckerSettings>().state.deprecatedDependencies, System.getenv().toSortedMap()).joinToString("|").let { hash(it.toByteArray()) })
    }
    override fun isCurrent(project: Project, snapshot: BuildSnapshot) = find(project, snapshot.sourceFile)?.let { owner(project, it) == snapshot.context.root && snapshot.fingerprint == fingerprint(project, snapshot.context.root) } == true
    override suspend fun isCurrentInBackground(project: Project, snapshot: BuildSnapshot): Boolean =
        withExternalInputs(project) { external -> !project.isDisposed && areCurrent(project, listOf(snapshot), external) }

    override suspend fun prepareEditValidation(project: Project, snapshots: Collection<BuildSnapshot>): () -> Boolean {
        // Native Gradle reads physical files. A same-stamp external write can leave
        // both VFS and PSI stale, so an edit must compare fresh bytes to their baseline.
        val saved = readAction {
            if (project.isDisposed) emptyMap() else snapshots.map { it.context.root }.distinct().flatMap { root ->
                project.service<GradleProjectCache>().savedHashes(root, files(project, root)).entries
            }.filter { it.key.fileSystem.protocol == "file" }.associate { it.key to it.value }
        }
        val diskCurrent = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            saved.all { (file, expected) ->
                context.ensureActive()
                val digest = MessageDigest.getInstance("SHA-256")
                try {
                    Files.newInputStream(Path.of(file.path)).use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    HexFormat.of().formatHex(digest.digest()) == expected
                } catch (_: IOException) { false }
            }
        }
        return withExternalInputs(project) { external ->
            val home = externalHome(project)
            val guard = { diskCurrent && !project.isDisposed && externalHome(project) == home && areCurrent(project, snapshots, external) }
            guard
        }
    }

    private fun areCurrent(project: Project, snapshots: Collection<BuildSnapshot>, externalHashes: Map<String, String>? = null): Boolean {
        val fingerprints = mutableMapOf<String, BuildFingerprint>()
        // Final synchronous validation needs fresh disk bytes too, shared across linked builds.
        val external by lazy {
            externalHashes ?: readExternalInputs(externalHome(project)) { com.intellij.openapi.progress.ProgressManager.checkCanceled() }
        }
        return snapshots.all { snapshot ->
            find(project, snapshot.sourceFile)?.let { owner(project, it) == snapshot.context.root &&
                snapshot.fingerprint == fingerprints.getOrPut(snapshot.context.root) { fingerprint(project, snapshot.context.root, external) } } == true
        }
    }
    override suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot> = withExternalInputs(project) { external ->
        val files = if (selection.scope == UpdateScope.CURRENT_FILE) listOfNotNull(selection.currentFile?.let { find(project, it) }) else roots(project).flatMap { files(project, it) }.distinct()
        val fingerprints = mutableMapOf<String, BuildFingerprint>()
        val unsaved = mutableMapOf<String, Boolean>()
        val inspectionFingerprints = mutableMapOf<String, BuildFingerprint>()
        files.mapNotNull { snapshot(project, it, fingerprints, unsaved, inspectionFingerprints = inspectionFingerprints, externalHashes = external) }
    }
    override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
        val policy = readAction { parseDeprecationPolicy(project.service<VersionCheckerSettings>().state.deprecatedDependencies) }
        val checked = GradleVersionLookup.check(project, snapshot.copy(declarations = snapshot.declarations.filter { "${it.artifact.namespace}:${it.artifact.name}" !in policy }), mode)
        val results = checked.results
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
        }, validUntilNanos = checked.expiresAtNanos, checkedAtNanos = checked.checkedAtNanos,
            coverage = snapshot.declarations.associate { declaration ->
                val result = results[declaration.id.location]
                declaration.id to when {
                    declaration.baseline.isEmpty() -> DeclarationCoverage(DeclarationOutcome.SKIPPED,
                        snapshot.skippedDeclarations[declaration.id] ?: "Unsupported Gradle version selector needs manual review.")
                    "${declaration.artifact.namespace}:${declaration.artifact.name}" in policy -> DeclarationCoverage(
                        DeclarationOutcome.MANUAL_REVIEW, "Artifact is explicitly marked deprecated; review its replacement.")
                    result?.reason != null -> DeclarationCoverage(DeclarationOutcome.MANUAL_REVIEW, result.reason)
                    result == null -> DeclarationCoverage(DeclarationOutcome.SKIPPED,
                        "Native Gradle task returned no result for this declaration.")
                    else -> DeclarationCoverage(DeclarationOutcome.CHECKED)
                }
            } + snapshot.skippedCoverage())
    }
    override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan = withExternalInputs(project) { external ->
        check(areCurrent(project, reports.keys, external)) { "Gradle build files or settings changed. Run the check again." }
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
            skipped += report.coverage.orEmpty().filter { (id, result) ->
                result.outcome == DeclarationOutcome.SKIPPED && id !in snapshot.skippedDeclarations
            }.map { (id, result) -> "${id.file}: ${id.location}: ${result.reason}" }
        }
        BulkUpdatePlan(edits.sortedByDescending { (it as GradleVersionEdit).range.startOffset }, skipped,
            followUp = if (edits.isEmpty()) emptyList() else listOf("Reload the Gradle project in IntelliJ after updating versions. Update dependency locks yourself if the build uses locking."),
            prepareValidation = { prepareEditValidation(project, reports.keys) })
    }
}
