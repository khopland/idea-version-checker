package io.github.khopland.versionchecker.maven

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.execution.ParametersListUtil
import io.github.khopland.versionchecker.core.VersionCheckFailureAdvice
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.idea.maven.model.MavenExplicitProfiles
import org.jetbrains.idea.maven.project.MavenGeneralSettings
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenDistributionsCache
import org.jetbrains.idea.maven.server.MavenServerManager
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class MavenGoalOutput(val exitCode: Int, val text: String)

/** Launch Maven's distribution entry point without a Run console or document saves. */
internal object MavenGoalProcess {
    private val slots = Semaphore(2)

    private data class Runtime(val mavenHome: Path, val javaHome: Path, val vmOptions: String, val root: Path)

    suspend fun run(manager: MavenProjectsManager, project: MavenProject, profiles: MavenExplicitProfiles,
                    properties: Map<String, String>, goal: String, repository: Path): MavenGoalOutput = slots.withPermit {
        withContext(Dispatchers.IO) {
            val distributions = MavenDistributionsCache.getInstance(manager.project)
            val distribution = distributions.getMavenDistribution(project.file)
            val root = distributions.getMultimoduleDirectory(project.file.parent.path)
            val connector = MavenServerManager.getInstance().getConnector(manager.project, root)
            val javaHome = connector.jdk.homePath?.let(Path::of)
                ?: throw IOException("Configure a local Maven import JDK in IntelliJ's Maven settings, then retry.")
            val runtime = Runtime(distribution.mavenHome, javaHome, connector.vmOptions, Path.of(root))
            val settings = readAction { manager.generalSettings.clone() }
            val id = project.mavenId
            execute(command(runtime, project.file.toNioPath(), "${id.groupId}:${id.artifactId}", settings, profiles, properties, goal, repository))
        }
    }

    private fun command(runtime: Runtime, pom: Path, selectedProject: String, settings: MavenGeneralSettings, profiles: MavenExplicitProfiles,
                         properties: Map<String, String>, goal: String, repository: Path): GeneralCommandLine {
        if (!settings.isUseMavenConfig && Files.isRegularFile(runtime.root.resolve(".mvn/maven.config")))
            throw MavenGoalConfigurationFailure("Maven goal checks cannot ignore an existing .mvn/maven.config. " +
                "Review that file and IntelliJ's Maven config setting, then retry.")
        val java = runtime.javaHome.resolve("bin/${if (SystemInfo.isWindows) "java.exe" else "java"}")
        val boot = runtime.mavenHome.resolve("boot")
        val launcher = if (Files.isDirectory(boot)) Files.list(boot).use { files ->
            files.filter { it.fileName.toString().let { name -> name.contains("classworlds") && name.endsWith(".jar") } }
                .toList().singleOrNull()
        } else null
        val configuration = runtime.mavenHome.resolve("bin/m2.conf")
        if (!Files.isRegularFile(java) || launcher == null || !Files.isRegularFile(configuration))
            throw IOException("Maven checks require a local Maven distribution and import JDK. Check IntelliJ's Maven settings, then retry.")
        val line = GeneralCommandLine(java.toString()).withWorkingDirectory(pom.parent).withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        line.addParameters(ParametersListUtil.parse(runtime.vmOptions))
        line.addParameters("-Dmaven.home=${runtime.mavenHome}", "-Dclassworlds.conf=$configuration",
            "-Dmaven.multiModuleProjectDirectory=${runtime.root}", "-Djansi.passthrough=true",
            "-Dstyle.color=never", "-classpath", launcher.toString(), "org.codehaus.plexus.classworlds.launcher.Launcher")
        line.addParameters("--batch-mode", "--non-recursive", "--file", pom.toString(), "--projects", selectedProject)
        if (settings.isWorkOffline) line.addParameter("--offline")
        if (settings.isAlwaysUpdateSnapshots) line.addParameter("--update-snapshots")
        settings.checksumPolicy.commandLineOption?.takeIf { it.isNotBlank() }?.let(line::addParameter)
        settings.userSettingsFile.takeIf { it.isNotBlank() }?.let { line.addParameters("--settings", it) }
        val selectedProfiles = profiles.enabledProfiles.sorted() + profiles.disabledProfiles.sorted().map { "!$it" }
        if (selectedProfiles.isNotEmpty()) line.addParameters("--activate-profiles", selectedProfiles.joinToString(","))
        properties.toSortedMap().forEach { (name, value) -> line.addParameter("-D$name=$value") }
        // The helper was installed in the repository captured from IDEA's effective settings.
        line.addParameter("-Dmaven.repo.local=$repository")
        line.addParameter(goal)
        return line
    }

    /** Keep both output streams bounded. Cancellation waits for process cleanup before deleting request files. */
    internal suspend fun execute(command: GeneralCommandLine, timeoutMillis: Long = 300_000,
                                 maxOutputChars: Int = 1_048_576): MavenGoalOutput = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMillis) {
            currentCoroutineContext().ensureActive()
            val process = try { command.createProcess() }
            catch (_: com.intellij.execution.ExecutionException) {
                // Native launch exceptions can include the complete command and user properties.
                throw IOException("Could not start Maven. Check IntelliJ's Maven distribution and import JDK, then retry.")
            }
            val handler = OSProcessHandler(process, "Maven version check", command.charset)
            try {
                suspendCancellableCoroutine { continuation ->
                    handler.addProcessListener(object : ProcessListener {
                        private val text = StringBuilder()
                        private var overflow = false
                        @Synchronized override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            if (overflow) return
                            val remaining = maxOutputChars - text.length
                            text.append(event.text.take(remaining))
                            if (event.text.length > remaining) {
                                overflow = true
                                if (continuation.isActive) continuation.resumeWithException(
                                    IOException("Maven output exceeded the diagnostic limit. Check Maven's output, then retry."))
                            }
                        }
                        @Synchronized override fun processTerminated(event: ProcessEvent) {
                            if (!continuation.isActive) return
                            if (overflow) continuation.resumeWithException(IOException("Maven output exceeded the diagnostic limit. Check Maven's output, then retry."))
                            else continuation.resume(MavenGoalOutput(event.exitCode, text.toString()))
                        }
                    })
                    handler.startNotify()
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    val descendants = process.descendants().use { it.toList() }
                    if (process.isAlive || descendants.any { it.isAlive }) {
                        descendants.forEach { it.destroy() }
                        process.destroy()
                        handler.waitFor(500)
                        descendants.forEach { if (it.isAlive) it.destroyForcibly() }
                        if (process.isAlive) process.destroyForcibly()
                        handler.waitFor(2_000)
                    }
                }
            }
        } ?: throw IOException("Maven version check timed out. Check Maven repository access, then retry.")
    }
}

internal class MavenGoalConfigurationFailure(override val recoveryMessage: String) : IOException(recoveryMessage), VersionCheckFailureAdvice
