package io.github.khopland.versionchecker

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.*
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.github.khopland.versionchecker.core.VersionResultCache
import io.github.khopland.versionchecker.core.UpdateScope
import io.github.khopland.versionchecker.core.VersionChangeKind
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.table.AbstractTableModel

class DependencyToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DependencyToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** Subscribes only to presentation events; opening, sorting and filtering cannot start native queries. */
internal class DependencyToolWindowPanel(private val project: Project,
    private val observations: () -> List<VersionResultCache.Observation> = { project.service<VersionCheckService>().observations() }) : JPanel(BorderLayout()), Disposable {
    internal val model = DependencyReviewTableModel(project.basePath)
    private val table = JBTable(model).apply { autoCreateRowSorter = true; setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    private val filter = JComboBox(DependencyReviewFilter.entries.toTypedArray())
    private val kind = JComboBox(arrayOf("All changes") + VersionChangeKind.entries.map { it.name }.toTypedArray())
    private val summary = JLabel()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val revision = AtomicLong()
    private val queued = AtomicBoolean()
    @Volatile private var disposed = false
    private var rows = emptyList<DependencyReviewRow>()
    private val expiryTimer = Timer(30_000) { requestUpdate() }

    init {
        val controls = JPanel(FlowLayout(FlowLayout.LEADING)).apply {
            add(JButton("Refresh Whole Project").apply {
                toolTipText = "Save build inputs and run fresh native checks."
                addActionListener { project.service<VersionCheckService>().refreshSelection(null) }
            })
            add(JButton("Review Updates…").apply {
                toolTipText = "Prepare a validated review before selecting or applying changes."
                addActionListener { project.service<BulkUpdateService>().preview(UpdateMode.MAJOR, UpdateScope.WHOLE_PROJECT) }
            })
            add(JButton("Open Source").apply { addActionListener { openSelectedSource() } })
            add(JButton("Export JSON…").apply { addActionListener { exportRows() } })
            add(filter); add(kind)
        }
        add(controls, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(JLabel("Cached observations; build inputs may have changed. Refresh to validate. Expired results disappear."), BorderLayout.NORTH)
            add(summary, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) { if (event.clickCount == 2) openSelectedSource() }
        })
        table.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "openSource")
        table.actionMap.put("openSource", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = openSelectedSource()
        })
        filter.addActionListener { applyFilter() }
        kind.addActionListener { applyFilter() }
        project.messageBus.connect(this).subscribe(VersionCheckStatusListener.TOPIC, VersionCheckStatusListener { requestUpdate() })
        requestUpdate()
        expiryTimer.start()
    }

    internal fun requestUpdate() {
        revision.incrementAndGet()
        if (disposed || !queued.compareAndSet(false, true)) return
        scope.launch {
            val captured = revision.get()
            try {
                val updated = DependencyReviewRows.from(observations(),
                    project.service<VersionCheckerSettings>().state.ignoredVersions)
                withContext(Dispatchers.EDT) {
                    if (!disposed && !project.isDisposed) { rows = updated; applyFilter() }
                }
            } finally {
                queued.set(false)
                if (!disposed && revision.get() != captured) requestUpdate()
            }
        }
    }

    private fun exportRows() {
        val target = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor("Export Dependency Review", "Export the displayed cached observations", "json"), project)
            .save(null as com.intellij.openapi.vfs.VirtualFile?, "dependency-review.json") ?: return
        val displayed = model.rows.toList()
        scope.launch {
            try {
                target.file.writeText(DependencyReviewExport.json(displayed, project.basePath))
            } catch (failure: Exception) {
                withContext(Dispatchers.EDT) {
                    if (!disposed && !project.isDisposed) Messages.showErrorDialog(project,
                        "Could not save the dependency review. Check the destination and retry.", "Export Dependency Review")
                }
            }
        }
    }

    private fun applyFilter() {
        if (disposed) return
        val selectedKind = (kind.selectedItem as? String)?.let { value -> VersionChangeKind.entries.find { it.name == value } }
        model.replace(DependencyReviewRows.filter(rows, filter.selectedItem as DependencyReviewFilter, selectedKind))
        summary.text = if (rows.isEmpty()) "No cached checks. Refresh Whole Project to check dependencies."
            else "${model.rowCount} of ${rows.size} declarations · ${rows.count { it.checking }} from checks in progress"
    }

    private fun openSelectedSource() {
        val index = table.selectedRow.takeIf { it >= 0 } ?: return
        val row = model.rows.getOrNull(table.convertRowIndexToModel(index)) ?: return
        // Cached offsets may have moved. Open the physical source file without trusting old PSI/ranges.
        val file = LocalFileSystem.getInstance().findFileByPath(row.source.file)?.takeIf { it.isValid } ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    override fun dispose() { disposed = true; scope.cancel(); expiryTimer.stop(); rows = emptyList(); model.replace(emptyList()) }
}

internal class DependencyReviewTableModel(private val basePath: String?) : AbstractTableModel() {
    var rows: List<DependencyReviewRow> = emptyList()
        private set
    private val columns = listOf("Build root", "Source", "Build system", "Package", "Current", "Available", "Change", "Outcome", "Mode", "Details")
    fun replace(value: List<DependencyReviewRow>) { rows = value; fireTableDataChanged() }
    override fun getRowCount() = rows.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]
    override fun isCellEditable(row: Int, column: Int) = false
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        fun relative(path: String) = basePath?.let { path.removePrefix("$it/") } ?: path
        return when (columnIndex) {
            0 -> relative(row.root)
            1 -> relative(row.source.file)
            2 -> row.adapter
            3 -> row.artifact
            4 -> row.current
            5 -> row.available.orEmpty()
            6 -> row.kind?.name.orEmpty()
            7 -> row.outcome + (if (row.checkFailed) " (check failed)" else "") + (if (row.checking) " (checking)" else "")
            8 -> row.mode.label
            else -> row.detail
        }
    }
}
