package io.github.khopland.versionchecker

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.Editor
import com.intellij.driver.sdk.FileEditorManager
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.ScrollType
import com.intellij.driver.sdk.ToolWindowManager
import com.intellij.driver.sdk.getNotifications
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.remote.Component
import java.awt.Point
import java.nio.file.Files
import java.nio.file.Path

// Screenshots for the Marketplace page. The IDE paints its own window into an image, so nothing else on the
// screen is captured and no input is sent.

@Remote("com.intellij.openapi.wm.WindowManager")
interface WindowManagerRef {
    fun getInstance(): WindowManagerRef
    fun getFrame(project: Project): WindowRef?
}

@Remote("java.awt.Window")
interface WindowRef {
    fun getWindows(): List<WindowRef>
    fun isShowing(): Boolean
    fun getLocationOnScreen(): Point
    fun getWidth(): Int
    fun getHeight(): Int
    fun setSize(width: Int, height: Int)
}

@Remote("javax.swing.RootPaneContainer")
interface RootPaneContainerRef {
    fun getRootPane(): JComponentRef
}

@Remote("javax.swing.JComponent")
interface JComponentRef {
    fun setVisible(visible: Boolean)
    fun getX(): Int
    fun getY(): Int
    fun paint(g: GraphicsRef)
}

@Remote("java.awt.image.BufferedImage")
interface BufferedImageRef {
    fun createGraphics(): GraphicsRef
}

@Remote("java.awt.Graphics2D")
interface GraphicsRef {
    fun create(): GraphicsRef
    fun scale(sx: Double, sy: Double)
    fun translate(x: Int, y: Int)
    fun dispose()
}

@Remote("java.io.File")
interface FileRef

@Remote("javax.imageio.ImageIO")
interface ImageIORef {
    fun write(image: BufferedImageRef, format: String, file: FileRef): Boolean
}

@Remote("com.intellij.openapi.editor.Editor")
interface EditorComponentRef {
    fun getContentComponent(): Component
}

@Remote("com.intellij.codeInsight.hint.HintManager")
interface HintManagerRef {
    fun getInstance(): HintManagerRef
    fun hideAllHints()
}

@Remote("com.intellij.openapi.editor.ex.EditorSettingsExternalizable")
interface EditorSettingsRef {
    fun getInstance(): EditorSettingsRef
    fun setStickyLinesShown(value: Boolean)
}

@Remote("com.intellij.diagnostic.MessagePool")
interface MessagePoolRef {
    fun getInstance(): MessagePoolRef
    fun clearErrors()
}

@Remote("com.intellij.openapi.ui.popup.JBPopupFactory")
interface JBPopupFactoryRef {
    fun getInstance(): JBPopupFactoryRef
    fun getChildPopups(component: JComponentRef): List<JBPopupRef>
}

@Remote("com.intellij.openapi.ui.popup.JBPopup")
interface JBPopupRef {
    fun cancel()
}

private const val SCALE = 2

private fun Driver.frame(): WindowRef = utility(WindowManagerRef::class).getInstance().getFrame(singleProject()) ?: error("No IDE frame")

/**
 * Gives every screenshot the same window size and leaves only the editor: tool windows are closed, and the
 * trial badge and memory indicator are hidden.
 */
fun Driver.prepareWindow(width: Int = 1280, height: Int = 800) {
    withContext(OnDispatcher.EDT) {
        frame().setSize(width, height)
        val toolWindows = utility(ToolWindowManager::class).getInstance(singleProject())
        listOf("Project", "Services", "Problems View", "Build", "Terminal", "Run", "Maven", "Gradle", "npm")
            .forEach { toolWindows.getToolWindow(it)?.hide() }
        // Sticky lines cover the top of a scrolled editor.
        utility(EditorSettingsRef::class).getInstance().setStickyLinesShown(false)
    }
    ideFrame {
        val clutter = xx("//div[starts-with(@visible_text, 'Trial:') or contains(@class, 'MemoryUsage')]").list()
        withContext(OnDispatcher.EDT) { clutter.forEach { cast(it.component, JComponentRef::class).setVisible(false) } }
    }
    Thread.sleep(1500)
}

/** Hides the notification balloons, such as the IDE's own "Install dependencies", and "IDE error occurred". */
fun Driver.hideBalloons() {
    withContext(OnDispatcher.EDT) {
        getNotifications(singleProject()).forEach { it.hideBalloon() }
        // Errors of other bundled plugins, which have nothing to do with this one.
        utility(MessagePoolRef::class).getInstance().clearErrors()
    }
    Thread.sleep(500)
}

/**
 * Paints the IDE window and the popups and tooltips over it into `<dir>/<name>.png`, at twice the size.
 * Popups are separate windows, so each is painted at its place on the frame.
 */
fun Driver.saveScreenshot(name: String) {
    val dir = Path.of(System.getProperty("versionchecker.screenshots"))
    Files.createDirectories(dir)
    val target = dir.resolve("$name.png").toAbsolutePath().toString()
    withContext(OnDispatcher.EDT) {
        val frame = frame()
        val origin = frame.getLocationOnScreen()
        val image = new(BufferedImageRef::class, frame.getWidth() * SCALE, frame.getHeight() * SCALE, 1 /* TYPE_INT_RGB */)
        val graphics = image.createGraphics()
        graphics.scale(SCALE.toDouble(), SCALE.toDouble())
        paintWindow(frame, graphics, 0, 0)
        for (window in utility(WindowRef::class).getWindows()) {
            // A remote reference's hashCode is the identity hash of the object in the IDE.
            if (window.hashCode() == frame.hashCode() || !window.isShowing()) continue
            val at = window.getLocationOnScreen()
            paintWindow(window, graphics, at.x - origin.x, at.y - origin.y)
        }
        graphics.dispose()
        utility(ImageIORef::class).write(image, "png", new(FileRef::class, target))
    }
    println("Screenshot: $target")
}

private fun Driver.paintWindow(window: WindowRef, graphics: GraphicsRef, x: Int, y: Int) {
    val root = cast(window, RootPaneContainerRef::class).getRootPane()
    val g = graphics.create()
    g.translate(x + root.getX(), y + root.getY())
    root.paint(g)
    g.dispose()
}

private fun Driver.editorComponent(): Component {
    val editor: Editor = service(FileEditorManager::class, singleProject()).getSelectedTextEditor() ?: error("No editor is open")
    return cast(editor, EditorComponentRef::class).getContentComponent()
}

/** Moves the caret into [caretText] and scrolls its line to the middle of the editor. */
fun Driver.centerCaretOn(caretText: String) {
    val editor = moveCaretTo(caretText)
    withContext(OnDispatcher.EDT) { editor.getScrollingModel().scrollToCaret(utility(ScrollType::class).valueOf("CENTER")) }
    Thread.sleep(500)
}

/** Shows the Alt+Enter popup at the caret, through the action rather than a key press. */
fun Driver.showIntentions() {
    invokeAction("ShowIntentionActions", component = editorComponent())
    Thread.sleep(3000)
}

/** Shows the description of the warning at the caret, like hovering over it. */
fun Driver.showWarningDescription() {
    invokeAction("ShowErrorDescription", component = editorComponent())
    Thread.sleep(2000)
}

/** Closes the popups and tooltips that the screenshots opened. */
fun Driver.closePopups() {
    withContext(OnDispatcher.EDT) {
        utility(HintManagerRef::class).getInstance().hideAllHints()
        val root = cast(frame(), RootPaneContainerRef::class).getRootPane()
        utility(JBPopupFactoryRef::class).getInstance().getChildPopups(root).forEach { it.cancel() }
    }
    Thread.sleep(500)
}
