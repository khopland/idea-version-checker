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
import org.jetbrains.idea.maven.project.MavenProjectsManager
import io.github.khopland.versionchecker.notifyStaleVersionFix

class NewerMavenDependencyInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val analysis = MavenDependencyAnalysis.forFile(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val propertyProblems = analysis.propertyProblems().groupBy { it.anchor }
        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                val problems = propertyProblems[tag].orEmpty()
                val propertyFixes = if (problems.isEmpty()) emptyArray() else analysis.propertyQuickFixes(tag)
                for (problem in problems) {
                    val highlight = problem.severity.highlight ?: continue
                    holder.registerProblem(tag, problem.message, highlight, *propertyFixes)
                }
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

/** Resolve the global local property owner; profile properties also override root dependencies. */
internal fun findLocalVersionPropertyOwner(context: XmlTag, name: String,
    activeProfiles: Collection<String>? = MavenProjectsManager.getInstance(context.project)
        .findProject(context.containingFile.virtualFile)?.activatedProfilesIds?.enabledProfiles): XmlTag? {
    val ancestors = generateSequence(context) { it.parentTag }.toList()
    val root = ancestors.lastOrNull { it.localName == "project" } ?: return null
    val profiles = root.findFirstSubTag("profiles")?.subTags.orEmpty().filter { it.localName == "profile" }
    val overrides = profiles.filter { profile ->
        activeProfiles == null || profile.findFirstSubTag("id")?.value?.trimmedText in activeProfiles
    }.mapNotNull { profile -> profile.findFirstSubTag("properties")?.findFirstSubTag(name) }
    if (overrides.isNotEmpty()) {
        val owner = overrides.singleOrNull() ?: return null
        // Without an imported model only an enclosing profile establishes local ownership.
        if (activeProfiles == null && owner.parentTag?.parentTag !in ancestors) return null
        return owner
    }
    return root.findFirstSubTag("properties")?.findFirstSubTag(name)
}

/** Only update a literal owner of an exact reference; ambiguous/inherited/composite values need review. */
internal fun findLocalVersionProperty(versionTag: XmlTag, rawVersion: String?, current: String,
    activeProfiles: Collection<String>? = MavenProjectsManager.getInstance(versionTag.project)
        .findProject(versionTag.containingFile.virtualFile)?.activatedProfilesIds?.enabledProfiles): XmlTag? {
    val name = rawVersion?.let(::versionPropertyName) ?: return null
    return findLocalVersionPropertyOwner(versionTag, name, activeProfiles)?.takeIf { it.value.trimmedText == current }
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
        val tag = pointer.element
        if (tag == null || !isCurrent() || tag.value.trimmedText != expected) {
            notifyStaleVersionFix(project, "maven", descriptor)
            return
        }
        tag.value.setText(latest)
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
        val dependency = pointer.element
        if (dependency == null || !isCurrent() || dependency.text != expected) {
            notifyStaleVersionFix(project, "maven", descriptor)
            return
        }
        val version = dependency.findFirstSubTag("version")
        if (version != null) version.value.setText(latest)
        else {
            val artifact = dependency.findFirstSubTag("artifactId") ?: return
            dependency.addAfter(dependency.createChildTag("version", dependency.namespace, latest, false), artifact)
            CodeStyleManager.getInstance(project).reformat(dependency)
        }
    }
}
