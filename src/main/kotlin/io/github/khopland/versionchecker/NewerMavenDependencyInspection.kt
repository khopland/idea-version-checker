package io.github.khopland.versionchecker

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag

class NewerMavenDependencyInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val analysis = MavenDependencyAnalysis.forFile(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                val problem = analysis.problem(tag) ?: return
                val highlight = problem.severity.highlight ?: return
                val fixes = problem.target?.let { arrayOf<LocalQuickFix>(UpdateDependencyVersionFix(it, problem.latest!!)) }
                    ?: emptyArray()
                holder.registerProblem(problem.anchor, problem.message, highlight, *fixes)
            }
        }
    }
}

internal fun isProjectDependency(tag: XmlTag): Boolean = tag.localName == "dependency" &&
    tag.parentTag?.localName == "dependencies" &&
    tag.parentTag?.parentTag?.localName in setOf("project", "profile", "dependencyManagement")

internal fun isProjectPlugin(tag: XmlTag): Boolean = tag.localName == "plugin" &&
    tag.parentTag?.localName == "plugins" &&
    (tag.parentTag?.parentTag?.localName == "build" ||
        (tag.parentTag?.parentTag?.localName == "pluginManagement" &&
            tag.parentTag?.parentTag?.parentTag?.localName == "build"))

/** Only update an exact property reference declared in this POM; inherited/composite values need review. */
internal fun findLocalVersionProperty(versionTag: XmlTag, rawVersion: String?, current: String): XmlTag? {
    val name = rawVersion?.let { Regex("""\$\{([^}]+)}""").matchEntire(it)?.groupValues?.get(1) } ?: return null
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

class UpdateDependencyVersionFix(target: XmlTag, private val latest: String) : LocalQuickFix {
    private val pointer: SmartPsiElementPointer<XmlTag> = SmartPointerManager.createPointer(target)
    private val expected = target.value.trimmedText
    override fun getFamilyName(): String = "Update Maven version"
    override fun getName(): String = "Update ${pointer.element?.localName ?: "version"} to $latest"
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val tag = pointer.element ?: return
        if (tag.value.trimmedText == expected) tag.value.setText(latest)
    }
}
