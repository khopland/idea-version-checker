package io.github.khopland.versionchecker

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import javax.swing.JTextArea
import javax.swing.Timer

/** Polls immutable memory snapshots only. Passive viewing never starts a provider action. */
internal class UpdateSessionSummaryPanel(
    owner: Disposable, private val read: () -> UpdateSessionSnapshot?,
    private val present: (UpdateSessionSnapshot) -> UpdateSessionSnapshot = { it },
) : JBScrollPane() {
    private val expired = "No retained update session is available. Sessions expire after eight subsequent updates and are cleared when the project closes."
    private var observed = read()
    internal val text = JTextArea(observed?.let(present)?.summary() ?: expired, 24, 84).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true; caretPosition = 0
        accessibleContext.accessibleName = "Version update session summary"
        accessibleContext.accessibleDescription = "Live follow-up steps and historical declaration and verification observations"
    }
    init {
        setViewportView(text)
        val timer = Timer(300) {
            val latest = read()
            if (latest !== observed) {
                observed = latest
                val caret = text.caretPosition
                text.text = latest?.let(present)?.summary() ?: expired
                text.caretPosition = caret.coerceAtMost(text.document.length)
            }
        }
        Disposer.register(owner) { timer.stop() }
        timer.start()
    }
}
