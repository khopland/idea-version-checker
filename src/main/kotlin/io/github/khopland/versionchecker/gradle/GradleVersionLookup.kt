package io.github.khopland.versionchecker.gradle

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.project.Project
import io.github.khopland.versionchecker.UpdateMode
import io.github.khopland.versionchecker.core.BuildSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Files
import java.nio.file.Path
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
    internal data class Request(val id: String, val group: String, val name: String, val current: String)
    internal data class Result(val versions: List<String> = emptyList(), val reason: String? = null)

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): Map<String, Result> = withContext(Dispatchers.IO) {
        val requests = snapshot.declarations.filter { it.baseline.isNotEmpty() }.map { Request(it.id.location, it.artifact.namespace, it.artifact.name, it.baseline) }
        if (requests.isEmpty()) return@withContext emptyMap()
        val directory = Files.createTempDirectory("gradle-version-checker-")
        val task = "versionChecker" + directory.fileName.toString().filter(Char::isLetterOrDigit)
        val requestFile = directory.resolve("requests.json")
        val output = directory.resolve("result.json")
        val init = directory.resolve("check.gradle")
        try {
            Files.writeString(requestFile, gson.toJson(requests))
            Files.writeString(init, script(requestFile, output, snapshot.sourceFile, mode, task))
            val settings = GradleExecutionSettings(ExternalSystemApiUtil.getExecutionSettings<GradleExecutionSettings>(project, snapshot.context.root, GradleConstants.SYSTEM_ID))
            settings.withArguments("--init-script", init.toString(), "--no-configuration-cache", "--no-configure-on-demand", "--refresh-dependencies")
            settings.setTasks(listOf(":$task"))
            val id = ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, project)
            val manager = GradleTaskManager()
            val context = currentCoroutineContext()
            val listener = object : ExternalSystemTaskNotificationListener {
                override fun onStart(workingDir: String, id: ExternalSystemTaskId) {
                    if (!context.isActive) manager.cancelTask(id, this)
                }
            }
            val handle = context[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) manager.cancelTask(id, listener) }
            try {
                context.ensureActive()
                // Invoke IntelliJ's native task manager without creating a run configuration or console.
                manager.executeTasks(snapshot.context.root, id, settings, listener)
            } finally { handle?.dispose() }
            check(Files.exists(output)) { "Gradle did not produce a version report" }
            gson.fromJson(Files.readString(output), object : TypeToken<Map<String, Result>>() {}.type)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    /** Resolve metadata only: never execute a build task or resolve artifact files. */
    fun script(requests: Path, output: Path, source: String, mode: UpdateMode, task: String): String {
        fun literal(value: String) = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
        return """
            import groovy.json.JsonSlurper
            import groovy.json.JsonOutput
            import org.gradle.api.artifacts.result.ResolvedDependencyResult
            import org.gradle.api.artifacts.result.UnresolvedDependencyResult
            import org.gradle.api.artifacts.component.ModuleComponentIdentifier
            gradle.projectsEvaluated {
                if (gradle.parent != null) return
                def requests = new JsonSlurper().parse(new File(${literal(requests.toString())}))
                def source = new File(${literal(source)}).canonicalFile
                gradle.rootProject.tasks.register(${literal(task)}) {
                    doLast {
                        def report = [:]
                        requests.each { request ->
                            def owners = gradle.rootProject.allprojects.findAll { p ->
                                (source.name.endsWith('.toml') || p.buildFile.canonicalFile == source) &&
                                p.configurations.any { c ->
                                    c.dependencies.any { d -> d.group == request.group && d.name == request.name && d.version == request.current } ||
                                    c.dependencyConstraints.any { d -> d.group == request.group && d.name == request.name && d.version == request.current }
                                }
                            }
                            def reason = null
                            def versions = owners.collect { p ->
                                def matches = { d -> d.group == request.group && d.name == request.name && d.version == request.current }
                                // Keep dependency attributes, especially the category of platform/BOM calls.
                                def originals = p.configurations.collectMany { c -> c.dependencies.findAll { d ->
                                    matches(d) &&
                                    d instanceof org.gradle.api.artifacts.ModuleDependency
                                }.toList() }
                                // Catalog consumers can request features without a literal wrapper in this file.
                                if (originals.any { original -> !original.requestedCapabilities.empty ||
                                    (original.hasProperty('capabilitySelectors') && !original.capabilitySelectors.empty) }) {
                                    reason = 'Dependency capabilities or features need manual review for ' + request.group + ':' + request.name
                                    return null
                                }
                                def contexts = p.configurations.findAll { c -> c.canBeResolved && c.allDependencies.any(matches) }
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
                            report[request.id] = [versions: reason == null ? versions : [], reason: reason]
                        }
                        new File(${literal(output.toString())}).text = JsonOutput.toJson(report)
                    }
                }
            }
        """.trimIndent()
    }
}
