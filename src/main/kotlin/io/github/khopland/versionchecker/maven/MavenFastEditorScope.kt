package io.github.khopland.versionchecker.maven

import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import io.github.khopland.versionchecker.core.BuildSnapshot
import org.jetbrains.idea.maven.dom.MavenDomProjectProcessorUtils
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.MavenPropertyResolver
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.project.MavenProjectsManager

/** An explicit editor-only scope; previews and manual refreshes always capture every declaration. */
internal object MavenFastEditorScope {
    fun select(manager: MavenProjectsManager, model: MavenDomProjectModel, snapshot: BuildSnapshot): BuildSnapshot {
        val tags = model.xmlTag?.let { PsiTreeUtil.findChildrenOfType(it, XmlTag::class.java) }.orEmpty()
        val management = tags.filter { isProjectDependency(it) && managed(it) }.associateBy { "DEPENDENCY:${it.textOffset}" }
        if (management.isEmpty()) return snapshot
        val used = hashSetOf<Pair<String, String>>()
        for (project in manager.nonIgnoredProjects) {
            val file = PsiManager.getInstance(manager.project).findFile(project.file) ?: return snapshot
            val source = MavenDomUtil.getMavenDomProjectModel(file) ?: return snapshot
            for (tag in PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)) {
                if (!isProjectDependency(tag) || managed(tag)) continue
                val key = key(tag, source) ?: return snapshot // Uncertain consumer detection retains full coverage.
                used += key
            }
        }
        // Preserve local overrides of a parent's managed artifact, even if unused in this reactor.
        val inherited = hashSetOf<Pair<String, String>>()
        for (parent in MavenDomProjectProcessorUtils.collectParentProjects(model)) {
            val parentTags = parent.xmlTag?.let { PsiTreeUtil.findChildrenOfType(it, XmlTag::class.java) }.orEmpty()
            for (tag in parentTags.filter { isProjectDependency(it) && managed(it) }) {
                inherited += key(tag, model) ?: return snapshot
            }
        }
        val selected = snapshot.declarations.filter { declaration ->
            val tag = management[declaration.id.location] ?: return@filter true
            val coordinate = declaration.coordinate()
            val key = coordinate.groupId to coordinate.artifactId
            key in used || key in inherited ||
                tag.findFirstSubTag("scope")?.value?.trimmedText == "import" ||
                tag.findFirstSubTag("version")?.value?.trimmedText?.let(::versionPropertyName) != null
        }
        if (selected.size == snapshot.declarations.size) return snapshot
        return snapshot.copy(declarations = selected,
            coverageDescription = "Fast Maven scope checks ${selected.size} of ${snapshot.declarations.size} supported declarations. Unused local management entries are omitted.")
    }

    private fun managed(tag: XmlTag) = generateSequence(tag.parentTag) { it.parentTag }.any { it.localName == "dependencyManagement" }
    private fun key(tag: XmlTag, model: MavenDomProjectModel): Pair<String, String>? {
        fun resolve(name: String): String? {
            val raw = tag.findFirstSubTag(name)?.value?.trimmedText ?: return null
            if ('$' !in raw) return raw.takeIf { it.isNotBlank() }
            return MavenPropertyResolver.resolve(raw, model).takeIf { it.isNotBlank() && '$' !in it }
        }
        return (resolve("groupId") ?: return null) to (resolve("artifactId") ?: return null)
    }
}
