package io.github.khopland.versionchecker

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import java.awt.Rectangle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project

/** Debug-only endpoint: installed inspection markup in the selected viewport, followed by a completed paint. */
@Service(Service.Level.PROJECT)
internal class DiagnosticVisibilityTrace(private val project: Project) : Disposable {
    private val selected = mutableMapOf<String, CheckPerformance.Interaction>() // EDT only; at most selected editors.
    private val highlighting = mutableMapOf<String, Long>() // EDT only.
    init {
        project.messageBus.connect(this).subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
            override fun daemonStarting(fileEditors: Collection<FileEditor>) {
                if (!CheckPerformance.enabled()) return
                val started = System.nanoTime()
                val files = fileEditors.filterIsInstance<TextEditor>().map { it.file.path }.toSet()
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) selected.keys.filter { it in files }.forEach { highlighting[it] = started }
                }
            }
            override fun daemonFinished(fileEditors: Collection<FileEditor>) {
                if (!CheckPerformance.enabled()) return
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) FileEditorManager.getInstance(project).selectedEditors.filterIsInstance<TextEditor>().forEach(::visible)
                }
            }
        })
    }
    fun activated(paths: Set<String>) {
        selected.clear()
        highlighting.clear()
        if (CheckPerformance.enabled()) paths.forEach { path ->
            CheckPerformance.start(CheckPerformance.Stage.FILE_ACTIVATED)?.let { selected[path] = it }
        }
        if (selected.isNotEmpty()) ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) FileEditorManager.getInstance(project).selectedEditors.filterIsInstance<TextEditor>().forEach(::visible)
        }
    }
    fun interaction(path: String): CheckPerformance.Interaction? = selected[path]
    private fun visible(textEditor: TextEditor) {
        val path = textEditor.file.path
        val interaction = selected[path] ?: return
        val editor = textEditor.editor
        if (!editor.contentComponent.isShowing) return
        val viewport = editor.scrollingModel.visibleArea
        val count = visibleVersionDiagnostics(project, editor, viewport)
        if (count == 0) return
        editor.contentComponent.paintImmediately(viewport)
        highlighting.remove(path)?.let { CheckPerformance.record(CheckPerformance.Stage.IDEA_HIGHLIGHTING, it, count, interaction) }
        CheckPerformance.record(CheckPerformance.Stage.DIAGNOSTIC_VISIBLE, interaction.started, count, interaction)
        selected.remove(path)
    }
    override fun dispose() { selected.clear(); highlighting.clear() }
}

private val versionInspectionTools = setOf("NewerMavenDependencyVersion", "NewerNpmDependencyVersion", "NewerGradleDependencyVersion")

/** Inspection markup belongs to the document, rather than the editor's separate markup model. */
internal fun visibleVersionDiagnostics(project: Project, editor: Editor, viewport: Rectangle): Int {
    val first = editor.xyToLogicalPosition(viewport.location).line
    val last = editor.xyToLogicalPosition(java.awt.Point(viewport.x + viewport.width, viewport.y + viewport.height)).line
    return DocumentMarkupModel.forDocument(editor.document, project, false).allHighlighters.count { highlighter ->
        highlighter.isValid && HighlightInfo.fromRangeHighlighter(highlighter)?.inspectionToolId in versionInspectionTools &&
            editor.document.getLineNumber(highlighter.startOffset.coerceAtMost(editor.document.textLength)) in first..last &&
            run {
                val start = editor.offsetToXY(highlighter.startOffset.coerceAtMost(editor.document.textLength))
                val end = editor.offsetToXY(highlighter.endOffset.coerceAtMost(editor.document.textLength))
                val bounds = if (start.y == end.y) java.awt.Rectangle(start.x, start.y, (end.x - start.x).coerceAtLeast(1), editor.lineHeight)
                    else java.awt.Rectangle(viewport.x, start.y, viewport.width, end.y - start.y + editor.lineHeight)
                viewport.intersects(bounds)
            }
    }
}
