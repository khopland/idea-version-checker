package io.github.khopland.versionchecker.npm

import com.intellij.json.psi.*
import com.intellij.openapi.components.service
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
        val files = project.service<NpmProjectCache>().files {
            FilenameIndex.getVirtualFilesByName("package.json", GlobalSearchScope.projectScope(project))
                .filter(::supported).sortedBy { it.path }
        }
        return if (selection.scope == UpdateScope.WHOLE_PROJECT) files else files.filter { it.path == selection.currentFile }
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
    /** Only the workspace root's overrides participate in npm resolution. Never rewrite them implicitly. */
    fun overrideReviewReason(project: Project, file: VirtualFile, declaration: VersionDeclaration): String? {
        val workspace = NpmWorkspaces.resolve(project, file)
        val rootFile = workspace.root.findChild("package.json") ?: return "npm root manifest is unavailable; review overrides"
        val rootPsi = com.intellij.psi.PsiManager.getInstance(project).findFile(rootFile)
            ?: return "npm root manifest is unavailable; review overrides"
        val root = root(rootPsi) ?: return "npm root manifest is invalid; review overrides"
        val overridesProperty = root.findProperty("overrides") ?: return null
        val overrides = overridesProperty.value as? JsonObject
            ?: return "npm overrides need manual review before updating dependencies"
        val dependencyName = declaration.id.location.substringAfter('/')
        val names = setOf(dependencyName, declaration.artifact.name)
        val rootDeclaration = declaration.id.file == rootFile.path
        // A $ reference follows its root declaration automatically, including npm aliases.
        fun safeReference(value: JsonValue?): Boolean = rootDeclaration && value is JsonStringLiteral &&
            value.value == "$" + dependencyName
        fun packageName(key: String): String {
            val versionSeparator = key.lastIndexOf('@')
            return if (versionSeparator > 0) key.substring(0, versionSeparator) else key
        }
        fun affected(entries: JsonObject): Boolean {
            if (entries.propertyList.map { it.name }.distinct().size != entries.propertyList.size) return true
            return entries.propertyList.any { entry ->
                // The parent already checks the object self override. It is not a package key.
                if (entry.name == ".") return@any false
                val value = entry.value
                val self = if (value is JsonObject) value.findProperty(".")?.value else value
                val overrideName = packageName(entry.name)
                // Changing a referenced spec can also invalidate a different direct override:
                // dependencies alpha=beta=1.2.3, overrides alpha=$beta, then update beta.
                val changesOtherReference = self is JsonStringLiteral && self.value == "$" + dependencyName &&
                    overrideName != dependencyName
                changesOtherReference ||
                    (overrideName in names && self != null && !safeReference(self)) ||
                    (value is JsonObject && affected(value))
            }
        }
        return if (affected(overrides))
            "npm root overrides may conflict with this dependency update; review the dependency and override together"
        else null
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
