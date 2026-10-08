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
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import io.github.khopland.versionchecker.UpdateMode
import io.github.khopland.versionchecker.CheckPerformance
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
)

/** Only npm view is executed. npm owns .npmrc, scopes, credentials, proxies and TLS settings. */
internal object NpmRegistry {
    fun command(project: Project, directory: Path, parameters: List<String>): GeneralCommandLine {
        val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
            ?: error("Configure a local Node.js interpreter and npm in IntelliJ's JavaScript runtime settings")
        check(interpreter is NodeJsLocalInterpreter) { "npm checks currently require a local Node.js interpreter" }
        val configured = NpmManager.getInstance(project).getPackageOrThrow(interpreter)
        check(NpmManager.getNpmPackagePresentableName(configured) == "npm") { "npm checks currently require npm as IntelliJ's configured package manager" }
        val npm = packageDirectory(configured) ?: bundledPackage(interpreter, directory) ?: configured
        return NpmUtil.createNpmCommandLine(directory, interpreter, npm, NpmCommand.VIEW,
            parameters + listOf("--json", "--loglevel=error", "--update-notifier=false", "--fetch-retries=0", "--workspaces=false", "--prefer-online"))
            .withCharset(StandardCharsets.UTF_8)
    }
    /** Settings often name the npm executable (e.g. /opt/homebrew/bin/npm) instead of the npm package directory. */
    internal fun packageDirectory(npm: NodePackage): NodePackage? {
        val path = runCatching { Path.of(npm.systemDependentPath).toRealPath() }.getOrNull() ?: return null
        val root = if (Files.isDirectory(path)) path else path.parent?.takeIf { it.fileName?.toString() == "bin" }?.parent ?: return null
        return NodePackage(root).takeIf { isNpmPackage(root) }
    }
    /** Version-manager shims (mise, asdf, Volta) only reveal the real Node binary when run. */
    private fun bundledPackage(interpreter: NodeJsLocalInterpreter, directory: Path): NodePackage? {
        val output = runCatching {
            CapturingProcessHandler(GeneralCommandLine(interpreter.interpreterSystemDependentPath, "-p", "process.execPath")
                .withWorkingDirectory(directory).withCharset(StandardCharsets.UTF_8)).runProcess(10_000)
        }.getOrNull()?.takeIf { it.exitCode == 0 && !it.isTimeout } ?: return null
        return runCatching { Path.of(output.stdout.trim()).toRealPath() }.getOrNull()?.let(::bundledPackage)
    }
    internal fun bundledPackage(node: Path): NodePackage? =
        listOfNotNull(node.parent?.parent?.resolve("lib/node_modules/npm"), node.parent?.resolve("node_modules/npm"))
            .firstOrNull(::isNpmPackage)?.let(::NodePackage)
    private fun isNpmPackage(root: Path) = Files.isRegularFile(root.resolve("bin").resolve("npm-cli.js"))
    suspend fun metadata(project: Project, directory: Path, name: String): NpmPackageMetadata {
        check(NpmSelector.validName(name)) { "Invalid npm package name" }
        return loadMetadata(name) { view(project, directory, it) }
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
        val text = view(project, directory, listOf("$name@$version", "deprecated")).trim()
        if (text.isEmpty()) return null
        val value = JsonParser.parseString(text)
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
    }
    fun eligible(metadata: NpmPackageMetadata, baseline: NpmVersion, mode: UpdateMode): List<String> {
        return metadata.versions.mapNotNull { text -> NpmVersion.parse(text)?.let { text to it } }
            .filter { (_, version) -> baseline.allows(version, mode) }
            .sortedByDescending { it.second }.map { it.first }
    }
    private suspend fun view(project: Project, directory: Path, parameters: List<String>): String =
        CheckPerformance.measure(CheckPerformance.Stage.NPM_VIEW) { runView(project, directory, parameters) }

    private suspend fun runView(project: Project, directory: Path, parameters: List<String>): String = withContext(Dispatchers.IO) {
        val output = withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine<ProcessOutput> { continuation ->
                val handler = OSProcessHandler(CheckPerformance.measure(CheckPerformance.Stage.NPM_COMMAND_SETUP) {
                    command(project, directory, parameters)
                })
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
        } ?: throw IOException("npm registry query timed out for ${parameters.first()}")
        if (output.exitCode != 0) {
            // Do not copy stderr/configuration or credentials into a diagnostic or log.
            val code = runCatching { JsonParser.parseString(output.stdout).asJsonObject.getAsJsonObject("error")?.get("code")?.asString }
                .getOrNull()?.takeIf { Regex("[A-Z0-9_]+").matches(it) }
            throw NpmViewFailure(code, "npm view failed for ${parameters.first()} (exit ${output.exitCode}${code?.let { "; $it" }.orEmpty()}). Check Node/npm settings and registry access.")
        }
        output.stdout
    }
}

internal class UnsupportedNpmMetadata : IOException("npm did not return complete stable-version metadata")
internal class NpmViewFailure(val code: String?, message: String) : IOException(message)
