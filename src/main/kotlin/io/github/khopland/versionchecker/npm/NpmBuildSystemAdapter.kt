package io.github.khopland.versionchecker.npm

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

    private fun validManifest(project: Project, file: VirtualFile): Boolean {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return false
        if (NpmManifest.root(psi) == null) return false
        var directory: VirtualFile? = file.parent
        while (directory != null) {
            val ancestor = directory.findChild("package.json")?.let { PsiManager.getInstance(project).findFile(it) }
            val manager = ancestor?.let(NpmManifest::root)?.findProperty("packageManager")?.value as? JsonStringLiteral
            if (manager != null) return manager.value.startsWith("npm@")
            directory = directory.parent
        }
        return true
    }
    override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? {
        if (!NpmManifest.supported(file) || !validManifest(project, file)) return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val localNames = workspaceNames(project, file)
        val declarations = NpmManifest.declarations(psi).map { declaration ->
            if (declaration.artifact.name in localNames) declaration.copy(baseline = "") else declaration
        }
        return BuildSnapshot(BuildContextId(id, file.parent.path, file.path), file.path,
            fingerprint(project, file), declarations)
    }
    override fun isCurrent(project: Project, snapshot: BuildSnapshot): Boolean {
        val file = findFile(project, snapshot.sourceFile) ?: return false
        return validManifest(project, file) && snapshot.fingerprint == fingerprint(project, file)
    }
    override suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot> = readAction {
        NpmManifest.files(project, selection).mapNotNull { snapshot(project, it) }
    }
    override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
        val directory = Path.of(snapshot.sourceFile).parent
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
                val parsed = NpmSelector.parse(declaration.artifact.name, declaration.selector)
                skipped += "${declaration.id.file}: ${declaration.id.location}: " +
                    if (parsed != null || declaration.selector.startsWith("workspace:")) "local workspace dependency; managed in the project"
                    else "selector '${declaration.selector}' needs manual review (supported: exact, ^ or ~ versions and npm aliases)"
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
    private fun findFile(project: Project, path: String): VirtualFile? =
        NpmManifest.files(project, BuildSelection(UpdateScope.CURRENT_FILE, path)).singleOrNull()

    private fun fingerprint(project: Project, file: VirtualFile): BuildFingerprint {
        // Workspace identities affect whether a dependency is local; include every project manifest.
        val files = NpmManifest.files(project, BuildSelection(UpdateScope.WHOLE_PROJECT))
            .associate { it.path to virtualDigest(it) }.toMutableMap()
        files[file.path] = virtualDigest(file)
        var ancestor: Path? = Path.of(file.path).parent
        while (ancestor != null) {
            for (name in listOf(".npmrc", "package.json")) {
                val path = ancestor.resolve(name)
                files["disk-config:$path"] = diskDigest(path)
            }
            ancestor = ancestor.parent
        }
        var virtualAncestor: VirtualFile? = file.parent
        while (virtualAncestor != null) {
            for (name in listOf(".npmrc", "package.json")) {
                files["virtual-config:${virtualAncestor.path}/$name"] = virtualAncestor.findChild(name)?.let(::virtualDigest) ?: "missing"
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

    private fun workspaceNames(project: Project, file: VirtualFile): Set<String> {
        val names = mutableSetOf<String>()
        val manifests = NpmManifest.files(project, BuildSelection(UpdateScope.WHOLE_PROJECT))
        var directory: VirtualFile? = file.parent
        while (directory != null) {
            val owner = directory.findChild("package.json")?.let { PsiManager.getInstance(project).findFile(it) }
            val root = owner?.let(NpmManifest::root)
            val workspaceValue = root?.findProperty("workspaces")?.value
            val workspaces = (workspaceValue as? JsonArray) ?: ((workspaceValue as? JsonObject)?.findProperty("packages")?.value as? JsonArray)
            val patterns = workspaces?.valueList?.mapNotNull { (it as? JsonStringLiteral)?.value }.orEmpty()
            if (patterns.isNotEmpty()) {
                fun matches(pattern: String, path: String) = runCatching {
                    FileSystems.getDefault().getPathMatcher("glob:${pattern.trimEnd('/')}").matches(Path.of(path))
                }.getOrDefault(false)
                for (manifest in manifests) {
                    val relative = VfsUtilCore.getRelativePath(manifest.parent, directory) ?: continue
                    if (patterns.filterNot { it.startsWith('!') }.any { matches(it, relative) } &&
                        patterns.filter { it.startsWith('!') }.none { matches(it.drop(1), relative) }) {
                        val psi = PsiManager.getInstance(project).findFile(manifest)
                        val name = psi?.let(NpmManifest::root)?.findProperty("name")?.value as? JsonStringLiteral
                        name?.value?.let(names::add)
                    }
                }
            }
            directory = directory.parent
        }
        return names
    }
}
