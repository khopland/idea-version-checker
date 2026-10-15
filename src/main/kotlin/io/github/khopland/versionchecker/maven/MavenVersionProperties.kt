package io.github.khopland.versionchecker.maven

import com.intellij.openapi.fileEditor.FileDocumentManager
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
    private val managingVersion: (XmlTag) -> XmlTag?,
) {
    private val root = model.xmlTag
    private val parentModels by lazy { MavenDomProjectProcessorUtils.collectParentProjects(model) }
    private val sourceFiles by lazy { (parentModels + model).mapNotNull { it.xmlTag?.containingFile }.toSet() }
    private val configuredProperties by lazy {
        // Inspection capture may run on EDT: use IDE state, including unsaved configuration edits.
        // Physical bytes are revalidated later by the background edit-input capture.
        val directory = root?.containingFile?.virtualFile?.parent ?: return@lazy emptyMap<String, String>()
        val config = generateSequence(directory) { it.parent }.mapNotNull { it.findChild(".mvn")?.takeIf { child -> child.isDirectory } }.firstOrNull()
            ?: return@lazy emptyMap<String, String>()
        buildMap {
            for (name in listOf("jvm.config", "maven.config")) {
                val file = config.findChild(name)?.takeUnless { it.isDirectory } ?: continue
                FileDocumentManager.getInstance().getDocument(file)?.text?.let { putAll(MavenConfigProperties.parse(it)) }
            }
        }
    }
    private val propertyNamePattern = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
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

    /** An inherited literal owner is safe to shadow only when reached through exact references. */
    fun inheritedTarget(version: XmlTag, current: String): XmlTag? {
        val context = root ?: return null
        if (version.containingFile !in sourceFiles) return null // An imported BOM has its own interpolation context.
        var raw = version.value.trimmedText
        val visited = hashSetOf<String>()
        while (true) {
            val name = versionPropertyName(raw) ?: return null
            if (!visited.add(name) || name in localNames || name in configuredProperties || !propertyNamePattern.matches(name) ||
                name.startsWith("project.") || name.startsWith("pom.") || name.startsWith("settings.") || name.startsWith("env.")) return null
            val owner = MavenDomProjectProcessorUtils.searchProperty(name, model, context.project) ?: return null
            if (owner.containingFile !in sourceFiles || owner.containingFile == context.containingFile ||
                owner.parentTag?.localName != "properties" ||
                findLocalVersionPropertyOwner(owner, name) != owner) return null
            val ownerRoot = generateSequence(owner) { it.parentTag }.last()
            if (ownerRoot.findFirstSubTag("profiles")?.subTags.orEmpty().any { profile ->
                    profile.findFirstSubTag("properties")?.findFirstSubTag(name) != null
                }) return null
            raw = owner.value.trimmedText
            if (raw == current) return owner.takeIf {
                MavenPropertyResolver.resolve("\${$name}", model) == current
            }
        }
    }

    fun consumers(inherited: Boolean = false): List<MavenPropertyConsumer> {
        if (root == null || (!inherited && localNames.isEmpty())) return emptyList()
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
                    val version = tag.findFirstSubTag("version") ?: (if (inherited) managingVersion(tag) else null) ?: continue
                    seen += key
                    val resolved = coordinate(tag) ?: continue
                    val target = (if (inherited) inheritedTarget(version, resolved.version) else target(version, resolved.version)) ?: continue
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
