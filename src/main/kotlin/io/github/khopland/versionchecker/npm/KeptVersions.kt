package io.github.khopland.versionchecker.npm

import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonPsiUtil
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import io.github.khopland.versionchecker.core.OutdatedVersionInspection

/**
 * `package.json` cannot have suppress comments, so it lists the versions to keep:
 * `"versionChecker": {"keep": {"react": "waiting for the new types"}}`. The value says why, and a plain list of names
 * works too. The list of a package.json above this one in the project counts as well, so the root of a workspace keeps
 * a version for every package.
 */
internal object KeptVersions {
    const val KEY = "versionChecker"
    const val KEEP = "keep"

    /**
     * The name that keeps the version at [element]: the package, also for an override such as `parent>foo`, or the
     * package manager for `packageManager`.
     */
    fun name(element: PsiElement): String? {
        val property = PsiTreeUtil.getParentOfType(element, JsonProperty::class.java, false) ?: return null
        val value = property.value as? JsonStringLiteral ?: return null
        if (property.name == "packageManager" && property.parent?.parent is JsonFile) return value.value.substringBefore('@').ifEmpty { null }
        // `"foo": {".": "1.0.0"}` in npm overrides pins foo itself.
        val owner = if (property.name == ".") property.parent?.parent as? JsonProperty ?: return null else property
        return NpmPackages.target(owner.name)
    }

    fun names(root: JsonObject): Set<String> = when (val keep = (root.findProperty(KEY)?.value as? JsonObject)?.findProperty(KEEP)?.value) {
        is JsonObject -> keep.propertyList.mapTo(HashSet()) { it.name }
        is JsonArray -> keep.valueList.mapNotNullTo(HashSet()) { (it as? JsonStringLiteral)?.value }
        else -> emptySet()
    }

    /** Whether [file], or a package.json above it in the project, keeps [name]. */
    fun keeps(file: JsonFile, name: String): Boolean = name in kept(file)

    /**
     * The names that [file] and the package.json files above it keep. Every declaration asks, for every inspection,
     * so the files are read once per change instead.
     */
    private fun kept(file: JsonFile): Set<String> = CachedValuesManager.getManager(file.project).getCachedValue(file, KEPT, {
        val vfile = file.originalFile.virtualFile
        val index = ProjectFileIndex.getInstance(file.project)
        val psi = PsiManager.getInstance(file.project)
        val roots = if (vfile == null) sequenceOf(file.topLevelValue as? JsonObject) else
            generateSequence(vfile.parent) { it.parent }.takeWhile { index.isInContent(it) }
                .map { (it.findChild("package.json")?.let(psi::findFile) as? JsonFile)?.topLevelValue as? JsonObject }
        CachedValueProvider.Result.create(roots.filterNotNull().flatMapTo(HashSet(), ::names) as Set<String>,
            PsiModificationTracker.MODIFICATION_COUNT, ProjectRootManager.getInstance(file.project))
    }, false)

    private val KEPT = Key.create<CachedValue<Set<String>>>("version-checker.kept-versions")
}

/** Makes a kept version count as suppressed for the editor, the bulk updates and the tool window, and adds the action that keeps one. */
class KeptVersionSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId != OutdatedVersionInspection.SHORT_NAME) return false
        val file = element.containingFile as? JsonFile ?: return false
        if (file.name != "package.json") return false
        return KeptVersions.keeps(file, KeptVersions.name(element) ?: return false)
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> =
        if (toolId == OutdatedVersionInspection.SHORT_NAME && element?.containingFile?.name == "package.json") arrayOf(KeepVersionFix())
        else SuppressQuickFix.EMPTY_ARRAY

    /** Adds the package to `versionChecker.keep` in this package.json, with an empty reason to fill in. */
    private class KeepVersionFix : SuppressQuickFix {
        override fun getFamilyName() = "Keep this version"
        override fun isAvailable(project: Project, context: PsiElement) = context.isValid && KeptVersions.name(context) != null
        override fun isSuppressAll() = false
        override fun startInWriteAction() = false

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.startElement ?: return
            val file = element.containingFile as? JsonFile ?: return
            val name = KeptVersions.name(element) ?: return
            if (!FileModificationService.getInstance().preparePsiElementsForWrite(file)) return
            WriteCommandAction.writeCommandAction(project, file).withName("Keep $name at This Version").run<RuntimeException> {
                val root = file.topLevelValue as? JsonObject ?: return@run
                val generator = JsonElementGenerator(project)
                // The outermost object the fix creates is formatted, or else the new entry.
                var created: PsiElement? = null
                val keep = listOf(KeptVersions.KEY, KeptVersions.KEEP).fold(root as JsonObject?) { owner, key ->
                    val existing = owner?.findProperty(key)
                    when {
                        owner == null -> null
                        existing != null -> existing.value as? JsonObject
                        else -> (JsonPsiUtil.addProperty(owner, generator.createProperty(key, "{}"), false) as JsonProperty).also { created = created ?: it }.value as JsonObject
                    }
                } ?: return@run
                val added = JsonPsiUtil.addProperty(keep, generator.createProperty(name, "\"\""), false)
                val range = (created ?: added).textRange
                CodeStyleManager.getInstance(project).reformatRange(file, range.startOffset, range.endOffset)
            }
        }
    }
}
