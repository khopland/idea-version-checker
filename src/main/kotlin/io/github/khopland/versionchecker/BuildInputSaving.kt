package io.github.khopland.versionchecker

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.project.Project
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Save the adapter's native inputs, including shared inputs outside the selected file. */
internal suspend fun saveBuildInputs(project: Project, selection: BuildSelection,
                                    adapters: List<BuildSystemAdapter>? = null) = withContext(Dispatchers.IO) {
    if (project.isDisposed) return@withContext
    val matching = adapters ?: readAction { BuildSystemAdapter.matching(project, selection) }
    val paths = mutableSetOf<String>()
    for (adapter in matching) {
        currentCoroutineContext().ensureActive()
        paths += adapter.resolutionInputPaths(project, selection)
    }
    withContext(Dispatchers.EDT) save@{
        currentCoroutineContext().ensureActive()
        if (project.isDisposed) return@save
        val context = currentCoroutineContext()
        WriteIntentReadAction.run(Runnable {
            val documents = FileDocumentManager.getInstance()
            documents.unsavedDocuments.filter { documents.getFile(it)?.path in paths }.forEach {
                context.ensureActive()
                documents.saveDocument(it)
            }
        })
    }
}

internal fun saveChangedVersionFiles(plan: BulkUpdatePlan) {
    WriteIntentReadAction.run(Runnable {
        val documents = FileDocumentManager.getInstance()
        plan.changes.mapNotNull { it.element?.containingFile?.virtualFile }.distinct()
            .mapNotNull(documents::getCachedDocument).forEach(documents::saveDocument)
    })
}
