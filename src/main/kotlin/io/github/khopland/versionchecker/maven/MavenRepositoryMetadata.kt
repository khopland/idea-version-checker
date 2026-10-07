package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.*

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import org.jetbrains.idea.maven.dom.MavenVersionComparable
import org.jetbrains.idea.maven.model.MavenRemoteRepository
import java.nio.file.Files
import java.nio.file.Path

/** Fallback when Maven's resolver cannot sort a version list; Maven has already refreshed this metadata. */
internal object MavenRepositoryMetadata {
    private val unstable = Regex(DependencyUpdateReport.IGNORED_VERSIONS)
    private val remoteMetadata = Regex("""maven-metadata-(.+)\.xml""")

    fun latest(repository: Path, dependency: DependencyVersion, mode: UpdateMode, repositoryIds: Set<String>): String? {
        val root = repository.toAbsolutePath().normalize()
        val directory = root.resolve(dependency.groupId.replace('.', '/')).resolve(dependency.artifactId).normalize()
        if (!directory.startsWith(root) || !Files.isDirectory(directory)) return null
        val files = Files.list(directory).use { paths ->
            paths.filter { path ->
                val name = path.fileName.toString()
                name != "maven-metadata-local.xml" && remoteMetadata.matchEntire(name)?.groupValues?.get(1) in repositoryIds
            }.toList()
        }
        return latest(files.flatMap { versions(JDOMUtil.load(it)) }, dependency.version, mode)
    }

    /** Maven has already merged POM inheritance and active settings profiles into this model. */
    fun repositories(effectivePom: Element): List<MavenRemoteRepository> {
        val namespace = effectivePom.namespace
        return effectivePom.getChild("repositories", namespace)?.getChildren("repository", namespace).orEmpty().mapNotNull { repository ->
            val id = repository.getChildTextTrim("id", namespace) ?: return@mapNotNull null
            val url = repository.getChildTextTrim("url", namespace) ?: return@mapNotNull null
            fun policy(name: String): MavenRemoteRepository.Policy {
                val element = repository.getChild(name, namespace)
                return MavenRemoteRepository.Policy(
                    element?.getChildTextTrim("enabled", namespace)?.toBoolean() ?: true,
                    element?.getChildTextTrim("updatePolicy", namespace) ?: "daily",
                    element?.getChildTextTrim("checksumPolicy", namespace) ?: "warn"
                )
            }
            MavenRemoteRepository(id, repository.getChildTextTrim("name", namespace), url,
                repository.getChildTextTrim("layout", namespace) ?: "default", policy("releases"), policy("snapshots"))
        }
    }

    fun versions(metadata: Element): List<String> {
        val namespace = metadata.namespace
        return metadata.getChild("versioning", namespace)?.getChild("versions", namespace)
            ?.getChildren("version", namespace)?.map { it.textTrim }.orEmpty()
    }

    fun latest(versions: Collection<String>, current: String, mode: UpdateMode): String? {
        val baseline = MavenVersionComparable(current)
        return versions.asSequence().distinct()
            .filter { DependencyUpdateReport.isFixedVersion(it) && !unstable.matches(it) }
            .filter { MavenVersionComparable(it) > baseline && MavenVersionSemantics.allows(mode, current, it) }
            .maxWithOrNull { left, right -> MavenVersionComparable(left).compareTo(MavenVersionComparable(right)) }
    }
}
