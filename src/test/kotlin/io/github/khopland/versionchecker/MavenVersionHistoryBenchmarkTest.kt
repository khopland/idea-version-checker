package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random
import kotlin.system.measureNanoTime

/** Opt-in CPU comparison, including first index construction. No Maven goal, HTTP or editor paint. */
class MavenVersionHistoryBenchmarkTest {
    @Test fun compareRepeatedConsumersWithTheOriginalSelectionPath() {
        for ((historySize, consumers) in listOf(100 to 2, 100 to 100, 5_000 to 100)) {
            val versions = ((0 until historySize).map { "${1 + it / 500}.${it / 50 % 10}.${it % 50}" } +
                listOf("99-RC1", "100-SNAPSHOT", "LATEST", "2.0.0", "2.0")).shuffled(Random(42))
            val baselines = (0 until consumers).map { if (it % 2 == 0) "1.0.0" else "1.1.0" }
            fun original() = UpdateMode.entries.flatMap { mode -> baselines.map { current ->
                MavenRepositoryMetadata.latest(versions, current, mode) to MavenVersionIndexTest.originalEligible(versions, current, mode)
            } }
            fun indexed(): List<Pair<String?, List<String>>> {
                val value = MavenMetadataValue(versions)
                return UpdateMode.entries.flatMap { mode -> baselines.map { current ->
                    value.versionIndex.latest(current, mode) to value.versionIndex.eligible(current, mode)
                } }
            }
            val expected = original()
            repeat(3) { assertEquals(expected, original()); assertEquals(expected, indexed()) }
            val oldSamples = mutableListOf<Long>()
            val newSamples = mutableListOf<Long>()
            repeat(5) { sample ->
                fun old() { oldSamples += measureNanoTime { assertEquals(expected, original()) } }
                fun new() { newSamples += measureNanoTime { assertEquals(expected, indexed()) } }
                if (sample % 2 == 0) { old(); new() } else { new(); old() }
            }
            for ((path, samples) in listOf("original" to oldSamples, "indexed" to newSamples))
                println("maven-history versions=$historySize consumers=$consumers modes=3 path=$path samplesNs=$samples medianNs=${samples.sorted()[2]}")
        }
    }
}
