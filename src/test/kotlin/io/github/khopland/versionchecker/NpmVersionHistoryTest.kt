package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class NpmVersionHistoryTest {
    @Test fun `equal precedence keeps original candidate order and baseline spelling`() {
        val metadata = NpmPackageMetadata(listOf("1.0.0+first", "2.0.0+b", "1.0.0+second", "2.0.0+a", "2.0.0"))
        assertEquals("1.0.0+first", metadata.history.publishedBaseline(NpmVersion(1, 0, 0)))
        assertEquals(listOf("2.0.0+b", "2.0.0+a", "2.0.0"), NpmRegistry.eligible(metadata, NpmVersion(1, 0, 0), UpdateMode.MAJOR))
        assertNull(metadata.history.publishedBaseline(NpmVersion(1, 0, 1)))
    }

    @Test fun `mode boundaries include safe maximum components without overflow`() {
        val maximum = 9_007_199_254_740_991L
        val metadata = NpmPackageMetadata(listOf("$maximum.$maximum.$maximum", "$maximum.$maximum.1", "$maximum.1.0", "1.0.0"))
        for (mode in UpdateMode.entries) {
            assertEquals(listOf("$maximum.$maximum.$maximum", "$maximum.$maximum.1"),
                NpmRegistry.eligible(metadata, NpmVersion(maximum, maximum, 0), mode))
            assertTrue(NpmRegistry.eligible(metadata, NpmVersion(maximum, maximum, maximum), mode).isEmpty())
        }
    }

    @Test fun `all modes and baselines match original filtering on unordered mixed histories`() {
        val random = Random(42)
        val versions = (0 until 500).flatMap {
            val version = "${random.nextInt(8)}.${random.nextInt(8)}.${random.nextInt(8)}"
            listOf(version, "$version+build.$it", "$version-beta.1")
        }.shuffled(random) + listOf("invalid", "01.2.3", "9007199254740992.0.0", "1.2.3+build..1")
        for (history in listOf(emptyList(), listOf("invalid", "1.0.0-beta.1"), versions)) {
            val metadata = NpmPackageMetadata(history)
            repeat(40) {
                val baseline = NpmVersion(random.nextLong(10), random.nextLong(10), random.nextLong(10))
                assertEquals(history.firstOrNull { NpmVersion.parse(it) == baseline }, metadata.history.publishedBaseline(baseline))
                for (mode in UpdateMode.entries) {
                    val expected = history.mapNotNull { text -> NpmVersion.parse(text)?.let { text to it } }
                        .filter { baseline.allows(it.second, mode) }.sortedByDescending { it.second }.map { it.first }
                    assertEquals("$baseline / $mode", expected, NpmRegistry.eligible(metadata, baseline, mode))
                }
            }
        }
    }

    @Test fun `concurrent consumers share the same prepared history`() = runBlocking {
        val metadata = NpmPackageMetadata((0 until 1_000).map { "1.0.$it" })
        val histories = (0 until 16).map { async(Dispatchers.Default) { metadata.history } }.awaitAll()
        histories.forEach { assertSame(histories.first(), it) }
        assertEquals("1.0.999", histories.first().eligible(NpmVersion(1, 0, 0), UpdateMode.PATCH).first().text)
    }
}
