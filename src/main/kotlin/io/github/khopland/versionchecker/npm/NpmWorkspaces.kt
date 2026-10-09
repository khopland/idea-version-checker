package io.github.khopland.versionchecker.npm

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiManager
import com.intellij.util.text.minimatch.Minimatch
import com.intellij.util.text.minimatch.MinimatchOptions
import io.github.khopland.versionchecker.core.BuildSelection
import io.github.khopland.versionchecker.core.UpdateScope

internal data class NpmWorkspace(val root: VirtualFile, val names: Set<String>, val manifests: List<VirtualFile>)

/** Workspace ownership and package-manager selection, shared by discovery and edit validation. */
internal object NpmWorkspaces {
    val otherManagerFiles = listOf("pnpm-lock.yaml", "pnpm-workspace.yaml", "yarn.lock", "bun.lock", "bun.lockb")

    fun supportsManifest(project: Project, file: VirtualFile): Boolean {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return false
        val manifest = NpmManifest.root(psi) ?: return false
        val workspaceRoot = resolve(project, file).root
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

    /** Only declared members share a workspace context; nested independent projects stay separate. */
    fun resolve(project: Project, file: VirtualFile): NpmWorkspace =
        project.service<NpmProjectCache>().workspace(file) {
            val cache = project.service<NpmProjectCache>()
            var directory: VirtualFile? = file.parent
            var result: NpmWorkspace? = null
            while (directory != null) {
                val workspaceDirectory = directory
                val owner = cache.owner(workspaceDirectory) { workspaceOwner(project, workspaceDirectory) }
                if (owner != null && file in owner.manifests) {
                    result = owner
                    break
                }
                directory = directory.parent
            }
            result ?: NpmWorkspace(file.parent, emptySet(), listOf(file))
        }

    private fun workspaceOwner(project: Project, directory: VirtualFile): NpmWorkspace? {
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
        return NpmWorkspace(directory, names, (listOf(rootFile) + members).distinct())
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
