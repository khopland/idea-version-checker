package io.github.khopland.versionchecker.maven

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import io.github.khopland.versionchecker.core.Declaration
import io.github.khopland.versionchecker.core.DeclarationProvider
import io.github.khopland.versionchecker.core.Edit
import io.github.khopland.versionchecker.repo.MavenLookup
import io.github.khopland.versionchecker.repo.MavenRepo
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.project.MavenProjectsManager

/** Dependencies, dependency management, BOMs, plugins, extensions and the parent in `pom.xml`. */
class MavenProvider : DeclarationProvider {
    override val fileNames = listOf("pom.xml")

    override fun accepts(file: PsiFile) = file is XmlFile && file.name == "pom.xml"

    override fun collect(file: PsiFile): List<Declaration> {
        val xml = file as? XmlFile ?: return emptyList()
        val root = MavenPoms.root(xml) ?: return emptyList()
        val project = file.project
        val pom = file.virtualFile ?: return emptyList()
        if (MavenRepos.offline(project)) return emptyList()
        val manager = MavenProjectsManager.getInstance(project)
        val modules = manager.projects.mapTo(hashSetOf()) { "${it.mavenId.groupId}:${it.mavenId.artifactId}" }
        val dependencyRepos by lazy { MavenRepos.forPom(project, pom, plugins = false) }
        val pluginRepos by lazy { MavenRepos.forPom(project, pom, plugins = true) }
        val chain by lazy { MavenPoms.chain(project, xml) }
        val index = ProjectFileIndex.getInstance(project)
        val result = mutableListOf<Declaration>()

        fun add(owner: XmlTag, repos: () -> List<MavenRepo>, defaultGroup: String? = null) {
            val group = MavenPoms.text(MavenPoms.child(owner, "groupId")) ?: defaultGroup ?: return
            val artifact = MavenPoms.text(MavenPoms.child(owner, "artifactId")) ?: return
            if ('$' in group || '$' in artifact || "$group:$artifact" in modules) return
            val versionTag = MavenPoms.child(owner, "version") ?: return
            val raw = MavenPoms.text(versionTag) ?: return
            val range = MavenPoms.valueRange(versionTag)
            val lookup = MavenLookup(group, artifact, repos())
            val name = "$group:$artifact"
            val property = MavenPoms.propertyName(raw)
            if (property != null) {
                val value = MavenPoms.property(chain, property) ?: return
                val target = value.file.virtualFile
                val note = when {
                    target == null || !index.isInContent(target) -> "The version comes from ${value.name} in a parent POM outside the project"
                    MavenPoms.definedInProfile(chain, value.name) -> "A profile also sets ${value.name}"
                    else -> null
                }
                val edit = if (note == null) Edit(target!!, MavenPoms.valueRange(value.tag), value.value, value.name) else null
                result += Declaration(name, range, value.value, lookup, edit, note)
            } else if ('$' !in raw && raw.first() !in "[(" && raw !in setOf("LATEST", "RELEASE")) {
                result += Declaration(name, range, raw, lookup, Edit(pom, range, raw))
            }
        }

        for (section in listOf(root) + MavenPoms.profiles(root)) {
            MavenPoms.children(section, "dependencies", "dependency").forEach { add(it, { dependencyRepos }) }
            MavenPoms.children(section, "dependencyManagement", "dependencies", "dependency").forEach { add(it, { dependencyRepos }) }
            for (plugins in listOf(arrayOf("build", "plugins", "plugin"), arrayOf("build", "pluginManagement", "plugins", "plugin"))) {
                MavenPoms.children(section, *plugins).forEach { add(it, { pluginRepos }, "org.apache.maven.plugins") }
            }
            MavenPoms.children(section, "build", "extensions", "extension").forEach { add(it, { pluginRepos }) }
        }
        MavenPoms.child(root, "parent")?.let { parent ->
            val id = MavenId(MavenPoms.text(MavenPoms.child(parent, "groupId")), MavenPoms.text(MavenPoms.child(parent, "artifactId")),
                MavenPoms.text(MavenPoms.child(parent, "version")))
            if (manager.findProject(id) == null) add(parent, { dependencyRepos })
        }
        return result
    }
}
