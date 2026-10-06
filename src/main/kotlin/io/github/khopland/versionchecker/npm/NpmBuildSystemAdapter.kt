package io.github.khopland.versionchecker.npm

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.json.psi.*
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.npm.NpmManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiManager
import com.intellij.util.EnvironmentUtil
import io.github.khopland.versionchecker.*
import io.github.khopland.versionchecker.core.*
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal class NpmBuildSystemAdapter : BuildSystemAdapter {
    override val id = "npm"
    override val displayName = "npm"
    override val capabilities = AdapterCapabilities()
    override fun isOffline(project: Project) = false // npm evaluates its own offline/cache configuration.
    override fun supports(project: Project, selection: BuildSelection) =
        NpmManifest.files(project, selection).any { validManifest(project, it) }

    private val otherManagerFiles = listOf("pnpm-lock.yaml", "pnpm-workspace.yaml", "yarn.lock", "bun.lock", "bun.lockb")

    private fun validManifest(project: Project, file: VirtualFile): Boolean {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return false
        val manifest = NpmManifest.root(psi) ?: return false
        val workspaceRoot = workspace(project, file).root
        val rootManifest = workspaceRoot.findChild("package.json")?.let { PsiManager.getInstance(project).findFile(it) }?.let(NpmManifest::root)
        var explicitNpm = false
        for (root in listOfNotNull(rootManifest, manifest).distinct()) {
            val manager = root.findProperty("packageManager")?.value
            if (manager != null) {
                if (manager !is JsonStringLiteral || !manager.value.startsWith("npm@")) return false
                explicitNpm = true
            }
            val engines = root.findProperty("devEngines")?.value as? JsonObject
            val engineManager = engines?.findProperty("packageManager")?.value
            if (engineManager != null) {
                val engineName = (engineManager as? JsonObject)?.findProperty("name")?.value as? JsonStringLiteral
                if (engineName?.value != "npm") return false
                explicitNpm = true
            }
        }
        return explicitNpm || otherManagerFiles.none { workspaceRoot.findChild(it) != null || file.parent.findChild(it) != null }
    }
    override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? {
        if (!NpmManifest.supported(file) || !validManifest(project, file)) return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val workspace = workspace(project, file)
        val localNames = workspace.names
        val declarations = NpmManifest.declarations(psi).map { declaration ->
            if (declaration.artifact.name in localNames) declaration.copy(baseline = "") else declaration
        }
        return BuildSnapshot(BuildContextId(id, workspace.root.path, file.path), file.path,
            fingerprint(project, file), declarations)
    }
    override fun isCurrent(project: Project, snapshot: BuildSnapshot): Boolean {
        val file = findFile(project, snapshot.sourceFile) ?: return false
        return validManifest(project, file) && workspace(project, file).root.path == snapshot.context.root && snapshot.fingerprint == fingerprint(project, file)
    }
    override suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot> = readAction {
        NpmManifest.files(project, selection).mapNotNull { snapshot(project, it) }
    }
    override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
        val directory = Path.of(snapshot.context.root)
        val candidates = mutableListOf<UpdateCandidate>()
        val notices = mutableListOf<UpdateNotice>()
        val metadata = mutableMapOf<String, NpmPackageMetadata>()
        val deprecations = mutableMapOf<Pair<String, String>, String?>()
        suspend fun deprecated(name: String, version: String): String? {
            val key = name to version
            if (key !in deprecations) deprecations[key] = NpmRegistry.deprecated(project, directory, name, version)
            return deprecations[key]
        }
        val policy = readAction { project.service<VersionCheckerSettings>().state.deprecatedDependencies }
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { it.split('=', limit = 2).map(String::trim) }
            .associate { it[0] to (it.getOrNull(1)?.takeIf(String::isNotBlank) ?: "Deprecated by project policy") }
        for (declaration in snapshot.declarations) {
            val baseline = NpmVersion.parse(declaration.baseline) ?: continue
            val selector = NpmSelector.parse(declaration.artifact.name, declaration.selector) ?: continue
            val name = selector.packageName
            val explicit = policy[name]
            val versions = if (explicit == null) metadata[name] ?: NpmRegistry.metadata(project, directory, name).also { metadata[name] = it } else null
            val publishedBaseline = versions?.versions?.firstOrNull { NpmVersion.parse(it) == baseline }
            val notice = explicit ?: publishedBaseline?.let { deprecated(name, it) }
            if (notice != null) notices += UpdateNotice(declaration, NoticeKind.DEPRECATED,
                "npm package $name at ${declaration.selector} is deprecated: $notice")
            if (explicit != null) continue // An explicitly retired package needs replacement review.
            for (version in NpmRegistry.eligible(versions!!, baseline, mode)) {
                if (deprecated(name, version) != null) continue
                candidates += UpdateCandidate(declaration, version, selector.replace(version), baseline.change(NpmVersion.parse(version)!!))
                break
            }
        }
        return UpdateReport(candidates, notices)
    }
    override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan = readAction {
        check(reports.keys.all { isCurrent(project, it) }) { "npm manifests or configuration changed during the check. Run it again." }
        val edits = mutableListOf<VersionEdit>()
        val skipped = mutableListOf<String>()
        for ((snapshot, report) in reports) {
            val file = findFile(project, snapshot.sourceFile) ?: error("package.json is no longer available")
            val psi = PsiManager.getInstance(project).findFile(file) ?: error("package.json is no longer valid")
            val values = NpmManifest.values(psi)
            for (declaration in snapshot.declarations.filter { it.baseline.isEmpty() }) {
                skipped += "${declaration.id.file}: ${declaration.id.location}: ${NpmManifest.reviewReason(declaration)}"
            }
            for (candidate in report.candidates) {
                val value = values[candidate.declaration.id] ?: error("npm declaration no longer exists")
                check(value.value == candidate.declaration.selector) { "npm selector changed during the check" }
                edits += NpmVersionEdit(value, candidate.replacementSelector,
                    "${file.path}: ${candidate.declaration.id.location} (${candidate.declaration.artifact.name})")
            }
            report.notices.filter { notice -> report.candidates.none { it.declaration.id == notice.declaration.id } }
                .forEach { skipped += "${it.declaration.id.file}: ${it.message}" }
        }
        BulkUpdatePlan(edits, skipped.distinct(), isCurrent = { reports.keys.all { isCurrent(project, it) } })
    }

    internal fun workspaceDeclarations(project: Project, snapshot: BuildSnapshot): List<Pair<VersionDeclaration, JsonStringLiteral>> =
        NpmManifest.files(project, BuildSelection(UpdateScope.WHOLE_PROJECT)).flatMap { file ->
            if (!validManifest(project, file) || workspace(project, file).root.path != snapshot.context.root) return@flatMap emptyList()
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@flatMap emptyList()
            val values = NpmManifest.values(psi)
            NpmManifest.declarations(psi).mapNotNull { declaration -> values[declaration.id]?.let { declaration to it } }
        }

    /** npm members own their declarations: updating the root alone does not update a member. */
    @JvmOverloads
    internal fun quickFixes(project: Project, snapshot: BuildSnapshot, candidate: UpdateCandidate,
                            value: JsonStringLiteral,
                            declarations: List<Pair<VersionDeclaration, JsonStringLiteral>> = workspaceDeclarations(project, snapshot)): Array<LocalQuickFix> {
        val current = { isCurrent(project, snapshot) }
        val local = UpdateNpmVersionFix(value, candidate.replacementSelector, isCurrent = current)
        val latest = NpmVersion.parse(candidate.version) ?: return arrayOf(local)
        val edits = declarations.mapNotNull { (declaration, target) ->
            if (declaration.artifact != candidate.declaration.artifact || declaration.baseline.isEmpty()) return@mapNotNull null
            val selector = NpmSelector.parse(declaration.artifact.name, declaration.selector) ?: return@mapNotNull null
            // Keep newer declarations; a workspace update must never downgrade another package.
            if (selector.baseline >= latest) return@mapNotNull null
            NpmVersionEdit(target, selector.replace(candidate.version), "${declaration.id.file}: ${declaration.id.location}")
        }
        if (edits.mapNotNull { it.element?.containingFile?.virtualFile?.path }.distinct().size < 2) return arrayOf(local)
        return arrayOf(
            UpdateNpmVersionFix(value, candidate.replacementSelector, "Update locally to ${candidate.replacementSelector}", current),
            UpdateNpmWorkspaceVersionFix(candidate.declaration.artifact.name, candidate.version, edits, current)
        )
    }
    private fun findFile(project: Project, path: String): VirtualFile? =
        NpmManifest.files(project, BuildSelection(UpdateScope.CURRENT_FILE, path)).singleOrNull()

    private fun fingerprint(project: Project, file: VirtualFile): BuildFingerprint {
        // Workspace identities affect whether a dependency is local; include every project manifest.
        val files = NpmManifest.files(project, BuildSelection(UpdateScope.WHOLE_PROJECT))
            .associate { it.path to virtualDigest(it) }.toMutableMap()
        files[file.path] = virtualDigest(file)
        var ancestor: Path? = Path.of(file.path).parent
        while (ancestor != null) {
            for (name in listOf(".npmrc", "package.json") + otherManagerFiles) {
                val path = ancestor.resolve(name)
                files["disk-config:$path"] = if (name in otherManagerFiles) Files.exists(path).toString() else diskDigest(path)
            }
            ancestor = ancestor.parent
        }
        var virtualAncestor: VirtualFile? = file.parent
        while (virtualAncestor != null) {
            for (name in listOf(".npmrc", "package.json") + otherManagerFiles) {
                val config = virtualAncestor.findChild(name)
                files["virtual-config:${virtualAncestor.path}/$name"] =
                    if (name in otherManagerFiles) (config != null).toString() else config?.let(::virtualDigest) ?: "missing"
            }
            virtualAncestor = virtualAncestor.parent
        }
        val environment = EnvironmentUtil.getEnvironmentMap()
        for (path in listOf(Path.of(System.getProperty("user.home"), ".npmrc")) +
            listOf("NPM_CONFIG_USERCONFIG", "npm_config_userconfig", "NPM_CONFIG_GLOBALCONFIG", "npm_config_globalconfig")
                .mapNotNull { environment[it]?.takeIf(String::isNotBlank)?.let(Path::of) }) {
            files[path.toString()] = diskDigest(path)
        }
        val runtime = NodeJsInterpreterManager.getInstance(project).interpreterRef.referenceName + ":" +
            NpmManager.getInstance(project).packageRef.referenceName
        val policy = project.service<VersionCheckerSettings>().state.deprecatedDependencies
        return BuildFingerprint(files, digest((runtime + policy + environment.toSortedMap().toString()).toByteArray()))
    }
    private fun diskDigest(path: Path): String {
        val virtual = LocalFileSystem.getInstance().findFileByNioFile(path)
        val disk = if (Files.isRegularFile(path)) digest(Files.readAllBytes(path)) else "missing"
        val document = virtual?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
        return disk + ":" + document?.takeIf { FileDocumentManager.getInstance().isDocumentUnsaved(it) }?.let { digest(it.text.toByteArray()) }
    }
    private fun virtualDigest(file: VirtualFile): String =
        digest((FileDocumentManager.getInstance().getCachedDocument(file)?.text?.toByteArray() ?: file.contentsToByteArray()))
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Workspace(val root: VirtualFile, val names: Set<String>)

    /** Only declared members share a workspace context; nested independent projects stay separate. */
    private fun workspace(project: Project, file: VirtualFile): Workspace {
        val manifests = NpmManifest.files(project, BuildSelection(UpdateScope.WHOLE_PROJECT))
        var directory: VirtualFile? = file.parent
        while (directory != null) {
            val workspaceDirectory = directory
            val owner = workspaceDirectory.findChild("package.json")?.let { PsiManager.getInstance(project).findFile(it) }
            val workspaceValue = owner?.let(NpmManifest::root)?.findProperty("workspaces")?.value
            val workspaces = (workspaceValue as? JsonArray) ?: ((workspaceValue as? JsonObject)?.findProperty("packages")?.value as? JsonArray)
            val patterns = workspaces?.valueList?.mapNotNull { (it as? JsonStringLiteral)?.value }.orEmpty()
            fun matches(pattern: String, path: String) = runCatching {
                FileSystems.getDefault().getPathMatcher("glob:${pattern.removePrefix("./").trimEnd('/')}").matches(Path.of(path))
            }.getOrDefault(false)
            fun member(manifest: VirtualFile): Boolean {
                val relative = VfsUtilCore.getRelativePath(manifest.parent, workspaceDirectory) ?: return false
                return patterns.filterNot { it.startsWith('!') }.any { matches(it, relative) } &&
                    patterns.filter { it.startsWith('!') }.none { matches(it.drop(1), relative) }
            }
            if (patterns.isNotEmpty() && (file.parent == directory || member(file))) {
                val names = manifests.filter(::member).mapNotNull { manifest ->
                    val psi = PsiManager.getInstance(project).findFile(manifest)
                    (psi?.let(NpmManifest::root)?.findProperty("name")?.value as? JsonStringLiteral)?.value
                }.toSet()
                return Workspace(workspaceDirectory, names)
            }
            directory = directory.parent
        }
        return Workspace(file.parent, emptySet())
    }
}
