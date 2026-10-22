package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.DiagnosticSanitizer

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import io.github.khopland.versionchecker.core.UpdateScope
import io.github.khopland.versionchecker.core.CoverageSummary
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
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

internal data class VersionEditIdentity(val location: String, val expected: String, val latest: String)
internal fun VersionEdit.identity() = VersionEditIdentity(location, expected, latest)

internal class VersionUpdateTableModel(changes: List<VersionEdit>, private val basePath: String?) : AbstractTableModel() {
    var changes = changes
        private set
    private var selected = BooleanArray(changes.size) { true }
    private val choices = LinkedHashMap<VersionEditIdentity, Boolean>()
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
    fun replaceTarget(row: Int, edit: VersionEdit) {
        changes = changes.toMutableList().also { it[row] = edit }
        fireTableRowsUpdated(row, row)
    }
    /** Remember exact reviewed targets across scopes; new/replaced targets require explicit selection. */
    fun replaceChanges(replacement: List<VersionEdit>) {
        changes.forEachIndexed { index, edit -> choices[edit.identity()] = selected[index] }
        while (choices.size > 20_000) choices.remove(choices.keys.first())
        changes = replacement
        selected = BooleanArray(changes.size) { choices[changes[it].identity()] == true }
        fireTableDataChanged()
    }
}

/** Each checkbox selects one prepared, atomic declaration/property/catalog edit. */
internal class BulkUpdateDialog(project: Project, mode: UpdateMode, scopeLabel: String,
                                buildSystem: String, private var plan: BulkUpdatePlan) : DialogWrapper(project) {
    companion object { const val RECHECK_EXIT_CODE = 3 }
    internal val modeSelector = ComboBox(UpdateMode.entries.toTypedArray())
    internal val scopeSelector = ComboBox(UpdateScope.entries.filter {
        it != UpdateScope.MAVEN_PLATFORM || scopeLabel == it.label
    }.toTypedArray())
    internal val model = VersionUpdateTableModel(plan.changes, project.basePath)
    internal val table = object : JBTable(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            return if (row >= 0) this@BulkUpdateDialog.model.changes[convertRowIndexToModel(row)].location else null
        }
    }
    internal val filter = JBTextField()
    private val sorter = TableRowSorter<VersionUpdateTableModel>(model)
    private val tableScroll = JBScrollPane(table)
    private val freshness = JBLabel()
    internal val coverageLabel = JBLabel().apply { isVisible = false }
    private var coverage: CoverageSummary? = null
    private val emptyMessage = JBLabel()
    internal val reviewList = JBList<VersionReviewItem>()
    internal val reviewDetails = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; rows = 4 }
    private val reviewOpen = JButton("Open review source")
    private val reviewToggle = JBCheckBox()
    private val reviewScroll = JPanel(BorderLayout(8, 4)).apply {
        add(JBScrollPane(reviewList).apply { preferredSize = Dimension(280, 120) }, BorderLayout.WEST)
        add(JBScrollPane(reviewDetails), BorderLayout.CENTER)
        add(reviewOpen, BorderLayout.SOUTH)
        preferredSize = Dimension(800, 140); isVisible = false
    }
    internal val changeDetails = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; rows = 3 }
    private val openButton = JButton("Open declaration")
    private val diffButton = JButton("Preview change")
    internal val targetSelector = ComboBox<VersionTargetChoice>()
    private val targetReason = JBLabel()
    private var targetChoices = VersionTargetChoices(plan, mode)
    private var configuringTargets = false
    private var targetPopupOpen = false
    private var targetPopupChoice: VersionTargetChoice? = null
    internal val targetPopupListener = object : PopupMenuListener {
        override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) {
            targetPopupChoice = targetSelector.selectedItem as? VersionTargetChoice
            targetPopupOpen = true
        }
        override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) {
            targetPopupOpen = false
            commitTargetSelection()
        }
        override fun popupMenuCanceled(event: PopupMenuEvent) { restoreTargetSelection() }
    }
    internal var targetChoicesRequested = false
        private set
    internal val summary = JBLabel()
    var refreshRequested = false
        private set
    internal var recheckInteraction: CheckPerformance.Interaction? = null
        private set
    internal var applyInteraction: CheckPerformance.Interaction? = null
        private set
    private var configuring = true
    private var replacingRows = false
    private var previewState = VersionPreviewState(mode, UpdateScope.entries.first { it.label == scopeLabel })
    internal val checking get() = previewState.checking
    internal var onRecheckRequested: ((UpdateMode, UpdateScope, Boolean, CheckPerformance.Interaction?) -> Unit)? = null
    internal var onApplyRequested: ((BulkUpdatePlan, CheckPerformance.Interaction?) -> Unit)? = null
    private val commitSelectors = mutableListOf<() -> Unit>()
    internal val refreshAction: Action = object : AbstractAction("Refresh") {
        override fun actionPerformed(event: ActionEvent) { refreshRequested = true; requestRecheck() }
    }
    internal val loadTargetsAction: Action = object : AbstractAction("Check other targets") {
        override fun actionPerformed(event: ActionEvent) {
            if (!previewState.canApply) return
            targetChoicesRequested = true
            requestRecheck()
        }
    }

    init {
        title = "Review $buildSystem updates"
        modeSelector.renderer = choiceRenderer<UpdateMode> { it.label }
        scopeSelector.renderer = choiceRenderer<UpdateScope> { it.label }
        modeSelector.selectedItem = mode
        scopeSelector.selectedItem = UpdateScope.entries.first { it.label == scopeLabel }
        previewState = previewState.configured(scopeSelector.selectedItem as UpdateScope)
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
        table.selectionModel.addListSelectionListener {
            if (replacingRows) return@addListSelectionListener
            val edit = table.selectedRows.singleOrNull()?.let { model.changes.getOrNull(table.convertRowIndexToModel(it)) }
            changeDetails.text = edit?.review?.description().orEmpty()
            changeDetails.caretPosition = 0
            updateTargetDetails()
            updateSummary()
        }
        changeDetails.accessibleContext.accessibleName = "Selected update explanation"
        reviewList.cellRenderer = choiceRenderer { it: VersionReviewItem -> DiagnosticSanitizer.sanitize(listOfNotNull(it.declaration, it.reason).joinToString(": ")) }
        reviewList.accessibleContext.accessibleName = "Updates needing review"
        reviewDetails.accessibleContext.accessibleName = "Selected review explanation"
        reviewList.addListSelectionListener {
            reviewDetails.text = reviewList.selectedValue?.description().orEmpty()
            reviewDetails.caretPosition = 0
            reviewOpen.isEnabled = reviewList.selectedValue?.source != null
        }
        reviewOpen.addActionListener { reviewList.selectedValue?.source?.let { project.service<BulkUpdateService>().openReviewSource(it) } }
        openButton.mnemonic = 'O'.code
        diffButton.mnemonic = 'P'.code
        fun highlighted(): VersionEdit? = table.selectedRows.singleOrNull()?.let { model.changes.getOrNull(table.convertRowIndexToModel(it)) }
        openButton.addActionListener { highlighted()?.let { project.service<BulkUpdateService>().openDeclaration(it) } }
        diffButton.addActionListener { highlighted()?.let { project.service<BulkUpdateService>().previewEdit(it) } }
        targetSelector.accessibleContext.accessibleName = "Checked target version"
        targetSelector.addPopupMenuListener(targetPopupListener)
        targetSelector.addActionListener { if (!targetPopupOpen) commitTargetSelection() }
        val escape = KeyStroke.getKeyStroke("ESCAPE")
        targetSelector.getInputMap(JComponent.WHEN_FOCUSED).put(escape, "cancelTargetChoice")
        targetSelector.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(escape, "cancelTargetChoice")
        targetSelector.actionMap.put("cancelTargetChoice", object : AbstractAction() {
            override fun isEnabled() = targetPopupOpen
            override fun actionPerformed(event: ActionEvent) {
                restoreTargetSelection()
                targetSelector.isPopupVisible = false
            }
        })
        filter.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                sorter.rowFilter = filter.text.takeIf { it.isNotBlank() }?.let {
                    RowFilter.regexFilter("(?i)" + Pattern.quote(it), 1, 2, 3)
                }
                updateSummary()
            }
        })
        recheckOnCommittedSelection(modeSelector)
        recheckOnCommittedSelection(scopeSelector)
        configuring = false
        updatePlanDetails()
        updateSummary()
    }

    fun configure(prepared: PreparedVersionPreview, currentFileAvailable: Boolean, platformAvailable: Boolean = false) {
        targetChoices = VersionTargetChoices(plan, previewState.mode, prepared.targetPlans)
        configuring = true
        try {
            if (!currentFileAvailable) scopeSelector.removeItem(UpdateScope.CURRENT_FILE)
            if (platformAvailable && (0 until scopeSelector.itemCount).none { scopeSelector.getItemAt(it) == UpdateScope.MAVEN_PLATFORM })
                scopeSelector.addItem(UpdateScope.MAVEN_PLATFORM)
            if (!platformAvailable) scopeSelector.removeItem(UpdateScope.MAVEN_PLATFORM)
            previewState = previewState.configured(scopeSelector.selectedItem as UpdateScope)
        } finally { configuring = false }
        val minutes = TimeUnit.NANOSECONDS.toMinutes((System.nanoTime() - prepared.checkedAtNanos).coerceAtLeast(0))
        val age = if (minutes == 0L) "just now" else "$minutes min ago"
        val cached = when (prepared.reusedReports) {
            0 -> ""
            prepared.totalReports -> " · cached results"
            else -> " · some cached results"
        }
        freshness.text = "Results checked $age$cached"
        coverage = prepared.coverage
        coverageLabel.text = coverage?.let { "Recognized declarations: ${it.description}" }.orEmpty()
        coverageLabel.isVisible = coverage != null
        updatePlanDetails()
        updateTargetDetails()
    }

    fun selectedPlan(): BulkUpdatePlan {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        val preparedGeneration = previewState.generation
        return targetChoices.plan(model.selectedIndices).guardedBy {
            previewState.authorizes(preparedGeneration, modeSelector.selectedItem as UpdateMode,
                scopeSelector.selectedItem as UpdateScope)
        }
    }

    internal fun replacePrepared(prepared: PreparedVersionPreview, mode: UpdateMode, scope: UpdateScope,
                                 buildSystems: String, currentFileAvailable: Boolean, platformAvailable: Boolean) {
        if (isDisposed) return
        val highlighted = table.selectedRows.map { model.changes[table.convertRowIndexToModel(it)].identity() }.toSet()
        val position = tableScroll.viewport.viewPosition
        plan = prepared.plan
        previewState = previewState.prepared(mode, scope)
        configuring = true
        try {
            modeSelector.selectedItem = mode
            scopeSelector.selectedItem = scope
            // Model listeners run in reverse registration order. Our summary listener must
            // not inspect row mappings until JTable has notified its sorter of the new size.
            replacingRows = true
            try { model.replaceChanges(plan.changes) } finally { replacingRows = false }
            configure(prepared, currentFileAvailable, platformAvailable)
        } finally { configuring = false }
        title = "Review $buildSystems updates"
        refreshRequested = false
        table.clearSelection()
        model.changes.forEachIndexed { index, edit ->
            val row = table.convertRowIndexToView(index)
            if (row >= 0 && edit.identity() in highlighted) table.addRowSelectionInterval(row, row)
        }
        tableScroll.viewport.viewPosition = position
        updatePlanDetails()
        updateSummary()
        CheckPerformance.record(CheckPerformance.Stage.PREVIEW_READY, recheckInteraction?.started ?: System.nanoTime(), model.rowCount)
    }

    internal fun recheckFailed(message: String) {
        if (isDisposed) return
        previewState = previewState.failed(message)
        coverageLabel.isVisible = false
        freshness.text = "$message Use Refresh to retry."
        updateSummary()
    }

    public override fun doOKAction() {
        if (!previewState.canApply) return
        if (modeSelector.selectedItem != previewState.mode || scopeSelector.selectedItem != previewState.scope) {
            requestRecheck()
            return
        }
        if (model.selectedIndices.isEmpty()) return
        if (table.isEditing && !table.cellEditor.stopCellEditing()) return
        applyInteraction = CheckPerformance.start(CheckPerformance.Stage.BULK_APPLY_INVOKED)
        onApplyRequested?.let { callback ->
            val selected = selectedPlan()
            val trace = applyInteraction
            val preparedGeneration = previewState.generation
            previewState = previewState.applying()
            updateSummary()
            // Production reviews are modeless. Accessibility button handlers can run
            // outside a write-safe transaction even on EDT; acquire one before applying.
            ApplicationManager.getApplication().invokeLater({
                try {
                    if (!isDisposed && previewState.generation == preparedGeneration &&
                        previewState.phase == VersionPreviewState.Phase.Applying) callback(selected, trace)
                } finally {
                    previewState = previewState.applyFinished(preparedGeneration)
                    if (!isDisposed) updateSummary()
                }
            }, ModalityState.nonModal())
            return
        }
        super.doOKAction()
    }

    private fun requestRecheck() {
        if (isDisposed) return
        recheckInteraction = CheckPerformance.start(CheckPerformance.Stage.PREVIEW_INVOKED)
        previewState = previewState.recheck()
        val callback = onRecheckRequested
        if (callback == null) close(RECHECK_EXIT_CODE) else {
            commitSelectors.forEach { it() }
            freshness.text = "Checking ${(scopeSelector.selectedItem as UpdateScope).label} · ${(modeSelector.selectedItem as UpdateMode).label}…"
            coverageLabel.isVisible = false
            updateSummary()
            callback(modeSelector.selectedItem as UpdateMode, scopeSelector.selectedItem as UpdateScope, refreshRequested, recheckInteraction)
            refreshRequested = false
        }
    }

    private fun recheckOnCommittedSelection(selector: ComboBox<*>) {
        var preparedItem = selector.selectedItem
        commitSelectors += { preparedItem = selector.selectedItem }
        var popupOpen = false
        var scheduled = false
        fun restorePreparedItem() {
            val wasConfiguring = configuring
            configuring = true
            try { selector.selectedItem = preparedItem } finally { configuring = wasConfiguring }
        }
        fun requestRecheck() {
            if (configuring || popupOpen || scheduled || isDisposed || selector.selectedItem == preparedItem) return
            scheduled = true
            // Finish the combo's key/mouse event before disposing its containing dialog.
            SwingUtilities.invokeLater {
                scheduled = false
                if (!configuring && !popupOpen && !isDisposed && selector.selectedItem != preparedItem) {
                    this@BulkUpdateDialog.requestRecheck()
                }
            }
        }
        selector.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) { popupOpen = true }
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) {
                popupOpen = false
                requestRecheck()
            }
            override fun popupMenuCanceled(event: PopupMenuEvent) {
                restorePreparedItem()
            }
        })
        val cancelChoice = "cancelVersionPreviewChoice"
        val escape = KeyStroke.getKeyStroke("ESCAPE")
        selector.getInputMap(JComponent.WHEN_FOCUSED).put(escape, cancelChoice)
        selector.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(escape, cancelChoice)
        selector.actionMap.put(cancelChoice, object : AbstractAction() {
            override fun isEnabled() = popupOpen
            override fun actionPerformed(event: ActionEvent) {
                restorePreparedItem()
                selector.isPopupVisible = false
            }
        })
        selector.addActionListener {
            if (configuring) preparedItem = selector.selectedItem else requestRecheck()
        }
    }

    private fun updateSummary() {
        if (replacingRows) return
        val selected = model.selectedIndices
        val visible = (0 until table.rowCount).map { table.convertRowIndexToModel(it) }.toSet()
        val hidden = selected.count { it !in visible }
        summary.text = "${selected.size} selected · ${table.rowCount} of ${model.rowCount} visible" +
            if (hidden > 0) " · $hidden selected hidden by filter" else ""
        setOKButtonText("Update ${selected.size} selected declarations")
        setOKActionEnabled(selected.isNotEmpty() && previewState.canApply)
        openButton.isEnabled = table.selectedRows.size == 1 && previewState.canInspect
        diffButton.isEnabled = openButton.isEnabled
        targetSelector.isEnabled = openButton.isEnabled && targetSelector.itemCount > 1 && previewState.canApply
        loadTargetsAction.isEnabled = previewState.canApply && previewState.mode != UpdateMode.PATCH && !targetChoicesRequested
    }

    private fun updateTargetDetails() {
        configuringTargets = true
        try {
            targetSelector.removeAllItems()
            val row = table.selectedRows.singleOrNull()?.let(table::convertRowIndexToModel)
            if (row != null && row in model.changes.indices) {
                targetChoices.options(row).forEach(targetSelector::addItem)
                targetSelector.selectedItem = targetChoices.selected(row)
                targetReason.text = if (targetSelector.itemCount > 1)
                    "Each target has its own native check. Compatibility is unverified."
                else if (targetChoicesRequested || previewState.mode == UpdateMode.PATCH)
                    "No other checked branch target for this declaration."
                else "Use Check other targets for checked patch and minor alternatives."
            } else targetReason.text = "Select one declaration to choose a target."
        } finally { configuringTargets = false }
    }

    private fun restoreTargetSelection() {
        configuringTargets = true
        try { targetSelector.selectedItem = targetPopupChoice } finally { configuringTargets = false }
    }

    private fun commitTargetSelection() {
        if (configuringTargets || !previewState.canApply || isDisposed) return
        val row = table.selectedRows.singleOrNull()?.let(table::convertRowIndexToModel) ?: return
        val choice = targetSelector.selectedItem as? VersionTargetChoice ?: return
        if (!targetChoices.choose(row, choice)) return
        previewState = previewState.prepared(previewState.mode, previewState.scope)
        replacingRows = true
        try { model.replaceTarget(row, choice.edit) } finally { replacingRows = false }
        table.clearSelection()
        table.convertRowIndexToView(row).takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) }
        changeDetails.text = choice.edit.review?.description().orEmpty()
        changeDetails.caretPosition = 0
        updateTargetDetails()
        updateSummary()
    }

    private fun updatePlanDetails() {
        emptyMessage.text = if (plan.changes.isEmpty()) when {
            coverage?.let { it.checked == 0 && (it.skipped > 0 || it.review > 0) } == true ->
                "No declarations were checked. Review the skipped or unsupported items below."
            coverage == CoverageSummary() -> "No recognized version declarations were found."
            else -> "No automatic updates available for ${previewState.mode.label}."
        } else
            "Each shared property or catalog version is one change affecting all listed consumers."
        val items = plan.reviewItems.ifEmpty { plan.skipped.map { VersionReviewItem(it) } }
        reviewList.setListData(items.toTypedArray())
        if (items.isNotEmpty()) reviewList.selectedIndex = 0
        reviewOpen.isEnabled = reviewList.selectedValue?.source != null
        reviewToggle.text = "Needs review (${items.size})"
        reviewToggle.isVisible = items.isNotEmpty()
        reviewScroll.isVisible = items.isNotEmpty() && reviewToggle.isSelected
    }

    override fun dispose() {
        previewState = previewState.closed(isOK)
        super.dispose()
    }

    override fun getPreferredFocusedComponent(): JComponent = filter

    override fun createLeftSideActions(): Array<Action> = arrayOf(refreshAction, loadTargetsAction)

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
            add(controls, BorderLayout.NORTH)
            add(JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(freshness); add(coverageLabel) }, BorderLayout.CENTER)
            add(search, BorderLayout.SOUTH)
        }
        val selectionButtons = JPanel(FlowLayout(FlowLayout.LEADING)).apply {
            add(JButton("Select visible").apply { mnemonic = 'V'.code; addActionListener {
                this@BulkUpdateDialog.model.selectRows((0 until table.rowCount).map { table.convertRowIndexToModel(it) }.toSet(), true)
            } })
            add(JButton("Clear selection").apply { mnemonic = 'C'.code; addActionListener {
                this@BulkUpdateDialog.model.selectRows(this@BulkUpdateDialog.model.changes.indices.toSet(), false)
            } })
            add(openButton); add(diffButton)
        }
        val targetControls = JPanel(FlowLayout(FlowLayout.LEADING)).apply {
            add(JBLabel("Target:").apply { labelFor = targetSelector }); add(targetSelector); add(targetReason)
        }
        val selection = JPanel(BorderLayout(0, 4)).apply {
            add(JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(selectionButtons); add(targetControls) }, BorderLayout.NORTH)
            add(JBScrollPane(changeDetails), BorderLayout.CENTER); add(summary, BorderLayout.SOUTH)
        }
        val footer = JPanel(BorderLayout(0, 8)).apply {
            add(selection, BorderLayout.NORTH)
            add(emptyMessage, BorderLayout.CENTER)
            reviewToggle.addActionListener {
                reviewScroll.isVisible = reviewToggle.isSelected && reviewList.model.size > 0
                reviewScroll.parent.revalidate(); reviewScroll.parent.repaint()
            }
            add(JPanel(BorderLayout()).apply {
                add(reviewToggle, BorderLayout.NORTH); add(reviewScroll, BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
            updatePlanDetails()
        }
        return JPanel(BorderLayout(0, 12)).apply {
            add(header, BorderLayout.NORTH); add(tableScroll, BorderLayout.CENTER); add(footer, BorderLayout.SOUTH)
            preferredSize = Dimension(860, 480)
        }
    }

    private fun <T> choiceRenderer(label: (T) -> String): SimpleListCellRenderer<T> =
        object : SimpleListCellRenderer<T>() {
            override fun customize(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.let(label).orEmpty()
            }
        }
}
