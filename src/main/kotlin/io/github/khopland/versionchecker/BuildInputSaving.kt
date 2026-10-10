package io.github.khopland.versionchecker

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import io.github.khopland.versionchecker.core.*

/** Save the adapter's native inputs, including shared inputs outside the selected file. */
internal fun saveBuildInputs(project: Project, selection: BuildSelection,
                            adapters: List<BuildSystemAdapter> = BuildSystemAdapter.matching(project, selection)) {
    val documents = FileDocumentManager.getInstance()
    val paths = adapters.flatMap { it.resolutionInputPaths(project, selection) }.toSet()
    documents.unsavedDocuments.filter { documents.getFile(it)?.path in paths }.forEach(documents::saveDocument)
}

internal fun saveChangedVersionFiles(plan: BulkUpdatePlan) {
    val documents = FileDocumentManager.getInstance()
    plan.changes.mapNotNull { it.element?.containingFile?.virtualFile }.distinct()
        .mapNotNull(documents::getCachedDocument).forEach(documents::saveDocument)
}
