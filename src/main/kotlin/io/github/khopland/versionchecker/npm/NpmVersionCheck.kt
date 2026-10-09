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

/** Independent packages can be queried concurrently; aliases share metadata and deprecation lookups. */
internal suspend fun checkNpmVersions(
    declarations: List<VersionDeclaration>, mode: UpdateMode, policy: Map<String, String>, slots: Semaphore,
    metadata: suspend (String) -> NpmPackageMetadata,
    deprecated: suspend (String, String) -> String?,
    publish: suspend (InspectionUpdate) -> Unit = {}
): UpdateReport = coroutineScope {
    val packages = declarations.mapNotNull { declaration ->
        val baseline = NpmVersion.parse(declaration.baseline) ?: return@mapNotNull null
        val selector = NpmSelector.parse(declaration.artifact.name, declaration.selector) ?: return@mapNotNull null
        Triple(declaration, baseline, selector)
    }.groupBy { it.third.packageName }
    val reports = packages.map { (name, usages) ->
        async {
            val report = try { slots.withPermit {
                val candidates = mutableListOf<UpdateCandidate>()
                val notices = mutableListOf<UpdateNotice>()
                val deprecations = mutableMapOf<String, String?>()
                val explicit = policy[name]
                val versions = if (explicit == null) metadata(name) else null
                suspend fun notice(version: String): String? {
                    versions?.deprecatedByVersion?.let { return it[version] }
                    if (version !in deprecations) deprecations[version] = deprecated(name, version)
                    return deprecations[version]
                }
                for ((declaration, baseline, selector) in usages) {
                    val publishedBaseline = versions?.versions?.firstOrNull { NpmVersion.parse(it) == baseline }
                    val reason = explicit ?: publishedBaseline?.let { notice(it) }
                    if (reason != null) notices += UpdateNotice(declaration, NoticeKind.DEPRECATED,
                        "npm package $name at ${declaration.selector} is deprecated: $reason")
                    if (explicit != null) continue
                    for (version in NpmRegistry.eligible(versions!!, baseline, mode)) {
                        if (notice(version) != null) continue
                        candidates += UpdateCandidate(declaration, version, selector.replace(version), baseline.change(NpmVersion.parse(version)!!))
                        break
                    }
                }
                UpdateReport(candidates, notices)
            } } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (failure: Exception) {
                UpdateReport(failure = "$name: ${failure.message ?: failure.javaClass.simpleName}", failureCause = failure)
            }
            publish(InspectionUpdate(report, usages.map { it.first.id }.toSet()))
            report
        }
    }.awaitAll()
    val order = declarations.mapIndexed { index, declaration -> declaration.id to index }.toMap()
    UpdateReport(reports.flatMap { it.candidates }.sortedBy { order[it.declaration.id] },
        reports.flatMap { it.notices }.sortedBy { order[it.declaration.id] },
        failure = reports.mapNotNull { it.failure }.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        failureCause = reports.firstNotNullOfOrNull { it.failureCause })
}
