package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.CheckPerformance
import org.jetbrains.idea.maven.buildtool.MavenLogEventHandler
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenGoalExecutionRequest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipInputStream

/** The helper travels in the plugin archive; only Maven itself contacts remote repositories. */
internal object MavenNativeMetadata {
    private val helper by lazy {
        val bytes = MavenNativeMetadata::class.java.getResourceAsStream("/maven-helper/version-checker-maven-helper.jar")
            ?.use { it.readBytes() } ?: error("Packaged Maven metadata helper is missing")
        val descriptor = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "META-INF/maven/plugin.xml" }
            String(zip.readBytes(), Charsets.UTF_8)
        }
        val version = com.intellij.openapi.util.JDOMUtil.load(descriptor).getChildTextTrim("version")
            ?: error("Maven helper version is missing")
        version to bytes
    }

    suspend fun load(manager: MavenProjectsManager, project: MavenProject, context: MavenResolutionContext,
                     keys: List<MavenMetadataKey>, threads: Int): Map<MavenMetadataKey, Result<MavenMetadataValue>> {
        require(keys.size <= 512)
        val version = install(context.repository)
        val input = Files.createTempFile("version-checker-metadata-request-", ".properties")
        val output = Files.createTempFile("version-checker-metadata-response-", ".properties")
        try {
            val request = Properties().apply {
                setProperty("count", keys.size.toString())
                keys.forEachIndexed { index, key ->
                    setProperty("$index.group", key.group); setProperty("$index.artifact", key.artifact)
                    setProperty("$index.plugin", key.plugin.toString()); setProperty("$index.version", key.version)
                }
            }
            Files.newOutputStream(input).use { request.store(it, null) }
            val properties = Properties().apply {
                putAll(context.properties)
                setProperty("versionchecker.input", input.toString())
                setProperty("versionchecker.output", output.toString())
                setProperty("versionchecker.threads", threads.coerceIn(1, 4).toString())
            }
            val id = project.mavenId
            withMavenCheckSession(manager, project, context.sessionConfiguration) { embedder ->
                val execution = MavenGoalExecutionRequest(project.file.toNioPath().toFile(), context.profiles,
                    listOf("${id.groupId}:${id.artifactId}"), properties)
                val results = CheckPerformance.measure(CheckPerformance.Stage.MAVEN_METADATA_BATCH, keys.size) {
                    withMavenProgress { reporter -> embedder.executeGoal(listOf(execution),
                        "io.github.khopland:version-checker-maven-helper:$version:lookup", reporter, MavenLogEventHandler) }
                }
                if (results.isEmpty() || results.any { !it.success }) throw IOException("Maven metadata batch failed:\n" +
                    results.flatMap { it.problems }.mapNotNull { it.description }.joinToString("\n"))
            }
            val response = Properties().apply { Files.newInputStream(output).use { load(it) } }
            check(response.getProperty("protocol") == "1" && response.getProperty("count") == keys.size.toString()) {
                "Invalid Maven metadata response"
            }
            return keys.mapIndexed { index, key -> key to runCatching {
                response.getProperty("$index.error")?.let { throw IOException("${key.group}:${key.artifact}: $it") }
                if (key.version.isEmpty()) MavenMetadataValue(versions =
                    (response.getProperty("$index.versions") ?: error("Missing Maven version index")).lineSequence()
                        .filter { it.isNotBlank() }.distinct().toList())
                else MavenMetadataValue(requiredMaven = response.getProperty("$index.requiredMaven")
                    ?: error("Missing plugin prerequisite response"))
            } }.toMap()
        } finally { Files.deleteIfExists(input); Files.deleteIfExists(output) }
    }

    private fun install(repository: Path): String {
        val (version, bytes) = helper
        val directory = repository.resolve("io/github/khopland/version-checker-maven-helper").resolve(version)
        Files.createDirectories(directory)
        val name = "version-checker-maven-helper-$version"
        write(directory.resolve("$name.jar"), bytes)
        write(directory.resolve("$name.pom"), """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>io.github.khopland</groupId><artifactId>version-checker-maven-helper</artifactId><version>$version</version><packaging>maven-plugin</packaging></project>""".toByteArray())
        return version
    }

    private fun write(path: Path, bytes: ByteArray) {
        if (Files.isRegularFile(path) && Files.readAllBytes(path).contentEquals(bytes)) return
        val temporary = Files.createTempFile(path.parent, "helper-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
