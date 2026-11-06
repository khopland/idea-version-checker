package io.github.khopland.versionchecker.npm

import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonPsiUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager

internal object NpmOverrides {
    /**
     * `name@^2` for a vulnerable 2.x, or `name@^0.3` for 0.3.x: an override for the copies in that range only, since an
     * override of the bare name would move copies of other majors too. Null when [fix] is outside that range, or for
     * 0.0.x, where each patch is its own range and a range value would move newer copies back.
     */
    fun key(name: String, current: String, fix: String): String? {
        val (major, minor) = current.split('.').let { (it.getOrNull(0) ?: return null) to (it.getOrNull(1) ?: return null) }
        if (major == "0" && minor == "0") return null
        val base = if (major == "0") "0.$minor" else major
        return if (fix.startsWith("$base.")) "$name@^$base" else null
    }

    /** The object at [keys] in [root], such as `pnpm`, `overrides`, or null when a key is missing or is not an object. */
    fun find(root: JsonObject, keys: List<String>): JsonObject? = keys.fold(root as JsonObject?) { owner, key -> owner?.findProperty(key)?.value as? JsonObject }

    /**
     * Whether [key] can go in the overrides at [keys]: neither it nor an override of every copy of [name] is there, and no
     * key on the way is set to something other than an object, which would get a second one.
     */
    fun canAdd(root: JsonObject, keys: List<String>, name: String, key: String): Boolean {
        var owner = root
        for (k in keys) owner = (owner.findProperty(k) ?: return true).value as? JsonObject ?: return false
        return owner.propertyList.none { it.name == name || it.name == key }
    }
}

/**
 * Alt+Enter: add an override of [key], such as `qs@^6`, to the object at [keys] in `package.json`, such as `overrides` for
 * npm or `pnpm`, `overrides`. The value is a range from [version], so newer copies in the range keep their version.
 */
class NpmOverrideFix(private val keys: Array<String>, private val name: String, private val key: String, private val version: String) :
    IntentionAction, PriorityAction {
    override fun getText() = "Override $key to ^$version in package.json"
    override fun getFamilyName() = "Override transitive dependency"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return false
        return NpmOverrides.canAdd(root, keys.toList(), name, key)
    }
    override fun startInWriteAction() = true
    override fun getPriority() = PriorityAction.Priority.TOP

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return
        val generator = JsonElementGenerator(project)
        // The outermost object the fix creates is formatted, or else the new entry.
        var created: PsiElement? = null
        val overrides = keys.fold(root) { owner, k ->
            owner.findProperty(k)?.value as? JsonObject
                ?: (JsonPsiUtil.addProperty(owner, generator.createProperty(k, "{}"), false) as JsonProperty).also { created = created ?: it }.value as JsonObject
        }
        val added = JsonPsiUtil.addProperty(overrides, generator.createProperty(key, "\"^$version\""), false)
        // The JSON formatter builds its blocks from the file, so it formats a range rather than the new element.
        val range = (created ?: added).textRange
        CodeStyleManager.getInstance(project).reformatRange(file, range.startOffset, range.endOffset)
    }
}

/**
 * Alt+Enter: add an override of [key] to `overrides` in `pnpm-workspace.yaml` next to `package.json`, creating the file
 * or the section when needed. The value is a range from [version], like [NpmOverrideFix].
 */
class PnpmWorkspaceOverrideFix(private val name: String, private val key: String, private val version: String) : IntentionAction, PriorityAction {
    override fun getText() = "Override $key to ^$version in ${PnpmWorkspace.FILE_NAME}"
    override fun getFamilyName() = "Override transitive dependency"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val dir = file?.virtualFile?.parent ?: return false
        val yaml = dir.findChild(PnpmWorkspace.FILE_NAME) ?: return true
        return PnpmWorkspace.text(yaml)?.let { PnpmWorkspace.canAddOverride(it, name, key) } == true
    }
    override fun startInWriteAction() = false
    override fun getPriority() = PriorityAction.Priority.TOP

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val dir = file?.virtualFile?.parent ?: return
        // Another file than the one in the editor, so the platform does not make it writable.
        dir.findChild(PnpmWorkspace.FILE_NAME)?.let { if (!FileModificationService.getInstance().prepareVirtualFilesForWrite(project, listOf(it))) return }
        WriteCommandAction.writeCommandAction(project).withName(text).withGlobalUndo().run<RuntimeException> {
            val yaml = dir.findChild(PnpmWorkspace.FILE_NAME) ?: dir.createChildData(this, PnpmWorkspace.FILE_NAME)
            val document = FileDocumentManager.getInstance().getDocument(yaml) ?: return@run
            document.setText(PnpmWorkspace.withOverride(document.text, key, "^$version"))
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        // The preview runs on a copy of package.json, which has no folder of its own.
        val before = file.originalFile.virtualFile?.parent?.findChild(PnpmWorkspace.FILE_NAME)?.let(PnpmWorkspace::text) ?: ""
        return IntentionPreviewInfo.CustomDiff(PlainTextFileType.INSTANCE, PnpmWorkspace.FILE_NAME, before, PnpmWorkspace.withOverride(before, key, "^$version"))
    }
}
