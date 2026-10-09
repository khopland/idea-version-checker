package io.github.khopland.versionchecker

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.khopland.versionchecker.maven.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MavenCategoryChecksTest {
    private val kinds = listOf(MavenArtifactKind.DEPENDENCY, MavenArtifactKind.PLUGIN, MavenArtifactKind.PARENT)
    private fun coordinate(kind: MavenArtifactKind) = DependencyVersion("fixture", kind.name, "1.0", kind)

    @Test fun `plugin failure preserves completed dependencies and allows parent results`() = runBlocking {
        val failure = IOException("Plugin repository unavailable")
        val published = mutableListOf<Pair<MavenArtifactKind, Result<Map<DependencyVersion, String>>>>()
        val updates = checkMavenCategories(kinds, { kind, result -> published += kind to result }) { kind ->
            if (kind == MavenArtifactKind.PLUGIN) throw failure
            mapOf(coordinate(kind) to "2.0")
        }
        assertEquals(mapOf(coordinate(kinds[0]) to "2.0", coordinate(kinds[2]) to "2.0"), updates)
        assertEquals(kinds, published.map { it.first })
        assertSame(failure, published[1].second.exceptionOrNull())
        assertTrue(published[0].second.isSuccess)
        assertTrue(published[2].second.isSuccess)
    }

    @Test fun `strict checks retain native fail fast behavior`() = runBlocking {
        val expected = IOException("Dependency repository unavailable")
        val checked = mutableListOf<MavenArtifactKind>()
        val failure = runCatching {
            checkMavenCategories(kinds, null) { kind -> checked += kind; throw expected }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(listOf(MavenArtifactKind.DEPENDENCY), checked)
    }

    @Test fun `coroutine and IDE cancellation stop later categories without publishing a failed category`() = runBlocking {
        for (cancelled in listOf(CancellationException("Cancelled"), ProcessCanceledException())) {
            val checked = mutableListOf<MavenArtifactKind>()
            val published = mutableListOf<MavenArtifactKind>()
            val failure = runCatching {
                checkMavenCategories(kinds, { kind, _ -> published += kind }) { kind ->
                    checked += kind
                    if (kind == MavenArtifactKind.PLUGIN) throw cancelled
                    mapOf(coordinate(kind) to "2.0")
                }
            }.exceptionOrNull()
            assertSame(cancelled, failure)
            assertEquals(kinds.take(2), checked)
            assertEquals(kinds.take(1), published)
        }
    }

    @Test fun `publisher failure propagates instead of qualifying an incomplete scan`() = runBlocking {
        val expected = IllegalStateException("Snapshot invalidated")
        val checked = mutableListOf<MavenArtifactKind>()
        val failure = runCatching {
            checkMavenCategories(kinds, { _, _ -> throw expected }) { kind ->
                checked += kind
                mapOf(coordinate(kind) to "2.0")
            }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(kinds.take(1), checked)
    }
}
