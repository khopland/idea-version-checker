package io.github.khopland.versionchecker

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.components.Service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

internal const val PROJECT_POLICY_FILE = ".version-checker.json"
private const val MAX_POLICY_SIZE = 1_048_576

internal data class SharedIgnoredVersion(val version: String, val until: Instant? = null, val reason: String? = null)
internal data class SharedDependencyRule(
    val ecosystem: String,
    val name: String,
    val ignoredVersions: List<SharedIgnoredVersion> = emptyList(),
    val deprecated: String? = null,
    val allowedUpdates: Set<VersionChangeKind>? = null,
)
internal data class SharedProjectPolicy(val rules: List<SharedDependencyRule> = emptyList(), val diagnostic: String? = null) {
    val byIdentity = rules.associateBy { it.ecosystem to it.name }
    val expirations = rules.flatMap { rule -> rule.ignoredVersions.mapNotNull { it.until } }.distinct().sorted()
}

/** Strict, exact identities only: invalid policy must never silently remove restrictions. */
internal fun parseSharedProjectPolicy(text: String?): SharedProjectPolicy {
    if (text == null) return SharedProjectPolicy()
    return try {
        require(text.length <= MAX_POLICY_SIZE) { "policy must be at most 1 MiB" }
        JsonReader(StringReader(text)).use { reader ->
            validatePolicyJson(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "unexpected trailing JSON" }
        }
        val root = JsonParser.parseString(text).objectValue("policy")
        root.onlyKeys(setOf("schemaVersion", "rules"), "policy")
        require(root["schemaVersion"]?.toString() == "1") { "schemaVersion must be 1" }
        val entries = root["rules"]?.takeIf { it.isJsonArray }?.asJsonArray
            ?: error("rules must be an array")
        val rules = entries.mapIndexed { index, value ->
            val at = "rules[$index]"
            val rule = value.objectValue(at)
            rule.onlyKeys(setOf("ecosystem", "package", "ignoredVersions", "deprecated", "allowedUpdates"), at)
            val ecosystem = rule.stringValue("ecosystem", at)
            require(ecosystem in setOf("maven", "npm", "gradle")) { "$at.ecosystem must be maven, npm or gradle" }
            val name = rule.stringValue("package", at)
            val validName = if (ecosystem == "npm") Regex("(?:@[A-Za-z0-9][A-Za-z0-9._-]*/)?[A-Za-z0-9][A-Za-z0-9._-]*").matches(name)
                else Regex("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+").matches(name)
            require(validName) { "$at.package must be an exact $ecosystem package identity" }
            val ignored = rule["ignoredVersions"]?.let { ignored ->
                require(ignored.isJsonArray) { "$at.ignoredVersions must be an array" }
                ignored.asJsonArray.mapIndexed { i, entry ->
                    val where = "$at.ignoredVersions[$i]"
                    val item = entry.objectValue(where)
                    item.onlyKeys(setOf("version", "until", "reason"), where)
                    val version = item.stringValue("version", where)
                    require(version.none(Char::isWhitespace) && version.none { it in "*?" }) { "$where.version must be exact" }
                    val until = item["until"]?.let { Instant.parse(item.stringValue("until", where)) }
                    SharedIgnoredVersion(version, until, item["reason"]?.let { item.stringValue("reason", where) })
                }
            }.orEmpty()
            val deprecated = rule["deprecated"]?.let { rule.stringValue("deprecated", at) }
            val allowed = rule["allowedUpdates"]?.let { allowed ->
                require(allowed.isJsonArray) { "$at.allowedUpdates must be an array" }
                allowed.asJsonArray.map { kind ->
                    val value = kind.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    require(value in setOf("patch", "minor", "major", "other")) { "$at.allowedUpdates contains an unknown update level" }
                    VersionChangeKind.valueOf(value!!.uppercase())
                }.toSet()
            }
            SharedDependencyRule(ecosystem, name, ignored, deprecated, allowed)
        }
        require(rules.map { it.ecosystem to it.name }.distinct().size == rules.size) { "duplicate ecosystem/package rules are not allowed" }
        SharedProjectPolicy(rules)
    } catch (failure: Exception) {
        SharedProjectPolicy(diagnostic = "$PROJECT_POLICY_FILE: ${failure.message ?: "invalid policy"}")
    }
}

private fun validatePolicyJson(reader: JsonReader, depth: Int) {
    require(depth <= 20) { "policy JSON is nested too deeply" }
    when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            reader.beginObject()
            val keys = mutableSetOf<String>()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(keys.add(key)) { "duplicate JSON field: $key" }
                validatePolicyJson(reader, depth + 1)
            }
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            while (reader.hasNext()) validatePolicyJson(reader, depth + 1)
            reader.endArray()
        }
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> reader.nextNull()
        else -> error("invalid JSON value")
    }
}

private fun JsonElement.objectValue(at: String): JsonObject {
    require(isJsonObject) { "$at must be an object" }
    return asJsonObject
}
private fun JsonObject.onlyKeys(keys: Set<String>, at: String) {
    require(keySet().all { it in keys }) { "$at contains unknown fields: ${(keySet() - keys).sorted().joinToString()}" }
}
private fun JsonObject.stringValue(key: String, at: String): String {
    val value = get(key)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isNotBlank()) {
        "$at.$key must be a non-empty string"
    }
    return value.asString
}

/** A retained edit is bound to exact policy text and local rules, including expiry transitions. */
internal data class ProjectPolicySnapshot(
    val sharedText: String?, val ignoredText: String, val deprecatedText: String,
    val shared: SharedProjectPolicy = parseSharedProjectPolicy(sharedText),
    val observedAt: Instant = Instant.now(),
) {
    val deprecationFingerprint: String by lazy { sha256("$deprecatedText|$sharedText".toByteArray(Charsets.UTF_8)) }
    private val localDeprecations by lazy { parseDeprecationPolicy(deprecatedText) }
    private val validSince = shared.expirations.lastOrNull { !observedAt.isBefore(it) }
    private val validUntil = shared.expirations.firstOrNull { observedAt.isBefore(it) }
    fun validAt(now: Instant): Boolean = (validSince == null || !now.isBefore(validSince)) &&
        (validUntil == null || now.isBefore(validUntil))

    fun isCurrent(project: Project): Boolean {
        val current = projectPolicy(project)
        return shared.diagnostic == null && sharedText == current.sharedText && ignoredText == current.ignoredText &&
            deprecatedText == current.deprecatedText && validAt(Instant.now())
    }

    fun deprecations(adapter: String): Map<String, String> = localDeprecations +
        shared.rules.filter { it.ecosystem == adapter && it.deprecated != null }
            .associate { it.name to "${it.deprecated} (shared policy: $PROJECT_POLICY_FILE)" }

    fun restriction(adapter: String, candidate: UpdateCandidate): String? =
        restriction(adapter, candidate.declaration.artifact, candidate.version, candidate.kind)

    fun restriction(adapter: String, artifact: ArtifactId, version: String, kind: VersionChangeKind): String? {
        shared.diagnostic?.let { return it }
        val identity = artifact.policyName(adapter)
        val rule = shared.byIdentity[adapter to identity] ?: return null
        val ignored = rule.ignoredVersions.firstOrNull { it.version == version && (it.until == null || observedAt.isBefore(it.until)) }
        val reason = when {
            rule.deprecated != null -> rule.deprecated
            ignored != null -> "Published version $version is ignored" +
                (ignored.reason?.let { ": $it" } ?: "") + (ignored.until?.let { " until $it" } ?: "")
            rule.allowedUpdates != null && kind !in rule.allowedUpdates -> "${kind.name.lowercase()} updates are not allowed"
            else -> return null
        }
        return "$reason (shared policy: $PROJECT_POLICY_FILE)"
    }
}

private fun ArtifactId.policyName(adapter: String): String =
    if (adapter == "maven" && namespace == "maven") name else artifactLabel(this)

/** Saved/document stamps make repeated status, row and atomic guard reads cheap. */
@Service(Service.Level.PROJECT)
internal class ProjectPolicyStore(private val project: Project) {
    private data class Source(val file: VirtualFile?, val savedStamp: Long?, val documentStamp: Long?)
    private var source: Source? = null
    private var text: String? = null
    private var parsed = SharedProjectPolicy()
    private var snapshot: ProjectPolicySnapshot? = null
    internal var parseCount: Int = 0
        private set

    @Synchronized fun current(): ProjectPolicySnapshot {
        val options = project.service<VersionCheckerSettings>().state
        val file = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath("$it/$PROJECT_POLICY_FILE") }
        val documents = FileDocumentManager.getInstance()
        val document = file?.let(documents::getCachedDocument)?.takeIf(documents::isDocumentUnsaved)
        val observed = Source(file, file?.modificationStamp, document?.modificationStamp)
        if (source != observed) {
            val loadedText = when {
                document != null && document.textLength > MAX_POLICY_SIZE -> "<project policy exceeds 1 MiB>"
                document != null -> document.text
                file == null -> null
                file.length > MAX_POLICY_SIZE -> "<project policy exceeds 1 MiB>"
                else -> try { String(file.contentsToByteArray(), Charsets.UTF_8).removePrefix("\uFEFF") }
                    catch (_: java.io.IOException) { "<unreadable project policy>" }
            }
            val loadedPolicy = if (loadedText == "<project policy exceeds 1 MiB>") SharedProjectPolicy(
                diagnostic = "$PROJECT_POLICY_FILE: policy must be at most 1 MiB") else parseSharedProjectPolicy(loadedText)
            source = observed
            text = loadedText
            parsed = loadedPolicy
            parseCount++
            snapshot = null
        }
        val now = Instant.now()
        return snapshot?.takeIf { it.ignoredText == options.ignoredVersions &&
            it.deprecatedText == options.deprecatedDependencies && it.validAt(now) }
            ?: ProjectPolicySnapshot(text, options.ignoredVersions, options.deprecatedDependencies, parsed, now)
                .also { snapshot = it }
    }
}

/** Reads only IDE-visible state. Fresh physical input capture happens before acquiring write access. */
internal fun projectPolicy(project: Project): ProjectPolicySnapshot = ReadAction.compute<ProjectPolicySnapshot, RuntimeException> {
    project.service<ProjectPolicyStore>().current()
}

internal fun projectDeprecations(project: Project, adapter: String): Map<String, String> = projectPolicy(project).deprecations(adapter)

/** Refresh catches externally created/deleted files, including a policy absent when the plan was prepared. */
internal suspend fun prepareProjectPolicyValidation(project: Project, expected: ProjectPolicySnapshot): CapturedEditInputs {
    if (expected.shared.diagnostic != null) return CapturedEditInputs { false }
    val path = project.basePath?.let { "$it/$PROJECT_POLICY_FILE" }
    if (path != null) withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByPath(path) }
    val matching = readAction { expected.isCurrent(project) }
    if (!matching) return CapturedEditInputs { false }
    val saved = prepareSavedBuildInputValidation(project, setOfNotNull(path)) { physical ->
        java.nio.file.Files.newInputStream(physical).use { it.readNBytes(MAX_POLICY_SIZE + 1) }
    }
    return CapturedEditInputs { matching && saved.isCurrent() && expected.isCurrent(project) }
}

internal fun BulkUpdatePlan.withProjectPolicy(project: Project, expected: ProjectPolicySnapshot): BulkUpdatePlan {
    val original = prepareValidation
    return copy(prepareValidation = EditInputCapture {
        val policy = prepareProjectPolicyValidation(project, expected)
        val inputs = original?.capture()
        CapturedEditInputs { policy.isCurrent() && inputs?.isCurrent() != false }
    }).guardedBy { expected.isCurrent(project) }
}
