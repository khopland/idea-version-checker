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

private val ignoredVersionRule = Regex("^(maven|npm|pnpm|yarn|bun|gradle)\\s+(\\S+)\\s*=\\s*(\\S+)\\s*$")

internal fun ignoredVersions(text: String): Set<IgnoredVersion> = text.lineSequence().mapNotNull { line ->
    val match = ignoredVersionRule.matchEntire(line.trim()) ?: return@mapNotNull null
    val (adapter, name, version) = match.destructured
    val artifact = if (adapter in setOf("npm", "pnpm", "yarn", "bun")) ArtifactId("npm", name) else {
        val parts = name.split(':')
        if (parts.size != 2 || parts.any(String::isBlank)) return@mapNotNull null
        ArtifactId(parts[0], parts[1])
    }
    IgnoredVersion(adapter, artifact, version)
}.toSet()

/** The same predicate serves hints, previews and status, without modifying raw cached reports. */
internal class VersionPresentationPolicy(private val adapter: String, text: String,
    private val sharedPolicy: ProjectPolicySnapshot? = null) {
    private val ignored = ignoredVersions(text)
    val candidateFilter: ((UpdateCandidate) -> Boolean)? get() = if (ignored.isEmpty() && sharedPolicy?.shared?.let { it.rules.isNotEmpty() || it.diagnostic != null } != true) null else ::includes

    fun includes(candidate: UpdateCandidate): Boolean {
        if (sharedPolicy?.restriction(adapter, candidate) != null) return false
        if (ignored.isEmpty()) return true
        val artifact = candidate.declaration.artifact
        // Maven snapshots store the coordinate in the name; settings use group/artifact identity.
        val identity = if (adapter == "maven" && artifact.namespace == "maven")
            ArtifactId(artifact.name.substringBefore(':'), artifact.name.substringAfter(':')) else artifact
        return IgnoredVersion(adapter, identity, candidate.version) !in ignored
    }

    fun filter(report: UpdateReport): UpdateReport {
        if (candidateFilter == null) return report
        val (visible, hiddenCandidates) = report.candidates.partition(::includes)
        val hidden = hiddenCandidates.map { it.declaration.id }.toSet() - visible.map { it.declaration.id }.toSet()
        val reasons = hiddenCandidates.associate { it.declaration.id to sharedPolicy?.restriction(adapter, it) }
        return report.copy(candidates = visible,
            failure = sharedPolicy?.shared?.diagnostic ?: report.failure, coverage = report.coverage?.mapValues { (id, result) ->
            if (id in hidden && result.outcome == DeclarationOutcome.CHECKED)
                result.copy(outcome = DeclarationOutcome.IGNORED, reason = reasons[id] ?: result.reason)
            else result
        })
    }

    fun coverageSummary(report: UpdateReport, snapshot: BuildSnapshot): CoverageSummary? = report.coverage?.let {
        CoverageSummary.from(it, snapshot.declarationIds(),
            if (candidateFilter == null) emptySet() else hiddenDeclarations(report))
    }

    private fun hiddenDeclarations(report: UpdateReport): Set<DeclarationId> =
        report.candidates.filter { !includes(it) }.map { it.declaration.id }.toSet() -
            report.candidates.filter(::includes).map { it.declaration.id }.toSet()

    fun count(report: UpdateReport): Int =
        if (candidateFilter == null) report.candidates.size else report.candidates.count(::includes)
}

internal fun UpdateReport.withoutIgnored(adapter: String, options: VersionCheckerSettings.Options): UpdateReport =
    VersionPresentationPolicy(adapter, options.ignoredVersions).filter(this)

internal fun UpdateReport.withProjectPolicy(adapter: String, project: Project): UpdateReport {
    val policy = projectPolicy(project)
    return VersionPresentationPolicy(adapter, policy.ignoredText, policy).filter(this)
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
