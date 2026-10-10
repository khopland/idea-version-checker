package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GradleScanSessionTest {
    private fun snapshot(file: String, root: String = "/root", configuration: String = "settings", declarations: Int = 1): BuildSnapshot =
        BuildSnapshot(BuildContextId("gradle", root, "$root/$file"), "$root/$file",
            BuildFingerprint(mapOf("$root/settings.gradle" to "saved"), configuration),
            (1..declarations).map { VersionDeclaration(DeclarationId("$root/$file", "$it"), ArtifactId("fixture", "alpha"), "1.0.0", "1.0.0") })

    @Test fun `one lazy invocation serves compatible files without collapsing declaration identities`() = runBlocking {
        supervisorScope {
            val first = snapshot("first/build.gradle")
            val second = snapshot("second/build.gradle")
            var calls = 0
            val session = GradleScanSession(this, listOf(first, second), UpdateMode.MAJOR, { 0L }) { batch ->
                calls++
                batch.associateWith { mapOf("1" to GradleVersionLookup.Result(listOf(it.sourceFile))) }
            }
            try {
                assertEquals(0, calls)
                assertEquals(listOf(first.sourceFile), session.check(first, UpdateMode.MAJOR)!!.results["1"]!!.versions)
                assertEquals(listOf(second.sourceFile), session.check(second, UpdateMode.MAJOR)!!.results["1"]!!.versions)
                assertEquals(1, calls)
                assertNull(session.check(second, UpdateMode.PATCH))
                assertNull(session.check(second.copy(declarations = emptyList()), UpdateMode.MAJOR))
            } finally { session.close() }
        }
    }

    @Test fun `root and fingerprint isolation plus bounded batches retain selected-file order`() {
        val snapshots = (1..100).map { snapshot("module$it/build.gradle", declarations = 100) }
        val batches = GradleScanSession.batches(snapshots + snapshot("other/build.gradle", root = "/other") +
            snapshot("changed/build.gradle", configuration = "changed"))
        assertEquals(listOf(20, 20, 20, 20, 20, 1, 1), batches.map { it.size })
        assertEquals(snapshots, batches.take(5).flatten())
        assertTrue(batches.all { it.map { s -> s.context.root to s.fingerprint }.distinct().size == 1 })
        assertEquals(listOf(32, 1), GradleScanSession.batches((1..33).map { snapshot("$it/build.gradle") }).map { it.size })
        assertEquals(listOf(1, 1), GradleScanSession.batches(listOf(snapshot("dense/build.gradle", declarations = 10_000),
            snapshot("small/build.gradle"))).map { it.size })
    }

    @Test fun `refresh before or during a batch rejects every pre-refresh result`() = runBlocking {
        supervisorScope {
            val first = snapshot("first/build.gradle")
            val second = snapshot("second/build.gradle")
            var generation = 1L
            var calls = 0
            val session = GradleScanSession(this, listOf(first, second), UpdateMode.MAJOR, { generation }) { batch ->
                calls++
                generation++
                batch.associateWith { emptyMap<String, GradleVersionLookup.Result>() }
            }
            try {
                assertNull(session.check(first, UpdateMode.MAJOR))
                assertNull(session.check(second, UpdateMode.MAJOR))
                assertEquals(1, calls)
            } finally { session.close() }
            val neverStarted = GradleScanSession(this, listOf(first), UpdateMode.MAJOR, { generation }) { error("Old batch ran") }
            try { generation++; assertNull(neverStarted.check(first, UpdateMode.MAJOR)) } finally { neverStarted.close() }
        }
    }

    @Test fun `consuming later files preserves batch age and expiry instead of issuing younger metadata`() = runBlocking {
        supervisorScope {
            var time = 100L
            val first = snapshot("first/build.gradle")
            val second = snapshot("second/build.gradle")
            val session = GradleScanSession(this, listOf(first, second), UpdateMode.MAJOR, { 0L }, now = { time }) { batch ->
                batch.associateWith { emptyMap<String, GradleVersionLookup.Result>() }
            }
            try {
                val initial = session.check(first, UpdateMode.MAJOR)!!
                time += 100
                val later = session.check(second, UpdateMode.MAJOR)!!
                assertEquals(initial.checkedAtNanos, later.checkedAtNanos)
                assertEquals(initial.expiresAtNanos, later.expiresAtNanos)
                time = later.expiresAtNanos
                assertNull(session.check(second, UpdateMode.MAJOR))
            } finally { session.close() }
        }
    }

    @Test fun `failed batches allow per-file fallback and other batches still complete`() = runBlocking {
        supervisorScope {
            val bad = snapshot("bad/build.gradle")
            val sameBatch = snapshot("good/build.gradle")
            val other = snapshot("other/build.gradle", root = "/other")
            var calls = 0
            val session = GradleScanSession(this, listOf(bad, sameBatch, other), UpdateMode.MAJOR, { 0L }) { batch ->
                calls++
                if (bad in batch) error("Native source failure")
                batch.associateWith { emptyMap<String, GradleVersionLookup.Result>() }
            }
            try {
                assertNull(session.check(bad, UpdateMode.MAJOR))
                assertNull(session.check(sameBatch, UpdateMode.MAJOR))
                assertEquals(emptyMap<String, GradleVersionLookup.Result>(), session.check(other, UpdateMode.MAJOR)!!.results)
                assertEquals(2, calls)
            } finally { session.close() }
        }
    }

    @Test fun `cancellation stops an active batch and closing cancels unstarted children`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val file = snapshot("build.gradle")
        val job = launch {
            supervisorScope {
                val session = GradleScanSession(this, listOf(file), UpdateMode.MAJOR, { 0L }) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                try { session.check(file, UpdateMode.MAJOR) } finally { session.close() }
            }
        }
        withTimeout(2000) { entered.await(); job.cancelAndJoin(); cancelled.await() }
        withTimeout(2000) {
            supervisorScope {
                val lazy = GradleScanSession(this, listOf(file), UpdateMode.MAJOR, { 0L }) { error("Unneeded batch ran") }
                lazy.close()
                assertNull(lazy.check(file, UpdateMode.MAJOR))
            }
        }
    }
}
