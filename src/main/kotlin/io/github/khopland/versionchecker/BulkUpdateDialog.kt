package io.github.khopland.versionchecker

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import io.github.khopland.versionchecker.core.UpdateScope
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

internal class VersionUpdateTableModel(val changes: List<VersionEdit>, private val basePath: String?) : AbstractTableModel() {
    private val selected = BooleanArray(changes.size) { true }
    val selectedIndices: Set<Int> get() = changes.indices.filter { selected[it] }.toSet()
    override fun getRowCount() = changes.size
    override fun getColumnCount() = 4
    override fun getColumnName(column: Int) = listOf("Update", "Declaration", "Current", "New")[column]
    override fun getColumnClass(column: Int): Class<*> = if (column == 0) Boolean::class.javaObjectType else String::class.java
    override fun isCellEditable(row: Int, column: Int) = column == 0
    override fun getValueAt(row: Int, column: Int): Any = when (column) {
        0 -> selected[row]
        1 -> basePath?.let { changes[row].location.removePrefix(it.trimEnd('/') + "/") } ?: changes[row].location
        2 -> changes[row].expected
        else -> changes[row].latest
    }
    override fun setValueAt(value: Any?, row: Int, column: Int) {
        if (column != 0 || value !is Boolean) return
        selected[row] = value
        fireTableRowsUpdated(row, row)
    }
    fun selectRows(rows: Set<Int>, value: Boolean) {
        rows.forEach { selected[it] = value }
        fireTableDataChanged()
    }
}

/** Each checkbox selects one prepared, atomic declaration/property/catalog edit. */
internal class BulkUpdateDialog(project: Project, mode: UpdateMode, scopeLabel: String,
                                buildSystem: String, private val plan: BulkUpdatePlan) : DialogWrapper(project) {
    companion object { const val RECHECK_EXIT_CODE = 3 }
    internal val modeSelector = ComboBox(UpdateMode.entries.toTypedArray())
    internal val scopeSelector = ComboBox(UpdateScope.entries.toTypedArray())
    internal val model = VersionUpdateTableModel(plan.changes, project.basePath)
    internal val table = object : JBTable(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            return if (row >= 0) this@BulkUpdateDialog.model.changes[convertRowIndexToModel(row)].location else null
        }
    }
    internal val filter = JBTextField()
    private val sorter = TableRowSorter<VersionUpdateTableModel>(model)
    private val freshness = JBLabel()
    internal val summary = JBLabel()
    var refreshRequested = false
        private set
    private var configuring = true
    internal val refreshAction: Action = object : AbstractAction("Refresh") {
        override fun actionPerformed(event: ActionEvent) { refreshRequested = true; close(RECHECK_EXIT_CODE) }
    }

    init {
        title = "Review $buildSystem updates"
        modeSelector.renderer = SimpleListCellRenderer.create("") { it.label }
        scopeSelector.renderer = SimpleListCellRenderer.create("") { it.label }
        modeSelector.selectedItem = mode
        scopeSelector.selectedItem = UpdateScope.entries.first { it.label == scopeLabel }
        table.rowSorter = sorter
        table.columnModel.getColumn(0).maxWidth = 70
        table.columnModel.getColumn(1).preferredWidth = 460
        table.columnModel.getColumn(2).preferredWidth = 120
        table.columnModel.getColumn(3).preferredWidth = 120
        table.accessibleContext.accessibleName = "Dependency version changes"
        table.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, setOf(KeyStroke.getKeyStroke("TAB")))
        table.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, setOf(KeyStroke.getKeyStroke("shift TAB")))
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("SPACE"), "toggleVersionSelection")
        table.actionMap.put("toggleVersionSelection", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                if (table.isEditing && !table.cellEditor.stopCellEditing()) return
                val rows = table.selectedRows.map { table.convertRowIndexToModel(it) }.toSet()
                if (rows.isNotEmpty()) {
                    model.selectRows(rows, rows.any { it !in model.selectedIndices })
                    table.clearSelection()
                    rows.map(table::convertRowIndexToView).filter { it >= 0 }.forEach { table.addRowSelectionInterval(it, it) }
                }
            }
        })
        filter.accessibleContext.accessibleName = "Filter version changes"
        modeSelector.accessibleContext.accessibleName = "Update mode"
        scopeSelector.accessibleContext.accessibleName = "Update scope"
        init()
        model.addTableModelListener { updateSummary() }
        filter.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                sorter.rowFilter = filter.text.takeIf { it.isNotBlank() }?.let {
                    RowFilter.regexFilter("(?i)" + Pattern.quote(it), 1, 2, 3)
                }
                updateSummary()
            }
        })
        modeSelector.addActionListener { if (!configuring) close(RECHECK_EXIT_CODE) }
        scopeSelector.addActionListener { if (!configuring) close(RECHECK_EXIT_CODE) }
        configuring = false
        updateSummary()
    }

    fun configure(prepared: PreparedVersionPreview, currentFileAvailable: Boolean) {
        configuring = true
        try {
            if (!currentFileAvailable) scopeSelector.removeItem(UpdateScope.CURRENT_FILE)
        } finally { configuring = false }
        val minutes = TimeUnit.NANOSECONDS.toMinutes((System.nanoTime() - prepared.checkedAtNanos).coerceAtLeast(0))
        val age = if (minutes == 0L) "just now" else "$minutes min ago"
        val cached = when (prepared.reusedReports) {
            0 -> ""
            prepared.totalReports -> " · cached results"
            else -> " · some cached results"
        }
        freshness.text = "Results checked $age$cached"
    }

    fun selectedPlan(): BulkUpdatePlan {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        return plan.select(model.selectedIndices)
    }

    override fun doOKAction() {
        if (table.isEditing && !table.cellEditor.stopCellEditing()) return
        if (model.selectedIndices.isNotEmpty()) super.doOKAction()
    }

    private fun updateSummary() {
        val selected = model.selectedIndices
        val visible = (0 until table.rowCount).map { table.convertRowIndexToModel(it) }.toSet()
        val hidden = selected.count { it !in visible }
        summary.text = "${selected.size} selected · ${table.rowCount} of ${model.rowCount} visible" +
            if (hidden > 0) " · $hidden selected hidden by filter" else ""
        setOKButtonText("Update ${selected.size} selected declarations")
        setOKActionEnabled(selected.isNotEmpty())
    }

    override fun getPreferredFocusedComponent(): JComponent = filter

    override fun createLeftSideActions(): Array<Action> = arrayOf(refreshAction)

    public override fun createCenterPanel(): JComponent {
        val controls = JPanel(FlowLayout(FlowLayout.LEADING)).apply {
            add(JBLabel("Scope:").apply { labelFor = scopeSelector; displayedMnemonic = 'S'.code }); add(scopeSelector)
            add(JBLabel("Updates:").apply { labelFor = modeSelector; displayedMnemonic = 'U'.code }); add(modeSelector)
        }
        val search = JPanel(BorderLayout(8, 0)).apply {
            add(JBLabel("Filter:").apply { labelFor = filter; displayedMnemonic = 'F'.code }, BorderLayout.WEST)
            add(filter, BorderLayout.CENTER)
        }
        val header = JPanel(BorderLayout(0, 8)).apply {
            add(controls, BorderLayout.NORTH); add(freshness, BorderLayout.CENTER); add(search, BorderLayout.SOUTH)
        }
        val selection = JPanel(FlowLayout(FlowLayout.LEADING)).apply {
            add(JButton("Select visible").apply { mnemonic = 'V'.code; addActionListener {
                this@BulkUpdateDialog.model.selectRows((0 until table.rowCount).map { table.convertRowIndexToModel(it) }.toSet(), true)
            } })
            add(JButton("Clear selection").apply { mnemonic = 'C'.code; addActionListener {
                this@BulkUpdateDialog.model.selectRows(this@BulkUpdateDialog.model.changes.indices.toSet(), false)
            } })
            add(summary)
        }
        val footer = JPanel(BorderLayout(0, 8)).apply {
            add(selection, BorderLayout.NORTH)
            add(JBLabel(if (plan.changes.isEmpty()) "No automatic updates available for ${modeSelector.selectedItem}." else
                "Each shared property or catalog version is one change affecting all listed consumers."), BorderLayout.CENTER)
            if (plan.skipped.isNotEmpty()) {
                val review = JBScrollPane(JBTextArea(plan.skipped.joinToString("\n")).apply {
                    isEditable = false; lineWrap = true; wrapStyleWord = true
                    accessibleContext.accessibleName = "Updates needing manual review"
                }).apply { preferredSize = Dimension(800, 100); isVisible = false }
                val details = JPanel(BorderLayout()).apply {
                    add(JBCheckBox("Needs review (${plan.skipped.size})").apply { addActionListener {
                        review.isVisible = isSelected; review.parent.revalidate(); review.parent.repaint()
                    } }, BorderLayout.NORTH)
                    add(review, BorderLayout.CENTER)
                }
                add(details, BorderLayout.SOUTH)
            }
        }
        return JPanel(BorderLayout(0, 12)).apply {
            add(header, BorderLayout.NORTH); add(JBScrollPane(table), BorderLayout.CENTER); add(footer, BorderLayout.SOUTH)
            preferredSize = Dimension(860, 480)
        }
    }
}
