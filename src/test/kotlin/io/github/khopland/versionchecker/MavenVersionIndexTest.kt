package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*
import org.jetbrains.idea.maven.dom.MavenVersionComparable
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class MavenVersionIndexTest {
    @Test fun `prepared histories preserve selection and plugin ordering for all modes`() {
        val spellings = listOf("", "LATEST", "RELEASE", "[1,2)", "\${version}", "1", "1.0", "1.0.0", "1.0-final",
            "1.0-GA", "1.0.1", "1.1", "1.2.3.4", "01.02.4", "1.3-RC1", "1.4-SNAPSHOT", "1.5-beta-2",
            "2", "2.0", "2.0.0", "2.1", "2.1.Final", "foo", "bar", "999999999999999999.1", "1.99999999999999.1")
        val baselines = listOf("", "0", "1", "1.0", "1.0-final", "1.0.1", "01.02.3", "1.3-RC1", "2.0", "2.1", "foo",
            "99.0", "999999999999999999.0", "1.99999999999999.0")
        repeat(20) { sample ->
            val versions = (spellings + spellings.take(8)).shuffled(Random(sample))
            val index = MavenMetadataValue(versions).versionIndex
            for (baseline in baselines) for (mode in UpdateMode.entries) {
                assertEquals("$sample $baseline $mode", MavenRepositoryMetadata.latest(versions, baseline, mode), index.latest(baseline, mode))
                assertEquals(originalEligible(versions, baseline, mode), index.eligible(baseline, mode))
            }
        }
    }

    @Test fun `equivalent Maven spellings retain the first repository spelling`() {
        for (versions in listOf(listOf("2", "2.0", "2.0.0"), listOf("2.0.0", "2.0", "2"))) {
            assertEquals(versions.first(), MavenMetadataValue(versions).versionIndex.latest("1", UpdateMode.MAJOR))
        }
    }

    companion object {
        internal fun originalEligible(versions: List<String>, current: String, mode: UpdateMode): List<String> = versions.distinct()
            .filter { MavenRepositoryMetadata.latest(listOf(it), current, mode) != null }
            .sortedWith { left, right -> MavenVersionComparable(right).compareTo(MavenVersionComparable(left)) }
    }
}
