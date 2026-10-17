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
import java.awt.GridLayout
import java.awt.Dimension
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.DocumentAdapter
import com.intellij.util.ui.JBUI
import javax.swing.event.DocumentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

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
    private val now: () -> Long = System::nanoTime,
    private val activity: () -> Boolean = { project.service<VersionCheckService>().hasActiveChecks },
    private val observations: () -> List<VersionResultCache.Observation> = { project.service<VersionCheckService>().observations() }) : JPanel(BorderLayout()), Disposable {
    internal val model = DependencyReviewTableModel(project.basePath, now)
    internal val table = JBTable(model).apply {
        autoCreateRowSorter = true
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        accessibleContext.accessibleName = "Cached dependency versions"
        // Retain the complete model for metadata/export; keep the initial table readable.
        listOf(9, 8, 2, 1, 0).forEach { removeColumn(columnModel.getColumn(it)) }
        columnModel.getColumn(0).preferredWidth = 240
        columnModel.getColumn(5).apply {
            preferredWidth = 100
            cellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean,
                                                            focused: Boolean, row: Int, column: Int): java.awt.Component =
                    super.getTableCellRendererComponent(table,
                        DependencyReviewFreshness.formatAge(this@DependencyToolWindowPanel.model.rows[table.convertRowIndexToModel(row)], now()),
                        selected, focused, row, column)
            }
        }
    }
    internal val search = JTextField().apply {
        toolTipText = "Search package names or source/build paths (literal text)"
        accessibleContext.accessibleName = "Search packages and paths"
    }
    internal val provider = JComboBox<String>()
    internal val buildRoot = JComboBox<String>()
    internal val filter = JComboBox(DependencyReviewFilter.entries.toTypedArray())
    internal val kind = JComboBox(arrayOf("All changes") + VersionChangeKind.entries.map { it.name }.toTypedArray())
    private val summary = JBTextArea().apply {
        isEditable = false; isFocusable = false; isOpaque = false
        lineWrap = true; wrapStyleWord = true; rows = 2
        accessibleContext.accessibleName = "Dependency check summary"
    }
    internal val details = JBTextArea("Select a dependency to see its source, context and check details.").apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        accessibleContext.accessibleName = "Selected dependency details"
    }
    private val toolbar: ActionToolbar
    private var updatingControls = false
    private var replacingRows = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val revision = AtomicLong()
    private val queued = AtomicBoolean()
    @Volatile private var disposed = false
    private var rows = emptyList<DependencyReviewRow>()
    private val expiryTimer = Timer(30_000) { requestUpdate() }

    init {
        border = JBUI.Borders.empty(4, 6)
        val actions = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Refresh Whole Project", "Save build inputs and run fresh native checks", AllIcons.Actions.Refresh) {
                override fun actionPerformed(event: AnActionEvent) { project.service<VersionCheckService>().refreshSelection(null) }
            })
            add(object : DumbAwareAction("Review Updates…", "Prepare a validated review before applying changes", AllIcons.Actions.Preview) {
                override fun actionPerformed(event: AnActionEvent) { project.service<BulkUpdateService>().preview(UpdateMode.MAJOR, UpdateScope.WHOLE_PROJECT) }
            })
            add(object : DumbAwareAction("Open Source", "Open the selected declaration's physical source file", AllIcons.Actions.EditSource) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(event: AnActionEvent) { event.presentation.isEnabled = canOpenSelectedSource() }
                override fun actionPerformed(event: AnActionEvent) = openSelectedSource()
            })
            add(object : DumbAwareAction("Export JSON…", "Export filtered rows in their displayed sort order", AllIcons.Actions.Download) {
                override fun actionPerformed(event: AnActionEvent) = exportRows()
            })
        }
        toolbar = ActionManager.getInstance().createActionToolbar("DependencyVersions", actions, true).apply { targetComponent = this@DependencyToolWindowPanel }
        val filters = JPanel(GridLayout(0, 2, 4, 4)).apply {
            add(labeled("Build system", provider)); add(labeled("Build root", buildRoot))
            add(labeled("Outcome", filter)); add(labeled("Change", kind))
        }
        provider.addItem("All build systems"); buildRoot.addItem("All build roots")
        provider.prototypeDisplayValue = "All build systems"
        buildRoot.prototypeDisplayValue = "All build roots"
        filter.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component =
                super.getListCellRendererComponent(list, when (value) {
                    DependencyReviewFilter.ALL -> "All outcomes"
                    DependencyReviewFilter.UPDATES -> "Updates"
                    DependencyReviewFilter.REVIEW -> "Needs review"
                    DependencyReviewFilter.FAILED -> "Failed checks"
                    else -> value
                }, index, selected, focus)
        }
        buildRoot.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component =
                super.getListCellRendererComponent(list, (value as? String)?.let(::relativePath), index, selected, focus)
        }
        add(JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(JPanel(BorderLayout(4, 0)).apply {
                add(labeled("Search", search), BorderLayout.CENTER)
                add(JButton("Clear filters").apply {
                    accessibleContext.accessibleName = "Clear dependency filters"
                    addActionListener { clearFilters() }
                }, BorderLayout.EAST)
            }, BorderLayout.CENTER)
            add(filters, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(OnePixelSplitter(true, 0.65f).apply {
            firstComponent = JBScrollPane(table).apply { minimumSize = Dimension(0, 80) }
            secondComponent = JBScrollPane(details).apply { minimumSize = Dimension(0, 70) }
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(JBTextArea("Cached results. Refresh to validate; expired results disappear.").apply {
                isEditable = false; isFocusable = false; isOpaque = false
                lineWrap = true; wrapStyleWord = true; rows = 2
                toolTipText = "Build inputs may have changed since these cached observations."
                accessibleContext.accessibleName = "Cached result limitations"
            }, BorderLayout.NORTH)
            add(summary, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        table.selectionModel.addListSelectionListener {
            if (!disposed && !it.valueIsAdjusting && !replacingRows) { updateDetails(); toolbar.updateActionsAsync() }
        }
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) { applyFilter() }
        })
        inputMap.put(KeyStroke.getKeyStroke("control F"), "focusSearch")
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("meta F"), "focusSearch")
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control F"), "focusSearch")
        actionMap.put("focusSearch", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) { search.requestFocusInWindow(); search.selectAll() }
        })
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) { if (event.clickCount == 2) openSelectedSource() }
        })
        table.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "openSource")
        table.actionMap.put("openSource", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = openSelectedSource()
        })
        filter.addActionListener { applyFilter() }
        kind.addActionListener { applyFilter() }
        provider.addActionListener { applyFilter() }
        buildRoot.addActionListener { buildRoot.toolTipText = buildRoot.selectedItem as? String; applyFilter() }
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
                    if (!disposed && !project.isDisposed) { presentRows(updated) }
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
        val displayed = visibleRows()
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

    private fun labeled(name: String, control: JComponent) = JPanel(BorderLayout(4, 0)).apply {
        add(JLabel(name).apply { labelFor = control }, BorderLayout.WEST)
        control.accessibleContext.accessibleName = name
        add(control, BorderLayout.CENTER)
        minimumSize = Dimension(0, preferredSize.height)
    }

    private fun relativePath(path: String) = project.basePath?.let {
        if (path == it) "." else path.removePrefix("$it/")
    } ?: path

    internal fun presentRows(updated: List<DependencyReviewRow>) {
        if (disposed) return
        rows = updated
        updatingControls = true
        try {
            fun choices(combo: JComboBox<String>, all: String, values: List<String>) {
                val selected = combo.selectedItem as? String
                // Expiry must not silently broaden a user's chosen scope to other roots/providers.
                val choices = (values + listOfNotNull(selected?.takeUnless { it == all })).distinct().sorted()
                combo.model = DefaultComboBoxModel((listOf(all) + choices).toTypedArray())
                if (selected != null) combo.selectedItem = selected
            }
            choices(provider, "All build systems", rows.map { it.adapter })
            choices(buildRoot, "All build roots", rows.map { it.root })
        } finally { updatingControls = false }
        applyFilter()
    }

    private fun applyFilter() {
        if (disposed || updatingControls) return
        val selected = selectedRow()?.identity()
        val selectedKind = (kind.selectedItem as? String)?.let { value -> VersionChangeKind.entries.find { it.name == value } }
        val query = DependencyReviewQuery(search.text,
            (provider.selectedItem as? String)?.takeIf { provider.selectedIndex > 0 },
            (buildRoot.selectedItem as? String)?.takeIf { buildRoot.selectedIndex > 0 },
            filter.selectedItem as DependencyReviewFilter, selectedKind)
        replacingRows = true
        try {
            model.replace(query.apply(rows))
            table.clearSelection()
            val index = model.rows.indexOfFirst { it.identity() == selected }
            if (index >= 0) table.convertRowIndexToView(index).takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) }
        } finally { replacingRows = false }
        updateDetails()
        val checking = activity()
        val paused = !project.service<VersionCheckerSettings>().state.enabled
        summary.text = (when {
            paused -> "Checks paused. "
            checking -> "Checking dependencies. "
            else -> ""
        }) + when {
            rows.isEmpty() && checking && !paused -> "No cached results available yet."
            rows.isEmpty() -> "No cached results available. Refresh Whole Project to check dependencies."
            model.rowCount == 0 -> "No matches in ${rows.size} cached declarations. Clear filters to show all."
            else -> "${model.rowCount} of ${rows.size} declarations · ${rows.count { it.checking }} from checks in progress"
        }
        summary.toolTipText = summary.text
        toolbar.updateActionsAsync()
    }

    internal fun clearFilters() {
        updatingControls = true
        try {
            search.text = ""
            provider.selectedIndex = 0
            buildRoot.selectedIndex = 0
            filter.selectedItem = DependencyReviewFilter.ALL
            kind.selectedIndex = 0
        } finally { updatingControls = false }
        applyFilter()
    }

    internal fun summaryText(): String = summary.text.orEmpty()

    internal fun selectedRow(): DependencyReviewRow? = table.selectedRow.takeIf { it >= 0 }
        ?.let { model.rows.getOrNull(table.convertRowIndexToModel(it)) }

    /** Capture on EDT before asynchronous export; model order can differ from the user's sort. */
    internal fun visibleRows(): List<DependencyReviewRow> = (0 until table.rowCount).map {
        model.rows[table.convertRowIndexToModel(it)]
    }

    internal fun canOpenSelectedSource() = selectedRow()?.source?.file?.let {
        LocalFileSystem.getInstance().findFileByPath(it)?.let { file -> file.isValid && !file.isDirectory }
    } == true

    private fun updateDetails() {
        val row = selectedRow()
        details.text = if (row == null) "Select a dependency to see its source, context and check details." else listOf(
            "Package: ${row.artifact}",
            "Observed: ${DependencyReviewFreshness.formatAge(row, now())}",
            "Cache expiry: ${DependencyReviewFreshness.formatExpiry(row, now())}",
            "Build system: ${row.adapter}", "Build root: ${relativePath(row.root)}",
            "Source: ${relativePath(row.source.file)}", "Declaration: ${row.source.location}",
            "Check source: ${row.origin?.checkSourceFile?.let(::relativePath) ?: "Unknown"}",
            row.origin?.declaration?.location?.let { "Check declaration: $it" }.orEmpty(),
            row.origin?.coverageDescription?.let { "Coverage scope: $it" }.orEmpty(),
            "Current: ${row.current}", "Available: ${row.available ?: "—"}", "Change: ${row.kind?.name ?: "—"}",
            "Outcome: ${row.outcome.label}${if (row.checkFailed) " (check failed)" else ""}${if (row.checking) " (checking)" else ""}",
            "Mode: ${row.mode.label}", "Details: ${row.detail.ifEmpty { "No additional diagnostic reported." }}",
            "Cached observations do not prove unchanged build inputs. Refresh and review before editing.",
        ).filter(String::isNotEmpty).joinToString("\n")
        details.caretPosition = 0
    }

    private fun openSelectedSource() {
        val row = selectedRow() ?: return
        // Cached offsets may have moved. Open the physical source file without trusting old PSI/ranges.
        val file = LocalFileSystem.getInstance().findFileByPath(row.source.file)?.takeIf { it.isValid && !it.isDirectory } ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    override fun dispose() { disposed = true; scope.cancel(); expiryTimer.stop(); rows = emptyList(); model.replace(emptyList()) }
}

internal class DependencyReviewTableModel(private val basePath: String?, private val now: () -> Long = System::nanoTime) : AbstractTableModel() {
    private var sampledAtNanos = now()
    var rows: List<DependencyReviewRow> = emptyList()
        private set
    private val columns = listOf("Build root", "Source", "Build system", "Package", "Current", "Available", "Change", "Outcome", "Mode", "Details", "Observed")
    fun replace(value: List<DependencyReviewRow>) {
        rows = value
        // Sorting must compare all ages against one clock sample, even across a millisecond boundary.
        sampledAtNanos = now()
        fireTableDataChanged()
    }
    override fun getRowCount() = rows.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]
    override fun getColumnClass(column: Int): Class<*> = if (column == 10) java.lang.Long::class.java else String::class.java
    override fun isCellEditable(row: Int, column: Int) = false
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
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
            7 -> row.outcome.label + (if (row.checkFailed) " (check failed)" else "") + (if (row.checking) " (checking)" else "")
            8 -> row.mode.label
            9 -> row.detail
            else -> DependencyReviewFreshness.elapsedMillis(row, sampledAtNanos)
        }
    }
}
