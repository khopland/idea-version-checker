package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NpmRegistryMetadataTest {
    @Test fun `normalizes one version and retains later deprecations without a latest tag limit`() {
        val single = NpmRegistry.parseMetadata("""{"name":"@scope/pkg","version":"1.0.0"}""", "@scope/pkg")
        assertEquals(listOf("1.0.0"), single.versions)
        assertEquals(emptyMap<String, String>(), single.deprecatedByVersion)
        val multiple = NpmRegistry.parseMetadata("""[
            {"name":"pkg","version":"1.0.0"},
            {"name":"pkg","version":"2.0.0","deprecated":"Broken release"},
            {"name":"pkg","version":"4.0.0","deprecated":""}
        ]""", "pkg")
        assertEquals(listOf("1.0.0", "2.0.0", "4.0.0"), multiple.versions)
        assertEquals(mapOf("2.0.0" to "Broken release"), multiple.deprecatedByVersion)
        assertEquals("4.0.0", NpmRegistry.eligible(multiple, NpmVersion(1, 0, 0), UpdateMode.MAJOR).first())
    }

    @Test fun `rejects malformed incomplete or mismatched objects`() {
        for (json in listOf("null", "{}", "\"1.0.0\"", "[\"1.0.0\"]", "{",
            """{"name":"other","version":"1.0.0"}""",
            """{"name":"pkg","version":1}""",
            """{"name":"pkg","version":"1.0.0","deprecated":false}""",
            """{"name":"pkg","version":"1.0.0","deprecated":null}""",
            """{"name":"pkg","version":"2.0.0-beta.1"}""",
            """[{"name":"pkg","version":"1.0.0"},{"name":"pkg","version":"1.0.0"}]""")) {
            assertThrows(json, UnsupportedNpmMetadata::class.java) { NpmRegistry.parseMetadata(json, "pkg") }
        }
    }

    @Test fun `combined query uses an explicit stable range and all three fields`() = runBlocking {
        val queries = mutableListOf<List<String>>()
        val metadata = NpmRegistry.loadMetadata("@scope/pkg") { args ->
            queries += args
            """{"name":"@scope/pkg","version":"1.0.0","deprecated":"Retired"}"""
        }
        assertEquals(listOf(listOf("@scope/pkg@>=0.0.0", "name", "version", "deprecated")), queries)
        assertEquals(mapOf("1.0.0" to "Retired"), metadata.deprecatedByVersion)
    }

    @Test fun `unsupported combined response falls back to strict versions-only parsing`(): Unit = runBlocking {
        val queries = mutableListOf<List<String>>()
        val metadata = NpmRegistry.loadMetadata("pkg") { args ->
            queries += args
            if (queries.size == 1) """{"version":"1.0.0"}""" else """["1.0.0","2.0.0"]"""
        }
        assertEquals(2, queries.size)
        assertEquals(listOf("pkg", "versions"), queries.last())
        assertNull(metadata.deprecatedByVersion)
        assertThrows(IllegalStateException::class.java) { NpmRegistry.parseLegacyMetadata("""["1.0.0",{}]""") }
    }

    @Test fun `no stable range match falls back but authentication and transport failures propagate`() = runBlocking {
        var calls = 0
        val metadata = NpmRegistry.loadMetadata("pkg") {
            if (++calls == 1) throw NpmViewFailure("E404", "No range match")
            """["1.0.0-beta.1"]"""
        }
        assertEquals(2, calls)
        assertTrue(NpmRegistry.eligible(metadata, NpmVersion(1, 0, 0), UpdateMode.MAJOR).isEmpty())
        for (code in listOf("E401", "E403", "ENOTFOUND", null)) {
            var attempts = 0
            val failure = NpmViewFailure(code, "Denied")
            try {
                NpmRegistry.loadMetadata("pkg") { attempts++; throw failure }
                fail("Expected failure")
            } catch (actual: NpmViewFailure) { assertSame(failure, actual) }
            assertEquals(1, attempts)
        }
    }

    @Test fun `cancellation cannot start the compatibility fallback`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val query = launch {
            NpmRegistry.loadMetadata("pkg") { calls++; started.complete(Unit); awaitCancellation() }
        }
        withTimeout(5_000) { started.await() }
        query.cancelAndJoin()
        assertEquals(1, calls)
    }

    @Test fun `large histories retain all version notices and accept empty stable histories`() {
        val metadata = NpmRegistry.parseMetadata((0 until 10_000).joinToString(",", "[", "]") {
            """{"name":"pkg","version":"1.0.$it","deprecated":"Retired $it"}"""
        }, "pkg")
        assertEquals(10_000, metadata.versions.size)
        assertEquals(10_000, metadata.deprecatedByVersion!!.size)
        assertEquals("Retired 9999", metadata.deprecatedByVersion["1.0.9999"])
        assertTrue(NpmRegistry.parseMetadata("[]", "pkg").versions.isEmpty())
    }
}
