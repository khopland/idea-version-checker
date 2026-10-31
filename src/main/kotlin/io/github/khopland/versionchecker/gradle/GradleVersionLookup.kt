package io.github.khopland.versionchecker.gradle

import com.google.gson.Gson
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.project.Project
import io.github.khopland.versionchecker.CheckPerformance
import io.github.khopland.versionchecker.UpdateMode
import io.github.khopland.versionchecker.core.BuildSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import org.jetbrains.plugins.gradle.service.task.GradleTaskManager
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

internal object GradleVersionLookup {
    private val gson = Gson()
    internal data class Request(val id: String, val group: String, val name: String, val current: String, val source: String? = null,
                                val plugin: Boolean = false)
    internal data class Result(val versions: List<String> = emptyList(), val reason: String? = null)
    internal data class Checked(val results: Map<String, Result>, val checkedAtNanos: Long) {
        val expiresAtNanos: Long get() = checkedAtNanos + TimeUnit.MINUTES.toNanos(10)
    }
    internal data class Timings(val configurationNs: Long, val queryNs: Long,
                                val ownerProjects: Int = 0, val ownerConfigurations: Int = 0, val semanticProbes: Int = 0)

    suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): Checked {
        currentCoroutineContext()[GradleScanSession]?.check(snapshot, mode)?.let { return it }
        val started = System.nanoTime()
        return Checked(checkWithIndex(project, snapshot, mode, indexed = true), started)
    }

    internal suspend fun checkBatch(project: Project, snapshots: List<BuildSnapshot>, mode: UpdateMode): Map<BuildSnapshot, Map<String, Result>> =
        checkBatchWithIndex(project, snapshots, mode, indexed = true)

    internal suspend fun checkWithIndex(project: Project, snapshot: BuildSnapshot, mode: UpdateMode, indexed: Boolean,
                                       observeWork: ((Timings) -> Unit)? = null): Map<String, Result> =
        checkBatchWithIndex(project, listOf(snapshot), mode, indexed, observeWork).getValue(snapshot)

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    internal suspend fun checkBatchWithIndex(project: Project, snapshots: List<BuildSnapshot>, mode: UpdateMode, indexed: Boolean,
                                            observeWork: ((Timings) -> Unit)? = null): Map<BuildSnapshot, Map<String, Result>> = withContext(Dispatchers.IO) {
        if (snapshots.isEmpty()) return@withContext emptyMap()
        val first = snapshots.first()
        require(snapshots.all { it.context.root == first.context.root && it.fingerprint == first.fingerprint }) {
            "Gradle batches must have the same linked build and resolution inputs"
        }
        val requestsBySnapshot = snapshots.mapIndexed { index, snapshot -> snapshot to
            snapshot.declarations.filter { it.baseline.isNotEmpty() }.map {
                if (GradleDeclarations.isPlugin(it)) Request("$index:${it.id.location}", it.artifact.name,
                    "${it.artifact.name}.gradle.plugin", it.baseline, snapshot.sourceFile, plugin = true)
                else Request("$index:${it.id.location}", it.artifact.namespace, it.artifact.name, it.baseline, snapshot.sourceFile)
            }
        }.toMap()
        val requests = requestsBySnapshot.values.flatten()
        if (requests.isEmpty()) return@withContext snapshots.associateWith { emptyMap() }
        val setupStarted = System.nanoTime()
        val directory = Files.createTempDirectory("gradle-version-checker-")
        val task = "versionChecker" + directory.fileName.toString().filter(Char::isLetterOrDigit)
        val configurationFile = directory.resolve("configuration.json")
        val output = directory.resolve("result.json")
        val init = directory.resolve("check.gradle")
        val timings = if (CheckPerformance.enabled() || observeWork != null) directory.resolve("timings.json") else null
        try {
            Files.writeString(configurationFile, configuration(requests, output, first.sourceFile, mode, task, timings, indexed))
            Files.writeString(init, script())
            val settings = GradleExecutionSettings(ExternalSystemApiUtil.getExecutionSettings<GradleExecutionSettings>(project, first.context.root, GradleConstants.SYSTEM_ID))
            settings.withArguments("-D$CONFIGURATION_PROPERTY=$configurationFile", "--init-script", init.toString(), "--no-configuration-cache", "--no-configure-on-demand", "--refresh-dependencies")
            settings.setTasks(listOf(":$task"))
            val id = ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, project)
            val manager = GradleTaskManager()
            val context = currentCoroutineContext()
            val interaction = CheckPerformance.current()
            val listener = object : ExternalSystemTaskNotificationListener {
                override fun onStart(workingDir: String, id: ExternalSystemTaskId) {
                    if (!context.isActive) manager.cancelTask(id, this)
                }
            }
            val handle = context[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) manager.cancelTask(id, listener) }
            val nativeStarted = System.nanoTime()
            try {
                context.ensureActive()
                // Invoke IntelliJ's native task manager without creating a run configuration or console.
                CheckPerformance.record(CheckPerformance.Stage.GRADLE_SETUP, setupStarted)
                CheckPerformance.measure(CheckPerformance.Stage.GRADLE_NATIVE, requests.size) {
                    manager.executeTasks(first.context.root, id, settings, listener)
                }
            } finally {
                handle?.dispose()
                val finished = System.nanoTime()
                if (timings != null && Files.exists(timings)) {
                    val measured = try { gson.fromJson(Files.readString(timings), Timings::class.java) }
                        catch (_: java.io.IOException) { null }
                        catch (_: com.google.gson.JsonParseException) { null }
                    // Durations are measured in the Gradle JVM. Place them at the end of the host
                    // invocation; JVM nanoTime origins and buffered console delivery are not comparable.
                    if (measured != null && measured.configurationNs >= 0 && measured.queryNs >= 0 &&
                        measured.configurationNs <= finished - nativeStarted - measured.queryNs) {
                        observeWork?.invoke(measured)
                        val queryStarted = finished - measured.queryNs
                        CheckPerformance.interval(CheckPerformance.Stage.GRADLE_QUERY, queryStarted, finished, requests.size, interaction)
                        CheckPerformance.interval(CheckPerformance.Stage.GRADLE_CONFIGURATION,
                            queryStarted - measured.configurationNs, queryStarted, interaction = interaction)
                        CheckPerformance.interval(CheckPerformance.Stage.GRADLE_OWNER_PROJECTS, finished, finished, measured.ownerProjects, interaction)
                        CheckPerformance.interval(CheckPerformance.Stage.GRADLE_OWNER_CONFIGURATIONS, finished, finished, measured.ownerConfigurations, interaction)
                        CheckPerformance.interval(CheckPerformance.Stage.GRADLE_SEMANTIC_PROBES, finished, finished, measured.semanticProbes, interaction)
                    }
                }
            }
            check(Files.exists(output)) { "Gradle did not produce a version report" }
            val results = parseResponse(Files.readString(output), requests.map { it.id })
            requestsBySnapshot.mapValues { (_, requested) -> requested.associate { request ->
                request.id.substringAfter(':') to checkNotNull(results[request.id]) { "Gradle omitted a requested declaration" }
            } }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    internal const val PROTOCOL_VERSION = 1
    internal const val CONFIGURATION_PROPERTY = "io.github.khopland.versionchecker.probeConfiguration"

    internal data class Configuration(
        val protocolVersion: Int = PROTOCOL_VERSION,
        val requests: List<Request>, val output: String, val source: String,
        val mode: String, val task: String, val timings: String?, val indexed: Boolean,
        val stablePattern: String = GradleVersions.STABLE,
        val suffixPattern: String = GradleVersions.SUFFIX
    )
    private data class Response(val protocolVersion: Int, val results: Map<String, Result>?)

    internal fun configuration(requests: List<Request>, output: Path, source: String, mode: UpdateMode,
                               task: String, timings: Path? = null, indexed: Boolean = true): String =
        gson.toJson(Configuration(requests = requests, output = output.toString(), source = source,
            mode = mode.name, task = task, timings = timings?.toString(), indexed = indexed))

    internal fun parseResponse(json: String, requestedIds: List<String> = emptyList()): Map<String, Result> {
        val response = checkNotNull(gson.fromJson(json, Response::class.java)) { "Gradle produced an empty version report" }
        check(response.protocolVersion == PROTOCOL_VERSION) { "Unsupported Gradle version report protocol: ${response.protocolVersion}" }
        val results = checkNotNull(response.results) { "Gradle version report omitted results" }
        check(requestedIds.all { results[it] != null }) { "Gradle omitted a requested declaration" }
        return results
    }

    /** Resolve metadata only: never execute a build task or resolve artifact files. */
    internal fun script(): String = checkNotNull(GradleVersionLookup::class.java.getResourceAsStream("/gradle/version-checker.gradle")) {
        "Missing packaged Gradle version checker script"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
