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
import com.intellij.util.text.minimatch.Minimatch
import com.intellij.util.text.minimatch.MinimatchOptions
import io.github.khopland.versionchecker.*
import io.github.khopland.versionchecker.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.sync.Semaphore

internal class NpmBuildSystemAdapter : BuildSystemAdapter {
    override val id = "npm"
    override val displayName = "npm"
    override val capabilities = AdapterCapabilities()
    private val registrySlots = Semaphore(4)
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
                val managers = when (engineManager) {
                    is JsonObject -> listOf(engineManager)
                    is JsonArray -> engineManager.valueList
                    else -> return false
                }
                // Conflicting or malformed entries do not establish an npm-only project.
                if (managers.isEmpty() || managers.any {
                        ((it as? JsonObject)?.findProperty("name")?.value as? JsonStringLiteral)?.value != "npm"
                    }) return false
                explicitNpm = true
            }
        }
        return explicitNpm || otherManagerFiles.none { workspaceRoot.findChild(it) != null || file.parent.findChild(it) != null }
    }
    override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? = snapshot(project, file, mutableMapOf())

    private fun snapshot(project: Project, file: VirtualFile, fingerprints: MutableMap<VirtualFile, BuildFingerprint>): BuildSnapshot? {
        if (!NpmManifest.supported(file) || !validManifest(project, file)) return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val workspace = workspace(project, file)
        val localNames = workspace.names
        val declarations = NpmManifest.declarations(psi).map { declaration ->
            if (declaration.artifact.name in localNames) declaration.copy(baseline = "") else declaration
        }
        return BuildSnapshot(BuildContextId(id, workspace.root.path, file.path), file.path,
            fingerprints.getOrPut(workspace.root) { fingerprint(project, workspace) }, declarations)
    }
    override fun isCurrent(project: Project, snapshot: BuildSnapshot): Boolean {
        val file = findFile(project, snapshot.sourceFile) ?: return false
        val workspace = workspace(project, file)
        return validManifest(project, file) && workspace.root.path == snapshot.context.root && snapshot.fingerprint == fingerprint(project, workspace)
    }
    override suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot> = readAction {
        val fingerprints = mutableMapOf<VirtualFile, BuildFingerprint>()
        NpmManifest.files(project, selection).mapNotNull { snapshot(project, it, fingerprints) }
    }
    override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
        val directory = Path.of(snapshot.context.root)
        val policy = readAction { project.service<VersionCheckerSettings>().state.deprecatedDependencies }
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { it.split('=', limit = 2).map(String::trim) }
            .associate { it[0] to (it.getOrNull(1)?.takeIf(String::isNotBlank) ?: "Deprecated by project policy") }
        return checkNpmVersions(snapshot.declarations, mode, policy, registrySlots,
            metadata = { NpmRegistry.metadata(project, directory, it) },
            deprecated = { name, version -> NpmRegistry.deprecated(project, directory, name, version) })
    }
    override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan = readAction {
        check(areCurrent(project, reports.keys)) { "npm manifests or configuration changed during the check. Run it again." }
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
        BulkUpdatePlan(edits, skipped.distinct(), isCurrent = { areCurrent(project, reports.keys) },
            followUp = if (edits.isEmpty()) emptyList() else listOf(NpmUpdateGuidance.message))
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

    private fun areCurrent(project: Project, snapshots: Collection<BuildSnapshot>): Boolean {
        val fingerprints = mutableMapOf<VirtualFile, BuildFingerprint>()
        return snapshots.all { snapshot ->
            val file = findFile(project, snapshot.sourceFile) ?: return@all false
            val workspace = workspace(project, file)
            validManifest(project, file) && workspace.root.path == snapshot.context.root &&
                snapshot.fingerprint == fingerprints.getOrPut(workspace.root) { fingerprint(project, workspace) }
        }
    }

    private fun fingerprint(project: Project, workspace: Workspace): BuildFingerprint {
        // Only this workspace's manifests affect local package identities and workspace-wide edits.
        val cache = project.service<NpmProjectCache>()
        val files = cache.manifestDigests(workspace) {
            workspace.manifests.associate { it.path to virtualDigest(project, it) }
        }.toMutableMap()
        // Registry commands run at the workspace root; member-local npmrc files do not configure them.
        var ancestor: Path? = Path.of(workspace.root.path)
        while (ancestor != null) {
            for (name in listOf(".npmrc", "package.json") + otherManagerFiles) {
                val path = ancestor.resolve(name)
                files["disk-config:$path"] = if (name in otherManagerFiles) Files.exists(path).toString() else diskDigest(path)
            }
            ancestor = ancestor.parent
        }
        var virtualAncestor: VirtualFile? = workspace.root
        while (virtualAncestor != null) {
            for (name in listOf(".npmrc", "package.json") + otherManagerFiles) {
                val config = virtualAncestor.findChild(name)
                files["virtual-config:${virtualAncestor.path}/$name"] =
                    if (name in otherManagerFiles) (config != null).toString() else config?.let { virtualDigest(project, it) } ?: "missing"
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
    private fun virtualDigest(project: Project, file: VirtualFile): String =
        project.service<NpmProjectCache>().digest(file) {
            digest(FileDocumentManager.getInstance().getCachedDocument(file)?.text?.toByteArray() ?: file.contentsToByteArray())
        }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    internal data class Workspace(val root: VirtualFile, val names: Set<String>, val manifests: List<VirtualFile>)

    /** Only declared members share a workspace context; nested independent projects stay separate. */
    private fun workspace(project: Project, file: VirtualFile): Workspace =
        project.service<NpmProjectCache>().workspace(file) {
            val cache = project.service<NpmProjectCache>()
            var directory: VirtualFile? = file.parent
            var result: Workspace? = null
            while (directory != null) {
                val workspaceDirectory = directory
                val owner = cache.owner(workspaceDirectory) { workspaceOwner(project, workspaceDirectory) }
                if (owner != null && file in owner.manifests) {
                    result = owner
                    break
                }
                directory = directory.parent
            }
            result ?: Workspace(file.parent, emptySet(), listOf(file))
        }

    private fun workspaceOwner(project: Project, directory: VirtualFile): Workspace? {
        val rootFile = directory.findChild("package.json") ?: return null
        val owner = PsiManager.getInstance(project).findFile(rootFile)
        val workspaceValue = owner?.let(NpmManifest::root)?.findProperty("workspaces")?.value
        val workspaces = (workspaceValue as? JsonArray) ?: ((workspaceValue as? JsonObject)?.findProperty("packages")?.value as? JsonArray)
        val patterns = workspaces?.valueList?.mapNotNull { (it as? JsonStringLiteral)?.value }.orEmpty()
        if (patterns.isEmpty()) return null
        // npm uses minimatch: a globstar can match zero directory levels, unlike Java's glob matcher.
        val options = MinimatchOptions(nocomment = true, nonegate = true)
        fun matchers(pattern: String) = expandBraceAlternatives(pattern.removePrefix("./").trimEnd('/'))
            .map { Minimatch(it, options) }.toList()
        val included = patterns.filterNot { it.startsWith('!') }.flatMap(::matchers)
        val excluded = patterns.filter { it.startsWith('!') }.flatMap { matchers(it.drop(1)) }
        val members = NpmManifest.files(project, BuildSelection(UpdateScope.WHOLE_PROJECT)).filter { manifest ->
            val relative = VfsUtilCore.getRelativePath(manifest.parent, directory)
            relative != null && included.any { it.match(relative) } && excluded.none { it.match(relative) }
        }
        val names = members.mapNotNull { manifest ->
            val psi = PsiManager.getInstance(project).findFile(manifest)
            (psi?.let(NpmManifest::root)?.findProperty("name")?.value as? JsonStringLiteral)?.value
        }.toSet()
        return Workspace(directory, names, (listOf(rootFile) + members).distinct())
    }

    /** The bundled minimatch port leaves brace expansion unimplemented. Expand alternatives first. */
    private fun expandBraceAlternatives(pattern: String): Sequence<String> = sequence {
        val group = Regex("""\{([^{}]*,[^{}]*)}""").find(pattern)
        if (group == null) {
            yield(pattern)
        } else {
            for (alternative in group.groupValues[1].split(',')) {
                yieldAll(expandBraceAlternatives(pattern.replaceRange(group.range, alternative)))
            }
        }
    }
}
