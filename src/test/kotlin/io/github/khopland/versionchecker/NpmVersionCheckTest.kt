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

    @Test fun `complete metadata supplies baseline notices and candidates for every mode without extra queries`() = runBlocking {
        val versions = NpmPackageMetadata(listOf("1.0.0", "1.0.1", "1.0.2", "1.2.0", "1.3.0", "2.0.0", "3.0.0"),
            mapOf("1.0.0" to "Old release", "1.0.2" to "Broken patch", "1.3.0" to "Broken minor", "3.0.0" to "Broken major"))
        for ((mode, expected) in listOf(UpdateMode.PATCH to "1.0.1", UpdateMode.MINOR to "1.2.0", UpdateMode.MAJOR to "2.0.0")) {
            var calls = 0
            val report = checkNpmVersions(listOf(declaration("alpha"), declaration("alias", "npm:alpha@~1.0.0")),
                mode, emptyMap(), Semaphore(4), metadata = { calls++; versions },
                deprecated = { _, _ -> error("Complete metadata must not need deprecation queries") })
            assertEquals(1, calls)
            assertEquals(listOf(expected, expected), report.candidates.map { it.version })
            assertEquals(listOf("^$expected", "npm:alpha@~$expected"), report.candidates.map { it.replacementSelector })
            assertEquals(2, report.notices.size)
            assertTrue(report.notices.all { "Old release" in it.message })
        }
    }

    @Test fun `unpublished baseline and empty stable histories have no invented notices`() = runBlocking {
        for (versions in listOf(NpmPackageMetadata(emptyList(), emptyMap()), NpmPackageMetadata(listOf("2.0.0"), emptyMap()))) {
            val report = checkNpmVersions(listOf(declaration("alpha")), UpdateMode.MAJOR, emptyMap(), Semaphore(4),
                metadata = { versions }, deprecated = { _, _ -> error("Unexpected deprecation query") })
            assertTrue(report.notices.isEmpty())
            assertEquals(versions.versions.size, report.candidates.size)
        }
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

    @Test fun `finished package and aliases publish before an unrelated slow package`() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val first = CompletableDeferred<InspectionUpdate>()
        val declarations = listOf(declaration("slow"), declaration("fast"), declaration("alias", "npm:fast@~1.0.0"))
        val scan = async {
            checkNpmVersions(declarations, UpdateMode.MAJOR, emptyMap(), Semaphore(4),
                metadata = { name ->
                    if (name == "slow") slow.await()
                    NpmPackageMetadata(listOf("1.0.0", "1.1.0"), emptyMap())
                }, deprecated = { _, _ -> error("Unexpected lookup") }, publish = { first.complete(it) })
        }
        try {
            val update = withTimeout(5_000) { first.await() }
            assertEquals(setOf(declarations[1].id, declarations[2].id), update.completedDeclarations)
            assertEquals(listOf("^1.1.0", "npm:fast@~1.1.0"), update.report.candidates.map { it.replacementSelector })
            assertFalse("The slow query is still running", scan.isCompleted)
            slow.complete(Unit)
            val report = withTimeout(5_000) { scan.await() }
            assertTrue(report.successful)
            assertEquals(declarations, report.candidates.map { it.declaration })
        } finally { slow.complete(Unit); scan.cancelAndJoin() }
    }

    @Test fun `package failure preserves successful hints without reporting a successful complete check`() = runBlocking {
        val healthy = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<InspectionUpdate>()
        val declarations = listOf(declaration("broken"), declaration("healthy"))
        val scan = async {
            checkNpmVersions(declarations, UpdateMode.MAJOR, emptyMap(), Semaphore(4),
                metadata = { name ->
                    if (name == "broken") error("Registry unavailable")
                    healthy.await()
                    NpmPackageMetadata(listOf("1.1.0"), emptyMap())
                }, deprecated = { _, _ -> null }, publish = { if (!it.report.successful) failure.complete(it) })
        }
        try {
            assertFalse(withTimeout(5_000) { failure.await() }.report.successful)
            assertFalse("Failure must not cancel the other package", scan.isCompleted)
            healthy.complete(Unit)
            val report = withTimeout(5_000) { scan.await() }
            assertFalse(report.successful)
            assertTrue(report.failure!!.contains("broken: Registry unavailable"))
            assertEquals(listOf(declarations[1]), report.candidates.map { it.declaration })
        } finally { healthy.complete(Unit); scan.cancelAndJoin() }
    }

    @Test fun `cancelling after an early hint neither publishes unfinished packages nor caches a failure`() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val updates = mutableListOf<InspectionUpdate>()
        val slots = Semaphore(4)
        val scan = launch {
            checkNpmVersions(listOf(declaration("fast"), declaration("slow")), UpdateMode.MAJOR, emptyMap(), slots,
                metadata = { name -> if (name == "slow") awaitCancellation() else NpmPackageMetadata(listOf("1.1.0"), emptyMap()) },
                deprecated = { _, _ -> null }, publish = { updates += it; first.complete(Unit) })
        }
        withTimeout(5_000) { first.await() }
        scan.cancelAndJoin()
        assertEquals(1, updates.size)
        assertTrue(updates.single().report.successful)
        assertEquals(4, slots.availablePermits)
    }

    @Test fun `IDE process cancellation propagates instead of becoming a package failure`() = runBlocking {
        var published = false
        val failure = runCatching {
            checkNpmVersions(listOf(declaration("cancelled")), UpdateMode.MAJOR, emptyMap(), Semaphore(4),
                metadata = { throw com.intellij.openapi.progress.ProcessCanceledException() }, deprecated = { _, _ -> null },
                publish = { published = true })
        }.exceptionOrNull()
        assertTrue(failure is com.intellij.openapi.progress.ProcessCanceledException)
        assertFalse(published)
    }
}
