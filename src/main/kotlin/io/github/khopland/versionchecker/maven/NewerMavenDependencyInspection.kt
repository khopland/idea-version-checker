package io.github.khopland.versionchecker.maven

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.codeStyle.CodeStyleManager

class NewerMavenDependencyInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val analysis = MavenDependencyAnalysis.forFile(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                val problem = analysis.problem(tag) ?: return
                val highlight = problem.severity.highlight ?: return
                val fixes = analysis.quickFixes(tag, problem)
                holder.registerProblem(problem.anchor, problem.message, highlight, *fixes)
            }
        }
    }
}

internal fun isProjectDependency(tag: XmlTag): Boolean = tag.localName == "dependency" &&
    tag.parentTag?.localName == "dependencies" &&
    tag.parentTag?.parentTag?.localName in setOf("project", "profile", "dependencyManagement")

internal fun isProjectParent(tag: XmlTag): Boolean = tag.localName == "parent" &&
    tag.parentTag?.localName == "project" && tag.parentTag?.parentTag == null

internal fun isProjectPlugin(tag: XmlTag): Boolean = tag.localName == "plugin" &&
    tag.parentTag?.localName == "plugins" &&
    (tag.parentTag?.parentTag?.localName == "build" ||
        (tag.parentTag?.parentTag?.localName == "pluginManagement" &&
            tag.parentTag?.parentTag?.parentTag?.localName == "build"))

/** Only update an exact property reference declared in this POM; inherited/composite values need review. */
internal fun findLocalVersionProperty(versionTag: XmlTag, rawVersion: String?, current: String): XmlTag? {
    val name = rawVersion?.let(::versionPropertyName) ?: return null
    var parent = versionTag.parentTag
    while (parent != null) {
        if (parent.localName == "profile" || parent.localName == "project") {
            val property = parent.findFirstSubTag("properties")?.findFirstSubTag(name)
            if (property != null) return property.takeIf { it.value.trimmedText == current }
        }
        parent = parent.parentTag
    }
    return null
}

internal fun versionPropertyName(raw: String): String? = Regex("""\$\{([^}]+)}""").matchEntire(raw)?.groupValues?.get(1)

class UpdateDependencyVersionFix @JvmOverloads constructor(target: XmlTag, private val latest: String,
                                 private val actionName: String? = null,
                                 private val isCurrent: () -> Boolean = { true }) : LocalQuickFix {
    private val pointer: SmartPsiElementPointer<XmlTag> = SmartPointerManager.createPointer(target)
    private val expected = target.value.trimmedText
    override fun getFamilyName(): String = "Update Maven version"
    override fun getName(): String = actionName ?: "Update ${pointer.element?.localName ?: "version"} to $latest"
    override fun getElementToMakeWritable(currentFile: PsiFile): PsiElement? = pointer.element
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val tag = pointer.element ?: return
        if (isCurrent() && tag.value.trimmedText == expected) tag.value.setText(latest)
    }
}

class OverrideDependencyVersionFix(dependency: XmlTag, private val latest: String,
                                   private val isCurrent: () -> Boolean = { true }) : LocalQuickFix {
    private val pointer = SmartPointerManager.createPointer(dependency)
    private val expected = dependency.text
    override fun getFamilyName() = "Override Maven dependency version locally"
    override fun getName() = "Override version locally with $latest"
    override fun getElementToMakeWritable(currentFile: PsiFile): PsiElement? = pointer.element
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val dependency = pointer.element ?: return
        if (!isCurrent() || dependency.text != expected) return
        val version = dependency.findFirstSubTag("version")
        if (version != null) version.value.setText(latest)
        else {
            val artifact = dependency.findFirstSubTag("artifactId") ?: return
            dependency.addAfter(dependency.createChildTag("version", dependency.namespace, latest, false), artifact)
            CodeStyleManager.getInstance(project).reformat(dependency)
        }
    }
}
