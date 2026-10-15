package io.github.khopland.versionchecker.gradle

import io.github.khopland.versionchecker.core.EditInputCapture

import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInspection.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import io.github.khopland.versionchecker.*
import io.github.khopland.versionchecker.core.*

internal class GradleVersionEdit(file: PsiFile, val range: TextRange, override val latest: String,
                                 override val location: String, original: String) : VersionEdit {
    constructor(file: PsiFile, range: TextRange, latest: String, location: String) :
        this(file, range, latest, location, file.text)
    override val buildSystemId = "gradle"
    override val documentChange = DocumentVersionChange(file, range, latest, original)
    override val element: PsiFile? get() = documentChange.file
    override val expected = documentChange.expected
    override fun isValid() = documentChange.isValid()
    override fun apply() {
        DocumentVersionChange.prepare(listOf(documentChange))?.forEach { it.apply() }
    }
}

class NewerGradleDependencyInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file.virtualFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val adapter = BuildSystemAdapter.find("gradle") as? GradleBuildSystemAdapter ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!adapter.supports(holder.project, BuildSelection(UpdateScope.CURRENT_FILE, file.path)))
            return PsiElementVisitor.EMPTY_VISITOR
        val snapshot = holder.project.service<VersionCheckService>().inspectionSnapshot(adapter, file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val report = holder.project.service<VersionCheckService>().updates(adapter, snapshot) ?: return PsiElementVisitor.EMPTY_VISITOR
        val options = holder.project.service<VersionCheckerSettings>().state
        val ignoredPolicy = options.ignoredVersions
        val preparedUpdates = GradlePreparedUpdate.prepare(holder.file, report)
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                for (prepared in preparedUpdates) {
                    val range = prepared.target?.range ?: continue
                    val notice = prepared.notice
                    val candidate = prepared.candidates.firstOrNull()
                    if (candidate == null && notice == null) continue
                    val kind = when (notice?.kind) {
                        NoticeKind.DEPRECATED -> VersionChangeKind.DEPRECATED
                        NoticeKind.MANUAL_REVIEW -> VersionChangeKind.OTHER
                        else -> candidate?.kind ?: VersionChangeKind.OTHER
                    }
                    val highlight = kind.severity(options).highlight ?: continue
                    val safe = prepared.edit != null
                    val lease = report.lease?.forDeclarations(prepared.affectedDeclarations)
                    val updateFixes = prepared.edit?.let { edit -> arrayOf<LocalQuickFix>(UpdateGradleVersionFix(edit, {
                        lease?.isCurrent() == true && options.enabled && options.ignoredVersions == ignoredPolicy
                    }).withInputValidation { adapter.prepareEditValidation(holder.project, listOf(snapshot)) }) } ?: emptyArray()
                    val fixes = updateFixes + (if (notice != null) emptyList() else prepared.candidates.distinctBy { it.declaration.artifact to it.version }
                        .map { IgnorePublishedVersionFix(IgnoredVersion("gradle", it.declaration.artifact, it.version)) })
                    val message = notice?.message ?: updateHint(candidate!!.declaration.artifact, candidate.kind, candidate.declaration.baseline, candidate.version) + if (!safe) " (shared version needs review)" else ""
                    holder.registerProblem(file, message, highlight, range, *fixes)
                }
            }
        }
    }
}

internal class UpdateGradleVersionFix(private val edit: GradleVersionEdit, private val isCurrent: () -> Boolean) : LocalQuickFix {
    private var prepareValidation: EditInputCapture? = null
    internal fun withInputValidation(prepare: EditInputCapture) = apply { prepareValidation = prepare }
    override fun getFamilyName() = "Update Gradle version"
    override fun getName() = "Update declared version to ${edit.latest}"
    override fun startInWriteAction() = false
    override fun getElementToMakeWritable(currentFile: PsiFile): PsiElement? = edit.element
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) =
        CheckPerformance.locally(CheckPerformance.start(CheckPerformance.Stage.FIX_INVOKED)) {
            val interaction = CheckPerformance.current()
            if (!isCurrent() || !edit.isValid()) {
                notifyStaleVersionFix(project, "gradle", descriptor)
                return@locally
            }
            if (!FileModificationService.getInstance().preparePsiElementsForWrite(listOfNotNull(edit.element))) return@locally
            if (!BulkUpdatePlan(listOf(edit), emptyList(), isCurrent, prepareValidation = prepareValidation).apply(project, interaction))
                notifyStaleVersionFix(project, "gradle", descriptor)
        }
}
