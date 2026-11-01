package io.github.khopland.versionchecker.npm

import com.intellij.codeInspection.*
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.khopland.versionchecker.VersionCheckerSettings

/**
 * Finds a package that `package.json` lists twice. A dev dependency that is also a dependency is installed anyway,
 * and `optionalDependencies` overrides the same name in `dependencies`. Peer dependencies are often repeated
 * in `devDependencies` on purpose, so they are not reported. No network access.
 */
class DuplicateDependencyInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (!VersionCheckerSettings.getInstance().enabled) return null
        if (file !is JsonFile || file.name != "package.json" || "/node_modules/" in file.virtualFile?.path.orEmpty()) return null
        val root = file.topLevelValue as? JsonObject ?: return null
        fun section(name: String) = (root.findProperty(name)?.value as? JsonObject)?.propertyList.orEmpty()
        val dependencies = section("dependencies").associateBy { it.name }
        val optional = section("optionalDependencies").associateBy { it.name }
        fun problem(property: JsonProperty, message: String, section: String) = manager.createProblemDescriptor(property.nameElement, message,
            isOnTheFly, arrayOf(RemoveDependencyFix(section)), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        fun version(property: JsonProperty) = (property.value as? JsonStringLiteral)?.value?.let { " ($it)" }.orEmpty()

        val devProblems = section("devDependencies").mapNotNull { property ->
            val (other, kept) = dependencies[property.name]?.let { "dependencies" to it }
                ?: optional[property.name]?.let { "optionalDependencies" to it } ?: return@mapNotNull null
            problem(property, "${property.name} is also in $other${version(kept)}", "devDependencies")
        }
        val overridden = section("dependencies").mapNotNull { property ->
            val kept = optional[property.name] ?: return@mapNotNull null
            problem(property, "${property.name} is also in optionalDependencies${version(kept)}, which overrides this entry", "dependencies")
        }
        return (devProblems + overridden).toTypedArray()
    }
}

/** Deletes the property and its comma. */
internal class RemoveDependencyFix(private val section: String) : LocalQuickFix {
    override fun getName() = "Remove from $section"
    override fun getFamilyName() = "Remove duplicate npm dependency"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val property = PsiTreeUtil.getParentOfType(descriptor.psiElement, JsonProperty::class.java, false) ?: return
        val parent = property.parent ?: return
        val next = PsiTreeUtil.skipWhitespacesForward(property)
        if (next?.text == ",") {
            parent.deleteChildRange(property, (next.nextSibling as? PsiWhiteSpace) ?: next)
            return
        }
        val previous = PsiTreeUtil.skipWhitespacesBackward(property)
        if (previous?.text == ",") parent.deleteChildRange(previous, property) else property.delete()
    }
}
