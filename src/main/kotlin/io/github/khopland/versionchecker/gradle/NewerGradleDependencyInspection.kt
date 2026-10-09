package io.github.khopland.versionchecker.gradle

import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInspection.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import io.github.khopland.versionchecker.*
import io.github.khopland.versionchecker.core.*

internal class GradleVersionEdit(file: PsiFile, val range: TextRange, override val latest: String,
                                 override val location: String, private val original: String) : VersionEdit {
    constructor(file: PsiFile, range: TextRange, latest: String, location: String) :
        this(file, range, latest, location, file.text)
    private val pointer = SmartPointerManager.createPointer(file)
    override val element: PsiFile? get() = pointer.element
    override val expected = range.substring(original)
    override fun isValid() = element?.text == original
    override fun apply() {
        val file = element ?: return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: error("Gradle file has no document")
        document.replaceString(range.startOffset, range.endOffset, latest)
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
    }
}

class NewerGradleDependencyInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file.virtualFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val adapter = BuildSystemAdapter.find("gradle") as? GradleBuildSystemAdapter ?: return PsiElementVisitor.EMPTY_VISITOR
        val snapshot = adapter.inspectionSnapshot(holder.project, file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val report = holder.project.service<VersionCheckService>().updates(adapter, snapshot) ?: return PsiElementVisitor.EMPTY_VISITOR
        val options = holder.project.service<VersionCheckerSettings>().state
        val original = holder.file.text
        val declarations = GradleDeclarations.parse(file.path, original)
        // Duplicate candidates remain ambiguous; never choose one arbitrarily for a quick fix.
        val candidatesById = report.candidates.groupBy { it.declaration.id }.mapValues { it.value.singleOrNull() }
        val noticesById = report.notices.withIndex().groupBy { it.value.declaration.id }.mapValues { it.value.first() }
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                for ((range, consumers) in declarations.groupBy { it.range }) {
                    if (range == null) continue
                    val candidates = consumers.map { candidatesById[it.declaration.id] }
                    // Preserve report order when several consumers of a shared version have notices.
                    val notice = consumers.mapNotNull { noticesById[it.declaration.id] }.minByOrNull { it.index }?.value
                    val candidate = candidates.filterNotNull().firstOrNull()
                    if (candidate == null && notice == null) continue
                    val kind = when (notice?.kind) {
                        NoticeKind.DEPRECATED -> VersionChangeKind.DEPRECATED
                        NoticeKind.MANUAL_REVIEW -> VersionChangeKind.OTHER
                        else -> candidate?.kind ?: VersionChangeKind.OTHER
                    }
                    val highlight = kind.severity(options).highlight ?: continue
                    val safe = notice == null && consumers.all { it.reason == null } && candidates.all { it != null } && candidates.map { it!!.version }.distinct().size == 1
                    val fixes = if (safe) arrayOf<LocalQuickFix>(UpdateGradleVersionFix(GradleVersionEdit(file, range, candidate!!.version, file.name, original), { adapter.isCurrent(holder.project, snapshot) })) else emptyArray()
                    val message = notice?.message ?: "Newer Gradle version of ${candidate!!.declaration.artifact.namespace}:${candidate.declaration.artifact.name} is available: ${candidate.declaration.baseline} → ${candidate.version}" + if (!safe) " (shared version needs review)" else ""
                    holder.registerProblem(file, message, highlight, range, *fixes)
                }
            }
        }
    }
}

internal class UpdateGradleVersionFix(private val edit: GradleVersionEdit, private val isCurrent: () -> Boolean) : LocalQuickFix {
    override fun getFamilyName() = "Update Gradle version"
    override fun getName() = "Update declared version to ${edit.latest}"
    override fun startInWriteAction() = false
    override fun getElementToMakeWritable(currentFile: PsiFile): PsiElement? = edit.element
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        if (!isCurrent() || !edit.isValid()) {
            notifyStaleVersionFix(project, "gradle", descriptor)
            return
        }
        if (!FileModificationService.getInstance().preparePsiElementsForWrite(listOfNotNull(edit.element))) return
        if (!BulkUpdatePlan(listOf(edit), emptyList(), isCurrent).apply(project))
            notifyStaleVersionFix(project, "gradle", descriptor)
    }
}
