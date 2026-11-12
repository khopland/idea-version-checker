package io.github.khopland.versionchecker.docker

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.khopland.versionchecker.core.*
import java.net.URI
import java.net.URLEncoder

/**
 * `FROM image:tag` in a `Dockerfile`, `Dockerfile.*`, `*.dockerfile` or `Containerfile`. Build stages, `scratch`, images
 * without a version tag and tags with a variable are not checked.
 */
class DockerfileProvider : DeclarationProvider {
    override val fileNames = listOf("Dockerfile", "Containerfile")

    override fun buildFiles(project: Project, scope: GlobalSearchScope): Collection<VirtualFile> {
        val names = HashSet<String>()
        FilenameIndex.processAllFileNames({ if (isDockerfile(it)) names += it; true }, scope, null)
        return names.flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }
    }

    override fun accepts(file: PsiFile) = isDockerfile(file.name)

    private fun isDockerfile(name: String) = name in fileNames || name.startsWith("Dockerfile.") || name.endsWith(".dockerfile")

    override fun collect(file: PsiFile): List<Declaration> {
        val vfile = file.virtualFile ?: return emptyList()
        return from.findAll(file.text).mapNotNull { imageDeclaration(vfile, it.groups[1]!!) }.toList()
    }

    private companion object {
        val from = Regex("""(?im)^[ \t]*FROM[ \t]+(?:--platform=\S+[ \t]+)?(\S+)""")
    }
}

/** `image: image:tag` in a Compose file: `compose.yaml`, `docker-compose.yml` and their variants such as `compose.prod.yaml`. */
class ComposeProvider : DeclarationProvider {
    override val fileNames = listOf("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml")

    override fun buildFiles(project: Project, scope: GlobalSearchScope): Collection<VirtualFile> =
        listOf("yml", "yaml").flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }.filter { compose.matches(it.name) }

    override fun accepts(file: PsiFile) = compose.matches(file.name)

    override fun collect(file: PsiFile): List<Declaration> {
        val vfile = file.virtualFile ?: return emptyList()
        return image.findAll(file.text).mapNotNull { imageDeclaration(vfile, it.groups[1]!!) }.toList()
    }

    private companion object {
        val compose = Regex("""(?:docker-)?compose(?:[.\-][\w.\-]+)?\.ya?ml""")
        val image = Regex("""(?m)^[ \t]*(?:-[ \t]+)?image[ \t]*:[ \t]*['"]?([^\s'"#]+)""")
    }
}

/** The tag of the image that [reference] names. With a digest it is checked but not changed, because the digest names one build. */
private fun imageDeclaration(file: VirtualFile, reference: MatchGroup): Declaration? {
    val image = Image.parse(reference.value) ?: return null
    val range = TextRange.from(reference.range.first + image.tagOffset, image.tag.length)
    val edit = if (image.digest) null else Edit(file, range, image.tag)
    val note = if (image.digest) "The digest must match the tag: change both" else null
    return Declaration(image.name, range, image.tag, RegistryTags(image), edit, note)
}

/**
 * `[registry/]repository:tag[@digest]`. A first part with a dot or a port is a registry, and everything else is on
 * Docker Hub, where a name without an owner is in `library`. [tagOffset] is where the tag starts in the reference.
 */
class Image private constructor(val registry: String, val repository: String, val tag: String, val tagOffset: Int, val digest: Boolean) {
    /** The name as people write it: `node`, `bitnami/redis` or `ghcr.io/owner/image`. */
    val name get() = if (registry == HUB) repository.removePrefix("library/") else "$registry/$repository"

    companion object {
        const val HUB = "registry-1.docker.io"
        private val hubNames = setOf("docker.io", "index.docker.io", HUB)
        private val reference = Regex("""(?:([a-zA-Z0-9.\-]+(?::\d+)?)/)?([a-z0-9._\-]+(?:/[a-z0-9._\-]+)*):(\w[\w.\-]*)(@sha256:[0-9a-f]+)?""")

        /** Null without a tag that starts with a version, so for `node`, `node:latest`, `node:$TAG` and a build stage. */
        fun parse(text: String): Image? {
            val match = reference.matchEntire(text) ?: return null
            val (first, rest, tag) = match.destructured
            if (Versions.numbers(tag).isEmpty()) return null
            val host = first.takeIf { '.' in it || ':' in it || it == "localhost" }
            val path = if (host == null && first.isNotEmpty()) "$first/$rest" else rest
            val hub = host == null || host in hubNames
            val repository = if (hub && '/' !in path) "library/$path" else path
            return Image(if (hub) HUB else host!!, repository, tag, match.groups[3]!!.range.first, match.groups[4] != null)
        }
    }
}

/**
 * The tags of an image from its registry, with an anonymous token when the registry asks for one. A private image has no
 * tags to offer. Only tags with the same shape as the current one count: see [shaped].
 */
class RegistryTags(private val image: Image, private val base: String = "https://${image.registry}", private val http: () -> Http = Http::getInstance) : Lookup {
    override val key = "docker:${image.registry}/${image.repository}:${shape(image.tag)}"

    override suspend fun fetch(): Releases {
        val tags = mutableListOf<String>()
        // Docker Hub sends every tag at once when no page size is given. Other registries send pages of at most this size.
        var url: String? = "$base/v2/${image.repository}/tags/list" + if (image.registry == Image.HUB) "" else "?n=1000"
        var token: String? = null
        var pages = 0
        while (url != null && pages++ < MAX_PAGES) {
            var response = get(url, token)
            if (response.status == 401 && token == null) {
                token = token(response.header("WWW-Authenticate")) ?: return NONE
                response = get(url, token)
            }
            // A private or deleted image.
            if (response.status == 401 || response.status == 403 || response.status == 404) return NONE
            if (!response.ok) throw HttpFailure("${image.registry} returned HTTP ${response.status}")
            val list = JsonParser.parseString(response.text()).asJsonObject.get("tags")
            if (list != null && list.isJsonArray) tags += list.asJsonArray.map { it.asString }
            url = next(response.header("Link"), url)
        }
        return Releases(shaped(tags, image.tag), ignoresMinimumAge = true)
    }

    // Not stored: the token is new each time, so a stored copy would never be found again.
    private suspend fun get(url: String, token: String?) =
        http().get(url, if (token == null) emptyMap() else mapOf("Authorization" to "Bearer $token"), store = false)

    private suspend fun token(challenge: String?): String? {
        val response = http().get(tokenUrl(challenge, image.repository) ?: return null, store = false)
        if (!response.ok) return null
        val json = JsonParser.parseString(response.text()).asJsonObject
        return (json.get("token") ?: json.get("access_token"))?.asString
    }

    companion object {
        private const val MAX_PAGES = 50
        // The registry gives no release times, so the minimum release age cannot apply.
        private val NONE get() = Releases(emptyList(), ignoresMinimumAge = true)
        private val field = Regex("""(\w+)="([^"]*)"""")
        private val nextLink = Regex("""<([^>]+)>\s*;\s*rel="?next"?""")

        /** Where a `Bearer realm="…",service="…",scope="…"` challenge says to get a token. No credentials are sent there. */
        fun tokenUrl(challenge: String?, repository: String): String? {
            if (challenge?.startsWith("Bearer ", ignoreCase = true) != true) return null
            val fields = field.findAll(challenge).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val realm = fields["realm"]?.takeIf { it.startsWith("https://") } ?: return null
            val query = listOfNotNull(fields["service"]?.let { "service" to it }, "scope" to (fields["scope"] ?: "repository:$repository:pull"))
            return realm + "?" + query.joinToString("&") { (name, value) -> "$name=" + URLEncoder.encode(value, Charsets.UTF_8) }
        }

        /** The next page from a `Link: <…>; rel="next"` header, which is relative to [url]. */
        fun next(link: String?, url: String): String? = link?.let { nextLink.find(it) }?.let { URI(url).resolve(it.groupValues[1]).toString() }

        /**
         * The tags with the same `v` prefix and number of parts as [current], so `20-alpine` is offered `22-alpine` and
         * never `22.11.0-alpine`. The variant after the numbers is matched later, like for every other version.
         */
        fun shaped(tags: List<String>, current: String): List<String> {
            val shape = shape(current)
            return tags.filter { shape(it) == shape }
        }

        /** Null for a tag that does not start with a version. A date such as `20240923` has its own shape, so `3` is not offered it. */
        private fun shape(tag: String): String? {
            val numbers = Versions.numbers(tag)
            if (numbers.isEmpty()) return null
            val prefix = if (tag.startsWith("v", ignoreCase = true)) "v" else ""
            return prefix + if (numbers.size == 1 && numbers[0] >= 1_000_000) "date" else numbers.size
        }
    }
}
