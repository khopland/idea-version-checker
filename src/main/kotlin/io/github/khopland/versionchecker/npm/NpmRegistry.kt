package io.github.khopland.versionchecker.npm

import com.google.gson.JsonParser
import com.google.gson.JsonParseException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.*
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
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

internal data class NpmRuntime(val interpreter: NodeJsLocalInterpreter, val npm: NodePackage)

/** Only npm view is executed. npm owns .npmrc, scopes, credentials, proxies and TLS settings. */
internal object NpmRegistry {
    suspend fun resolve(project: Project, directory: Path): NpmRuntime = withContext(Dispatchers.IO) {
        CheckPerformance.measure(CheckPerformance.Stage.NPM_RUNTIME_RESOLUTION) {
            val (interpreter, configured) = readAction {
                val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
                    ?: throw NpmRuntimeFailure("Configure a local Node.js interpreter and npm in IntelliJ's JavaScript runtime settings, then retry.")
                if (interpreter !is NodeJsLocalInterpreter)
                    throw NpmRuntimeFailure("npm checks require a local Node.js interpreter. Choose one in IntelliJ's JavaScript runtime settings, then retry.")
                val configured = NpmManager.getInstance(project).getPackageOrThrow(interpreter)
                if (NpmManager.getNpmPackagePresentableName(configured) != "npm")
                    throw NpmRuntimeFailure("Choose npm as IntelliJ's configured package manager, then retry.")
                interpreter to configured
            }
            val npm = packageDirectory(configured) ?: bundledPackage(interpreter, directory) ?: configured
            NpmRuntime(interpreter, npm)
        }
    }

    suspend fun command(project: Project, directory: Path, parameters: List<String>): GeneralCommandLine =
        command(resolve(project, directory), directory, parameters)

    private fun command(runtime: NpmRuntime, directory: Path, parameters: List<String>): GeneralCommandLine =
        NpmUtil.createNpmCommandLine(directory, runtime.interpreter, runtime.npm, NpmCommand.VIEW,
            parameters + listOf("--json", "--loglevel=error", "--update-notifier=false", "--fetch-retries=0", "--workspaces=false", "--prefer-online"))
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
    suspend fun metadata(project: Project, directory: Path, name: String): NpmPackageMetadata {
        check(NpmSelector.validName(name)) { "Invalid npm package name" }
        return metadata(resolve(project, directory), directory, name)
    }

    suspend fun metadata(runtime: NpmRuntime, directory: Path, name: String): NpmPackageMetadata {
        check(NpmSelector.validName(name)) { "Invalid npm package name" }
        return loadMetadata(name) { view(runtime, directory, it) }
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
        val root = JsonParser.parseString(json)
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
    suspend fun deprecated(project: Project, directory: Path, name: String, version: String): String? {
        return deprecated(resolve(project, directory), directory, name, version)
    }
    suspend fun deprecated(runtime: NpmRuntime, directory: Path, name: String, version: String): String? {
        val text = view(runtime, directory, listOf("$name@$version", "deprecated")).trim()
        if (text.isEmpty()) return null
        val value = JsonParser.parseString(text)
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
    }
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
        } ?: throw NpmViewFailure("ETIMEDOUT", "npm registry query timed out for ${parameters.first()}")
        if (output.exitCode != 0) {
            // Do not copy stderr/configuration or credentials into a diagnostic or log.
            val code = runCatching { JsonParser.parseString(output.stdout).asJsonObject.getAsJsonObject("error")?.get("code")?.asString }
                .getOrNull()?.takeIf { Regex("[A-Z0-9_]+").matches(it) }
            throw NpmViewFailure(code, "npm view failed for ${parameters.first()} (exit ${output.exitCode}${code?.let { "; $it" }.orEmpty()}). Check Node/npm settings and registry access.")
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
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (outputType == ProcessOutputTypes.STDOUT) result.appendStdout(event.text)
                }
                override fun processTerminated(event: ProcessEvent) {
                    result.setExitCode(event.exitCode)
                    if (continuation.isActive) continuation.resume(result)
                }
            })
            continuation.invokeOnCancellation { handler.destroyProcess() }
            handler.startNotify()
        }
    }

}

internal class UnsupportedNpmMetadata : IOException("npm did not return complete stable-version metadata")
internal class NpmRuntimeFailure(override val recoveryMessage: String) : IOException(recoveryMessage), VersionCheckFailureAdvice

internal class NpmViewFailure(val code: String?, message: String) : IOException(message), VersionCheckFailureAdvice {
    override val recoveryMessage: String get() = when (code) {
        "E401", "E403", "ENEEDAUTH" -> "npm registry access was denied. Check the registry credentials and package permissions in .npmrc, then retry."
        "ENOTCACHED" -> "npm could not find registry metadata in its offline cache. Check npm's offline configuration and registry access, then retry."
        "ETIMEDOUT", "ESOCKETTIMEDOUT", "ECONNRESET", "ECONNREFUSED", "ENOTFOUND", "EAI_AGAIN", "ENETUNREACH" ->
            "npm could not reach the registry. Check the network, registry URL and proxy configuration, then retry."
        "CERT_HAS_EXPIRED", "DEPTH_ZERO_SELF_SIGNED_CERT", "SELF_SIGNED_CERT_IN_CHAIN", "UNABLE_TO_VERIFY_LEAF_SIGNATURE" ->
            "npm could not verify the registry certificate. Check the registry certificate and npm's trusted certificate configuration, then retry."
        "E404", "ETARGET", "ENOVERSIONS" -> "npm could not read package versions. Check the package name, scoped registry and package access, then retry."
        else -> "npm could not check package versions. Check IntelliJ's Node/npm settings and registry access, then retry."
    }
}
