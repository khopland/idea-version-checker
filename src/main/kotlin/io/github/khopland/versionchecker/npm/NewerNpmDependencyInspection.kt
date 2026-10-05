package io.github.khopland.versionchecker.npm

import com.intellij.codeInspection.*
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import io.github.khopland.versionchecker.*
import io.github.khopland.versionchecker.core.*

internal class NpmVersionEdit(value: JsonStringLiteral, override val latest: String,
                              override val location: String) : VersionEdit {
    private val pointer = SmartPointerManager.createPointer(value)
    override val element: JsonStringLiteral? get() = pointer.element
    override val expected = value.value
    override fun isValid() = element?.value == expected
    override fun apply() { element!!.replace(JsonElementGenerator(element!!.project).createStringLiteral(latest)) }
}

class NewerNpmDependencyInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file.virtualFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!NpmManifest.supported(file)) return PsiElementVisitor.EMPTY_VISITOR
        val adapter = BuildSystemAdapter.find("npm") ?: return PsiElementVisitor.EMPTY_VISITOR
        val snapshot = adapter.snapshot(holder.project, file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val report = holder.project.service<VersionCheckService>().updates(adapter, snapshot) ?: return PsiElementVisitor.EMPTY_VISITOR
        val options = holder.project.service<VersionCheckerSettings>().state
        val candidates = report.candidates.associateBy { it.declaration.id }
        val notices = report.notices.associateBy { it.declaration.id }
        val values = NpmManifest.values(holder.file).entries.associate { it.value to it.key }
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val id = values[element] ?: return
                val candidate = candidates[id]
                val notice = notices[id]
                if (candidate == null && notice == null) return
                val kind = if (notice != null) VersionChangeKind.DEPRECATED else candidate!!.kind
                val highlight = kind.severity(options).highlight ?: return
                val message = notice?.message ?: "Newer npm version of ${candidate!!.declaration.artifact.name} is available: ${candidate.declaration.selector} → ${candidate.replacementSelector} (declared range)"
                val fixes = candidate?.let { arrayOf<LocalQuickFix>(UpdateNpmVersionFix(element as JsonStringLiteral, it.replacementSelector) { adapter.isCurrent(holder.project, snapshot) }) }.orEmpty()
                holder.registerProblem(element, message, highlight, *fixes)
            }
        }
    }
}

class UpdateNpmVersionFix(value: JsonStringLiteral, latest: String, private val isCurrent: () -> Boolean = { true }) : LocalQuickFix {
    private val edit = NpmVersionEdit(value, latest, "npm dependency")
    override fun getFamilyName() = "Update npm version"
    override fun getName() = "Update declared version to ${edit.latest}"
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        if (isCurrent() && edit.isValid()) edit.apply()
    }
}
