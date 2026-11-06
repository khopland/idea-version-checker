package io.github.khopland.versionchecker.npm

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager

/**
 * Highlights open `package.json` files again when a lockfile below or next to them changes, for example after an
 * install, so the vulnerable transitive dependency warnings follow what is installed.
 */
class LockfileListener : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val folders = events.mapNotNull { event -> event.file?.takeIf { it.name in Lockfile.FILE_NAMES }?.parent }.distinct()
        if (folders.isEmpty()) return
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val daemon = DaemonCodeAnalyzer.getInstance(project)
            FileEditorManager.getInstance(project).openFiles
                .filter { file -> file.name == "package.json" && folders.any { VfsUtilCore.isAncestor(it, file, false) } }
                .mapNotNull { PsiManager.getInstance(project).findFile(it) }
                .forEach { daemon.restart(it, "Lockfile changed") }
        }
    }
}
