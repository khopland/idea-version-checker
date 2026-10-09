package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random
import kotlin.system.measureNanoTime

/** Opt-in local CPU comparison. No npm process, HTTP request, editor rendering or timing assertion. */
class NpmVersionHistoryBenchmarkTest {
    @Test fun compareRepeatedConsumersWithTheOriginalSelectionPath() = runBlocking {
        for ((historySize, consumers) in listOf(100 to 2, 100 to 200, 5_000 to 200)) {
            val versions = (0 until historySize).map { "${1 + it / 500}.${it / 50 % 10}.${it % 50}" }
                .shuffled(Random(42))
            val deprecated = versions.filterIndexed { index, _ -> index % 7 == 0 }.associateWith { "Retired" }
            val declarations = (0 until consumers).map { index ->
                VersionDeclaration(DeclarationId("member-${index / 2}/package.json", "dependencies/$index"),
                    ArtifactId("npm", if (index % 2 == 0) "example" else "alias"),
                    if (index % 2 == 0) "^1.0.0" else "npm:example@~1.0.0", "1.0.0")
            }
            val expected = UpdateMode.entries.map { original(NpmPackageMetadata(versions, deprecated), declarations, it) }
            suspend fun current(): List<UpdateReport> {
                // One raw response shared across files/modes, including its first local preparation.
                val metadata = NpmPackageMetadata(versions, deprecated)
                return UpdateMode.entries.map { mode ->
                    val reports = declarations.chunked(2).map { file ->
                        checkNpmVersions(file, mode, emptyMap(), Semaphore(4), metadata = { metadata },
                            deprecated = { _, _ -> error("Complete metadata must not query deprecations") })
                    }
                    UpdateReport(reports.flatMap { it.candidates }, reports.flatMap { it.notices })
                }
            }
            fun reference() = UpdateMode.entries.map { original(NpmPackageMetadata(versions, deprecated), declarations, it) }
            repeat(3) { assertEquals(expected, reference()); assertEquals(expected, current()) }
            val originalSamples = mutableListOf<Long>()
            val currentSamples = mutableListOf<Long>()
            repeat(5) { sample ->
                fun old() { originalSamples += measureNanoTime { assertEquals(expected, reference()) } }
                suspend fun new() { currentSamples += measureNanoTime { assertEquals(expected, current()) } }
                if (sample % 2 == 0) { old(); new() } else { new(); old() }
            }
            for ((path, samples) in listOf("original" to originalSamples, "current" to currentSamples)) {
                println("npm-history versions=$historySize consumers=$consumers modes=3 path=$path samplesMs=${samples.map { it / 1_000_000.0 }} medianMs=${samples.sorted()[2] / 1_000_000.0} p95Ms=${samples.max() / 1_000_000.0}")
            }
        }
    }

    private fun original(metadata: NpmPackageMetadata, declarations: List<VersionDeclaration>, mode: UpdateMode): UpdateReport {
        val candidates = mutableListOf<UpdateCandidate>()
        val notices = mutableListOf<UpdateNotice>()
        for (declaration in declarations) {
            val baseline = NpmVersion.parse(declaration.baseline)!!
            val selector = NpmSelector.parse(declaration.artifact.name, declaration.selector)!!
            val publishedBaseline = metadata.versions.firstOrNull { NpmVersion.parse(it) == baseline }
            publishedBaseline?.let { metadata.deprecatedByVersion!![it] }?.let { reason ->
                notices += UpdateNotice(declaration, NoticeKind.DEPRECATED,
                    "npm package ${selector.packageName} at ${declaration.selector} is deprecated: $reason")
            }
            val eligible = metadata.versions.mapNotNull { text -> NpmVersion.parse(text)?.let { text to it } }
                .filter { baseline.allows(it.second, mode) }.sortedByDescending { it.second }.map { it.first }
            eligible.firstOrNull { metadata.deprecatedByVersion!![it] == null }?.let { version ->
                candidates += UpdateCandidate(declaration, version, selector.replace(version), baseline.change(NpmVersion.parse(version)!!))
            }
        }
        return UpdateReport(candidates, notices)
    }
}
