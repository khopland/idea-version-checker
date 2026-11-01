package io.github.khopland.versionchecker.maven

import com.intellij.codeInspection.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import io.github.khopland.versionchecker.VersionCheckerSettings
import io.github.khopland.versionchecker.core.Versions
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager

/** Finds Maven declarations that repeat what the parent or dependency management already gives. No network access. */
class RedundantOverrideInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor> =
        RedundantOverrides.find(file).map { problem ->
            manager.createProblemDescriptor(problem.tag, problem.message, isOnTheFly, arrayOf(RemoveOverrideFix(problem.fixName, problem.removeParent)),
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        }.toTypedArray()
}

internal data class Redundancy(val tag: XmlTag, val message: String, val fixName: String, val removeParent: Boolean = false)

/** Deletes the highlighted tag, or its enclosing `<dependency>`. */
internal class RemoveOverrideFix(private val name: String, private val removeParent: Boolean) : LocalQuickFix {
    override fun getName() = name
    override fun getFamilyName() = "Remove redundant Maven override"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val tag = descriptor.psiElement as? XmlTag ?: return
        (if (removeParent) tag.parentTag else tag)?.delete()
    }
}

internal object RedundantOverrides {
    private val versionProperty = Regex("""(?i).*[.\-_]?version$""")

    fun find(file: PsiFile): List<Redundancy> {
        if (!VersionCheckerSettings.getInstance().enabled) return emptyList()
        val xml = file as? XmlFile ?: return emptyList()
        if (xml.name != "pom.xml") return emptyList()
        val root = MavenPoms.root(xml) ?: return emptyList()
        val manager = MavenProjectsManager.getInstance(file.project)
        val imported = manager.findProject(file.virtualFile ?: return emptyList()) ?: return emptyList()
        if (imported.hasReadingErrors()) return emptyList()
        val chain = MavenPoms.chain(file.project, xml)
        // One warning per tag: a property equal to the parent's is reported as that, even when it is also unused.
        return (dependencyVersions(root, imported) + managedDuplicates(root, imported, manager) + properties(root, chain) +
            unusedProperties(root, chain, manager)).distinctBy { it.tag }
    }

    private fun coordinate(dependency: XmlTag): Pair<String, String>? {
        val group = MavenPoms.text(MavenPoms.child(dependency, "groupId")) ?: return null
        val artifact = MavenPoms.text(MavenPoms.child(dependency, "artifactId")) ?: return null
        if ('$' in group || '$' in artifact) return null
        val type = MavenPoms.text(MavenPoms.child(dependency, "type"))
        if (MavenPoms.child(dependency, "classifier") != null || (type != null && type != "jar")) return null
        return group to artifact
    }

    private fun resolved(raw: String, imported: MavenProject): String? {
        val value = MavenPoms.propertyName(raw)?.let { imported.properties.getProperty(it) } ?: raw
        return value.takeIf { '$' !in it }
    }

    /** A `<version>` in `<dependencies>` that equals, or is older than, the managed version. */
    private fun dependencyVersions(root: XmlTag, imported: MavenProject) = MavenPoms.children(root, "dependencies", "dependency").mapNotNull { dependency ->
        val (group, artifact) = coordinate(dependency) ?: return@mapNotNull null
        val version = MavenPoms.child(dependency, "version") ?: return@mapNotNull null
        val value = MavenPoms.text(version)?.let { resolved(it, imported) } ?: return@mapNotNull null
        val managed = imported.findManagedDependencyVersion(group, artifact) ?: return@mapNotNull null
        when {
            managed == value -> Redundancy(version, "Version $value is already managed", "Remove redundant version")
            newer(managed, value) -> Redundancy(version, "Dependency management gives a newer version $managed (local override: $value)",
                "Remove version override and use $managed")
            else -> null
        }
    }

    /** A `<dependencyManagement>` entry that repeats the version a parent module already manages. */
    private fun managedDuplicates(root: XmlTag, imported: MavenProject, manager: MavenProjectsManager): List<Redundancy> {
        val parent = imported.parentId?.let(manager::findProject) ?: return emptyList()
        return MavenPoms.children(root, "dependencyManagement", "dependencies", "dependency").mapNotNull { dependency ->
            val (group, artifact) = coordinate(dependency) ?: return@mapNotNull null
            if (dependency.subTags.any { it.localName !in setOf("groupId", "artifactId", "version") }) return@mapNotNull null
            val version = MavenPoms.child(dependency, "version") ?: return@mapNotNull null
            val value = MavenPoms.text(version)?.let { resolved(it, imported) } ?: return@mapNotNull null
            if (parent.findManagedDependencyVersion(group, artifact) != value) return@mapNotNull null
            Redundancy(version, "The parent already manages $group:$artifact at $value", "Remove redundant dependency management entry", removeParent = true)
        }
    }

    /** A property that a parent sets to the same, or a newer, value. */
    private fun properties(root: XmlTag, chain: PomChain): List<Redundancy> {
        if (chain.ancestors.isEmpty()) return emptyList()
        val parents = PomChain(chain.ancestors.first(), chain.ancestors.drop(1), chain.complete)
        return MavenPoms.child(root, "properties")?.subTags.orEmpty().mapNotNull { property ->
            val name = property.localName
            val value = MavenPoms.text(property)?.takeIf { '$' !in it } ?: return@mapNotNull null
            if (MavenPoms.definedInProfile(chain, name)) return@mapNotNull null
            val inherited = MavenPoms.property(parents, name)?.value ?: return@mapNotNull null
            when {
                inherited == value -> Redundancy(property, "Property $name has the same value as in the parent", "Remove redundant property")
                Versions.isStable(value) && newer(inherited, value) -> Redundancy(property,
                    "The parent sets a newer $name $inherited (local override: $value)", "Remove property override and use $inherited")
                else -> null
            }
        }
    }

    /** A version property that no POM in the project or the parent chain refers to. */
    private fun unusedProperties(root: XmlTag, chain: PomChain, manager: MavenProjectsManager): List<Redundancy> {
        if (!chain.complete) return emptyList()
        val properties = MavenPoms.child(root, "properties")?.subTags.orEmpty().filter { versionProperty.matches(it.localName) }
        if (properties.isEmpty()) return emptyList()
        val psi = com.intellij.psi.PsiManager.getInstance(root.project)
        val texts = (chain.all.map { it.text } + manager.projects.mapNotNull { psi.findFile(it.file)?.text }).distinct()
        return properties.filter { property -> texts.none { "\${${property.localName}}" in it } }
            .map { Redundancy(it, "Property ${it.localName} is not used", "Remove unused property") }
    }

    private fun newer(candidate: String, current: String) =
        Versions.isStable(candidate) && Versions.flavor(candidate) == Versions.flavor(current) && Versions.compare(candidate, current) > 0
}
