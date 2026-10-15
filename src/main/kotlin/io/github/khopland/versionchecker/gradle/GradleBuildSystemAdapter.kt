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
    private fun supported(project: Project, file: VirtualFile): Boolean {
        if (file.isDirectory || !(file.name in setOf("build.gradle", "build.gradle.kts") ||
                file.name == "libs.versions.toml" && file.parent.name == "gradle")) return false
        val root = owner(project, file) ?: return false
        val directories = listOf(root) + GradleSettings.getInstance(project).getLinkedProjectSettings(root)?.modules.orEmpty()
        val boundary = directories.mapNotNull { find(project, it) }
            .filter { VfsUtilCore.isAncestor(it, file, false) }.maxByOrNull { it.path.length } ?: return false
        return generateSequence(file.parent) { it.parent }.takeWhile { it != boundary }.none { it.name in excluded }
    }
    private val excluded = GradleInputInventory.excludedDirectories
    private fun roots(project: Project) = GradleSettings.getInstance(project).linkedProjectsSettings.mapNotNull { it.externalProjectPath }.sortedByDescending { it.length }
    private fun owner(project: Project, file: VirtualFile) = roots(project).firstOrNull { root ->
        if (file.name.endsWith(".toml")) {
            find(project, root)?.findFileByRelativePath("gradle/libs.versions.toml") == file
        } else {
            val directories = listOf(root) + GradleSettings.getInstance(project).getLinkedProjectSettings(root)?.modules.orEmpty()
            directories.any { directory -> find(project, directory)?.let { VfsUtilCore.isAncestor(it, file, false) } == true }
        }
    }
    private val includedBuild = Regex("""\bincludeBuild\s*(?:\(\s*)?(["'])([^"'\r\n$]+)\1(?=[ \t]*(?:[){;}]|\r?$))""", RegexOption.MULTILINE)
    private fun scriptText(file: VirtualFile): String = FileDocumentManager.getInstance().getCachedDocument(file)?.text
        ?: String(file.contentsToByteArray(), Charsets.UTF_8)

    private fun inputDirectories(project: Project, root: String): GradleInputDirectories {
        val settings = GradleSettings.getInstance(project).getLinkedProjectSettings(root)
        val directories = (listOf(root) + settings?.modules.orEmpty().sorted()).distinct().toMutableList()
        val logicDirectories = mutableSetOf<String>()
        // Settings scripts name included builds, including unlinked siblings. Inspect the
        // current editor text so an unsaved include change cannot reuse the old inventory.
        var index = 0
        val visited = mutableSetOf<String>()
        while (index < directories.size) {
            val directory = directories[index++]
            val canonical = find(project, directory)?.canonicalPath ?: directory
            if (!visited.add(canonical)) continue
            find(project, directory)?.findChild("buildSrc")?.takeIf { it.isDirectory }?.let { buildSrc ->
                logicDirectories += buildSrc.path
                val canonicalBuildSrc = buildSrc.canonicalPath ?: buildSrc.path
                if (directories.none { (find(project, it)?.canonicalPath ?: it) == canonicalBuildSrc }) directories += buildSrc.path
            }
            for (name in listOf("settings.gradle", "settings.gradle.kts")) {
                val script = find(project, directory)?.findChild(name) ?: continue
                for (match in includedBuild.findAll(scriptText(script))) {
                    val path = Path.of(directory).resolve(match.groupValues[2]).normalize().toString()
                    if (find(project, path) != null) {
                        logicDirectories += path
                        val canonicalPath = find(project, path)?.canonicalPath ?: path
                        if (directories.none { (find(project, it)?.canonicalPath ?: it) == canonicalPath }) directories += path
                    }
                }
            }
        }
        // Gradle imports included builds as additional modules. Outside modules are
        // executable build inputs, so conservatively capture their source/resources too.
        // Imported modules may use the canonical alias of the user's linked symlink.
        // Compare VFS-known identities here without adding physical IO to this read action.
        val rootLocation = Path.of(find(project, root)?.canonicalPath ?: root).normalize()
        logicDirectories += directories.filter {
            !Path.of(find(project, it)?.canonicalPath ?: it).normalize().startsWith(rootLocation)
        }
        return GradleInputDirectories(directories.toList(), logicDirectories.toSet())
    }
    private fun inventory(project: Project, root: String): GradleInputInventory {
        val directories = inputDirectories(project, root)
        return project.service<GradleProjectCache>().inventory(root, directories) {
            GradleInputInventory.discover(directories) { find(project, it) }
        }
    }
    private fun files(project: Project, root: String): List<VirtualFile> = inventory(project, root).files

    private fun find(project: Project, path: String): VirtualFile? = LocalFileSystem.getInstance().findFileByPath(path)
        ?: ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { it.fileSystem.findFileByPath(path) }
    override fun supports(project: Project, selection: BuildSelection): Boolean = when (selection.scope) {
        UpdateScope.CURRENT_FILE -> selection.currentFile?.let { find(project, it) }?.let { supported(project, it) } == true
        UpdateScope.WHOLE_PROJECT -> roots(project).any { root -> files(project, root).any { supported(project, it) } }
        UpdateScope.MAVEN_PLATFORM -> false
    }
    override suspend fun resolutionInputPaths(project: Project, selection: BuildSelection): Set<String> {
        val (inputs, home) = readAction {
            val selectedRoots = if (selection.scope == UpdateScope.CURRENT_FILE)
                listOfNotNull(selection.currentFile?.let { find(project, it) }?.let { owner(project, it) }) else roots(project)
            val paths = selectedRoots.flatMap { inventory(project, it).resolutionInputPaths() }.toSet()
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
        !inventory(project, snapshot.context.root).hasUnsavedDocuments()

    private fun snapshot(project: Project, file: VirtualFile, fingerprints: MutableMap<String, BuildFingerprint>, unsaved: MutableMap<String, Boolean>,
                         allowUnsaved: Boolean = false, inspectionFingerprints: MutableMap<String, BuildFingerprint> = mutableMapOf(),
                         externalHashes: Map<String, String>? = null): BuildSnapshot? {
        if (!supported(project, file)) return null
        val root = owner(project, file) ?: return null
        if (!allowUnsaved && unsaved.getOrPut(root) { inventory(project, root).hasUnsavedDocuments() }) return null
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
        val captured = readAction {
            if (project.isDisposed) emptyList() else snapshots.map { it.context.root }.distinct().map { root ->
                val inventory = inventory(project, root)
                val saved = project.service<GradleProjectCache>().savedHashes(root, inventory.files)
                    .filterKeys { it.fileSystem.protocol == "file" }
                inventory to saved
            }
        }
        val diskCurrent = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            captured.all { (inventory, saved) -> inventory.diskMatches(saved) { context.ensureActive() } }
        }
        return withExternalInputs(project) { external ->
            val home = externalHome(project)
            val guard = { diskCurrent && !project.isDisposed && externalHome(project) == home &&
                snapshots.all { inputCoverageLimitation(project, it.context.root) == null } && areCurrent(project, snapshots, external) }
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
        val limitation = readAction { inputCoverageLimitation(project, snapshot.context.root) }
        if (limitation != null) return UpdateReport(emptyList(), snapshot.declarations.map {
            UpdateNotice(it, NoticeKind.MANUAL_REVIEW, limitation)
        }, coverage = snapshot.declarations.associate {
            it.id to DeclarationCoverage(DeclarationOutcome.MANUAL_REVIEW, limitation)
        } + snapshot.skippedCoverage())
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
    /** Arbitrary executable Gradle can load inputs we cannot infer statically. */
    private fun inputCoverageLimitation(project: Project, root: String): String? {
        for (file in files(project, root).filter { it.name.endsWith(".gradle") || it.name.endsWith(".gradle.kts") }) {
            val text = scriptText(file).lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            if (includedBuild.findAll(text).any { match ->
                    find(project, Path.of(file.parent.path).resolve(match.groupValues[2]).normalize().toString()) == null
                }) return "Included build inputs are unavailable; Gradle input coverage is limited."
            if (Regex("""\bincludeBuild\b""").findAll(text).count() != includedBuild.findAll(text).count())
                return "Computed included build paths need manual review; Gradle input coverage is limited."
            // External/dynamic applied scripts have no imported input inventory. Keep
            // results reviewable until a fresh check and explicit source review occur.
            val applied = Regex("""\bapply\s*(?:\(\s*)?from\s*[:=]\s*(["'])([^"']+)\1(?=[ \t]*(?:[);}]|\r?$))""", RegexOption.MULTILINE)
            for (match in applied.findAll(text)) {
                val path = match.groupValues[2]
                val resolved = runCatching { Path.of(file.parent.path).resolve(path).normalize() }.getOrNull()
                if ('$' in path || resolved == null || !resolved.startsWith(Path.of(root).normalize()) ||
                    find(project, resolved.toString()) !in files(project, root))
                    return "External applied scripts need manual review; Gradle input coverage is limited."
            }
            if (Regex("""\bapply\s*(?:\(\s*)?from\s*[:=]""").findAll(text).count() != applied.findAll(text).count())
                return "Computed applied script paths need manual review; Gradle input coverage is limited."
        }
        return null
    }
    override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan = withExternalInputs(project) { external ->
        check(areCurrent(project, reports.keys, external)) { "Gradle build files or settings changed. Run the check again." }
        val edits = mutableListOf<VersionEdit>(); val skipped = mutableListOf<String>()
        val followUp = mutableSetOf<String>()
        for ((snapshot, report) in reports) {
            val limitation = inputCoverageLimitation(project, snapshot.context.root)
            if (limitation != null) {
                skipped += "${snapshot.sourceFile}: $limitation"
                continue
            }
            val psi = find(project, snapshot.sourceFile)?.let { PsiManager.getInstance(project).findFile(it) } ?: error("Gradle build file is unavailable")
            for (prepared in GradlePreparedUpdate.prepare(psi, report)) {
                prepared.edit?.let { edits += it; followUp += prepared.followUp }
                if (prepared.edit == null && prepared.reportSkipped)
                    skipped += "${prepared.location}: ${prepared.blockingReasons.first()}"
            }
            skipped += report.notices.map { "${it.declaration.id.file}: ${it.message}" }
            skipped += report.coverage.orEmpty().filter { (id, result) ->
                result.outcome == DeclarationOutcome.SKIPPED && id !in snapshot.skippedDeclarations
            }.map { (id, result) -> "${id.file}: ${id.location}: ${result.reason}" }
        }
        BulkUpdatePlan(edits.sortedByDescending { (it as GradleVersionEdit).range.startOffset }, skipped,
            followUp = followUp.toList(),
            prepareValidation = { prepareEditValidation(project, reports.keys) })
    }
}
