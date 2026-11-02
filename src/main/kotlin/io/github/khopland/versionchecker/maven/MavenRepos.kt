package io.github.khopland.versionchecker.maven

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.repo.MavenRepo
import org.jdom.Element
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenSettingsCache
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Credentials for one `<server>` in `settings.xml`. */
internal data class MavenServer(val headers: Map<String, String>, val encrypted: Boolean)

/** One `<mirror>` in `settings.xml`. */
internal data class MavenMirror(val id: String, val mirrorOf: String, val url: String, val blocked: Boolean = false) {
    companion object {
        /** Like Maven's `DefaultMirrorSelector`: an exact id match first, then the first matching pattern. */
        fun select(mirrors: List<MavenMirror>, id: String, url: String): MavenMirror? =
            mirrors.firstOrNull { it.mirrorOf == id } ?: mirrors.firstOrNull { matches(it.mirrorOf, id, url) }

        fun matches(pattern: String, id: String, url: String): Boolean {
            if (pattern == "*" || pattern == id) return true
            var result = false
            for (part in pattern.split(',').map(String::trim)) {
                when {
                    part.length > 1 && part.startsWith("!") -> if (part.substring(1) == id) return false
                    part == id -> return true
                    part == "external:*" && external(url) -> result = true
                    part == "external:http:*" && external(url) && url.startsWith("http:", ignoreCase = true) -> result = true
                    part == "*" -> result = true
                }
            }
            return result
        }

        private fun external(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return true
            return !uri.scheme.equals("file", ignoreCase = true) && uri.host != "localhost" && uri.host != "127.0.0.1"
        }
    }
}

/** Reads `<servers>` and `<mirrors>` from settings files. Results are cached until a settings file or `settings-security.xml` changes. */
internal object MavenSettings {
    private class Parsed(val stamp: String, val servers: Map<String, MavenServer>, val mirrors: List<MavenMirror>)

    private val cache = ConcurrentHashMap<Path, Parsed>()
    private val placeholder = Regex("""\$\{(env\.)?([^}]+)}""")

    fun servers(files: List<Path>, security: Path = MavenPasswords.securityFile()): Map<String, MavenServer> {
        val result = linkedMapOf<String, MavenServer>()
        // The user settings file comes first and wins over the global file.
        for (file in files) parse(file, security)?.servers?.forEach { (id, server) -> result.putIfAbsent(id, server) }
        return result
    }

    /** User mirrors come first, and a global mirror with the same id is dropped, as Maven merges them. */
    fun mirrors(files: List<Path>, security: Path = MavenPasswords.securityFile()): List<MavenMirror> =
        files.flatMap { parse(it, security)?.mirrors.orEmpty() }.distinctBy { it.id }

    private fun stamp(file: Path) = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull()

    private fun parse(file: Path, security: Path): Parsed? {
        val stamp = "${stamp(file) ?: return null}/${stamp(security)}"
        cache[file]?.takeIf { it.stamp == stamp }?.let { return it }
        val master by lazy { MavenPasswords.master(security) }
        val root = runCatching { JDOMUtil.load(file) }.getOrNull()
        val servers = root?.let { runCatching { parse(it) { master } }.getOrNull() }.orEmpty()
        val mirrors = root?.let { runCatching { mirrors(it) }.getOrNull() }.orEmpty()
        return Parsed(stamp, servers, mirrors).also { cache[file] = it }
    }

    private fun Element.child(name: String) = children.firstOrNull { it.name == name }
    private fun Element.text(name: String) = child(name)?.textTrim?.let(::expand)?.takeIf { it.isNotEmpty() }

    fun mirrors(settings: Element): List<MavenMirror> =
        settings.child("mirrors")?.children?.filter { it.name == "mirror" }.orEmpty().mapNotNull { mirror ->
            MavenMirror(mirror.text("id") ?: "default", mirror.text("mirrorOf") ?: return@mapNotNull null,
                mirror.text("url") ?: return@mapNotNull null, mirror.text("blocked") == "true")
        }

    fun parse(settings: Element, master: () -> String? = { null }): Map<String, MavenServer> {
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
            val stored = server.text("password")
            val password = if (stored != null && MavenPasswords.isEncrypted(stored)) master()?.let { MavenPasswords.decrypt(stored, it) } else stored
            val encrypted = stored != null && password == null
            if (user != null && password != null) headers["Authorization"] = Http.basicAuth(user, password)
            id to MavenServer(headers, encrypted)
        }.toMap()
    }

    private fun expand(text: String) = placeholder.replace(text) { match ->
        val name = match.groupValues[2]
        (if (match.groupValues[1].isNotEmpty()) System.getenv(name) else System.getProperty(name) ?: System.getenv(name)) ?: match.value
    }
}

/** The repositories IDEA imported for a POM, with mirrors from settings.xml applied, plus credentials from settings.xml. */
internal object MavenRepos {
    fun offline(project: Project) = MavenProjectsManager.getInstance(project).generalSettings.isWorkOffline

    fun forPom(project: Project, pom: VirtualFile, plugins: Boolean): List<MavenRepo> {
        val imported = MavenProjectsManager.getInstance(project).findProject(pom)
        val remote = imported?.let { if (plugins) it.remotePluginRepositories else it.remoteRepositories }.orEmpty()
            .filter { it.releasesPolicy?.isEnabled != false }
            .map { MavenRepo(it.id, it.url) }
        val settingsFiles = runCatching { MavenSettingsCache.getInstance(project).getEffectiveSettingsFiles() }.getOrDefault(emptyList())
        return resolve(remote, MavenSettings.servers(settingsFiles), MavenSettings.mirrors(settingsFiles))
    }

    /** Maven Central is the default when nothing usable is declared, and goes through the mirrors too. */
    fun resolve(remote: List<MavenRepo>, servers: Map<String, MavenServer>, mirrors: List<MavenMirror>): List<MavenRepo> {
        fun usable(repos: List<MavenRepo>) = repos.mapNotNull { mirrored(it, mirrors) }
            .filter { it.url.startsWith("http") }
            .distinctBy { it.base }
            .map { repo(it.id, it.url, servers[it.id]) }
        return usable(remote).ifEmpty { usable(listOf(MavenRepo.CENTRAL)) }.ifEmpty { listOf(MavenRepo.CENTRAL) }
    }

    /** A blocked mirror makes Maven fail for the repository, so it is left out. */
    private fun mirrored(repo: MavenRepo, mirrors: List<MavenMirror>): MavenRepo? {
        val mirror = MavenMirror.select(mirrors, repo.id, repo.url) ?: return repo
        return if (mirror.blocked) null else MavenRepo(mirror.id, mirror.url)
    }

    private fun repo(id: String, url: String, server: MavenServer?): MavenRepo {
        val hint = when {
            server == null -> "Add a <server> with id '$id' to settings.xml"
            server.encrypted -> "Could not decrypt the password for server '$id'. Check ~/.m2/settings-security.xml; Maven 4 encrypted passwords are not supported"
            else -> "Check the credentials for server '$id' in settings.xml"
        }
        return MavenRepo(id, url, server?.headers.orEmpty(), hint)
    }
}
