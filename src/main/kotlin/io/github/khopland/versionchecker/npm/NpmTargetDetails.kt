package io.github.khopland.versionchecker.npm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import io.github.khopland.versionchecker.core.DeclarationId
import io.github.khopland.versionchecker.core.DiagnosticSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.URI
import java.nio.file.Path

/** Provider-owned identity of one prepared target, including aliases' actual registry package. */
internal data class NpmTarget(
    val declaration: DeclarationId,
    val workspaceRoot: String,
    val packageName: String,
    val version: String,
    val baseline: String,
    val selector: String
)

internal data class NpmTargetDetails(
    val engines: Map<String, String>? = null,
    val peers: Map<String, String>? = null,
    val repository: String? = null,
    val homepage: String? = null,
    val declaredPeers: Map<String, String> = emptyMap()
) {
    fun description(target: NpmTarget): String = buildString {
        appendLine("Published requirements for ${target.packageName}@${target.version}")
        appendLine("Declared baseline: ${target.baseline} (selector floor; installed version unknown)")
        appendLine("Engine requirements: ${requirements(engines)}")
        appendLine("Runtime baseline: Unknown (not probed)")
        appendLine("Peer requirements: ${requirements(peers)}")
        peers?.keys?.forEach { name ->
            appendLine("$name project declaration: ${declaredPeers[name] ?: "Unknown"}; installed version unknown")
        }
        appendLine("Repository: ${repository ?: "Unknown"}")
        appendLine("Homepage: ${homepage ?: "Unknown"}")
        append("Compatibility: Unverified. Published constraints have not been evaluated. Details do not authorize edits.")
    }.let(DiagnosticSanitizer::sanitize)

    private fun requirements(values: Map<String, String>?) = when {
        values == null -> "Unknown"
        values.isEmpty() -> "No entries published"
        else -> values.entries.joinToString { (name, value) -> "$name: $value" }
    }

    companion object {
        fun parse(json: String, target: NpmTarget): NpmTargetDetails {
            val root = runCatching { JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
                ?: return NpmTargetDetails()
            if (root.string("name") != target.packageName || root.string("version") != target.version) return NpmTargetDetails()
            fun requirements(field: String): Map<String, String>? {
                val value = root.get(field)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
                if (value.size() > 256) return null
                return value.entrySet().associate { (key, _) ->
                    if (!safeText(key)) return null
                    key to (value.string(key)?.takeIf(::safeText) ?: return null)
                }
            }
            val repository = root.get("repository")?.let {
                if (it.isJsonObject) it.asJsonObject.string("url") else root.string("repository")
            }
            return NpmTargetDetails(requirements("engines"), requirements("peerDependencies"),
                safeHttpsUrl(repository), safeHttpsUrl(root.string("homepage")))
        }

        private fun JsonObject.string(name: String): String? = get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        private fun safeText(value: String) = value.isNotBlank() && value.length <= 4096 && value.none { it.isISOControl() }

        /** No inferred hosts/tags, credential-bearing authorities, or query/fragment secrets. */
        fun safeHttpsUrl(value: String?): String? {
            if (value == null || value.length > 2048 || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
            return value.substringBefore('?').substringBefore('#')
        }
    }
}

/** Captures the same workspace and npm configuration used by version checks, before and after IO. */
internal object NpmTargetDetailsLoader {
    suspend fun load(project: Project, target: NpmTarget): NpmTargetDetails {
        suspend fun context() = NpmBuildInputs.capture(project, { setOf(target.workspaceRoot) }) { external ->
            check(!project.isDisposed)
            val file = LocalFileSystem.getInstance().findFileByPath(target.declaration.file)
                ?: error("npm declaration unavailable")
            val workspace = NpmWorkspaces.resolve(project, file)
            check(workspace.root.path == target.workspaceRoot && NpmWorkspaces.supportsManifest(project, file))
            val declarations = PsiManager.getInstance(project).findFile(file)?.let(NpmManifest::declarations).orEmpty()
            val declaration = declarations.singleOrNull { it.id == target.declaration }
            checkNotNull(declaration)
            check(declaration.selector == target.selector && declaration.baseline == target.baseline)
            val unsaved = FileDocumentManager.getInstance().unsavedDocuments.mapNotNull {
                FileDocumentManager.getInstance().getFile(it)?.path
            }.toSet()
            check(!NpmBuildInputs.hasUnsavedResolutionInputs(project, target.workspaceRoot, unsaved))
            NpmBuildInputs.fingerprint(project, workspace, external) to declarations
        }
        val before = context()
        val details = NpmRegistry.targetDetails(project, Path.of(target.workspaceRoot), target)
        check(before == context()) { "npm inputs changed" }
        val peers = before.second.filter { it.id.location.substringBefore('/') in
            setOf("dependencies", "devDependencies", "optionalDependencies") }.groupBy { it.id.location.substringAfter('/') }
            .mapNotNull { (name, declarations) -> declarations.map { it.selector }.distinct().singleOrNull()?.let { name to it } }.toMap()
        return details.copy(declaredPeers = peers)
    }
}

/** Selection is local-only. Only request() starts IO; obsolete completions can never publish. */
internal class NpmTargetDetailsRequests(
    private val scope: CoroutineScope,
    private val load: suspend (NpmTarget) -> NpmTargetDetails,
    private val publish: (NpmTarget, NpmTargetDetails?) -> Unit
) {
    var target: NpmTarget? = null
        private set
    var loading = false
        private set
    private var generation = 0L
    private var job: Job? = null
    private var closed = false

    fun select(selected: NpmTarget?) {
        generation++
        job?.cancel()
        job = null
        loading = false
        target = selected
    }

    fun request() {
        val selected = target ?: return
        if (closed || loading) return
        val requestedGeneration = ++generation
        loading = true
        job = scope.launch {
            val details = try { load(selected) } catch (cancelled: CancellationException) {
                complete(selected, requestedGeneration, null)
                throw cancelled
            } catch (_: Exception) { null }
            complete(selected, requestedGeneration, details)
        }
    }

    private fun complete(selected: NpmTarget, requestedGeneration: Long, details: NpmTargetDetails?) {
        if (!closed && generation == requestedGeneration && target == selected) {
            loading = false
            publish(selected, details)
        }
    }

    fun close() {
        closed = true
        select(null)
        scope.cancel()
    }
}
