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
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
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
 * Every outdated version in the project in one table, with the patch, minor and major update of each.
 * The list checks again when a build file changes, after an update and after **Check Now**, while it is visible.
 */
class DependencyUpdatesPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true), Disposable {
    private val report = DependencyReport.getInstance(project)
    private val model = ListTableModel<Row>(arrayOf(
        Column("Dependency", comparator = compareBy { it.declaration.name }) { row ->
            icon = row.file.fileType.icon
            append(row.declaration.name)
            row.failure?.let { append("  could not check: $it", SimpleTextAttributes.ERROR_ATTRIBUTES) }
            if (row.updates?.deprecated != null) append("  deprecated", SimpleTextAttributes.ERROR_ATTRIBUTES)
        },
        Column("Current", width = "1.20.300-android") { append(it.declaration.current) },
        version(UpdateKind.PATCH),
        version(UpdateKind.MINOR),
        version(UpdateKind.MAJOR),
        Column("Location", comparator = compareBy<Row>({ it.file.path }, { it.line })) { row ->
            append("${location(row)}:${row.line}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        },
    ), mutableListOf())
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

        val update = DefaultActionGroup("Update", UpdateKind.entries.map { UpdateRowsAction(it) }).apply {
            isPopup = true
            templatePresentation.icon = AllIcons.Actions.Upload
            templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("VersionChecker.DependencyUpdates",
            DefaultActionGroup(RefreshAction(), update), true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val menu = DefaultActionGroup(UpdateKind.entries.map { UpdateRowsAction(it) }).apply {
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
                if (model.items.any { it.file == file || it.declaration.edit?.file == file }) {
                    alarm.cancelAllRequests()
                    alarm.addRequest(::refreshWhenShown, TYPING_DELAY)
                }
            }
        }, this)
        UiNotifyConnector.installOn(this, object : Activatable {
            override fun showNotify() {
                if (stale) refresh()
            }
        })
        report.watch(this, ::show)
    }

    private fun refreshWhenShown() {
        if (isShowing) refresh() else stale = true
    }

    private fun refresh() {
        stale = false
        report.refresh()
    }

    private fun show(state: DependencyReport.State) {
        val selected = table.selectedObjects.map { it.declaration.edit?.key ?: it.declaration.name }.toSet()
        model.items = state.rows
        table.selection = state.rows.filter { (it.declaration.edit?.key ?: it.declaration.name) in selected }
        val outdated = state.rows.filter { it.failure == null }
        val failed = state.rows.size - outdated.size
        val empty = table.emptyText
        when {
            state.disabled -> {
                empty.text = "Version checks are turned off"
                empty.appendLine("Open settings", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, VersionCheckerConfigurable::class.java)
                }
            }
            state.running && state.rows.isEmpty() -> empty.text = "Checking versions…"
            state.done && state.checked == 0 -> empty.text = "No versions found in build files"
            state.done -> {
                empty.text = "All dependencies are up to date"
                empty.appendLine("${count(state.checked, "version")} checked in ${count(state.files, "build file")}", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            }
            else -> empty.text = ""
        }
        status.text = when {
            state.disabled -> ""
            state.running -> "Checking versions…"
            !state.done -> ""
            else -> listOfNotNull(
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
        sink[CommonDataKeys.NAVIGATABLE_ARRAY] = rows.map { row ->
            OpenFileDescriptor(project, row.file, row.declaration.range.startOffset) as Navigatable
        }.toTypedArray().takeIf { it.isNotEmpty() }
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
