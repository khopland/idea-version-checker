package io.github.khopland.versionchecker.maven

import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.xml.XmlTag
import io.github.khopland.versionchecker.*
import io.github.khopland.versionchecker.core.EditInputCapture

/** Shadow a proven parent property; the parent declaration and its version reference remain intact. */
internal class DefineInheritedMavenPropertyFix(
    root: XmlTag,
    private val property: String,
    private val latest: String,
    private val consumers: List<String>,
    private val uncheckedManagement: List<String>,
    private val isCurrent: () -> Boolean,
) : LocalQuickFix {
    private val pointer = SmartPointerManager.createPointer(root)
    private val expected = root.text
    private var prepareValidation: EditInputCapture? = null
    internal fun withInputValidation(prepare: EditInputCapture) = apply { prepareValidation = prepare }
    override fun getFamilyName() = "Define inherited Maven version property locally"
    override fun getName() = "Define <$property> as $latest in this POM (${consumers.size} checked artifact${if (consumers.size == 1) "" else "s"})"
    override fun startInWriteAction() = false
    override fun getElementToMakeWritable(currentFile: PsiFile): PsiElement? = pointer.element

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
        val root = pointer.element ?: return IntentionPreviewInfo.EMPTY
        val copy = root.copy() as XmlTag
        insert(copy)
        return IntentionPreviewInfo.CustomDiff(XmlFileType.INSTANCE,
            "${root.containingFile.name} — checked: ${consumers.joinToString()}; " +
                (if (uncheckedManagement.isEmpty()) "" else "unused managed entries changed but unchecked: ${uncheckedManagement.joinToString()}; ") +
                "child modules inherit this property",
            root.text, copy.text)
    }

    private fun insert(root: XmlTag): XmlTag {
        val properties = root.findFirstSubTag("properties") ?: root.addAfter(
            root.createChildTag("properties", root.namespace, null, false),
            root.subTags.lastOrNull { it.localName in setOf("modelVersion", "parent", "groupId", "artifactId", "version", "packaging", "name", "description", "url") }
        ) as XmlTag
        properties.addSubTag(properties.createChildTag(property, root.namespace, latest, false), false)
        return properties
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) =
        CheckPerformance.locally(CheckPerformance.start(CheckPerformance.Stage.FIX_INVOKED)) {
            val root = pointer.element
            if (root == null || !isCurrent() || root.text != expected) {
                notifyStaleVersionFix(project, "maven", descriptor)
                return@locally
            }
            if (!FileModificationService.getInstance().preparePsiElementsForWrite(listOf(root))) return@locally
            val edit = object : VersionEdit {
                override val element get() = pointer.element
                override val expected = this@DefineInheritedMavenPropertyFix.expected
                override val latest = this@DefineInheritedMavenPropertyFix.latest
                override val location = root.containingFile.name
                override val buildSystemId = "maven"
                override fun isValid() = element?.let { it.text == expected &&
                    it.findFirstSubTag("properties")?.findFirstSubTag(property) == null } == true
                override fun apply() {
                    CodeStyleManager.getInstance(project).reformat(insert(element!!))
                }
            }
            val plan = BulkUpdatePlan(listOf(edit), emptyList(), isCurrent, prepareValidation = prepareValidation)
            if (!plan.apply(project, CheckPerformance.current())) notifyStaleVersionFix(project, "maven", descriptor)
            else notifyVersionUpdates(project, emptyList(), setOf("maven"), changedFiles = plan.appliedChanges)
        }
}
