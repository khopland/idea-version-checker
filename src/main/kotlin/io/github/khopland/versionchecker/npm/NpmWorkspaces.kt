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

internal data class NpmWorkspace(val root: VirtualFile, val names: Set<String>, val manifests: List<VirtualFile>, val valid: Boolean = true)

/** Workspace ownership and package-manager selection, shared by discovery and edit validation. */
internal object NpmWorkspaces {
    val otherManagerFiles = NodePackageManager.entries.filter { it != NodePackageManager.NPM }.flatMap { it.markers }.distinct()

    fun manager(project: Project, file: VirtualFile): NodePackageManager? {
        val workspace = resolve(project, file)
        if (!workspace.valid || !NpmManifest.supported(project, file)) return null
        val rootFile = workspace.root.findChild("package.json") ?: return null
        if (!NpmManifest.supported(project, rootFile)) return null
        return NodePackageManager.detect(project, file, workspace.root)
    }

    fun supportsManifest(project: Project, file: VirtualFile, manager: NodePackageManager = NodePackageManager.NPM): Boolean =
        manager(project, file) == manager

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
        val workspaceValue = owner?.let { NpmManifest.ownershipRoot(it, currentDocument = true) }?.findProperty("workspaces")?.value
        val workspaces = (workspaceValue as? JsonArray) ?: ((workspaceValue as? JsonObject)?.findProperty("packages")?.value as? JsonArray)
        val pnpm = NodePackageManager.detect(project, rootFile, directory) == NodePackageManager.PNPM
        val yaml = directory.findChild("pnpm-workspace.yaml")?.takeUnless { it.isDirectory }
        val patterns = if (pnpm && yaml != null) PnpmWorkspace.patterns(yaml) else if (pnpm) emptyList()
            else workspaces?.valueList?.mapNotNull { (it as? JsonStringLiteral)?.value }.orEmpty()
        if (patterns == null) {
            // Invalid ownership cannot turn descendants into independent npm projects.
            val descendants = NpmManifest.files(project, BuildSelection(UpdateScope.WHOLE_PROJECT))
                .filter { VfsUtilCore.isAncestor(directory, it, false) }
            return NpmWorkspace(directory, emptySet(), (listOf(rootFile) + descendants).distinct(), valid = false)
        }
        if (patterns.isEmpty()) return if (pnpm && yaml != null) NpmWorkspace(directory, emptySet(), listOf(rootFile)) else null
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
