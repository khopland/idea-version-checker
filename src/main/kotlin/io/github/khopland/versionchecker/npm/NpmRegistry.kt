package io.github.khopland.versionchecker.npm

import com.google.gson.JsonParser
import com.google.gson.JsonParseException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.*
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.NodeCommandLineUtil
import com.intellij.javascript.nodejs.npm.NpmManager
import com.intellij.javascript.nodejs.npm.NpmUtil
import com.intellij.javascript.nodejs.util.NodePackage
import com.intellij.lang.javascript.buildTools.npm.rc.NpmCommand
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import io.github.khopland.versionchecker.UpdateMode
import io.github.khopland.versionchecker.CheckPerformance
import io.github.khopland.versionchecker.core.VersionCheckFailureAdvice
import kotlinx.coroutines.*
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.resume

/** A null deprecation map means the older versions-only query needs per-version lookups. */
internal data class NpmPackageMetadata(
    val versions: List<String>,
    val deprecatedByVersion: Map<String, String>? = null
) {
    val history: NpmVersionHistory by lazy {
        CheckPerformance.measure(CheckPerformance.Stage.NPM_VERSION_INDEX, versions.size) { NpmVersionHistory(versions) }
    }
}

internal data class NpmRuntime(val interpreter: NodeJsLocalInterpreter, val npm: NodePackage,
                               val manager: NodePackageManager = NodePackageManager.NPM,
                               val modernYarn: Boolean = false)

/** Read-only native metadata commands retain each manager’s registry and authentication settings. */
internal object NpmRegistry {
    private const val MAX_STDOUT_CHARS = 16 * 1024 * 1024
    suspend fun resolve(project: Project, directory: Path, manager: NodePackageManager = NodePackageManager.NPM): NpmRuntime = withContext(Dispatchers.IO) {
        CheckPerformance.measure(CheckPerformance.Stage.NPM_RUNTIME_RESOLUTION) {
            val (interpreter, configured) = readAction {
                val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
                    ?: throw NpmRuntimeFailure("Configure a local Node.js interpreter and ${manager.title} in IntelliJ's JavaScript runtime settings, then retry.")
                if (interpreter !is NodeJsLocalInterpreter)
                    throw NpmRuntimeFailure("${manager.title} checks require a local Node.js interpreter. Choose one in IntelliJ's JavaScript runtime settings, then retry.")
                val configured = NpmManager.getInstance(project).getPackageOrThrow(interpreter)
                val matches = when (manager) {
                    NodePackageManager.NPM -> NpmManager.getNpmPackagePresentableName(configured) == "npm"
                    NodePackageManager.PNPM -> NpmUtil.isPnpmPackage(configured)
                    NodePackageManager.YARN -> NpmUtil.isYarnAlikePackage(configured)
                    NodePackageManager.BUN -> NpmUtil.isBunPackage(configured)
                }
                if (!matches) throw NpmRuntimeFailure("Choose ${manager.title} as IntelliJ's configured package manager, then retry.")
                interpreter to configured
            }
            val npm = if (manager == NodePackageManager.NPM) packageDirectory(configured) ?: bundledPackage(interpreter, directory) ?: configured
                else configured
            val runtime = NpmRuntime(interpreter, npm, manager)
            if (manager != NodePackageManager.YARN) runtime else {
                val output = withTimeoutOrNull(10_000) { execute(nativeCommand(runtime, directory, listOf("--version"))) }
                val major = output?.takeIf { it.exitCode == 0 }?.stdout?.trim()?.substringBefore('.')?.toIntOrNull()
                    ?: throw NpmRuntimeFailure("Could not determine the configured Yarn version. Check IntelliJ's package-manager settings.")
                runtime.copy(modernYarn = major >= 2)
            }
        }
    }

    suspend fun command(project: Project, directory: Path, parameters: List<String>,
                        manager: NodePackageManager = NodePackageManager.NPM): GeneralCommandLine =
        command(resolve(project, directory, manager), directory, parameters)

    private fun command(runtime: NpmRuntime, directory: Path, parameters: List<String>): GeneralCommandLine =
        if (runtime.manager != NodePackageManager.NPM) nativeCommand(runtime, directory,
            arguments(runtime.manager, parameters, runtime.modernYarn)) else
            NpmUtil.createNpmCommandLine(directory, runtime.interpreter, runtime.npm, NpmCommand.VIEW,
                parameters + listOf("--json", "--loglevel=error", "--update-notifier=false", "--fetch-retries=0", "--workspaces=false", "--prefer-online"))
                .withCharset(StandardCharsets.UTF_8)

    internal fun arguments(manager: NodePackageManager, parameters: List<String>, modernYarn: Boolean): List<String> {
        val fullDocument = parameters.size > 2 || parameters.getOrNull(1) == "deprecated"
        val fields = if (fullDocument) emptyList() else parameters.drop(1)
        return when (manager) {
            NodePackageManager.NPM -> listOf("view") + parameters + listOf("--json", "--loglevel=error", "--update-notifier=false", "--fetch-retries=0", "--workspaces=false", "--prefer-online")
            NodePackageManager.PNPM -> listOf("view", parameters.first()) + fields + "--json"
            NodePackageManager.YARN -> if (modernYarn) listOf("npm", "info", parameters.first(), "--fields",
                if (fullDocument) parameters.drop(1).plus(listOf("name", "version")).distinct().joinToString(",") else fields.joinToString(","), "--json")
                else listOf("info", parameters.first()) + fields + listOf("--json", "--non-interactive")
            NodePackageManager.BUN -> listOf("info", parameters.first()) + fields + "--json"
        }
    }

    private fun nativeCommand(runtime: NpmRuntime, directory: Path, arguments: List<String>): GeneralCommandLine {
        val line = GeneralCommandLine().withWorkingDirectory(directory).withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        NodeCommandLineUtil.configureUsefulEnvironment(line)
        val configured = runtime.npm.systemDependentPath
        val executable = if (Files.isRegularFile(Path.of(configured))) configured
            else NpmUtil.getValidNpmCliJsFilePath(runtime.npm, runtime.interpreter)
        if (listOf(".js", ".cjs", ".mjs").any(executable::endsWith)) {
            line.withExePath(runtime.interpreter.interpreterSystemDependentPath).addParameter(executable)
        } else line.withExePath(executable)
        NodeCommandLineUtil.prependNodeDirToPATH(line, runtime.interpreter)
        line.addParameters(arguments)
        line.withEnvironment("COREPACK_ENABLE_NETWORK", "0")
        line.withEnvironment("COREPACK_ENABLE_DOWNLOAD_PROMPT", "0")
        line.withEnvironment("NO_UPDATE_NOTIFIER", "1")
        if (runtime.manager == NodePackageManager.PNPM) {
            validatePnpmConfiguration(directory, line.effectiveEnvironment)
            for ((key, value) in mapOf("pm_on_fail" to "ignore", "runtime_on_fail" to "ignore")) {
                line.withEnvironment("pnpm_config_$key", value)
                line.withEnvironment("PNPM_CONFIG_${key.uppercase()}", value)
            }
            line.withEnvironment("npm_config_manage_package_manager_versions", "false")
            line.withEnvironment("NPM_CONFIG_MANAGE_PACKAGE_MANAGER_VERSIONS", "false")
        }
        return line
    }

    /** pnpm can install configuration packages before a view command; don't trigger that bootstrap. */
    internal fun validatePnpmConfiguration(directory: Path, environment: Map<String, String>) {
        val yamlFiles = listOf(directory.resolve("pnpm-workspace.yaml")) +
            NodePackageManager.pnpmGlobalConfig(environment).map { directory.resolve(it).normalize() }
        for (path in yamlFiles.filter { it.fileName.toString().endsWith(".yaml") && Files.isRegularFile(it) }) {
            val config = PnpmWorkspace.parse(Files.readString(path)) ?: error("Cannot read pnpm configuration for a manifest-only check")
            val dependencies = config["configDependencies"]
            check(dependencies == null) {
                "pnpm configDependencies can install packages during registry checks; review these dependencies manually"
            }
            check(config["useNodeVersion"] == null) { "pnpm useNodeVersion can install a runtime during registry checks; review this project manually" }
        }
        var ancestor: Path? = directory
        val rcFiles = NpmBuildInputs.userConfigurationPaths(environment, directory)
            .filter { it.fileName.toString() in setOf(".npmrc", "rc") }.toMutableList()
        while (ancestor != null) { rcFiles.add(ancestor.resolve(".npmrc")); ancestor = ancestor.parent }
        rcFiles.addAll(yamlFiles.filter { it.fileName.toString() == "rc" })
        val bootstrapSetting = Regex("(?im)^\\s*(?:use[-_]?node[-_]?version|config[-_]?dependencies)\\s*=\\s*[^\\s#;]")
        check(rcFiles.filter(Files::isRegularFile).none { bootstrapSetting.containsMatchIn(Files.readString(it)) } &&
            environment.none { (key, value) -> value.isNotBlank() && key.lowercase() in setOf("npm_config_use_node_version", "pnpm_config_use_node_version", "npm_config_config_dependencies", "pnpm_config_config_dependencies") }) {
            "pnpm configuration can install packages or runtimes during registry checks; review this project manually"
        }
    }

    /** Explicit user-invoked follow-up; registry checks never call this command. */
    suspend fun lockfileCommand(project: Project, directory: Path): GeneralCommandLine {
        return lockfileCommand(resolve(project, directory), directory)
    }

    internal fun lockfileCommand(runtime: NpmRuntime, directory: Path): GeneralCommandLine =
        NpmUtil.createNpmCommandLine(directory, runtime.interpreter, runtime.npm, NpmCommand.INSTALL,
            io.github.khopland.versionchecker.followup.NpmLockfileSync.arguments)
            .withCharset(StandardCharsets.UTF_8)

    /** Settings often name the npm executable (e.g. /opt/homebrew/bin/npm) instead of the npm package directory. */
    internal fun packageDirectory(npm: NodePackage): NodePackage? {
        val path = runCatching { Path.of(npm.systemDependentPath).toRealPath() }.getOrNull() ?: return null
        val root = if (Files.isDirectory(path)) path else path.parent?.takeIf { it.fileName?.toString() == "bin" }?.parent ?: return null
        return NodePackage(root).takeIf { isNpmPackage(root) }
    }
    /** Version-manager shims (mise, asdf, Volta) only reveal the real Node binary when run. */
    private suspend fun bundledPackage(interpreter: NodeJsLocalInterpreter, directory: Path): NodePackage? {
        val output = try {
            withTimeoutOrNull(10_000) {
                execute(GeneralCommandLine(interpreter.interpreterSystemDependentPath, "-p", "process.execPath")
                    .withWorkingDirectory(directory).withCharset(StandardCharsets.UTF_8))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }?.takeIf { it.exitCode == 0 } ?: return null
        return runCatching { Path.of(output.stdout.trim()).toRealPath() }.getOrNull()?.let(::bundledPackage)
    }
    internal fun bundledPackage(node: Path): NodePackage? =
        listOfNotNull(node.parent?.parent?.resolve("lib/node_modules/npm"), node.parent?.resolve("node_modules/npm"))
            .firstOrNull(::isNpmPackage)?.let(::NodePackage)
    private fun isNpmPackage(root: Path) = Files.isRegularFile(root.resolve("bin").resolve("npm-cli.js"))
    suspend fun metadata(project: Project, directory: Path, name: String, manager: NodePackageManager = NodePackageManager.NPM): NpmPackageMetadata {
        check(NpmSelector.validName(name)) { "Invalid npm package name" }
        return metadata(resolve(project, directory, manager), directory, name)
    }

    suspend fun metadata(runtime: NpmRuntime, directory: Path, name: String): NpmPackageMetadata {
        check(NpmSelector.validName(name)) { "Invalid npm package name" }
        return if (runtime.manager == NodePackageManager.NPM) loadMetadata(name) { view(runtime, directory, it) }
            else parseLegacyMetadata(view(runtime, directory, listOf(name, "versions")))
    }

    internal suspend fun loadMetadata(name: String, query: suspend (List<String>) -> String): NpmPackageMetadata {
        try {
            // An explicit range includes stable releases beyond the latest tag. Requesting name
            // alongside version keeps npm from simplifying the response into scalars.
            return parseMetadata(query(listOf("$name@>=0.0.0", "name", "version", "deprecated")), name)
        } catch (_: UnsupportedNpmMetadata) {
            // Older npm/registry responses may not provide complete per-version objects.
        } catch (failure: NpmViewFailure) {
            // A package with only prereleases has no match for the stable range. The old query
            // distinguishes that successful empty result from a missing/inaccessible package.
            if (failure.code !in setOf("E404", "ETARGET", "ENOVERSIONS")) throw failure
        }
        return parseLegacyMetadata(query(listOf(name, "versions")))
    }

    fun parseMetadata(json: String, name: String): NpmPackageMetadata {
        val root = try { JsonParser.parseString(json) } catch (_: JsonParseException) { throw UnsupportedNpmMetadata() }
        val entries = when {
            root.isJsonObject -> listOf(root)
            root.isJsonArray -> root.asJsonArray.toList()
            else -> throw UnsupportedNpmMetadata()
        }
        val versions = linkedSetOf<String>()
        val deprecations = mutableMapOf<String, String>()
        for (entry in entries) {
            if (!entry.isJsonObject) throw UnsupportedNpmMetadata()
            val item = entry.asJsonObject
            fun string(field: String): String? {
                val value = item.get(field) ?: return null
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw UnsupportedNpmMetadata()
                return value.asString
            }
            if (string("name") != name) throw UnsupportedNpmMetadata()
            val version = string("version") ?: throw UnsupportedNpmMetadata()
            if (NpmVersion.parse(version) == null || !versions.add(version)) throw UnsupportedNpmMetadata()
            string("deprecated")?.takeIf { it.isNotBlank() }?.let { deprecations[version] = it }
        }
        return NpmPackageMetadata(versions.toList(), deprecations.toMap())
    }

    fun parseLegacyMetadata(json: String): NpmPackageMetadata {
        val root = payload(json)
        val versions = if (root.isJsonObject) root.asJsonObject.get("versions") else root
        check(versions != null && (versions.isJsonArray || versions.isJsonPrimitive && versions.asJsonPrimitive.isString)) {
            "npm did not return package versions"
        }
        val values = if (versions.isJsonArray) versions.asJsonArray.map {
            check(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "npm returned malformed package versions" }
            it.asString
        } else listOf(versions.asString)
        return NpmPackageMetadata(values)
    }
    internal fun payload(text: String): com.google.gson.JsonElement {
        val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
            ?: text.lineSequence().mapNotNull { runCatching { JsonParser.parseString(it) }.getOrNull() }
                .firstOrNull { it.isJsonObject && it.asJsonObject.get("type")?.asString == "inspect" }
            ?: error("Package manager did not return valid registry JSON")
        if (parsed.isJsonObject && parsed.asJsonObject.get("type")?.asString == "inspect")
            return parsed.asJsonObject.get("data") ?: com.google.gson.JsonNull.INSTANCE
        return parsed
    }

    suspend fun deprecated(project: Project, directory: Path, name: String, version: String,
                           manager: NodePackageManager = NodePackageManager.NPM): String? {
        return deprecated(resolve(project, directory, manager), directory, name, version)
    }
    suspend fun deprecated(runtime: NpmRuntime, directory: Path, name: String, version: String): String? {
        val text = view(runtime, directory, listOf("$name@$version", "deprecated")).trim()
        if (text.isEmpty()) return null
        val root = payload(text)
        val value = if (root.isJsonObject) {
            val item = root.asJsonObject
            check(item.get("name")?.asString == name && item.get("version")?.asString == version) {
                "Package manager returned metadata for another package version"
            }
            item.get("deprecated") ?: return null
        } else root
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
    }
    /** One explicit selected-version lookup; this never enumerates or prepares candidate versions. */
    suspend fun targetDetails(project: Project, directory: Path, target: NpmTarget): NpmTargetDetails {
        check(NpmSelector.validName(target.packageName) && NpmVersion.parse(target.version) != null)
        return loadTargetDetails(target) { payload(view(resolve(project, directory, target.manager), directory, it)).toString() }
    }

    internal suspend fun loadTargetDetails(target: NpmTarget, query: suspend (List<String>) -> String): NpmTargetDetails =
        NpmTargetDetails.parse(query(listOf("${target.packageName}@${target.version}", "name", "version",
            "engines", "peerDependencies", "repository", "homepage")), target)

    fun eligible(metadata: NpmPackageMetadata, baseline: NpmVersion, mode: UpdateMode): List<String> {
        return metadata.history.eligible(baseline, mode).map { it.text }.toList()
    }
    private suspend fun view(runtime: NpmRuntime, directory: Path, parameters: List<String>): String =
        CheckPerformance.measure(CheckPerformance.Stage.NPM_VIEW) { runView(runtime, directory, parameters) }

    private suspend fun runView(runtime: NpmRuntime, directory: Path, parameters: List<String>): String = withContext(Dispatchers.IO) {
        val output = withTimeoutOrNull(60_000) {
            execute(CheckPerformance.measure(CheckPerformance.Stage.NPM_COMMAND_SETUP) {
                command(runtime, directory, parameters)
            })
        } ?: throw NpmViewFailure("ETIMEDOUT", "${runtime.manager.title} registry query timed out for ${parameters.first()}", runtime.manager)
        if (output.exitCode != 0) {
            // Do not copy stderr/configuration or credentials into a diagnostic or log.
            val code = runCatching { JsonParser.parseString(output.stdout).asJsonObject.getAsJsonObject("error")?.get("code")?.asString }
                .getOrNull()?.takeIf { Regex("[A-Z0-9_]+").matches(it) }
            val operation = if (runtime.manager == NodePackageManager.NPM) "npm view" else "${runtime.manager.title} metadata query"
            throw NpmViewFailure(code, "$operation failed for ${parameters.first()} (exit ${output.exitCode}${code?.let { "; $it" }.orEmpty()}). Check Node/${runtime.manager.title} settings and registry access.", runtime.manager)
        }
        output.stdout
    }

    /** Both shim probes and registry queries terminate their native process on cancellation. */
    internal suspend fun execute(command: GeneralCommandLine): ProcessOutput = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        suspendCancellableCoroutine { continuation ->
            val handler = OSProcessHandler(command)
            val result = ProcessOutput()
            handler.addProcessListener(object : ProcessListener {
                private var stdoutChars = 0
                private var overflow = false
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (outputType != ProcessOutputTypes.STDOUT) return
                    val exceeded = synchronized(this) {
                        if (overflow) false else {
                            val remaining = MAX_STDOUT_CHARS - stdoutChars
                            result.appendStdout(event.text.take(remaining))
                            stdoutChars += minOf(event.text.length, remaining)
                            (event.text.length > remaining).also { if (it) overflow = true }
                        }
                    }
                    if (exceeded) handler.destroyProcess()
                }
                override fun processTerminated(event: ProcessEvent) {
                    result.setExitCode(event.exitCode)
                    val exceeded = synchronized(this) { overflow }
                    if (continuation.isActive) {
                        if (exceeded) continuation.resumeWith(Result.failure(NpmViewFailure(null, "npm output exceeded 16M character limit")))
                        else continuation.resume(result)
                    }
                }
            })
            continuation.invokeOnCancellation { handler.destroyProcess() }
            handler.startNotify()
        }
    }

}

internal class UnsupportedNpmMetadata : IOException("npm did not return complete stable-version metadata")
internal class NpmRuntimeFailure(override val recoveryMessage: String) : IOException(recoveryMessage), VersionCheckFailureAdvice

internal class NpmViewFailure(val code: String?, message: String,
                             private val manager: NodePackageManager = NodePackageManager.NPM) : IOException(message), VersionCheckFailureAdvice {
    override val recoveryMessage: String get() {
        val title = manager.title
        val configuration = when (manager) {
            NodePackageManager.NPM -> ".npmrc"
            NodePackageManager.PNPM -> ".npmrc or pnpm-workspace.yaml"
            NodePackageManager.YARN -> ".yarnrc or .yarnrc.yml"
            NodePackageManager.BUN -> ".npmrc or bunfig.toml"
        }
        return when (code) {
            "E401", "E403", "ENEEDAUTH" -> "$title registry access was denied. Check the registry credentials and package permissions in $configuration, then retry."
            "ENOTCACHED" -> "$title could not find registry metadata in its offline cache. Check $title's offline configuration and registry access, then retry."
            "ETIMEDOUT", "ESOCKETTIMEDOUT", "ECONNRESET", "ECONNREFUSED", "ENOTFOUND", "EAI_AGAIN", "ENETUNREACH" ->
                "$title could not reach the registry. Check the network, registry URL and proxy configuration, then retry."
            "CERT_HAS_EXPIRED", "DEPTH_ZERO_SELF_SIGNED_CERT", "SELF_SIGNED_CERT_IN_CHAIN", "UNABLE_TO_VERIFY_LEAF_SIGNATURE" ->
                "$title could not verify the registry certificate. Check the registry certificate and $title's trusted certificate configuration, then retry."
            "E404", "ETARGET", "ENOVERSIONS" -> "$title could not read package versions. Check the package name, scoped registry and package access, then retry."
            else -> "$title could not check package versions. Check IntelliJ's Node/$title settings and registry access, then retry."
        }
    }
}
