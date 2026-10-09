package io.github.khopland.versionchecker.maven

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.dom.MavenDomProjectProcessorUtils
import org.jetbrains.idea.maven.dom.MavenPropertyResolver
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel

internal data class MavenPropertyConsumer(val tag: XmlTag, val coordinate: DependencyVersion, val target: XmlTag)

/** Resolve parent declarations in the child's property context, without guessing from version values. */
internal class MavenVersionProperties(
    private val model: MavenDomProjectModel,
    private val activeProfiles: Collection<String>,
    private val coordinate: (XmlTag) -> DependencyVersion?,
) {
    private val root = model.xmlTag
    private val localNames by lazy {
        val context = root ?: return@lazy emptySet<String>()
        val properties = listOfNotNull(context.findFirstSubTag("properties")) +
            context.findFirstSubTag("profiles")?.subTags.orEmpty().filter {
                it.findFirstSubTag("id")?.value?.trimmedText in activeProfiles
            }.mapNotNull { it.findFirstSubTag("properties") }
        properties.flatMap { it.subTags.map(XmlTag::getLocalName) }.toSet()
    }

    fun target(version: XmlTag, current: String): XmlTag? {
        val context = root ?: return null
        var raw = version.value.trimmedText
        val visited = hashSetOf<String>()
        while (true) {
            val name = versionPropertyName(raw) ?: return null
            if (!visited.add(name)) return null
            val owner = if (name in localNames) findLocalVersionPropertyOwner(context, name, activeProfiles)
                else MavenDomProjectProcessorUtils.searchProperty(name, model, context.project)
            owner ?: return null
            raw = owner.value.trimmedText
            if (raw == current) return owner.takeIf { it.containingFile == context.containingFile }
        }
    }

    fun consumers(): List<MavenPropertyConsumer> {
        if (root == null || localNames.isEmpty()) return emptyList()
        val models = mutableListOf(model)
        MavenDomProjectProcessorUtils.processParentProjects(model) { parent ->
            models += parent
            false // This API stops walking when the processor returns true.
        }
        val seen = hashSetOf<List<String>>()
        return buildList {
            for ((level, source) in models.withIndex()) {
                val tags = source.xmlTag?.let { PsiTreeUtil.findChildrenOfType(it, XmlTag::class.java) }.orEmpty()
                    .filter { (isProjectDependency(it) || isProjectPlugin(it)) && active(it) }
                    .sortedByDescending { ancestors(it).any { ancestor -> ancestor.localName == "profile" } }
                for (tag in tags) {
                    val key = key(tag)
                    if (key in seen) continue
                    if (level > 0 && (tag.findFirstSubTag("inherited")?.value?.trimmedText == "false" ||
                            ancestors(tag).any { it.localName == "build" && it.findFirstSubTag("inherited")?.value?.trimmedText == "false" })) {
                        seen += key
                        continue
                    }
                    val version = tag.findFirstSubTag("version") ?: continue
                    seen += key
                    val resolved = coordinate(tag) ?: continue
                    val target = target(version, resolved.version) ?: continue
                    add(MavenPropertyConsumer(tag, resolved, target))
                }
            }
        }
    }

    private fun ancestors(tag: XmlTag) = generateSequence(tag.parentTag) { it.parentTag }
    private fun active(tag: XmlTag) = ancestors(tag).all {
        it.localName != "profile" || it.findFirstSubTag("id")?.value?.trimmedText in activeProfiles
    }

    private fun key(tag: XmlTag): List<String> {
        fun resolved(name: String, default: String = "") = MavenPropertyResolver.resolve(
            tag.findFirstSubTag(name)?.value?.trimmedText ?: default, model)
        val plugin = isProjectPlugin(tag)
        return listOf(
            if (plugin) "plugin" else "dependency",
            if (ancestors(tag).any { it.localName in setOf("dependencyManagement", "pluginManagement") }) "managed" else "direct",
            resolved("groupId", if (plugin) "org.apache.maven.plugins" else ""), resolved("artifactId"),
            if (plugin) "" else resolved("type", "jar"), if (plugin) "" else resolved("classifier"),
        )
    }
}
