package io.github.khopland.versionchecker.maven

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import io.github.khopland.versionchecker.core.ApplicationHint
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
import org.jetbrains.idea.maven.project.MavenSettingsCache
import java.nio.file.Files

/** Dependencies, dependency management, BOMs, plugins, extensions and the parent in `pom.xml`. */
class MavenProvider : DeclarationProvider {
    private class Cached(val stamp: Stamp, val declarations: List<Declaration>)

    /** What [collect] reads: the POM and its parents, by their PSI stamps, and what the POMs of a project share. */
    private data class Stamp(val chain: List<Long>, val shared: Shared.Stamp)

    /**
     * What every POM of a project reads beside its own files: Maven's projects and settings, and the content roots.
     * `related()` makes one for all the POMs it visits, so the settings files are looked at once.
     */
    private class Shared(project: Project) {
        data class Stamp(val imported: Long, val roots: Long, val settings: List<Long?>, val security: String, val offline: Boolean)

        private val manager = MavenProjectsManager.getInstance(project)
        val stamp = Stamp(MavenPoms.imported(manager), ProjectRootManager.getInstance(project).modificationCount,
            // Repositories and their credentials come from the settings files, which are outside the project.
            runCatching { MavenSettingsCache.getInstance(project).getEffectiveSettingsFiles() }.getOrDefault(emptyList())
                .map { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrNull() },
            MavenSecurity().stamp, manager.generalSettings.isWorkOffline)

        /** The project's own modules, which are not looked up in a repository. */
        val modules by lazy { manager.projects.mapTo(hashSetOf()) { "${it.mavenId.groupId}:${it.mavenId.artifactId}" } }
    }

    private companion object {
        val CACHE = Key.create<Cached>("version-checker.maven-declarations")
    }

    override val fileNames = listOf("pom.xml")

    override fun accepts(file: PsiFile) = file is XmlFile && file.name == "pom.xml"

    /** Other project POMs that use a property defined in another file, so a fix agrees with every module. */
    override fun related(file: PsiFile, declarations: List<Declaration>): List<Declaration> {
        val self = file.virtualFile ?: return emptyList()
        val shared = declarations.mapNotNull { it.edit }.filter { it.file != self }.mapTo(hashSetOf()) { it.key }
        if (shared.isEmpty()) return emptyList()
        val psi = PsiManager.getInstance(file.project)
        val environment = Shared(file.project)
        return MavenProjectsManager.getInstance(file.project).projects.asSequence()
            .map { it.file }.filter { it != self && it.isValid }
            .mapNotNull { psi.findFile(it) as? XmlFile }
            .flatMap {
                ProgressManager.checkCanceled()
                collect(it, environment)
            }
            .filter { it.edit?.key in shared }
            .toList()
    }

    /**
     * The Nexus IQ application in `clm.applicationId`, or in the configuration of the Sonatype CLM plugin, as the
     * `clm-maven-plugin` reads it, in this POM or a parent. Otherwise the artifact IDs of this POM and its parents in the
     * project, since an application is often named after the project's root artifact.
     */
    override fun application(file: PsiFile): ApplicationHint? {
        val xml = file as? XmlFile ?: return null
        MavenPoms.root(xml) ?: return null
        val chain = MavenPoms.chain(file.project, xml)
        fun literal(text: String?) = text?.let { MavenPoms.propertyName(it)?.let { name -> MavenPoms.property(chain, name)?.value } ?: it }?.takeIf { '$' !in it }
        val declared = MavenPoms.property(chain, "clm.applicationId")?.value ?: chain.all.firstNotNullOfOrNull { pom ->
            val build = MavenPoms.root(pom)?.let { MavenPoms.child(it, "build") }
            (MavenPoms.children(build, "plugins", "plugin") + MavenPoms.children(build, "pluginManagement", "plugins", "plugin"))
                .filter { MavenPoms.text(MavenPoms.child(it, "artifactId")) == "clm-maven-plugin" }
                .flatMap { listOf(it) + MavenPoms.children(it, "executions", "execution") }
                .firstNotNullOfOrNull { literal(MavenPoms.text(MavenPoms.child(it, "configuration", "applicationId"))) }
        }
        val index = ProjectFileIndex.getInstance(file.project)
        val names = chain.all.filter { pom -> pom.virtualFile?.let { index.isInContent(it) } == true }
            .mapNotNull { pom -> MavenPoms.root(pom)?.let { MavenPoms.text(MavenPoms.child(it, "artifactId")) }?.takeIf { '$' !in it } }
        return ApplicationHint(declared, names.distinct(), "clm.applicationId in the POM")
    }

    override fun collect(file: PsiFile): List<Declaration> = collect(file, Shared(file.project))

    /** `related()` visits every imported project, so each POM's declarations are collected once per change to what it reads. */
    private fun collect(file: PsiFile, shared: Shared): List<Declaration> {
        val xml = file as? XmlFile ?: return emptyList()
        val root = MavenPoms.root(xml) ?: return emptyList()
        val project = file.project
        val pom = file.virtualFile ?: return emptyList()
        if (shared.stamp.offline) return emptyList()
        val chain = MavenPoms.chain(project, xml)
        val stamp = Stamp(MavenPoms.stamps(chain), shared.stamp)
        file.getUserData(CACHE)?.takeIf { it.stamp == stamp }?.let { return it.declarations }
        val declarations = collect(xml, root, pom, chain, shared.modules)
        file.putUserData(CACHE, Cached(stamp, declarations))
        return declarations
    }

    private fun collect(xml: XmlFile, root: XmlTag, pom: VirtualFile, chain: PomChain, modules: Set<String>): List<Declaration> {
        val project = xml.project
        val manager = MavenProjectsManager.getInstance(project)
        val dependencyRepos by lazy { MavenRepos.forPom(project, pom, plugins = false) }
        val pluginRepos by lazy { MavenRepos.forPom(project, pom, plugins = true) }
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
