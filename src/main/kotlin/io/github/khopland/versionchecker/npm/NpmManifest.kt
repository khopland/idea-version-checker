package io.github.khopland.versionchecker.npm

import com.intellij.json.psi.*
import com.intellij.openapi.project.DumbService
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.khopland.versionchecker.core.*

internal object NpmManifest {
    val sections = setOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")
    private val excluded = setOf("node_modules", "bower_components", ".git", ".idea", ".npm", ".yarn", ".pnpm-store", "vendor", "dist", "build", "coverage", ".next", ".nuxt")
    fun supported(file: VirtualFile): Boolean {
        if (file.isDirectory || file.name != "package.json") return false
        var parent = file.parent
        while (parent != null) {
            if (parent.name in excluded) return false
            parent = parent.parent
        }
        return true
    }
    fun files(project: Project, selection: BuildSelection): List<VirtualFile> {
        if (selection.scope == UpdateScope.CURRENT_FILE && selection.currentFile == null) return emptyList()
        if (DumbService.isDumb(project)) return emptyList()
        return FilenameIndex.getVirtualFilesByName("package.json", GlobalSearchScope.projectScope(project))
            .filter { supported(it) && (selection.scope == UpdateScope.WHOLE_PROJECT || it.path == selection.currentFile) }
            .sortedBy { it.path }
    }
    fun root(file: PsiFile): JsonObject? {
        if (file !is JsonFile || !supported(file.virtualFile ?: return null) ||
            PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) != null) return null
        val root = file.topLevelValue as? JsonObject ?: return null
        return root.takeIf { it.propertyList.map { property -> property.name }.distinct().size == it.propertyList.size }
    }

    fun values(file: PsiFile): Map<DeclarationId, JsonStringLiteral> {
        val root = root(file) ?: return emptyMap()
        return buildMap {
            for (section in sections) {
                val entries = (root.findProperty(section)?.value as? JsonObject)?.propertyList ?: continue
                // npm's JSON parser takes the last duplicate; do not edit a different occurrence.
                if (entries.map { it.name }.distinct().size != entries.size) continue
                for (entry in entries) {
                    val value = entry.value as? JsonStringLiteral ?: continue
                    put(DeclarationId(file.virtualFile.path, "$section/${entry.name}"), value)
                }
            }
        }
    }
    fun reviewReason(declaration: VersionDeclaration): String? {
        if (declaration.id.location.startsWith("peerDependencies/")) return "peer compatibility requires manual review"
        if (declaration.baseline.isNotEmpty()) return null
        return when {
            NpmSelector.parse(declaration.artifact.name, declaration.selector) != null || declaration.selector.startsWith("workspace:") ->
                "local workspace dependency; managed in the project"
            declaration.selector.startsWith("file:") || declaration.selector.startsWith("link:") ->
                "local path dependency; managed in the project"
            else -> "selector '${declaration.selector}' needs manual review (supported: exact, ^ or ~ versions and npm aliases)"
        }
    }

    fun declarations(file: PsiFile): List<VersionDeclaration> = values(file).map { (id, value) ->
        val name = (value.parent as JsonProperty).name
        val selector = NpmSelector.parse(name, value.value)
        VersionDeclaration(id, ArtifactId("npm", selector?.packageName ?: name), value.value, selector?.baseline?.toString()?.takeUnless { id.location.startsWith("peerDependencies/") }.orEmpty())
    }
}
