package io.github.khopland.versionchecker.maven

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.repo.MavenRepo
import org.jdom.Element
import org.jetbrains.idea.maven.model.MavenRemoteRepository
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenSettingsCache
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Credentials for one `<server>` in `settings.xml`. */
internal data class MavenServer(val headers: Map<String, String>, val encrypted: Boolean)

/** Reads `<servers>` from settings files. Results are cached until a file changes. */
internal object MavenServers {
    private class Parsed(val stamp: Long, val servers: Map<String, MavenServer>)

    private val cache = ConcurrentHashMap<Path, Parsed>()
    private val placeholder = Regex("""\$\{(env\.)?([^}]+)}""")

    fun servers(files: List<Path>): Map<String, MavenServer> {
        val result = linkedMapOf<String, MavenServer>()
        // The user settings file comes first and wins over the global file.
        for (file in files) parse(file).forEach { (id, server) -> result.putIfAbsent(id, server) }
        return result
    }

    private fun parse(file: Path): Map<String, MavenServer> {
        val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return emptyMap()
        cache[file]?.takeIf { it.stamp == stamp }?.let { return it.servers }
        val servers = runCatching { parse(JDOMUtil.load(file)) }.getOrDefault(emptyMap())
        cache[file] = Parsed(stamp, servers)
        return servers
    }

    fun parse(settings: Element): Map<String, MavenServer> {
        fun Element.child(name: String) = children.firstOrNull { it.name == name }
        fun Element.text(name: String) = child(name)?.textTrim?.let(::expand)?.takeIf { it.isNotEmpty() }
        val servers = settings.child("servers")?.children?.filter { it.name == "server" }.orEmpty()
        return servers.mapNotNull { server ->
            val id = server.text("id") ?: return@mapNotNull null
            val headers = linkedMapOf<String, String>()
            server.child("configuration")?.child("httpHeaders")?.children?.filter { it.name == "property" }?.forEach { property ->
                val name = property.text("name")
                val value = property.text("value")
                if (name != null && value != null) headers[name] = value
            }
            val user = server.text("username")
            val password = server.text("password")
            val encrypted = password != null && password.startsWith("{") && password.endsWith("}")
            if (user != null && password != null && !encrypted) headers["Authorization"] = Http.basicAuth(user, password)
            id to MavenServer(headers, encrypted)
        }.toMap()
    }

    private fun expand(text: String) = placeholder.replace(text) { match ->
        val name = match.groupValues[2]
        (if (match.groupValues[1].isNotEmpty()) System.getenv(name) else System.getProperty(name) ?: System.getenv(name)) ?: match.value
    }
}

/** The repositories IDEA imported for a POM, with mirrors applied, plus credentials from settings.xml. */
internal object MavenRepos {
    fun offline(project: Project) = MavenProjectsManager.getInstance(project).generalSettings.isWorkOffline

    fun forPom(project: Project, pom: VirtualFile, plugins: Boolean): List<MavenRepo> {
        val imported = MavenProjectsManager.getInstance(project).findProject(pom)
            ?: return listOf(MavenRepo.CENTRAL)
        val remote = if (plugins) imported.remotePluginRepositories else imported.remoteRepositories
        val settingsFiles = runCatching { MavenSettingsCache.getInstance(project).getEffectiveSettingsFiles() }.getOrDefault(emptyList())
        val servers = MavenServers.servers(settingsFiles)
        return remote.filter { it.releasesPolicy?.isEnabled != false && it.url.startsWith("http") }
            .distinctBy { it.url.trimEnd('/') }
            .map { repo(it, servers[it.id]) }
            .ifEmpty { listOf(MavenRepo.CENTRAL) }
    }

    private fun repo(remote: MavenRemoteRepository, server: MavenServer?): MavenRepo {
        val hint = when {
            server == null -> "Add a <server> with id '${remote.id}' to settings.xml"
            server.encrypted -> "Encrypted passwords for server '${remote.id}' are not supported. Use a token or an environment variable"
            else -> "Check the credentials for server '${remote.id}' in settings.xml"
        }
        return MavenRepo(remote.id, remote.url, server?.headers.orEmpty(), hint)
    }
}
