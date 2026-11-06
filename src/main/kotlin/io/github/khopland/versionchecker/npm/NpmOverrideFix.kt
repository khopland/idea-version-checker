package io.github.khopland.versionchecker.npm

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonPsiUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * Alt+Enter: add the package at [version] to the overrides at [keys], such as `overrides` for npm or `pnpm`, `overrides`.
 * The next install replaces it everywhere in the tree.
 */
class NpmOverrideFix(private val keys: List<String>, private val name: String, private val version: String) : IntentionAction, PriorityAction {
    override fun getText() = "Override $name to $version in package.json"
    override fun getFamilyName() = "Override transitive dependency"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = (file as? JsonFile)?.topLevelValue is JsonObject
    override fun startInWriteAction() = true
    override fun getPriority() = PriorityAction.Priority.TOP

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return
        val generator = JsonElementGenerator(project)
        // The outermost object the fix creates is formatted, or else the new entry.
        var created: PsiElement? = null
        val overrides = keys.fold(root) { owner, key ->
            owner.findProperty(key)?.value as? JsonObject
                ?: (JsonPsiUtil.addProperty(owner, generator.createProperty(key, "{}"), false) as JsonProperty).also { created = created ?: it }.value as JsonObject
        }
        val added = JsonPsiUtil.addProperty(overrides, generator.createProperty(name, "\"$version\""), false)
        // The JSON formatter builds its blocks from the file, so it formats a range rather than the new element.
        val range = (created ?: added).textRange
        CodeStyleManager.getInstance(project).reformatRange(file, range.startOffset, range.endOffset)
    }
}
