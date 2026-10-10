package io.github.khopland.versionchecker.gradle

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    internal data class Request(val id: String, val group: String, val name: String, val current: String, val source: String? = null)
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
                Request("$index:${it.id.location}", it.artifact.namespace, it.artifact.name, it.baseline, snapshot.sourceFile)
            }
        }.toMap()
        val requests = requestsBySnapshot.values.flatten()
        if (requests.isEmpty()) return@withContext snapshots.associateWith { emptyMap() }
        val setupStarted = System.nanoTime()
        val directory = Files.createTempDirectory("gradle-version-checker-")
        val task = "versionChecker" + directory.fileName.toString().filter(Char::isLetterOrDigit)
        val requestFile = directory.resolve("requests.json")
        val output = directory.resolve("result.json")
        val init = directory.resolve("check.gradle")
        val timings = if (CheckPerformance.enabled() || observeWork != null) directory.resolve("timings.json") else null
        try {
            Files.writeString(requestFile, gson.toJson(requests))
            Files.writeString(init, script(requestFile, output, first.sourceFile, mode, task, timings, indexed))
            val settings = GradleExecutionSettings(ExternalSystemApiUtil.getExecutionSettings<GradleExecutionSettings>(project, first.context.root, GradleConstants.SYSTEM_ID))
            settings.withArguments("--init-script", init.toString(), "--no-configuration-cache", "--no-configure-on-demand", "--refresh-dependencies")
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
            val results: Map<String, Result> = gson.fromJson(Files.readString(output), object : TypeToken<Map<String, Result>>() {}.type)
            requestsBySnapshot.mapValues { (_, requested) -> requested.associate { request ->
                request.id.substringAfter(':') to checkNotNull(results[request.id]) { "Gradle omitted a requested declaration" }
            } }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    /** Resolve metadata only: never execute a build task or resolve artifact files. */
    fun script(requests: Path, output: Path, source: String, mode: UpdateMode, task: String, timings: Path? = null,
               indexed: Boolean = true): String {
        fun literal(value: String) = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
        return """
            import groovy.json.JsonSlurper
            import groovy.json.JsonOutput
            import org.gradle.api.artifacts.result.ResolvedDependencyResult
            import org.gradle.api.artifacts.result.UnresolvedDependencyResult
            import org.gradle.api.artifacts.component.ModuleComponentIdentifier
            ${if (timings != null) "def versionCheckerInitStarted = System.nanoTime()" else ""}
            gradle.projectsEvaluated {
                if (gradle.parent != null) return
                def requests = new JsonSlurper().parse(new File(${literal(requests.toString())}))
                def source = new File(${literal(source)}).canonicalFile
                gradle.rootProject.tasks.register(${literal(task)}) {
                    doLast {
                        def work = [ownerProjects: 0, ownerConfigurations: 0, semanticProbes: 0]
                        ${if (timings != null) "def versionCheckerQueryStarted = System.nanoTime(); try {" else ""}
                        def report = [:]
                        def coordinate = { d -> [d.group, d.name, d.version] }
                        def requested = requests.collect { [it.group, it.name, it.current] }.toSet()
                        def sources = requests.collect { new File(it.source ?: source.path).canonicalFile }.toSet()
                        def ownersByCoordinate = [:].withDefault { new LinkedHashSet() }
                        def originalsByOwner = [:].withDefault { [] }
                        def contextsByOwner = [:].withDefault { new LinkedHashSet() }
                        if (${indexed}) {
                            // Index only requested coordinates. Each source owner/configuration is visited once;
                            // inheritance, attributes and owner identity remain part of every semantic probe.
                            gradle.rootProject.allprojects.each { p ->
                                work.ownerProjects++
                                if (!sources.any { it.name.endsWith('.toml') || p.buildFile.canonicalFile == it }) return
                                p.configurations.each { c ->
                                    work.ownerConfigurations++
                                    c.dependencies.each { d ->
                                        def key = coordinate(d)
                                        if (requested.contains(key)) {
                                            ownersByCoordinate[key].add(p)
                                            if (d instanceof org.gradle.api.artifacts.ModuleDependency)
                                                originalsByOwner[[p.path, key]].add(d)
                                        }
                                    }
                                    c.dependencyConstraints.each { d ->
                                        def key = coordinate(d)
                                        if (requested.contains(key)) ownersByCoordinate[key].add(p)
                                    }
                                    if (c.canBeResolved) c.allDependencies.each { d ->
                                        def key = coordinate(d)
                                        if (requested.contains(key)) contextsByOwner[[p.path, key]].add(c)
                                    }
                                }
                            }
                        }
                        def checkedCoordinates = [:]
                        requests.each { request ->
                            def requestKey = [request.group, request.name, request.current]
                            def requestSource = new File(request.source ?: source.path).canonicalFile
                            def checkedKey = [requestSource, requestKey]
                            if (${indexed} && checkedCoordinates.containsKey(checkedKey)) {
                                report[request.id] = checkedCoordinates[checkedKey]
                                return
                            }
                            def owners = ${if (indexed) "ownersByCoordinate[requestKey].findAll { p -> requestSource.name.endsWith('.toml') || p.buildFile.canonicalFile == requestSource }" else """gradle.rootProject.allprojects.findAll { p ->
                                work.ownerProjects++
                                (requestSource.name.endsWith('.toml') || p.buildFile.canonicalFile == requestSource) &&
                                p.configurations.any { c ->
                                    work.ownerConfigurations++
                                    c.dependencies.any { d -> d.group == request.group && d.name == request.name && d.version == request.current } ||
                                    c.dependencyConstraints.any { d -> d.group == request.group && d.name == request.name && d.version == request.current }
                                }
                            }"""}
                            def reason = null
                            def versions = owners.collect { p ->
                                work.semanticProbes++
                                def matches = { d -> d.group == request.group && d.name == request.name && d.version == request.current }
                                // Keep dependency attributes, especially the category of platform/BOM calls.
                                def originals = ${if (indexed) "originalsByOwner[[p.path, requestKey]]" else """p.configurations.collectMany { c -> c.dependencies.findAll { d ->
                                    matches(d) &&
                                    d instanceof org.gradle.api.artifacts.ModuleDependency
                                }.toList() }"""}
                                // Catalog consumers can request features without a literal wrapper in this file.
                                if (originals.any { original -> !original.requestedCapabilities.empty ||
                                    (original.hasProperty('capabilitySelectors') && !original.capabilitySelectors.empty) }) {
                                    reason = 'Dependency capabilities or features need manual review for ' + request.group + ':' + request.name
                                    return null
                                }
                                def contexts = ${if (indexed) "contextsByOwner[[p.path, requestKey]]" else "p.configurations.findAll { c -> c.canBeResolved && c.allDependencies.any(matches) }"}
                                if (contexts.empty) {
                                    reason = 'No resolvable source configuration for ' + request.group + ':' + request.name
                                    return null
                                }
                                // A copy retains the source resolution strategy, including version-specific substitutions.
                                // Read only the resolution graph; do not resolve artifact files or execute build tasks.
                                for (context in contexts) {
                                    def probe = context.copyRecursive(matches)
                                    probe.transitive = false
                                    def originalResults = probe.incoming.resolutionResult.root.dependencies
                                    for (originalResult in originalResults) {
                                        if (originalResult instanceof UnresolvedDependencyResult)
                                            throw new GradleException('Could not check ' + request.group + ':' + request.name + ' in ' + p.path, originalResult.failure)
                                        if (!(originalResult instanceof ResolvedDependencyResult) ||
                                            !(originalResult.selected.id instanceof ModuleComponentIdentifier) ||
                                            originalResult.selected.moduleVersion.group != request.group ||
                                            originalResult.selected.moduleVersion.name != request.name ||
                                            originalResult.selected.selectionReason.selectedByRule) {
                                            reason = 'Dependency substitution needs manual review for ' + request.group + ':' + request.name
                                            return null
                                        }
                                    }
                                }
                                def attributes = [:]
                                originals.each { original -> original.attributes.keySet().each { key ->
                                    def value = original.attributes.getAttribute(key)
                                    if (attributes.containsKey(key) && attributes[key] != value)
                                        throw new GradleException('Conflicting dependency attributes need review for ' + request.group + ':' + request.name)
                                    attributes[key] = value
                                } }
                                def configuration = { version ->
                                    def dep = p.dependencies.create(request.group + ':' + request.name + ':' + version)
                                    dep.attributes { target -> attributes.each { key, value -> target.attribute(key, value) } }
                                    def probe = p.configurations.detachedConfiguration(dep)
                                    probe.transitive = false
                                    probe
                                }
                                def numbers = { s -> (s =~ /^[0-9]+(?:\.[0-9]+)*/).with { it.find(); it.group().tokenize('.').collect { n -> new BigInteger(n) } } }
                                def old = numbers(request.current)
                                def qualifier = { s ->
                                    def match = s =~ ${literal(GradleVersions.SUFFIX)}
                                    match.find() ? match.group(1).toLowerCase(Locale.ROOT) : ''
                                }
                                def channel = { s -> def q = qualifier(s); q in ['jre', 'android'] ? q : '' }
                                def servicePacks = qualifier(request.current).startsWith('sp')
                                def supported = []
                                def conf = configuration('+')
                                conf.resolutionStrategy.componentSelection.all { selection ->
                                    def v = selection.candidate.version
                                    def stable = v ==~ ${literal(GradleVersions.STABLE)}
                                    def next = stable ? numbers(v) : []
                                    if (!stable || channel(v) != channel(request.current) ||
                                        (${literal(mode.name)} != 'MAJOR' && old[0] != next[0]) ||
                                        (${literal(mode.name)} == 'PATCH' && (old.size() > 1 ? old[1] : BigInteger.ZERO) != (next.size() > 1 ? next[1] : BigInteger.ZERO))) {
                                        selection.reject('Outside stable update scope')
                                    } else if (servicePacks) {
                                        // Gradle orders mixed-case qualifiers differently. Ask its
                                        // repository resolver for candidates, then order supported
                                        // service packs numerically and resolve the chosen version.
                                        supported.add(v)
                                        selection.reject('Collecting supported service-pack versions')
                                    }
                                }
                                def result = conf.incoming.resolutionResult.root.dependencies.find()
                                if (servicePacks && !supported.empty) {
                                    def compare = { a, b ->
                                        def left = numbers(a); def right = numbers(b)
                                        for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
                                            def c = (i < left.size() ? left[i] : BigInteger.ZERO) <=> (i < right.size() ? right[i] : BigInteger.ZERO)
                                            if (c != 0) return c
                                        }
                                        def lq = qualifier(a); def rq = qualifier(b)
                                        def lp = lq.startsWith('sp'); def rp = rq.startsWith('sp')
                                        if (lp != rp) return lp ? 1 : -1
                                        if (lp) {
                                            def ln = new BigInteger(lq.substring(2) ?: '0'); def rn = new BigInteger(rq.substring(2) ?: '0')
                                            def c = ln <=> rn
                                            if (c != 0) return c
                                        }
                                        // Prefer the declared spelling for equivalent versions.
                                        (a == request.current ? 1 : 0) <=> (b == request.current ? 1 : 0)
                                    }
                                    def chosen = supported.sort(compare).last()
                                    result = configuration(chosen).incoming.resolutionResult.root.dependencies.find()
                                    if (result instanceof ResolvedDependencyResult && result.selected.moduleVersion?.version != chosen)
                                        throw new GradleException('Version replacement needs manual review for ' + request.group + ':' + request.name)
                                }
                                if (result instanceof UnresolvedDependencyResult) throw new GradleException('Could not check ' + request.group + ':' + request.name + ' in ' + p.path, result.failure)
                                if (!(result instanceof ResolvedDependencyResult) || result.selected.moduleVersion == null ||
                                    result.selected.moduleVersion.group != request.group || result.selected.moduleVersion.name != request.name)
                                    throw new GradleException('Dependency substitution needs manual review for ' + request.group + ':' + request.name)
                                result.selected.moduleVersion.version
                            }
                            def reportResult = [versions: reason == null ? versions : [], reason: reason]
                            report[request.id] = reportResult
                            if (${indexed}) checkedCoordinates[checkedKey] = reportResult
                        }
                        new File(${literal(output.toString())}).text = JsonOutput.toJson(report)
                        ${if (timings != null) "} finally { try { new File(${literal(timings.toString())}).text = JsonOutput.toJson(work + [configurationNs: versionCheckerQueryStarted - versionCheckerInitStarted, queryNs: System.nanoTime() - versionCheckerQueryStarted]) } catch (Exception ignoredTimingFailure) {} }" else ""}
                    }
                }
            }
        """.trimIndent()
    }
}
