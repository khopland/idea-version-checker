package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class MavenReleaseAgeTest {
    private val now = 1_800_000_000_000L
    private val age = MavenReleaseAge(24, now)
    private fun hoursAgo(hours: Long) = now - TimeUnit.HOURS.toMillis(hours)
    private val dependency = DependencyVersion("example", "library", "1.0")
    private val plugin = DependencyVersion("example", "plugin", "1.0", MavenArtifactKind.PLUGIN)

    private class Repository(val published: Map<String, Long>, val requiredMaven: Map<String, String> = emptyMap(),
                             val failing: Set<MavenMetadataKey> = emptySet()) {
        val rounds = mutableListOf<List<MavenMetadataKey>>()
        suspend fun load(keys: List<MavenMetadataKey>): Map<MavenMetadataKey, Result<MavenMetadataValue>> {
            rounds += keys
            return keys.associateWith { key ->
                when {
                    key in failing -> Result.failure(IOException("Repository refused ${key.version}"))
                    key.release -> Result.success(MavenMetadataValue(published = published.getValue(key.version)))
                    else -> Result.success(MavenMetadataValue(requiredMaven = requiredMaven[key.version].orEmpty()))
                }
            }
        }
    }

    @Test fun `metadata updated before the cutoff settles every listed version`() {
        assertTrue(age.settled(MavenMetadataValue(listOf("1.0"), lastUpdated = hoursAgo(25))))
        assertFalse(age.settled(MavenMetadataValue(listOf("1.0"), lastUpdated = hoursAgo(2))))
        assertFalse("Unknown update times need release dates", age.settled(MavenMetadataValue(listOf("1.0"))))
        assertNull("Zero disables the minimum age", MavenReleaseAge.of(0))
    }

    @Test fun `recent releases are skipped with growing lookahead`() = runBlocking {
        val repository = Repository(mapOf("2.0" to hoursAgo(1), "1.3" to hoursAgo(5), "1.2" to hoursAgo(10),
            "1.1" to hoursAgo(30), "1.0" to hoursAgo(100)))
        val selection = MavenCandidateWalk.select(mapOf(dependency to listOf("2.0", "1.3", "1.2", "1.1", "1.0")), age,
            setOf(dependency), "3.9.9", repository::load)
        assertEquals(mapOf(dependency to "1.1"), selection.selected)
        assertTrue(selection.failures.isEmpty())
        assertEquals(listOf(listOf("2.0"), listOf("1.3", "1.2"), listOf("1.1", "1.0")),
            repository.rounds.map { round -> round.map { it.version } })
        assertTrue(repository.rounds.flatten().all { it.release && !it.plugin })
    }

    @Test fun `settled coordinates and disabled ages need no release dates`() = runBlocking {
        val repository = Repository(emptyMap())
        val candidates = mapOf(dependency to listOf("2.0", "1.1"))
        assertEquals(mapOf(dependency to "2.0"), MavenCandidateWalk.select(candidates, age, emptySet(), null, repository::load).selected)
        assertEquals(mapOf(dependency to "2.0"), MavenCandidateWalk.select(candidates, null, setOf(dependency), null, repository::load).selected)
        assertTrue(repository.rounds.isEmpty())
    }

    @Test fun `plugins must be old enough and runnable`() = runBlocking {
        val repository = Repository(mapOf("3.0" to hoursAgo(1), "2.0" to hoursAgo(48), "1.1" to hoursAgo(72)),
            requiredMaven = mapOf("2.0" to "99.0", "1.1" to "3.6.3"))
        val selection = MavenCandidateWalk.select(mapOf(plugin to listOf("3.0", "2.0", "1.1")), age, setOf(plugin), "3.9.9", repository::load)
        assertEquals(mapOf(plugin to "1.1"), selection.selected)
        val requested = repository.rounds.flatten()
        assertTrue("Plugin release dates use plugin repositories", requested.filter { it.release }.all { it.plugin })
        assertEquals(setOf("3.0", "2.0", "1.1"), requested.filter { !it.release }.map { it.version }.toSet())

        val undated = Repository(emptyMap(), requiredMaven = mapOf("3.0" to "99.0"))
        assertEquals(mapOf(plugin to "2.0"), MavenCandidateWalk.select(mapOf(plugin to listOf("3.0", "2.0")), null,
            emptySet(), "3.9.9", undated::load).selected)
        assertTrue(undated.rounds.flatten().none { it.release })
    }

    @Test fun `unreadable release dates fail only their coordinate`() = runBlocking {
        val other = DependencyVersion("example", "other", "1.0")
        val failing = MavenMetadataKey("example", "library", false, "2.0", release = true)
        val repository = Repository(mapOf("2.0" to hoursAgo(48)), failing = setOf(failing))
        val selection = MavenCandidateWalk.select(mapOf(dependency to listOf("2.0"), other to listOf("2.0")), age,
            setOf(dependency, other), null, repository::load)
        assertEquals(mapOf(other to "2.0"), selection.selected)
        assertEquals(setOf(dependency), selection.failures.keys)
        assertTrue(selection.failures.getValue(dependency).message!!.contains("refused"))
    }

    @Test fun `no candidate old enough leaves no update`() = runBlocking {
        val repository = Repository(mapOf("2.0" to hoursAgo(1), "1.1" to hoursAgo(2)))
        val selection = MavenCandidateWalk.select(mapOf(dependency to listOf("2.0", "1.1")), age, setOf(dependency), null, repository::load)
        assertTrue(selection.selected.isEmpty())
        assertTrue(selection.failures.isEmpty())
    }
}
