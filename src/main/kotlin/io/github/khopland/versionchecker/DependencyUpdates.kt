package io.github.khopland.versionchecker

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.pom.Navigatable
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.TableView
import com.intellij.util.Alarm
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.EditSourceOnEnterKeyHandler
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.update.Activatable
import com.intellij.util.ui.update.UiNotifyConnector
import io.github.khopland.versionchecker.core.Change
import io.github.khopland.versionchecker.core.DependencyReport
import io.github.khopland.versionchecker.core.DependencyReport.Row
import io.github.khopland.versionchecker.core.Edits
import io.github.khopland.versionchecker.core.UpdateKind
import io.github.khopland.versionchecker.core.Versions
import io.github.khopland.versionchecker.core.VersionsChangedListener
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTable

object VersionCheckerIcons {
    @JvmField
    val ToolWindow = IconLoader.getIcon("/icons/dependencyUpdates.svg", VersionCheckerIcons::class.java)
}

class DependencyUpdatesToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DependencyUpdatesPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
    }

    companion object {
        const val ID = "Dependency Updates"
    }
}

/** **Tools → Versions → Show Dependency Updates**. */
class ShowDependencyUpdatesAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow(DependencyUpdatesToolWindowFactory.ID)?.activate(null)
    }
}

/**
 * Every outdated version in the project in one table, with the patch, minor and major update of each. With
 * **Check for known vulnerabilities** on, also every vulnerable version, worst first, with its severity and safe version.
 * The list checks again when a build file changes, after an update and after **Check Now**, while it is visible.
 */
class DependencyUpdatesPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true), Disposable {
    private val report = DependencyReport.getInstance(project)
    private val model = ListTableModel<Row>(columns(false), mutableListOf())
    private var vulnerabilities = false
    val table = TableView(model)
    private val status = JBLabel().apply {
        border = JBUI.Borders.empty(3, 8)
        foreground = UIUtil.getContextHelpForeground()
    }
    private val alarm = Alarm(this)
    private var stale = true

    init {
        Disposer.register(parent, this)
        model.isSortable = true
        table.setShowGrid(false)
        table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        TableSpeedSearch.installOn(table) { value, _ -> (value as? Row)?.let { "${it.declaration.name} ${location(it)}" } }
        EditSourceOnDoubleClickHandler.install(table)
        EditSourceOnEnterKeyHandler.install(table)

        val update = DefaultActionGroup("Update", UpdateKind.entries.map { UpdateRowsAction(it) } + SafeRowsAction()).apply {
            isPopup = true
            templatePresentation.icon = AllIcons.Actions.Upload
            templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("VersionChecker.DependencyUpdates",
            DefaultActionGroup(RefreshAction(), update), true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val menu = DefaultActionGroup(UpdateKind.entries.map { UpdateRowsAction(it) } + SafeRowsAction()).apply {
            addSeparator()
            add(ActionManager.getInstance().getAction(IdeActions.ACTION_EDIT_SOURCE))
        }
        PopupHandler.installPopupMenu(table, menu, ActionPlaces.TOOLWINDOW_POPUP)

        setContent(JPanel(BorderLayout()).apply {
            add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        })

        project.messageBus.connect(this).subscribe(VersionsChangedListener.TOPIC, VersionsChangedListener { refreshWhenShown() })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (watches(file)) {
                    alarm.cancelAllRequests()
                    alarm.addRequest(::refreshWhenShown, TYPING_DELAY)
                }
            }
        }, this)
        Disposer.register(this, UiNotifyConnector.installOn(this, object : Activatable {
            override fun showNotify() {
                if (stale) refresh()
            }
        }))
        report.watch(this, ::show)
    }

    /** Any build file of the last check, so a file without outdated versions can get one, and a file that sets a listed version. */
    internal fun watches(file: VirtualFile) = file in report.current.value.files || model.items.any { it.declaration.edit?.file == file }

    private fun refreshWhenShown() {
        if (isShowing) refresh() else stale = true
    }

    private fun refresh() {
        stale = false
        report.refresh()
    }

    /** The severity and safe version columns show only while vulnerabilities are checked. */
    private fun columns(vulnerabilities: Boolean): Array<ColumnInfo<*, *>> = listOfNotNull<ColumnInfo<*, *>>(
        Column("Dependency", comparator = compareBy { it.declaration.name }) { row ->
            icon = row.file.fileType.icon
            append(row.declaration.name)
            row.failure?.let { append("  could not check: $it", SimpleTextAttributes.ERROR_ATTRIBUTES) }
            if (row.updates?.deprecated != null) append("  deprecated", SimpleTextAttributes.ERROR_ATTRIBUTES)
        },
        Column("Current", width = "1.20.300-android", comparator = compareBy(Versions.comparator) { it.declaration.current }) {
            append(it.declaration.current)
        },
        Column("Vulnerabilities", width = "moderate (10)", comparator = compareBy { it.worst?.rank ?: -1 }) { row ->
            val worst = row.worst ?: return@Column
            val count = row.vulnerable!!.advisories.distinctBy { it.displayId }.size
            append((worst.severity?.takeIf { worst.rank > 0 }?.lowercase() ?: "unknown") + if (count > 1) " ($count)" else "",
                if (worst.rank >= 3) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }.takeIf { vulnerabilities },
        Column("Safe", width = "1.20.300-android", comparator = compareBy(nullsLast(Versions.comparator)) { it.vulnerable?.fix }) { row ->
            val version = row.vulnerable?.fix ?: return@Column
            append(version, if (row.safe != null) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }.takeIf { vulnerabilities },
        version(UpdateKind.PATCH),
        version(UpdateKind.MINOR),
        version(UpdateKind.MAJOR),
        Column("Location", comparator = compareBy<Row>({ it.file.path }, { it.line })) { row ->
            append("${location(row)}:${row.line}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        },
    ).toTypedArray()

    private fun show(state: DependencyReport.State) {
        val selected = table.selectedObjects.map { it.declaration.edit?.key ?: it.declaration.name }.toSet()
        if (state.vulnerabilities != vulnerabilities) {
            vulnerabilities = state.vulnerabilities
            model.columnInfos = columns(vulnerabilities)
        }
        model.items = state.rows
        table.selection = state.rows.filter { (it.declaration.edit?.key ?: it.declaration.name) in selected }
        val outdated = state.rows.filter { row -> row.updates?.let { !it.targets.isEmpty || it.deprecated != null } == true }
        val failed = state.rows.count { it.failure != null }
        val vulnerable = state.rows.count { it.vulnerable != null }
        val empty = table.emptyText
        when {
            state.running && state.rows.isEmpty() -> empty.text = "Checking versions…"
            state.done && state.checked == 0 -> empty.text = "No versions found in build files"
            state.done -> {
                empty.text = "All dependencies are up to date"
                empty.appendLine("${count(state.checked, "version")} checked in ${count(state.files.size, "build file")}", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            }
            else -> empty.text = ""
        }
        status.text = when {
            state.running -> "Checking versions…"
            !state.done -> ""
            else -> listOfNotNull(
                if (vulnerable > 0) "$vulnerable vulnerable" else null,
                state.vulnerabilityError?.let { "vulnerabilities not checked: $it" },
                "${outdated.size} of ${count(state.checked, "version")} outdated",
                UpdateKind.entries.reversed().mapNotNull { kind ->
                    outdated.count { it.offered(kind) != null }.takeIf { it > 0 }?.let { "$it ${kind.label}" }
                }.joinToString(" · ").takeIf { it.isNotEmpty() },
                if (failed > 0) "${count(failed, "version")} not checked" else null,
            ).joinToString(" · ")
        }
    }

    private fun location(row: Row): String =
        project.guessProjectDir()?.let { VfsUtilCore.getRelativePath(row.file, it) } ?: row.file.presentableUrl

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        val rows = table.selectedObjects
        sink[CommonDataKeys.NAVIGATABLE_ARRAY] = rows.map<Row, Navigatable> { it.source }.toTypedArray().takeIf { it.isNotEmpty() }
    }

    /** Writes the [mode] update of each selected row, or of every row when none is selected, as one undoable edit. */
    fun update(mode: UpdateKind) {
        val changes = targets().mapNotNull { row ->
            val version = row.fixes[mode] ?: return@mapNotNull null
            Change(row.declaration.edit!!, row.declaration.edit.format(version))
        }
        // The edit tells the list to check again.
        Edits.apply(project, changes, "Update ${mode.label} versions")
    }

    /**
     * Writes the safe version of each selected row, or of every row when none is selected, as one undoable edit. Rows that
     * share a version, such as a Maven property, get the highest one any of them needs.
     */
    fun updateSafe() {
        val changes = targets().filter { it.safe != null }.groupBy { it.declaration.edit!!.key }.values.map { rows ->
            val edit = rows.first().declaration.edit!!
            Change(edit, edit.format(rows.map { it.safe!! }.maxWith(Versions.comparator)))
        }
        Edits.apply(project, changes, "Update to safe versions")
    }

    private fun targets(): List<Row> = table.selectedObjects.ifEmpty { model.items }

    override fun dispose() {}

    private inner class UpdateRowsAction(private val mode: UpdateKind) : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val rows = targets()
            val count = rows.count { mode in it.fixes }
            val which = if (table.selectedObjects.isEmpty()) "All" else "Selected"
            e.presentation.text = "${mode.title} Updates" + if (count > 0) " ($which: $count)" else ""
            e.presentation.description = "Update the ${which.lowercase()} versions to the newest ${mode.label} release"
            e.presentation.isEnabled = count > 0
        }

        override fun actionPerformed(e: AnActionEvent) = update(mode)
    }

    private inner class SafeRowsAction : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val count = targets().count { it.safe != null }
            val which = if (table.selectedObjects.isEmpty()) "All" else "Selected"
            e.presentation.text = "Safe Versions" + if (count > 0) " ($which: $count)" else ""
            e.presentation.description = "Update the ${which.lowercase()} vulnerable versions to the nearest version without known vulnerabilities"
            e.presentation.isVisible = vulnerabilities
            e.presentation.isEnabled = count > 0
        }

        override fun actionPerformed(e: AnActionEvent) = updateSafe()
    }

    private inner class RefreshAction : DumbAwareAction("Check Again", "Read repository metadata again and check every build file", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !report.current.value.running
        }

        override fun actionPerformed(e: AnActionEvent) = refreshAll()
    }

    companion object {
        private const val TYPING_DELAY = 1500

        private fun count(n: Int, noun: String) = "$n ${StringUtil.pluralize(noun, n)}"

        private fun version(kind: UpdateKind) = Column(kind.title, width = "1.20.300-android",
            comparator = compareBy(nullsLast(Versions.comparator)) { row: Row -> row.offered(kind) }) { row ->
            val version = row.offered(kind) ?: return@Column
            append(version, if (row.canWrite(version)) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}

/** A column that renders its row with [render], with the editor warning as the tooltip. */
private class Column(name: String, private val width: String? = null, private val comparator: Comparator<Row>? = null,
                     render: ColoredTableCellRenderer.(Row) -> Unit) : ColumnInfo<Row, Row>(name) {
    private val renderer = object : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val item = value as? Row ?: return
            render(item)
            toolTipText = item.message
        }
    }

    override fun valueOf(item: Row) = item
    override fun getRenderer(item: Row) = renderer
    override fun getComparator() = comparator

    /** Version columns fit a long version, so the names and locations get the rest. */
    override fun getWidth(table: JTable) = width?.let { table.getFontMetrics(table.font).stringWidth(it) + JBUI.scale(16) } ?: -1
}
