package io.github.khopland.versionchecker

import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.io.DigestUtil
import io.github.khopland.versionchecker.core.*

/** The `distributionUrl` value of a wrapper properties file and the version it names. */
internal data class WrapperDistribution(val range: TextRange, val url: String, val version: String?, val checksum: Boolean) {
    /** Properties escape `:` as `\:`; versions never contain either, so replace them textually. */
    fun withVersion(latest: String) = url.replace(version!!, latest)

    companion object {
        fun parse(text: String, versionOf: (String) -> String?): WrapperDistribution? {
            var offset = 0
            var found: WrapperDistribution? = null
            var checksum = false
            for (line in text.split('\n')) {
                val trimmed = line.trimStart()
                val key = trimmed.substringBefore('=').trim()
                if (!trimmed.startsWith('#') && !trimmed.startsWith('!') && '=' in trimmed) {
                    if (key == "distributionSha256Sum" && trimmed.substringAfter('=').isNotBlank()) checksum = true
                    if (key == "distributionUrl") {
                        val raw = line.substringAfter('=')
                        val value = raw.trim().removeSuffix("\r")
                        val start = offset + line.indexOf('=') + 1 + raw.indexOf(value)
                        found = WrapperDistribution(TextRange(start, start + value.length), value, versionOf(value.replace("\\", "")), false)
                    }
                }
                offset += line.length + 1
            }
            return found?.copy(checksum = checksum)
        }
    }
}

internal data class WrapperLookup(val latest: String? = null, val failure: String? = null, val review: String? = null,
                                  val validUntilNanos: Long? = null, val checkedAtNanos: Long? = null)

/** One wrapper properties file per build root; the only declaration is the distribution version. */
internal abstract class WrapperBuildSystemAdapter(
    override val id: String, override val displayName: String,
    /** Build system whose reload follows a wrapper update. */
    val followUpSystem: String, val artifact: ArtifactId,
) : BuildSystemAdapter {
    override val capabilities = AdapterCapabilities()

    /** Call inside a read action. */
    abstract fun wrapperFiles(project: Project): List<VirtualFile>
    abstract fun version(url: String): String?
    abstract fun review(distribution: WrapperDistribution): String?
    abstract suspend fun lookup(project: Project, file: String, current: String, mode: UpdateMode): WrapperLookup
    abstract fun kind(current: String, latest: String): VersionChangeKind
    abstract fun newer(current: String, latest: String): Boolean

    /** Light test projects keep their content outside the local file system. */
    protected fun findFile(project: Project, path: String): VirtualFile? = LocalFileSystem.getInstance().findFileByPath(path)
        ?: com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { it.fileSystem.findFileByPath(path) }

    private fun selected(project: Project, selection: BuildSelection) = wrapperFiles(project).filter {
        selection.scope == UpdateScope.WHOLE_PROJECT || it.path == selection.currentFile
    }

    override fun supports(project: Project, selection: BuildSelection) = selected(project, selection).isNotEmpty()

    private fun text(file: VirtualFile) = FileDocumentManager.getInstance().getCachedDocument(file)?.text
        ?: String(file.contentsToByteArray(), file.charset)

    internal fun distribution(text: String) = WrapperDistribution.parse(text, ::version)

    override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? {
        if (wrapperFiles(project).none { it.path == file.path }) return null
        val text = text(file)
        val distribution = distribution(text) ?: return null
        val reason = when {
            distribution.version == null -> "The distribution URL does not name a recognized version; manual review required"
            distribution.checksum -> "distributionSha256Sum must change with the version; update the wrapper with its own task"
            else -> review(distribution)
        }
        val declaration = VersionDeclaration(DeclarationId(file.path, LOCATION), artifact, distribution.version.orEmpty(),
            if (reason == null) distribution.version!! else "")
        return BuildSnapshot(BuildContextId(id, file.parent.path, id), file.path,
            BuildFingerprint(mapOf(file.path to DigestUtil.sha256Hex(text.toByteArray())), ""), listOf(declaration),
            skippedDeclarations = reason?.let { mapOf(declaration.id to it) }.orEmpty())
    }

    override fun isCurrent(project: Project, snapshot: BuildSnapshot): Boolean {
        val file = findFile(project, snapshot.sourceFile)?.takeIf { it.isValid } ?: return false
        return snapshot.fingerprint.files[file.path] == DigestUtil.sha256Hex(text(file).toByteArray())
    }

    override suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot> = readAction {
        selected(project, selection).mapNotNull { snapshot(project, it) }
    }

    override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
        val declaration = snapshot.declarations.single()
        snapshot.skippedDeclarations[declaration.id]?.let { reason ->
            return UpdateReport(notices = listOf(UpdateNotice(declaration, NoticeKind.MANUAL_REVIEW, reason)),
                coverage = mapOf(declaration.id to DeclarationCoverage(DeclarationOutcome.MANUAL_REVIEW, reason)))
        }
        val lookup = lookup(project, snapshot.sourceFile, declaration.baseline, mode)
        lookup.failure?.let { return UpdateReport(failure = it, coverage = mapOf(declaration.id to DeclarationCoverage(DeclarationOutcome.FAILED, it))) }
        lookup.review?.let { reason ->
            return UpdateReport(notices = listOf(UpdateNotice(declaration, NoticeKind.MANUAL_REVIEW, reason)),
                coverage = mapOf(declaration.id to DeclarationCoverage(DeclarationOutcome.MANUAL_REVIEW, reason)))
        }
        val latest = lookup.latest?.takeIf { newer(declaration.baseline, it) }
        return UpdateReport(listOfNotNull(latest?.let { UpdateCandidate(declaration, it, kind = kind(declaration.baseline, it)) }),
            validUntilNanos = lookup.validUntilNanos, checkedAtNanos = lookup.checkedAtNanos,
            coverage = mapOf(declaration.id to DeclarationCoverage(DeclarationOutcome.CHECKED)))
    }

    /** Call inside a read action. */
    internal fun edit(file: PsiFile, candidate: UpdateCandidate): WrapperVersionEdit? {
        val distribution = distribution(file.text) ?: return null
        if (distribution.version != candidate.declaration.baseline || distribution.checksum) return null
        return WrapperVersionEdit(file, distribution.range, distribution.withVersion(candidate.version), candidate.version,
            distribution.version, "${file.virtualFile.path}: $displayName ${distribution.version}", id)
    }

    override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan = readAction {
        check(reports.keys.all { isCurrent(project, it) }) { "$displayName properties changed during the check. Run it again." }
        val edits = mutableListOf<VersionEdit>()
        val skipped = mutableListOf<String>()
        val review = mutableListOf<VersionReviewItem>()
        for ((snapshot, report) in reports) {
            val file = findFile(project, snapshot.sourceFile)
                ?.let { PsiManager.getInstance(project).findFile(it) } ?: error("$displayName properties are no longer available")
            for (notice in report.notices) {
                skipped += "${snapshot.sourceFile}: ${notice.message}"
                review += VersionReviewItem(notice.message, VersionReviewSource(snapshot.sourceFile), displayName,
                    notice.declaration.selector, buildContext = id)
            }
            for (candidate in report.candidates) edit(file, candidate)?.let { edits += it.withReview(listOf(candidate.declaration), id) }
        }
        BulkUpdatePlan(edits, skipped.distinct(), reviewItems = review.distinct(),
            prepareValidation = { prepareEditValidation(project, reports.keys) })
    }

    companion object {
        const val LOCATION = "distributionUrl"
    }
}

internal class WrapperVersionEdit(file: PsiFile, range: TextRange, url: String, override val latest: String,
                                  override val expected: String, override val location: String,
                                  override val buildSystemId: String) : VersionEdit {
    override val documentChange = DocumentVersionChange(file, range, url, file.text)
    override val element: PsiFile? get() = documentChange.file
    override fun isValid() = documentChange.isValid()
    override fun apply() { DocumentVersionChange.prepare(listOf(documentChange))?.forEach { it.apply() } }
}

/** Shared by the Maven and Gradle wrapper inspections; each lives behind its optional plugin. */
abstract class WrapperVersionInspection internal constructor(private val adapterId: String) : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file.virtualFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!file.name.endsWith("-wrapper.properties")) return PsiElementVisitor.EMPTY_VISITOR
        val adapter = BuildSystemAdapter.find(adapterId) as? WrapperBuildSystemAdapter ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!adapter.supports(holder.project, BuildSelection(UpdateScope.CURRENT_FILE, file.path))) return PsiElementVisitor.EMPTY_VISITOR
        val service = holder.project.service<VersionCheckService>()
        val snapshot = service.inspectionSnapshot(adapter, file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val report = service.updates(adapter, snapshot) ?: return PsiElementVisitor.EMPTY_VISITOR
        val distribution = adapter.distribution(holder.file.text) ?: return PsiElementVisitor.EMPTY_VISITOR
        val options = holder.project.service<VersionCheckerSettings>().state
        val policy = projectPolicy(holder.project)
        return object : PsiElementVisitor() {
            override fun visitFile(psi: PsiFile) {
                report.notices.firstOrNull()?.let {
                    holder.registerProblem(psi, it.message, VersionChangeKind.OTHER.highlightType(), distribution.range)
                    return
                }
                val candidate = report.candidates.firstOrNull() ?: return
                val lease = report.lease?.forDeclarations(setOf(candidate.declaration.id))
                val fixes = adapter.edit(psi, candidate)?.let { edit -> arrayOf<LocalQuickFix>(UpdateWrapperVersionFix(edit, adapter.followUpSystem) {
                    lease?.isCurrent() == true && options.enabled && policy.isCurrent(holder.project)
                }.withInputValidation { adapter.prepareEditValidation(holder.project, listOf(snapshot)) }) } ?: emptyArray()
                holder.registerProblem(psi, updateHint(adapter.artifact, candidate.kind, candidate.declaration.baseline, candidate.version),
                    candidate.kind.highlightType(), distribution.range, *fixes)
            }
        }
    }
}

internal class UpdateWrapperVersionFix(private val edit: WrapperVersionEdit, private val followUpSystem: String,
                                       private val isCurrent: () -> Boolean) : LocalQuickFix {
    private var prepareValidation: EditInputCapture? = null
    internal fun withInputValidation(prepare: EditInputCapture) = apply { prepareValidation = prepare }
    override fun getFamilyName() = "Update wrapper version"
    override fun getName() = "Update wrapper to ${edit.latest}"
    override fun startInWriteAction() = false
    override fun getElementToMakeWritable(currentFile: PsiFile): PsiElement? = edit.element
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) =
        CheckPerformance.locally(CheckPerformance.start(CheckPerformance.Stage.FIX_INVOKED)) {
            val interaction = CheckPerformance.current()
            if (!isCurrent() || !edit.isValid()) {
                notifyStaleVersionFix(project, edit.buildSystemId, descriptor)
                return@locally
            }
            if (!FileModificationService.getInstance().preparePsiElementsForWrite(listOfNotNull(edit.element))) return@locally
            val plan = BulkUpdatePlan(listOf(edit), emptyList(), isCurrent, prepareValidation = prepareValidation)
            if (!plan.apply(project, interaction)) notifyStaleVersionFix(project, edit.buildSystemId, descriptor)
            else notifyVersionUpdates(project, emptyList(), setOf(followUpSystem), changedFiles = plan.appliedChanges)
        }
}
