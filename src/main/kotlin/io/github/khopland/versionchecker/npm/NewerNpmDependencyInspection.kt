package io.github.khopland.versionchecker.npm

import io.github.khopland.versionchecker.core.EditInputCapture

import com.intellij.codeInspection.*
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.codeInsight.FileModificationService
import com.intellij.psi.*
import io.github.khopland.versionchecker.*
import io.github.khopland.versionchecker.core.*

internal class NpmVersionEdit(value: JsonStringLiteral, override val latest: String,
                              override val location: String, override val npmTarget: NpmTarget? = null) : VersionEdit {
    override val buildSystemId = "npm"
    private val pointer = SmartPointerManager.createPointer(value)
    override val documentChange = DocumentVersionChange(value.containingFile, value.textRange,
        JsonElementGenerator(value.project).createStringLiteral(latest).text, value.containingFile.text)
    override val element: JsonStringLiteral? get() = pointer.element
    override val expected = value.value
    override fun isValid() = element?.value == expected && documentChange.isValid()
    override fun preview(): VersionEditPreview? = documentChange.preview()
    override fun apply() { DocumentVersionChange.prepare(listOf(documentChange))?.forEach { it.apply() } }
}

class NewerNpmDependencyInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file.virtualFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!NpmManifest.supported(holder.project, file)) return PsiElementVisitor.EMPTY_VISITOR
        val adapter = BuildSystemAdapter.find("npm") as? NpmBuildSystemAdapter ?: return PsiElementVisitor.EMPTY_VISITOR
        val snapshot = holder.project.service<VersionCheckService>().inspectionSnapshot(adapter, file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val report = holder.project.service<VersionCheckService>().updates(adapter, snapshot) ?: return PsiElementVisitor.EMPTY_VISITOR
        val options = holder.project.service<VersionCheckerSettings>().state
        val candidates = report.candidates.associateBy { it.declaration.id }
        val notices = report.notices.associateBy { it.declaration.id }
        val values = NpmManifest.values(holder.file).entries.associate { it.value to it.key }
        val workspaceDeclarationsByArtifact by lazy {
            adapter.workspaceDeclarations(holder.project, snapshot).groupBy { it.first.artifact }
        }
        // Pass-local ownership keeps PSI/edit guards tied to this snapshot. Cache absent actions
        // too: a standalone package or a single eligible manifest must not repeat preparation.
        val workspaceFixes = mutableMapOf<Pair<ArtifactId, String>, LocalQuickFix?>()
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val id = values[element] ?: return
                val candidate = candidates[id]
                val notice = notices[id]
                if (candidate == null && notice == null) return
                val kind = if (notice != null) VersionChangeKind.DEPRECATED else candidate!!.kind
                val highlight = kind.severity(options).highlight ?: return
                val message = notice?.message ?: updateHint(candidate!!.declaration.artifact, candidate.kind, candidate.declaration.selector, candidate.replacementSelector)
                val fixes = candidate?.let {
                    val key = it.declaration.artifact to it.version
                    if (key !in workspaceFixes) workspaceFixes[key] = adapter.workspaceFix(holder.project, snapshot, it,
                        workspaceDeclarationsByArtifact[it.declaration.artifact].orEmpty(), report.lease)
                    adapter.quickFixes(holder.project, snapshot, it, element as JsonStringLiteral, workspaceFixes[key], report.lease) + IgnorePublishedVersionFix(IgnoredVersion("npm", it.declaration.artifact, it.version))
                }.orEmpty()
                holder.registerProblem(element, message, highlight, *fixes)
            }
        }
    }
}

class UpdateNpmVersionFix internal constructor(private val edit: NpmVersionEdit, private val actionName: String?,
                                              private val isCurrent: () -> Boolean) : LocalQuickFix {
    private var prepareValidation: EditInputCapture? = null
    internal fun withInputValidation(prepare: EditInputCapture) = apply { prepareValidation = prepare }
    constructor(value: JsonStringLiteral, latest: String, isCurrent: () -> Boolean = { true }) :
        this(NpmVersionEdit(value, latest, "npm dependency"), null, isCurrent)
    override fun getFamilyName() = "Update npm version"
    override fun getName() = actionName ?: "Update declared version to ${edit.latest}"
    override fun startInWriteAction() = false
    override fun getElementToMakeWritable(currentFile: PsiFile): PsiElement? = edit.element
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) =
        CheckPerformance.locally(CheckPerformance.start(CheckPerformance.Stage.FIX_INVOKED)) {
            val interaction = CheckPerformance.current()
            if (!isCurrent() || !edit.isValid()) {
                notifyStaleVersionFix(project, "npm", descriptor)
                return@locally
            }
            if (!FileModificationService.getInstance().preparePsiElementsForWrite(listOfNotNull(edit.element))) return@locally
            val plan = BulkUpdatePlan(listOf(edit), emptyList(), isCurrent, prepareValidation = prepareValidation)
            if (plan.apply(project, interaction))
                NpmUpdateGuidance.notify(project, plan.appliedChanges)
            else notifyStaleVersionFix(project, "npm", descriptor)
        }
}

internal class UpdateNpmWorkspaceVersionFix(name: String, version: String, private val edits: List<NpmVersionEdit>,
                                          private val isCurrent: () -> Boolean) : LocalQuickFix {
    private var prepareValidation: EditInputCapture? = null
    internal fun withInputValidation(prepare: EditInputCapture) = apply { prepareValidation = prepare }
    private val actionName = "Update $name across workspace to $version (${edits.size} declarations)"
    override fun getFamilyName() = "Update npm workspace version"
    override fun getName() = actionName
    override fun startInWriteAction() = false
    override fun getElementToMakeWritable(currentFile: PsiFile): PsiElement? = null
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) =
        CheckPerformance.locally(CheckPerformance.start(CheckPerformance.Stage.FIX_INVOKED)) {
            val interaction = CheckPerformance.current()
            if (!isCurrent() || edits.any { !it.isValid() }) {
                notifyStaleVersionFix(project, "npm", descriptor)
                return@locally
            }
            val elements = edits.mapNotNull { it.element }
            if (!FileModificationService.getInstance().preparePsiElementsForWrite(elements)) return@locally
            val plan = BulkUpdatePlan(edits, emptyList(), isCurrent, followUp = listOf(NpmUpdateGuidance.message), prepareValidation = prepareValidation)
            if (plan.apply(project, interaction)) notifyVersionUpdates(project, plan.followUp, changedFiles = plan.appliedChanges)
            else notifyStaleVersionFix(project, "npm", descriptor)
        }
}
