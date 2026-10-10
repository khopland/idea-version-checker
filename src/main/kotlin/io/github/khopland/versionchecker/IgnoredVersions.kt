package io.github.khopland.versionchecker

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.khopland.versionchecker.core.*

/** Project-local presentation policy. Raw repository results remain available when an ignore is removed. */
internal data class IgnoredVersion(val adapter: String, val artifact: ArtifactId, val version: String) {
    override fun toString() = "$adapter ${artifactLabel(artifact)} = $version"
}

internal fun artifactLabel(artifact: ArtifactId) =
    if (artifact.namespace.isEmpty() || artifact.namespace == "npm") artifact.name else "${artifact.namespace}:${artifact.name}"

internal fun ignoredVersions(text: String): Set<IgnoredVersion> = text.lineSequence().mapNotNull { line ->
    val match = Regex("^(maven|npm|gradle)\\s+(\\S+)\\s*=\\s*(\\S+)\\s*$").matchEntire(line.trim()) ?: return@mapNotNull null
    val (adapter, name, version) = match.destructured
    val artifact = if (adapter == "npm") ArtifactId("npm", name) else {
        val parts = name.split(':')
        if (parts.size != 2 || parts.any(String::isBlank)) return@mapNotNull null
        ArtifactId(parts[0], parts[1])
    }
    IgnoredVersion(adapter, artifact, version)
}.toSet()

internal fun UpdateReport.withoutIgnored(adapter: String, options: VersionCheckerSettings.Options): UpdateReport {
    val ignored = ignoredVersions(options.ignoredVersions)
    if (ignored.isEmpty()) return this
    return copy(candidates = candidates.filter {
        val artifact = it.declaration.artifact
        // Maven snapshots store the coordinate in the name; settings use group/artifact identity.
        val identity = if (adapter == "maven" && artifact.namespace == "maven")
            ArtifactId(artifact.name.substringBefore(':'), artifact.name.substringAfter(':')) else artifact
        IgnoredVersion(adapter, identity, it.version) !in ignored
    })
}

internal class IgnorePublishedVersionFix(private val ignored: IgnoredVersion) : LocalQuickFix, LowPriorityAction {
    override fun getFamilyName() = "Ignore published version"
    override fun getName() = "Ignore ${artifactLabel(ignored.artifact)} ${ignored.version} in this project"
    override fun startInWriteAction() = false
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val options = project.service<VersionCheckerSettings>().state
        if (ignored !in ignoredVersions(options.ignoredVersions))
            options.ignoredVersions = listOf(options.ignoredVersions.trimEnd(), ignored.toString()).filter(String::isNotEmpty).joinToString("\n")
        project.service<VersionCheckService>().statusChanged()
        refreshEditorProblems(project, this)
    }
}

internal fun updateHint(artifact: ArtifactId, kind: VersionChangeKind, current: String, latest: String): String =
    "${artifactLabel(artifact)} · ${kind.name.lowercase()} update: $current → $latest"
