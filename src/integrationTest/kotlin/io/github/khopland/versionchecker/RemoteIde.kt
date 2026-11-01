package io.github.khopland.versionchecker

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.Document
import com.intellij.driver.sdk.Editor
import com.intellij.driver.sdk.FileEditorManager
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.PsiFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.ideFrame

// Stubs for the IDE classes the tests call. Quick fixes run through the same code as Alt+Enter, without key presses.

@Remote("com.intellij.psi.PsiDocumentManager")
interface PsiDocumentManagerRef {
    fun getPsiFile(document: Document): PsiFile?
    fun commitAllDocuments()
}

@Remote("com.intellij.codeInsight.intention.IntentionAction")
interface IntentionActionRef

@Remote("com.intellij.codeInsight.intention.impl.IntentionActionWithTextCaching")
interface IntentionWithTextRef {
    fun getText(): String
    fun getAction(): IntentionActionRef
}

@Remote("com.intellij.codeInsight.intention.impl.CachedIntentions")
interface CachedIntentionsRef {
    fun getAllActions(): List<IntentionWithTextRef>
}

@Remote("com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler")
interface ShowIntentionActionsHandlerRef {
    fun calcCachedIntentions(project: Project, editor: Editor, file: PsiFile): CachedIntentionsRef
    fun chooseActionAndInvoke(file: PsiFile, editor: Editor, action: IntentionActionRef, text: String, problemOffset: Int): Boolean
}

/** The Alt+Enter actions at the first occurrence of [caretText] in the open editor. */
fun Driver.intentionsAt(caretText: String): List<String> = withContext {
    val (editor, file) = editorAt(caretText)
    withContext(OnDispatcher.EDT) {
        utility(ShowIntentionActionsHandlerRef::class).calcCachedIntentions(singleProject(), editor, file).getAllActions().map { it.getText() }
    }
}

/** Runs the Alt+Enter action called [text] at [caretText], like choosing it from the popup. */
fun Driver.applyIntention(caretText: String, text: String) = withContext {
    val (editor, file) = editorAt(caretText)
    withContext(OnDispatcher.EDT) {
        val handler = utility(ShowIntentionActionsHandlerRef::class)
        val action = handler.calcCachedIntentions(singleProject(), editor, file).getAllActions().firstOrNull { it.getText() == text }
            ?: error("No intention '$text' at '$caretText'")
        check(handler.chooseActionAndInvoke(file, editor, action.getAction(), text, -1)) { "'$text' did not run" }
    }
}

/** Puts the caret just inside the first occurrence of [caretText] in the open editor. */
fun Driver.moveCaretTo(caretText: String): Editor {
    val editor = service(FileEditorManager::class, singleProject()).getSelectedTextEditor() ?: error("No editor is open")
    val offset = editor.getDocument().getText().indexOf(caretText)
    check(offset >= 0) { "'$caretText' is not in the open file" }
    withContext(OnDispatcher.EDT) { editor.getCaretModel().moveToOffset(offset + 1) }
    return editor
}

private fun Driver.editorAt(caretText: String): Pair<Editor, PsiFile> {
    val editor = moveCaretTo(caretText)
    val file = service(PsiDocumentManagerRef::class, singleProject()).getPsiFile(editor.getDocument()) ?: error("No PSI file")
    return editor to file
}

@Remote("com.intellij.openapi.vfs.LocalFileSystem")
interface LocalFileSystemRef {
    fun getInstance(): LocalFileSystemRef
    fun refreshAndFindFileByPath(path: String): com.intellij.driver.sdk.VirtualFile?
}

/**
 * Messages shown in the banner at the top of the open file, such as "Could not check 3 versions: …".
 * This reads the text of the IDE frame's components; it sends no input.
 */
fun Driver.fileLevelMessages(prefix: String): List<String> =
    ideFrame().getAllTexts { it.text.startsWith(prefix) }.map { it.text }.distinct()
