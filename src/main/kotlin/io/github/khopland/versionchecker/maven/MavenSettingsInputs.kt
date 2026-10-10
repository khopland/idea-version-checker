package io.github.khopland.versionchecker.maven

import com.intellij.openapi.util.JDOMUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files
import java.nio.file.Path

/** The same settings/credential paths participate in resolution identity and edit guards. */
internal object MavenSettingsInputs {
    fun userSettings(manager: MavenProjectsManager): Path = manager.generalSettings.userSettingsFile
        .takeIf { it.isNotBlank() }?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".m2/settings.xml")

    fun files(userSettings: Path, mavenHome: Path? = null): Set<Path> {
        val files = linkedSetOf(userSettings.toAbsolutePath().normalize())
        val visited = hashSetOf<Path>()
        fun security(path: Path) {
            val normalized = path.toAbsolutePath().normalize()
            // Track missing targets too: creating one must invalidate an existing result.
            files.add(normalized)
            if (!visited.add(normalized) || visited.size > 8 || !Files.isRegularFile(normalized)) return
            val relocation = runCatching { JDOMUtil.load(normalized).getChildTextTrim("relocation") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: return
            val expanded = relocation.replace("\${user.home}", System.getProperty("user.home"))
            val target = if (expanded.startsWith("~/")) Path.of(System.getProperty("user.home"), expanded.substring(2)) else Path.of(expanded)
            security(if (target.isAbsolute) target else normalized.parent.resolve(target))
        }
        security(userSettings.resolveSibling("settings-security.xml"))
        security(Path.of(System.getProperty("user.home"), ".m2/settings-security.xml"))
        if (mavenHome != null) {
            files.add(mavenHome.resolve("conf/settings.xml").toAbsolutePath().normalize())
            security(mavenHome.resolve("conf/settings-security.xml"))
        }
        return files
    }
}
