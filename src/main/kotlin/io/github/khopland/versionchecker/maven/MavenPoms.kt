package io.github.khopland.versionchecker.maven

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenSettingsCache

/** A POM and its parents: from the project when they are modules, otherwise from the local repository. */
internal class PomChain(val self: XmlFile, val ancestors: List<XmlFile>, val complete: Boolean) {
    val all get() = listOf(self) + ancestors
}

internal data class PropertyValue(val file: XmlFile, val tag: XmlTag, val name: String, val value: String)

internal object MavenPoms {
    private val propertyReference = Regex("""^\$\{([^}]+)}$""")

    fun child(tag: XmlTag?, vararg path: String): XmlTag? = path.fold(tag) { current, name -> current?.findFirstSubTag(name) }

    fun children(tag: XmlTag?, vararg path: String): List<XmlTag> {
        val parent = child(tag, *path.dropLast(1).toTypedArray()) ?: return emptyList()
        return parent.findSubTags(path.last()).toList()
    }

    fun text(tag: XmlTag?): String? = tag?.takeIf { it.subTags.isEmpty() }?.value?.trimmedText?.takeIf { it.isNotEmpty() }

    /** The file range of a tag's value without surrounding whitespace. */
    fun valueRange(tag: XmlTag): TextRange {
        val range = tag.value.textRange
        val text = tag.containingFile.text
        var start = range.startOffset
        var end = range.endOffset
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return TextRange(start, end)
    }

    fun propertyName(raw: String): String? = propertyReference.matchEntire(raw)?.groupValues?.get(1)

    fun root(file: XmlFile): XmlTag? = file.rootTag?.takeIf { it.localName == "project" }

    fun profiles(root: XmlTag): List<XmlTag> = children(root, "profiles", "profile")

    fun chain(project: Project, file: XmlFile): PomChain {
        val manager = MavenProjectsManager.getInstance(project)
        val ancestors = mutableListOf<XmlFile>()
        val seen = hashSetOf(file.virtualFile?.path)
        var current = file
        while (ancestors.size < MAX_DEPTH) {
            val parent = child(root(current), "parent") ?: return PomChain(file, ancestors, true)
            val id = MavenId(text(child(parent, "groupId")), text(child(parent, "artifactId")), text(child(parent, "version")))
            if (id.groupId == null || id.artifactId == null || id.version == null || '$' in id.toString()) return PomChain(file, ancestors, false)
            val next = manager.findProject(id)?.file ?: localPom(project, manager, id)
            val psi = next?.let { PsiManager.getInstance(project).findFile(it) } as? XmlFile
            if (psi == null || !seen.add(next.path)) return PomChain(file, ancestors, false)
            ancestors += psi
            current = psi
        }
        return PomChain(file, ancestors, false)
    }

    private fun localPom(project: Project, manager: MavenProjectsManager, id: MavenId) = run {
        val repository = manager.projects.firstOrNull()?.localRepositoryPath
            ?: runCatching { MavenSettingsCache.getInstance(project).getEffectiveUserLocalRepo() }.getOrNull() ?: return@run null
        val path = repository.resolve(id.groupId!!.replace('.', '/')).resolve(id.artifactId!!).resolve(id.version!!)
            .resolve("${id.artifactId}-${id.version}.pom")
        LocalFileSystem.getInstance().findFileByNioFile(path)
    }

    /** The first literal definition of [name] in the chain, following `${other}` references. */
    fun property(chain: PomChain, name: String): PropertyValue? {
        var current = name
        repeat(MAX_DEPTH) {
            val (file, tag) = chain.all.firstNotNullOfOrNull { pom ->
                root(pom)?.let { child(it, "properties", current) }?.let { pom to it }
            } ?: return null
            val value = text(tag) ?: return null
            current = propertyName(value) ?: return if ('$' in value) null else PropertyValue(file, tag, current, value)
        }
        return null
    }

    fun definedInProfile(chain: PomChain, name: String) = chain.all.any { pom ->
        root(pom)?.let { root -> profiles(root).any { child(it, "properties", name) != null } } == true
    }

    private const val MAX_DEPTH = 10
}
