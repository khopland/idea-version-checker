package io.github.khopland.versionchecker

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path

/** Read Maven's own downloaded POMs; repository access stays with IDEA/Maven. */
internal object MavenRelocation {
    fun read(repository: Path, dependency: DependencyVersion): String? {
        val root = repository.toAbsolutePath().normalize()
        val pom = root.resolve(dependency.groupId.replace('.', '/')).resolve(dependency.artifactId)
            .resolve(dependency.version).resolve("${dependency.artifactId}-${dependency.version}.pom").normalize()
        if (!pom.startsWith(root) || !Files.isRegularFile(pom)) return null
        return parse(JDOMUtil.load(pom), dependency)
    }

    fun parse(pom: Element, dependency: DependencyVersion): String? {
        val namespace = pom.namespace
        val relocation = pom.getChild("distributionManagement", namespace)?.getChild("relocation", namespace) ?: return null
        fun value(name: String, fallback: String): String = relocation.getChildTextTrim(name, namespace)
            ?.takeIf { it.isNotEmpty() } ?: fallback
        val target = DependencyVersion(value("groupId", dependency.groupId), value("artifactId", dependency.artifactId),
            value("version", dependency.version))
        if (target == dependency) return null
        val message = relocation.getChildTextTrim("message", namespace)?.takeIf { it.isNotBlank() }
        return "${target.groupId}:${target.artifactId}:${target.version}" + message?.let { " ($it)" }.orEmpty()
    }
}
