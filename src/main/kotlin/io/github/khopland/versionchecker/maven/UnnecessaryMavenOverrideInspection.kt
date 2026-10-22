package io.github.khopland.versionchecker.maven

import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.util.PsiTreeUtil
import io.github.khopland.versionchecker.BulkUpdatePlan
import io.github.khopland.versionchecker.VersionCheckerSettings
import io.github.khopland.versionchecker.VersionEdit
import io.github.khopland.versionchecker.IgnoredVersion
import io.github.khopland.versionchecker.ignoredVersions
import io.github.khopland.versionchecker.projectPolicy
import io.github.khopland.versionchecker.withProjectPolicy
import io.github.khopland.versionchecker.core.ArtifactId
import io.github.khopland.versionchecker.core.prepareSavedBuildInputValidation
import org.jetbrains.idea.maven.dom.MavenDomProjectProcessorUtils
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.MavenPropertyResolver
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.dom.MavenVersionComparable
import org.jetbrains.idea.maven.model.MavenId

/** Compare declarations with their fallback, without requesting repository metadata. */
class UnnecessaryMavenOverrideInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val analysis = MavenOverrideAnalysis.forFile(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val overrides = analysis.overrides().associateBy { it.target }
        if (overrides.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR
        val inputs = MavenOverrideInputs(analysis.sources)
        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                val override = overrides[tag] ?: return
                holder.registerProblem(tag, override.message, RemoveMavenOverrideFix(override, inputs))
            }
        }
    }
}

internal data class MavenOverrideProblem(val target: XmlTag, val inherited: XmlTag, val value: String,
                                        val inheritedValue: String, val property: Boolean, val removal: XmlTag = target) {
    val newer get() = value != inheritedValue
    val message: String get() = if (newer) {
        val subject = if (property) "version for property '${target.localName}'" else "dependency version"
        "Parent provides newer $subject $inheritedValue (local override: $value)"
    } else if (property)
        "Property '${target.localName}' matches the parent value '$value'; this override is redundant"
    else "Dependency version '$value' is inherited from the parent; this override is redundant"
}

internal class MavenOverrideAnalysis private constructor(private val model: MavenDomProjectModel) {
    private val manager = MavenProjectsManager.getInstance(model.manager.project)
    val sources: List<XmlTag> = buildList {
        var current: MavenDomProjectModel? = model
        val seen = hashSetOf<VirtualFile>()
        while (current != null) {
            val root = current.xmlTag ?: break
            if (!seen.add(root.containingFile.virtualFile)) break
            add(root)
            val parent = current.mavenParent
            if (parent.xmlTag == null) break
            val expected = MavenId(parent.groupId.stringValue, parent.artifactId.stringValue, parent.version.stringValue)
            // Unlike collectParentProjects, this also finds locally cached external parent POMs.
            current = object : MavenDomProjectProcessorUtils.DomParentProjectFileProcessor<MavenDomProjectModel>(manager) {
                override fun processSuperParent(parentFile: VirtualFile): MavenDomProjectModel? = null
                override fun processRelativeParent(parentFile: VirtualFile): MavenDomProjectModel? {
                    if (parent.relativePath.xmlTag != null && parent.relativePath.stringValue.isNullOrBlank()) return null
                    return doProcessParent(parentFile)
                }
                override fun doProcessParent(parentFile: VirtualFile): MavenDomProjectModel? {
                    val candidate = MavenDomUtil.getMavenDomProjectModel(model.manager.project, parentFile) ?: return null
                    val file = candidate.xmlTag?.containingFile ?: return null
                    if (MavenDomUtil.describe(file) != expected || manager.findProject(parentFile)?.hasReadingErrors() == true) return null
                    return candidate
                }
            }.process(current)
        }
    }
    private val root get() = sources.first()
    private val profiles by lazy { sources.flatMap { it.findFirstSubTag("profiles")?.subTags.orEmpty().toList() } }
    private val profiledProperties by lazy {
        profiles.flatMap { it.findFirstSubTag("properties")?.subTags.orEmpty().toList() }.mapTo(hashSetOf()) { it.localName }
    }
    private val propertyConsumers by lazy {
        val analysis = MavenDependencyAnalysis(model, manager.findProject(root.containingFile.virtualFile)!!, emptyMap())
        sources.flatMap { PsiTreeUtil.findChildrenOfType(it, XmlTag::class.java) }.mapNotNull { tag ->
            if (!isProjectDependency(tag) && !isProjectPlugin(tag)) return@mapNotNull null
            if (generateSequence(tag.parentTag) { it.parentTag }.any { it.localName == "profile" }) return@mapNotNull null
            val version = tag.findFirstSubTag("version") ?: return@mapNotNull null
            val target = propertyTarget(version.value.trimmedText) ?: return@mapNotNull null
            val coordinate = analysis.coordinate(tag) ?: return@mapNotNull null
            MavenPropertyConsumer(tag, coordinate, target)
        }.groupBy { it.target }
    }
    private val propertyEligibility by lazy { MavenPropertyEligibility(sources.map { it.containingFile }) }
    private val policy by lazy { projectPolicy(model.manager.project) }
    private val ignored by lazy { ignoredVersions(policy.ignoredText) }
    private fun allowsUpgrade(group: String, artifact: String, current: String, inherited: String): Boolean {
        val id = ArtifactId(group, artifact)
        return IgnoredVersion("maven", id, inherited) !in ignored && "$group:$artifact" !in policy.deprecations("maven") &&
            policy.restriction("maven", id, inherited, MavenVersionSemantics.between(current, inherited)) == null
    }
    private val propertyIndexes by lazy {
        sources.associateWith { source -> source.findFirstSubTag("properties")?.subTags.orEmpty().groupBy { it.localName } }
    }
    private val configuredProperties by lazy {
        val directory = root.containingFile.virtualFile.parent
        val config = generateSequence(directory) { it.parent }.firstNotNullOfOrNull { it.findChild(".mvn") }
        listOf("jvm.config", "maven.config").flatMap { name ->
            config?.findChild(name)?.let { FileDocumentManager.getInstance().getDocument(it)?.text }
                ?.let { MavenConfigProperties.parse(it).keys }.orEmpty()
        }.toSet()
    }
    private data class DependencySection(val declarations: Map<Key?, List<XmlTag>>, val imports: Boolean)
    private val dependencySections = hashMapOf<Pair<XmlTag, Boolean>, DependencySection>()
    private fun section(source: XmlTag, managed: Boolean): DependencySection = dependencySections.getOrPut(source to managed) {
        val declarations = dependencies(source, managed)
        DependencySection(declarations.groupBy(::key), declarations.any { it.findFirstSubTag("scope")?.value?.trimmedText == "import" })
    }
    private val profiledDependencyKeys by lazy {
        profiles.flatMap { profile -> listOf(false, true).flatMap { section(profile, it).declarations.keys } }.toSet()
    }

    fun overrides(): List<MavenOverrideProblem> {
        if (sources.size < 2) return emptyList()
        val properties = propertyIndexes.getValue(root).values.mapNotNull { owners ->
            val property = owners.singleOrNull() ?: return@mapNotNull null
            val name = property.localName
            if (name in profiledProperties || reservedProperty(name)) return@mapNotNull null
            val inherited = sources.drop(1).firstNotNullOfOrNull { source ->
                propertyIndexes.getValue(source)[name]
            }?.singleOrNull() ?: return@mapNotNull null
            val value = literal(property) ?: return@mapNotNull null
            val inheritedValue = literal(inherited) ?: return@mapNotNull null
            // Equal expressions can use different project contexts. Only compare literal properties.
            if (inheritedValue != value) {
                if (name in configuredProperties || !newerVersion(value, inheritedValue) ||
                    MavenPropertyResolver.resolve("\${$name}", model) != value) return@mapNotNull null
                val consumers = propertyConsumers[property].orEmpty()
                if (consumers.isEmpty() || consumers.any { consumer -> consumer.coordinate.let {
                        !allowsUpgrade(it.groupId, it.artifactId, it.version, inheritedValue)
                    } } || propertyEligibility.blockers(property, consumers.mapNotNull { consumer ->
                        consumer.tag.findFirstSubTag("version")?.let { MavenPropertyEligibility.Consumer(it, root.containingFile) }
                    }).isNotEmpty()) return@mapNotNull null
            }
            MavenOverrideProblem(property, inherited, value, inheritedValue, property = true)
        }
        val dependencies = listOf(false, true).flatMap { managed ->
            section(root, managed).declarations.mapNotNull { (key, declarations) ->
                key ?: return@mapNotNull null
                val dependency = declarations.singleOrNull() ?: return@mapNotNull null
                if (null in profiledDependencyKeys || key in profiledDependencyKeys) return@mapNotNull null
                val version = dependency.findFirstSubTag("version") ?: return@mapNotNull null
                if (dependency.subTags.count { it.localName == "version" } != 1) return@mapNotNull null
                if (dependency.findFirstSubTag("scope")?.value?.trimmedText in setOf("import", "system")) return@mapNotNull null
                var removeDeclaration = managed
                val fallback = if (managed) inheritedVersion(key, sources.drop(1), true)?.tag
                else {
                    if (sources.drop(1).any { section(it, false).declarations[key].orEmpty().size > 1 }) return@mapNotNull null
                    val management = inheritedVersion(key, sources, true)
                    if (management != null) management.tag
                    else {
                        // A child dependency replaces the parent's entry. Restore that entire entry.
                        removeDeclaration = true
                        inheritedVersion(key, sources.drop(1), false)?.tag
                    }
                } ?: return@mapNotNull null
                if (fallback.containingFile == root.containingFile) return@mapNotNull null
                if (removeDeclaration && (!simpleDeclaration(dependency) || !simpleDeclaration(fallback.parentTag ?: return@mapNotNull null)))
                    return@mapNotNull null
                val value = versionValue(version) ?: return@mapNotNull null
                val inheritedValue = versionValue(fallback) ?: return@mapNotNull null
                if (value != inheritedValue && !newerVersion(value, inheritedValue)) return@mapNotNull null
                if (value != inheritedValue && !allowsUpgrade(key.group, key.artifact, value, inheritedValue)) return@mapNotNull null
                MavenOverrideProblem(version, fallback, value, inheritedValue, property = false,
                    removal = if (removeDeclaration) dependency else version)
            }
        }
        return properties + dependencies
    }

    private data class Key(val group: String, val artifact: String, val type: String, val classifier: String)

    private fun dependencies(source: XmlTag, managed: Boolean): List<XmlTag> =
        (if (managed) source.findFirstSubTag("dependencyManagement") else source)
            ?.findFirstSubTag("dependencies")?.subTags.orEmpty().filter { it.localName == "dependency" }

    private fun key(dependency: XmlTag): Key? {
        fun field(name: String, default: String? = null): String? {
            val tags = dependency.subTags.filter { it.localName == name }
            return if (tags.isEmpty()) default else tags.singleOrNull()?.let(::literal)
        }
        return Key(field("groupId") ?: return null, field("artifactId") ?: return null,
            field("type", "jar") ?: return null, field("classifier", "") ?: return null)
    }

    private fun simpleDeclaration(dependency: XmlTag) = dependency.attributes.isEmpty() && dependency.subTags.all {
        it.localName in setOf("groupId", "artifactId", "version", "type", "classifier")
    }

    // A blocked lookup must not fall back to a lower-priority source.
    private data class VersionSource(val tag: XmlTag?)

    private fun inheritedVersion(key: Key, sources: List<XmlTag>, managed: Boolean): VersionSource? {
        for (source in sources) {
            val section = section(source, managed)
            // An unresolved coordinate can shadow this declaration. Do not guess its identity.
            if (null in section.declarations) return VersionSource(null)
            val matches = section.declarations[key].orEmpty()
            if (matches.size > 1) return VersionSource(null)
            matches.singleOrNull()?.let { dependency ->
                val versions = dependency.subTags.filter { it.localName == "version" }
                if (versions.isNotEmpty()) return VersionSource(versions.singleOrNull())
                return VersionSource(null) // A local declaration replaces the inherited entry.
            }
            // Imported BOMs have a separate interpolation context. Leave them for manual review.
            if (managed && section.imports) return VersionSource(null)
        }
        return null
    }

    private fun versionValue(version: XmlTag): String? {
        if (version.subTags.isNotEmpty() || version.attributes.isNotEmpty()) return null
        val raw = version.value.trimmedText
        if ('$' in raw && propertyTarget(raw) == null) return null
        return MavenPropertyResolver.resolve(raw, model).takeIf(DependencyUpdateReport::isFixedVersion)
    }

    private fun propertyTarget(raw: String): XmlTag? {
        var reference = raw
        var target: XmlTag? = null
        val seen = hashSetOf<String>()
        while ('$' in reference) {
            val name = versionPropertyName(reference) ?: return null
            if (!seen.add(name) || name in profiledProperties || name in configuredProperties || reservedProperty(name)) return null
            target = sources.firstNotNullOfOrNull { propertyIndexes.getValue(it)[name] }?.singleOrNull() ?: return null
            if (target.subTags.isNotEmpty() || target.attributes.isNotEmpty()) return null
            reference = target.value.trimmedText
        }
        return target.takeIf { reference.isNotEmpty() }
    }

    private fun literal(tag: XmlTag): String? = tag.value.trimmedText.takeIf {
        it.isNotEmpty() && '$' !in it && tag.subTags.isEmpty() && tag.attributes.isEmpty()
    }

    private fun reservedProperty(name: String) = name in setOf("basedir", "baseUri") ||
        listOf("project.", "pom.", "settings.", "env.").any(name::startsWith)

    private fun newerVersion(current: String, inherited: String) =
        DependencyUpdateReport.isFixedVersion(current) && DependencyUpdateReport.isFixedVersion(inherited) &&
            MavenRepositoryMetadata.isStable(inherited) &&
            MavenVersionComparable(inherited) > MavenVersionComparable(current)

    companion object {
        fun forFile(file: PsiFile): MavenOverrideAnalysis? {
            if (!file.project.service<VersionCheckerSettings>().state.enabled) return null
            val manager = MavenProjectsManager.getInstance(file.project)
            val imported = manager.findProject(file.virtualFile ?: return null) ?: return null
            if (manager.isIgnored(imported) || imported.hasReadingErrors()) return null
            val model = MavenDomUtil.getMavenDomProjectModel(file) ?: return null
            if (model.xmlTag == null) return null
            return MavenOverrideAnalysis(model)
        }
    }
}

/** Share parent text across fixes from one inspection pass. */
internal class MavenOverrideInputs(sources: List<XmlTag>) {
    private val sources = sources.map { SmartPointerManager.createPointer(it) to it.text }
    val paths = sources.map { it.containingFile.virtualFile.path }.toSet()
    fun isCurrent() = sources.all { (pointer, text) -> pointer.element?.text == text }
}

internal class RemoveMavenOverrideFix(override: MavenOverrideProblem, private val inputs: MavenOverrideInputs) : LocalQuickFix {
    private val pointer = SmartPointerManager.createPointer(override.removal)
    private val expected = override.removal.text
    private val value = override.value
    private val inheritedValue = override.inheritedValue
    private val property = override.property
    private val declaration = override.removal != override.target
    private val managed = declaration && override.removal.parentTag?.parentTag?.localName == "dependencyManagement"
    private val policy = projectPolicy(override.target.project)
    override fun getFamilyName() = "Remove unnecessary Maven override"
    override fun getName(): String {
        val subject = if (property) "property" else if (managed) "dependency management"
            else if (declaration) "dependency" else "dependency version"
        return if (value == inheritedValue) "Remove redundant $subject override"
        else "Remove $subject override and use parent version $inheritedValue (was $value)"
    }
    override fun startInWriteAction() = false
    override fun getElementToMakeWritable(currentFile: PsiFile) = pointer.element

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
        val target = pointer.element ?: return IntentionPreviewInfo.EMPTY
        val parent = target.parentTag ?: return IntentionPreviewInfo.EMPTY
        val index = parent.subTags.indexOf(target)
        if (index < 0) return IntentionPreviewInfo.EMPTY
        val copy = parent.copy() as XmlTag
        copy.subTags[index].delete()
        return IntentionPreviewInfo.CustomDiff(XmlFileType.INSTANCE, parent.containingFile.name, parent.text, copy.text)
    }

    private fun current(): Boolean {
        val target = pointer.element ?: return false
        if (target.text != expected || !inputs.isCurrent()) return false
        return MavenOverrideAnalysis.forFile(target.containingFile)?.overrides()?.any {
            it.removal == target && it.property == property && it.value == value && it.inheritedValue == inheritedValue
        } == true
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val target = pointer.element ?: return
        if (!current() || !FileModificationService.getInstance().preparePsiElementsForWrite(listOf(target))) return
        val edit = object : VersionEdit {
            override val element get() = pointer.element
            override val expected = this@RemoveMavenOverrideFix.expected
            override val latest = inheritedValue
            override val location = target.containingFile.name
            override val buildSystemId = "maven"
            override fun isValid() = element?.text == expected
            override fun apply() { element!!.delete() }
        }
        BulkUpdatePlan(listOf(edit), emptyList(), ::current,
            prepareValidation = { prepareSavedBuildInputValidation(project, inputs.paths) }).withProjectPolicy(project, policy).apply(project)
    }
}
