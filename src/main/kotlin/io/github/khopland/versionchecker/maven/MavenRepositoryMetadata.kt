package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.*

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import org.jetbrains.idea.maven.dom.MavenVersionComparable
import java.nio.file.Files
import java.nio.file.Path

/** Fallback when Maven's resolver cannot sort a version list; Maven has already refreshed this metadata. */
internal object MavenRepositoryMetadata {
    private val unstable = Regex(DependencyUpdateReport.IGNORED_VERSIONS)
    private val remoteMetadata = Regex("""maven-metadata-(.+)\.xml""")

    fun latest(repository: Path, dependency: DependencyVersion, mode: UpdateMode): String? {
        val root = repository.toAbsolutePath().normalize()
        val directory = root.resolve(dependency.groupId.replace('.', '/')).resolve(dependency.artifactId).normalize()
        if (!directory.startsWith(root) || !Files.isDirectory(directory)) return null
        val files = Files.list(directory).use { paths ->
            paths.filter { path ->
                val name = path.fileName.toString()
                remoteMetadata.matches(name) && name != "maven-metadata-local.xml"
            }.toList()
        }
        return latest(files.flatMap { versions(JDOMUtil.load(it)) }, dependency.version, mode)
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
