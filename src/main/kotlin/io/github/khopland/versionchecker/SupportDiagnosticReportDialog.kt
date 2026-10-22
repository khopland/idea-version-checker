package io.github.khopland.versionchecker

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel

/** Saving writes the exact reviewed snapshot; neither preview nor export can refresh its inputs. */
internal class SupportDiagnosticReportDialog(private val reportProject: Project, internal val report: String) : DialogWrapper(reportProject) {
    internal val preview = JBTextArea(report).apply {
        isEditable = false
        accessibleContext.accessibleName = "Sanitized support report JSON"
        caretPosition = 0
    }
    private val status = JBLabel("Review before sharing. Package names and project-relative source paths remain.")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var savePending = false

    init {
        title = "Preview support report"
        setOKButtonText("Save As…")
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(status, BorderLayout.NORTH)
        add(JBScrollPane(preview), BorderLayout.CENTER)
        preferredSize = Dimension(850, 600)
    }

    public override fun doOKAction() {
        if (isDisposed || reportProject.isDisposed || savePending) return
        savePending = true
        setOKActionEnabled(false)
        ApplicationManager.getApplication().invokeLater({
            if (!isDisposed && !reportProject.isDisposed) chooseDestination()
        }, ModalityState.nonModal(), { isDisposed || reportProject.isDisposed })
    }

    private fun chooseDestination() {
        val target = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor("Save Support Report", "Save the exact previewed JSON snapshot", "json"), reportProject)
            .save(null as com.intellij.openapi.vfs.VirtualFile?, "version-checker-support.json")
        if (target == null) {
            savePending = false
            setOKActionEnabled(true)
            return
        }
        scope.launch {
            val message = try {
                saveTo(target.file.toPath())
                "Saved the previewed report. Review it before sharing."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "Could not save the report. Check the destination and retry." }
            withContext(Dispatchers.EDT) {
                if (!isDisposed && !reportProject.isDisposed) {
                    status.text = message
                    savePending = false
                    setOKActionEnabled(true)
                }
            }
        }
    }

    internal suspend fun saveTo(path: Path) = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        Files.writeString(path, report, Charsets.UTF_8)
    }

    override fun dispose() { scope.cancel(); super.dispose() }
}
