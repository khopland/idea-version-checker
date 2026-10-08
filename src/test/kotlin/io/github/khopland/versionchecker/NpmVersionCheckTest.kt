package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

class NpmVersionCheckTest {
    private fun declaration(name: String, selector: String = "^1.0.0", location: String = name) = VersionDeclaration(
        DeclarationId("package.json", location), ArtifactId("npm", name), selector, "1.0.0")

    @Test fun `independent packages run concurrently with at most four active queries`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val fourStarted = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val result = async {
            checkNpmVersions((1..8).map { declaration("package-$it") }, UpdateMode.MAJOR, emptyMap(), Semaphore(4),
                metadata = {
                    active++
                    peak = maxOf(peak, active)
                    if (active == 4) fourStarted.complete(Unit)
                    try { gate.await(); NpmPackageMetadata(listOf("1.0.0", "1.1.0")) } finally { active-- }
                }, deprecated = { _, _ -> null })
        }
        withTimeout(5_000.milliseconds) { fourStarted.await() }
        assertEquals(4, active)
        gate.complete(Unit)
        val report = withTimeout(5_000.milliseconds) { result.await() }
        assertEquals(4, peak)
        assertEquals(8, report.candidates.size)
    }

    @Test fun `aliases share lookups and deprecated latest versions are skipped`() = runBlocking {
        val metadataCalls = ConcurrentHashMap<String, Int>()
        val deprecationCalls = ConcurrentHashMap<String, Int>()
        val report = checkNpmVersions(listOf(declaration("alpha"), declaration("alias", "npm:alpha@~1.0.0")),
            UpdateMode.MAJOR, emptyMap(), Semaphore(4), metadata = { name ->
                metadataCalls.merge(name, 1, Int::plus)
                NpmPackageMetadata(listOf("1.0.0", "1.0.1", "1.0.2"))
            }, deprecated = { _, version ->
                deprecationCalls.merge(version, 1, Int::plus)
                if (version == "1.0.2") "Retired" else null
            })
        assertEquals(mapOf("alpha" to 1), metadataCalls)
        assertTrue(deprecationCalls.values.all { it == 1 })
        assertEquals(listOf("^1.0.1", "npm:alpha@~1.0.1"), report.candidates.map { it.replacementSelector })
    }

    @Test fun `explicit deprecation and local dependencies do not contact the registry`() = runBlocking {
        val report = checkNpmVersions(listOf(declaration("alpha"), declaration("local").copy(baseline = "")),
            UpdateMode.MAJOR, mapOf("alpha" to "Replace it"), Semaphore(4),
            metadata = { error("Unexpected registry query") }, deprecated = { _, _ -> error("Unexpected registry query") })
        assertTrue(report.candidates.isEmpty())
        assertEquals(1, report.notices.size)
        assertTrue(report.notices.single().message.contains("Replace it"))
    }

    @Test fun `cancellation stops all outstanding queries and releases slots`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val slots = Semaphore(4)
        val job = launch {
            checkNpmVersions((1..8).map { declaration("package-$it") }, UpdateMode.MAJOR, emptyMap(), slots,
                metadata = { started.complete(Unit); awaitCancellation() }, deprecated = { _, _ -> null })
        }
        withTimeout(5_000.milliseconds) { started.await() }
        job.cancelAndJoin()
        assertEquals(4, slots.availablePermits)
    }
}
