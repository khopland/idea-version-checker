package io.github.khopland.versionchecker.maven

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * Alt+Enter: add the package to `<dependencyManagement>` at [version]. Managed versions win over the ones
 * dependencies bring in, in this module and the modules that inherit from it.
 */
class PinDependencyFix(private val groupId: String, private val artifactId: String, private val version: String) : IntentionAction, PriorityAction {
    override fun getText() = "Pin $groupId:$artifactId to $version in dependency management"
    override fun getFamilyName() = "Pin transitive dependency"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val root = (file as? XmlFile)?.let(MavenPoms::root) ?: return false
        // Another warning's fix may have added it already.
        return (listOf(root) + MavenPoms.profiles(root)).flatMap { MavenPoms.children(it, "dependencyManagement", "dependencies", "dependency") }
            .none { MavenPoms.text(MavenPoms.child(it, "groupId")) == groupId && MavenPoms.text(MavenPoms.child(it, "artifactId")) == artifactId }
    }
    override fun startInWriteAction() = true
    override fun getPriority() = PriorityAction.Priority.TOP

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val root = (file as? XmlFile)?.let(MavenPoms::root) ?: return
        // Before <dependencies>, where Maven's own POMs keep it. Only the new tags are formatted.
        val management = root.findFirstSubTag("dependencyManagement")
        val dependencies = management?.findFirstSubTag("dependencies")
        val dependency = insert(dependencies ?: insert(management ?: insert(root, "dependencyManagement", root.findFirstSubTag("dependencies")),
            "dependencies", null), "dependency", null)
        for ((name, value) in listOf("groupId" to groupId, "artifactId" to artifactId, "version" to version)) {
            dependency.addSubTag(dependency.createChildTag(name, dependency.namespace, value, false), false)
        }
        val created = if (dependencies != null) dependency else if (management != null) dependency.parentTag!! else dependency.parentTag!!.parentTag!!
        CodeStyleManager.getInstance(project).reformat(created)
    }

    /** A new empty [name] tag in [parent], before [anchor] or at the end. In the parent's namespace, so it gets no `xmlns=""`. */
    private fun insert(parent: XmlTag, name: String, anchor: XmlTag?): XmlTag {
        val tag = parent.createChildTag(name, parent.namespace, null, false)
        return if (anchor != null) parent.addBefore(tag, anchor) as XmlTag else parent.addSubTag(tag, false)
    }
}
