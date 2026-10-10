package io.github.khopland.versionchecker.maven

import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.EnvironmentUtil
import io.github.khopland.versionchecker.CheckPerformance
import io.github.khopland.versionchecker.core.BuildFingerprint
import io.github.khopland.versionchecker.core.BuildSnapshot
import org.jetbrains.idea.maven.model.MavenExplicitProfiles
import org.jetbrains.idea.maven.dom.MavenVersionComparable
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenSettingsCache
import org.jetbrains.idea.maven.server.MavenDistributionsCache
import org.jetbrains.idea.maven.server.MavenServerManager
import com.intellij.util.lang.JavaVersion
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.coroutines.currentCoroutineContext

internal data class MavenResolutionContext(val id: String, val repository: Path,
                                          val properties: Map<String, String>, val profiles: MavenExplicitProfiles,
                                          val nativeSupported: Boolean, val sessionConfiguration: String)

/** Model reuse is stricter than raw metadata sharing: every source/configuration change rebuilds it. */
internal class MavenResolutionContexts {
    private data class Key(val path: String, val fingerprint: BuildFingerprint, val configuration: String, val generation: Long)
    private val entries = LinkedHashMap<Key, MavenResolutionContext>(16, .75f, true)
    @Synchronized fun clear() { entries.clear() }

    suspend fun get(manager: MavenProjectsManager, project: MavenProject, snapshot: BuildSnapshot,
                    generation: Long): MavenResolutionContext {
        val distributions = MavenDistributionsCache.getInstance(manager.project)
        val distribution = distributions.getMavenDistribution(project.file)
        val connector = MavenServerManager.getInstance().getConnector(manager.project, distributions.getMultimoduleDirectory(project.file.parent.path))
        val configuration = sortedMapOf<String, String>()
        fun file(path: Path) {
            if (Files.isRegularFile(path)) configuration[path.toAbsolutePath().normalize().toString()] = digest(Files.readAllBytes(path))
        }
        MavenSettingsInputs.files(MavenSettingsInputs.userSettings(manager), distribution.mavenHome).forEach(::file)
        val extensionDirectory = distribution.mavenHome.resolve("lib/ext")
        var extensions = connector.vmOptions.contains("maven.ext.class.path")
        if (Files.isDirectory(extensionDirectory)) Files.list(extensionDirectory).use { paths ->
            paths.filter(Files::isRegularFile).forEach { path ->
                file(path)
                if (path.fileName.toString().endsWith(".jar", ignoreCase = true)) extensions = true
            }
        }
        var directory: Path? = project.file.toNioPath().parent
        while (directory != null) {
            for (name in listOf("maven.config", "jvm.config", "extensions.xml", "wrapper/maven-wrapper.properties")) {
                val path = directory.resolve(".mvn").resolve(name)
                file(path)
                if (name == "extensions.xml" && Files.isRegularFile(path)) extensions = true
            }
            directory = directory.parent
        }
        configuration["runtime"] = "${distribution.mavenHome}:${distribution.version}:${connector.jdk.homePath}:${connector.jdk.versionString}:${connector.vmOptions}"
        configuration["settings"] = manager.generalSettings.hashCode().toString()
        configuration["environment"] = EnvironmentUtil.getEnvironmentMap().toSortedMap().toString()
        val profiles = readAction { manager.explicitProfiles.clone() }
        configuration["profiles"] = "${profiles.enabledProfiles.sorted()}:${profiles.disabledProfiles.sorted()}:${project.activatedProfilesIds}"
        val properties = MavenConfigProperties.read(project.file.toNioPath().parent)
        if ("maven.ext.class.path" in properties) extensions = true
        configuration["properties"] = properties.toSortedMap().toString()
        val configDigest = digest(configuration.toString().toByteArray())
        val key = Key(project.path, snapshot.fingerprint, configDigest, generation)
        synchronized(this) { entries[key] }?.let { return it }
        val context = withMavenCheckSession(manager, project, if (extensions) null else configDigest) { embedder ->
            suspend fun repository(): Path {
                val settings = MavenSettingsCache(manager.project)
                CheckPerformance.measure(CheckPerformance.Stage.MAVEN_SETTINGS) { settings.reloadAsync() }
                return settings.getEffectiveUserLocalRepo().toAbsolutePath().normalize()
            }
            val repository = if (extensions) repository() else
                currentCoroutineContext()[MavenScanSession]?.repository { repository() } ?: repository()
            val model = CheckPerformance.measure(CheckPerformance.Stage.MAVEN_MODEL) {
                JDOMUtil.load(embedder.evaluateEffectivePom(project.file.toNioPath().toFile(), profiles.enabledProfiles, profiles.disabledProfiles)
                    ?: error("Maven could not determine effective repositories"))
            }
            // Include order, source repositories and Maven-applied mirror URLs/policies, never IDs alone.
            val repositoryIdentity = listOf(false, true).joinToString("\n") { plugins ->
                val section = if (plugins) "pluginRepositories" else "repositories"
                val declared = MavenRepositoryMetadata.repositories(model, plugins)
                fun policy(value: org.jetbrains.idea.maven.model.MavenRemoteRepository.Policy?) =
                    value?.let { "${it.isEnabled}:${it.updatePolicy}:${it.checksumPolicy}" }.orEmpty()
                JDOMUtil.write(model.getChild(section, model.namespace) ?: org.jdom.Element(section)) +
                    embedder.resolveRepositories(declared).joinToString { "${it.id}:${it.url}:${policy(it.releasesPolicy)}:${policy(it.snapshotsPolicy)}" }
            }
            val build = model.getChild("build", model.namespace)
            val customVersions = listOf("plugins", "pluginManagement").any { section ->
                val parent = build?.getChild(section, model.namespace)
                val plugins = if (section == "pluginManagement") parent?.getChild("plugins", model.namespace) else parent
                plugins?.children.orEmpty().any { it.getChildTextTrim("artifactId", model.namespace) == "versions-maven-plugin" && it.getChild("configuration", model.namespace) != null }
            }
            val buildExtensions = build?.getChild("extensions", model.namespace)?.children.orEmpty().isNotEmpty()
            val supported = !extensions && !buildExtensions && !customVersions && distribution.version?.startsWith("3.") == true &&
                distribution.version?.let { MavenVersionComparable(it) >= MavenVersionComparable("3.6.3") } == true &&
                connector.jdk.versionString?.let { JavaVersion.tryParse(it)?.feature?.let { feature -> feature >= 17 } } == true &&
                properties.keys.none { it == "maven.version.rules" || it == "maven.ext.class.path" }
            MavenResolutionContext(digest("$configDigest\n$repository\n$repositoryIdentity".toByteArray()), repository, properties, profiles, supported, configDigest)
        }
        synchronized(this) {
            entries[key] = context
            while (entries.size > 128) entries.remove(entries.keys.first())
        }
        return context
    }

    companion object {
        fun digest(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

