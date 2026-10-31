package io.github.khopland.versionchecker.npm

import io.github.khopland.versionchecker.UpdateMode
import io.github.khopland.versionchecker.core.*
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private data class NpmUsage(
    val declaration: VersionDeclaration,
    val baseline: NpmVersion,
    val selector: NpmSelector
)

/** Independent packages can be queried concurrently; aliases share metadata and deprecation lookups. */
internal suspend fun checkNpmVersions(
    declarations: List<VersionDeclaration>,
    mode: UpdateMode,
    policy: Map<String, String>,
    slots: Semaphore,
    metadata: suspend (String) -> NpmPackageMetadata,
    deprecated: suspend (String, String) -> String?,
    reviewReasons: Map<DeclarationId, String> = emptyMap(),
    allowRegistryProtocol: Boolean = false,
    releaseAge: MinimumReleaseAge? = null,
    publish: suspend (InspectionUpdate) -> Unit = {}
): UpdateReport = coroutineScope {
    val packages = declarations.mapNotNull { declaration ->
        if (declaration.id in reviewReasons) return@mapNotNull null
        val baseline = NpmVersion.parse(declaration.baseline) ?: return@mapNotNull null
        val selector = NpmSelector.parse(declaration.artifact.name, declaration.selector, allowRegistryProtocol) ?: return@mapNotNull null
        NpmUsage(declaration, baseline, selector)
    }.groupBy { it.selector.packageName }
    val supported = packages.values.flatten().map { it.declaration.id }.toSet()
    val skipped = declarations.filter { it.id !in supported }.associate { it.id to
        if (it.id in reviewReasons) DeclarationCoverage(DeclarationOutcome.MANUAL_REVIEW, reviewReasons[it.id])
        else DeclarationCoverage(DeclarationOutcome.SKIPPED, NpmManifest.reviewReason(it) ?: "Unsupported npm selector needs manual review.") }
    if (skipped.isNotEmpty()) publish(InspectionUpdate(UpdateReport(coverage = skipped), skipped.keys))
    val reports = packages.map { (name, usages) ->
        async {
            val report = try {
                slots.withPermit {
                    checkNpmPackage(name, usages, mode, policy[name], metadata, deprecated, releaseAge)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (failure: Exception) {
                UpdateReport(failure = "$name: ${failure.message ?: failure.javaClass.simpleName}", failureCause = failure,
                    coverage = usages.associate { it.declaration.id to DeclarationCoverage(DeclarationOutcome.FAILED,
                        (failure as? VersionCheckFailureAdvice)?.recoveryMessage ?: "Native npm metadata check failed.") })
            }
            publish(InspectionUpdate(report, usages.map { it.declaration.id }.toSet()))
            report
        }
    }.awaitAll()
    val order = declarations.mapIndexed { index, declaration -> declaration.id to index }.toMap()
    UpdateReport(
        candidates = reports.flatMap { it.candidates }.sortedBy { order[it.declaration.id] },
        notices = reports.flatMap { it.notices }.sortedBy { order[it.declaration.id] },
        failure = reports.mapNotNull { it.failure }.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        failureCause = reports.firstNotNullOfOrNull { it.failureCause },
        coverage = skipped + reports.flatMap { it.coverage.orEmpty().entries }.associate { it.key to it.value })
}

private suspend fun checkNpmPackage(
    name: String,
    usages: List<NpmUsage>,
    mode: UpdateMode,
    explicitDeprecation: String?,
    metadata: suspend (String) -> NpmPackageMetadata,
    deprecated: suspend (String, String) -> String?,
    releaseAge: MinimumReleaseAge?
): UpdateReport {
    fun deprecationNotice(declaration: VersionDeclaration, reason: String) = UpdateNotice(
        declaration,
        NoticeKind.DEPRECATED,
        "npm package $name at ${declaration.selector} is deprecated: $reason"
    )

    if (explicitDeprecation != null) {
        return UpdateReport(notices = usages.map { deprecationNotice(it.declaration, explicitDeprecation) },
            coverage = usages.associate { it.declaration.id to DeclarationCoverage(DeclarationOutcome.MANUAL_REVIEW,
                "Package is explicitly marked deprecated; review its replacement before checking versions.") })
    }

    val versions = metadata(name)
    val history = versions.history
    val published = if (releaseAge == null) null else versions.publishedAt?.takeIf { it.isNotEmpty() }
        ?: throw NpmReleaseTimeFailure(name)
    val deprecations = mutableMapOf<String, String?>()
    suspend fun deprecationReason(version: String): String? {
        versions.deprecatedByVersion?.let { return it[version] }
        // Cache nulls too: aliases must not repeat lookups for non-deprecated versions.
        if (version !in deprecations) deprecations[version] = deprecated(name, version)
        return deprecations[version]
    }

    val candidates = mutableListOf<UpdateCandidate>()
    val notices = mutableListOf<UpdateNotice>()
    for ((declaration, baseline, selector) in usages) {
        val publishedBaseline = history.publishedBaseline(baseline)
        val reason = publishedBaseline?.let { deprecationReason(it) }
        if (reason != null) notices += deprecationNotice(declaration, reason)

        for ((text, version) in history.eligible(baseline, mode)) {
            // Unknown release times count as too new: a quarantined version must never be suggested.
            if (published != null && published[text]?.let { releaseAge!!.oldEnough(it) } != true) continue
            if (deprecationReason(text) != null) continue
            candidates += UpdateCandidate(declaration, text, selector.replace(text), baseline.change(version))
            break
        }
    }
    return UpdateReport(candidates, notices, coverage = usages.associate {
        it.declaration.id to DeclarationCoverage(DeclarationOutcome.CHECKED)
    })
}

internal class NpmReleaseTimeFailure(name: String) : java.io.IOException("$name: the registry did not return release times"),
    VersionCheckFailureAdvice {
    override val recoveryMessage = "The registry did not return release times, so the minimum release age cannot be applied. " +
        "Check the registry configuration, or set the minimum release age to 0."
}
