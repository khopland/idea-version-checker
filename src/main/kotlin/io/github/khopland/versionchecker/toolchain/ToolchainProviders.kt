package io.github.khopland.versionchecker.toolchain

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.repo.MavenLookup
import io.github.khopland.versionchecker.repo.MavenRepo
import io.github.khopland.versionchecker.wrapper.GradleReleases
import java.time.LocalDate
import java.time.ZoneOffset

/** `.nvmrc` and `.node-version`: one full Node.js version, with or without `v`. Aliases such as `lts/iron` are not checked. */
class NodeVersionProvider : DeclarationProvider {
    override val fileNames = listOf(".nvmrc", ".node-version")
    override fun accepts(file: PsiFile) = file.name in fileNames

    override fun collect(file: PsiFile): List<Declaration> {
        val vfile = file.virtualFile ?: return emptyList()
        val match = version.find(file.text) ?: return emptyList()
        val group = match.groups[1]!!
        val range = TextRange(group.range.first, group.range.last + 1)
        return listOf(Declaration("Node.js", range, group.value, NodeReleases(group.value), Edit(vfile, range, group.value)))
    }

    private companion object {
        val version = Regex("""\A\s*v?(\d+\.\d+\.\d+)[ \t]*(?:\r?\n|\z)""")
    }
}

/** `.sdkmanrc`: `candidate=version` lines, checked against the SDKMAN! candidate list for this computer. */
class SdkmanProvider : DeclarationProvider {
    override val fileNames = listOf(".sdkmanrc")
    override fun accepts(file: PsiFile) = file.name == ".sdkmanrc"

    override fun collect(file: PsiFile): List<Declaration> {
        val vfile = file.virtualFile ?: return emptyList()
        return line.findAll(file.text).mapNotNull { match ->
            val (candidate, version) = match.destructured
            if (!version.first().isDigit()) return@mapNotNull null
            val group = match.groups[2]!!
            val range = TextRange(group.range.first, group.range.last + 1)
            Declaration("SDKMAN! $candidate", range, version, SdkmanLookup(candidate), Edit(vfile, range, version))
        }.toList()
    }

    private companion object {
        val line = Regex("""(?m)^[ \t]*([\w-]+)[ \t]*=[ \t]*(\S+)[ \t]*$""")
    }
}

/** `.tool-versions` for asdf and mise: Node.js, Maven and Gradle. Other tools name versions their own way. */
class ToolVersionsProvider : DeclarationProvider {
    override val fileNames = listOf(".tool-versions")
    override fun accepts(file: PsiFile) = file.name == ".tool-versions"

    override fun collect(file: PsiFile): List<Declaration> {
        val vfile = file.virtualFile ?: return emptyList()
        return line.findAll(file.text).mapNotNull { match ->
            val (tool, version) = match.destructured
            if (!version.first().isDigit()) return@mapNotNull null
            val (name, lookup) = when (tool) {
                "nodejs", "node" -> "Node.js" to NodeReleases(version)
                "maven" -> "Maven" to MavenLookup("org.apache.maven", "apache-maven", listOf(MavenRepo.CENTRAL))
                "gradle" -> "Gradle" to GradleReleases()
                else -> return@mapNotNull null
            }
            val group = match.groups[2]!!
            val range = TextRange(group.range.first, group.range.last + 1)
            Declaration(name, range, version, lookup, Edit(vfile, range, version))
        }.toList()
    }

    private companion object {
        val line = Regex("""(?m)^[ \t]*([\w-]+)[ \t]+([^\s#]+)""")
    }
}

/**
 * Node.js releases from nodejs.org. Only LTS releases are offered as major updates, because the odd major versions are
 * supported for a few months. A project on a non-LTS major version still gets updates within it.
 */
class NodeReleases(current: String, private val http: () -> Http = Http::getInstance) : Lookup {
    private val major = current.substringBefore('.').toIntOrNull()
    override val key = "nodejs:$major"

    override suspend fun fetch(): Releases {
        val response = http().get(URL)
        if (!response.ok) throw HttpFailure("nodejs.org returned HTTP ${response.status}")
        return parse(JsonParser.parseString(response.text()).asJsonArray, major)
    }

    override val hasReleaseNotes get() = true
    override suspend fun releaseNotes(version: String) = "https://nodejs.org/en/blog/release/v$version"

    companion object {
        private const val URL = "https://nodejs.org/dist/index.json"

        /** LTS releases, and every release of the [major] version in use. */
        fun parse(index: JsonArray, major: Int?): Releases {
            val times = linkedMapOf<String, Long?>()
            index.map { it.asJsonObject }.forEach { release ->
                val version = release.get("version")?.asString?.removePrefix("v") ?: return@forEach
                val lts = release.get("lts")?.let { it.isJsonPrimitive && !(it.asJsonPrimitive.isBoolean && !it.asBoolean) } == true
                if (!lts && version.substringBefore('.').toIntOrNull() != major) return@forEach
                times[version] = release.get("date")?.asString?.let { runCatching { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
            }
            return Releases(times.keys.toList(), publishedAt = { times[it] })
        }
    }
}

/** The versions of one SDKMAN! candidate. Java has a list per platform, with a vendor suffix such as `-tem`. */
class SdkmanLookup(private val candidate: String, private val platform: String = platform(), private val http: () -> Http = Http::getInstance) : Lookup {
    override val key = "sdkman:$candidate:$platform"

    override suspend fun fetch(): Releases {
        val response = http().get("https://api.sdkman.io/2/candidates/$candidate/$platform/versions/all")
        if (response.status == 400 || response.status == 404) return Releases(emptyList())
        if (!response.ok) throw HttpFailure("api.sdkman.io returned HTTP ${response.status}")
        return Releases(response.text().split(',').map { it.trim() }.filter { it.isNotEmpty() })
    }

    companion object {
        /** SDKMAN!'s platform names, such as `darwinarm64` and `linuxx64`. */
        fun platform(os: String = System.getProperty("os.name"), arch: String = System.getProperty("os.arch")): String {
            val system = when {
                os.startsWith("Mac", ignoreCase = true) -> "darwin"
                os.startsWith("Windows", ignoreCase = true) -> "windows"
                else -> "linux"
            }
            val cpu = if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"
            return system + cpu
        }
    }
}
