package io.github.khopland.versionchecker.npm

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.util.PathUtil

/**
 * Highlights open `package.json` files again when a file that decides their transitive warnings or fixes changes: a
 * lockfile, for example after an install, `pnpm-workspace.yaml`, or the pnpm version recorded in `node_modules`.
 */
class LockfileListener : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        // Every change to any file comes here, so the names are checked before anything is asked of the projects.
        if (events.none { PathUtil.getFileName(it.path) in WATCHED }) return
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val daemon = DaemonCodeAnalyzer.getInstance(project)
            affected(events, FileEditorManager.getInstance(project).openFiles.asList())
                .mapNotNull { PsiManager.getInstance(project).findFile(it) }
                .forEach { daemon.restart(it, "Lockfile changed") }
        }
    }

    companion object {
        private val WATCHED = Lockfile.FILE_NAMES + PnpmWorkspace.FILE_NAME + NpmProvider.MODULES_FILE

        /** The `package.json` files among [open] in or below the folder of a file that [events] change. */
        internal fun affected(events: List<VFileEvent>, open: List<VirtualFile>): List<VirtualFile> {
            // An install or a branch switch sends many events, so the name is checked before the file is looked up.
            val folders = events.filter { PathUtil.getFileName(it.path) in WATCHED }.mapNotNull { event ->
                val file = event.file ?: return@mapNotNull null
                // node_modules/.modules.yaml belongs to the package.json next to node_modules.
                if (file.name == NpmProvider.MODULES_FILE) file.parent?.parent else file.parent
            }.distinct()
            if (folders.isEmpty()) return emptyList()
            return open.filter { file -> file.name == "package.json" && folders.any { VfsUtilCore.isAncestor(it, file, false) } }
        }
    }
}
