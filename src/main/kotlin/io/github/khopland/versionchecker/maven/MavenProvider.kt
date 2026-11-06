package io.github.khopland.versionchecker.maven

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import io.github.khopland.versionchecker.core.Declaration
import io.github.khopland.versionchecker.core.DeclarationProvider
import io.github.khopland.versionchecker.core.Ecosystem
import io.github.khopland.versionchecker.core.Edit
import io.github.khopland.versionchecker.core.PinFix
import io.github.khopland.versionchecker.core.Pkg
import io.github.khopland.versionchecker.core.Transitive
import io.github.khopland.versionchecker.repo.MavenLookup
import io.github.khopland.versionchecker.repo.MavenRepo
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.model.MavenArtifactState
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.project.MavenProjectsManager

/** Dependencies, dependency management, BOMs, plugins, extensions and the parent in `pom.xml`. */
class MavenProvider : DeclarationProvider {
    override val fileNames = listOf("pom.xml")

    override fun accepts(file: PsiFile) = file is XmlFile && file.name == "pom.xml"

    /** Other project POMs that use a property defined in another file, so a fix agrees with every module. */
    override fun related(file: PsiFile, declarations: List<Declaration>): List<Declaration> {
        val self = file.virtualFile ?: return emptyList()
        val shared = declarations.mapNotNull { it.edit }.filter { it.file != self }.mapTo(hashSetOf()) { it.key }
        if (shared.isEmpty()) return emptyList()
        val psi = PsiManager.getInstance(file.project)
        return MavenProjectsManager.getInstance(file.project).projects.asSequence()
            .map { it.file }.filter { it != self && it.isValid }
            .mapNotNull { psi.findFile(it) as? XmlFile }
            .flatMap { collect(it) }
            .filter { it.edit?.key in shared }
            .toList()
    }

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
                result += Declaration(name, range, value.value, lookup, edit, note, Ecosystem.MAVEN)
            } else if ('$' !in raw && raw.first() !in "[(" && raw !in setOf("LATEST", "RELEASE")) {
                result += Declaration(name, range, raw, lookup, Edit(pom, range, raw), ecosystem = Ecosystem.MAVEN)
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

    /**
     * The imported dependency tree, below each dependency this POM declares. Maven lists a package that two
     * dependencies share only once, and marks the other place as a duplicate, so duplicates are expanded from
     * where Maven added them, even where an exclusion on this path would leave some out. Losing versions of a conflict
     * are not used, so they are left out. The vulnerable dependency inspection checks the packages this POM declares
     * or manages, so they are not repeated, and the walk stops at a declared one, whose own dependencies show under it.
     * Modules of this project are not sent anywhere, but what they bring in is.
     */
    override fun transitive(file: PsiFile): List<Transitive> {
        val xml = file as? XmlFile ?: return emptyList()
        val root = MavenPoms.root(xml) ?: return emptyList()
        val pom = file.virtualFile ?: return emptyList()
        if (MavenRepos.offline(file.project)) return emptyList()
        val manager = MavenProjectsManager.getInstance(file.project)
        val project = manager.findProject(pom) ?: return emptyList()
        val tree = project.dependencyTree
        val modules = manager.projects.mapTo(hashSetOf()) { "${it.mavenId.groupId}:${it.mavenId.artifactId}" }
        val sections = listOf(root) + MavenPoms.profiles(root)
        fun key(tag: XmlTag) = MavenPoms.coordinates(tag, project)
        val declared = sections.flatMap { MavenPoms.children(it, "dependencies", "dependency") }
            .mapNotNull { tag -> key(tag)?.let { it to MavenPoms.valueRange(MavenPoms.child(tag, "artifactId")!!) } }
            .distinctBy { it.first }.toMap()
        val managed = sections.flatMap { MavenPoms.children(it, "dependencyManagement", "dependencies", "dependency") }.mapNotNullTo(hashSetOf(), ::key)
        val added = HashMap<String, MavenArtifactNode>()
        fun index(nodes: List<MavenArtifactNode>): Unit = nodes.forEach { node ->
            if (node.state == MavenArtifactState.ADDED && added.putIfAbsent(node.artifact.key, node) == null) index(node.dependencies)
        }
        index(tree)
        val repos by lazy { MavenRepos.forPom(file.project, pom, plugins = false) }
        return tree.flatMap { top ->
            val range = declared[top.artifact.key] ?: return@flatMap emptyList()
            // Breadth first, so each package gets its shortest path.
            val seen = hashSetOf(top.artifact.key)
            val queue = ArrayDeque(listOf(top to emptyList<String>()))
            buildList {
                while (queue.isNotEmpty()) {
                    ProgressManager.checkCanceled()
                    val (node, path) = queue.removeFirst()
                    val children = if (node.state == MavenArtifactState.DUPLICATE) added[node.artifact.key]?.dependencies.orEmpty() else node.dependencies
                    for (child in children) {
                        val artifact = child.artifact
                        if (child.state != MavenArtifactState.ADDED && child.state != MavenArtifactState.DUPLICATE || !seen.add(artifact.key)) continue
                        if (artifact.key in declared) continue
                        if (artifact.key !in managed && artifact.key !in modules) {
                            // Dependency management matches the type and classifier too, so only a plain jar can be pinned.
                            val pinnable = artifact.classifier.isNullOrEmpty() && (artifact.type == null || artifact.type == "jar")
                            add(Transitive(top.artifact.key, range, Pkg(Ecosystem.MAVEN, artifact.key, artifact.baseVersion), path,
                                if (pinnable) MavenLookup(artifact.groupId, artifact.artifactId, repos) else null))
                        }
                        queue += child to path + artifact.key
                    }
                }
            }
        }
    }

    /** Dependency management in this POM. A package it already manages is checked as a declaration instead. */
    override fun pin(file: PsiFile, transitive: Transitive, version: String): PinFix? {
        val (group, artifact) = transitive.pkg.name.split(':', limit = 2)
        return PinDependencyFix(group, artifact, version)
    }

    private val MavenArtifact.key get() = "$groupId:$artifactId"
}
