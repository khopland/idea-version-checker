package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.DeclarationId
import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class NpmTargetDetailsTest {
    private val target = NpmTarget(DeclarationId("/project/packages/app/package.json", "dependencies/alias"),
        "/project", "@fixture/actual", "2.0.1+build", "1.0.0", "npm:@fixture/actual@^1.0.0")

    @Test fun exactSelectedVersionIsTheOnlyQueryIncludingAliasRegistryIdentity() = runBlocking {
        val queries = mutableListOf<List<String>>()
        val details = NpmRegistry.loadTargetDetails(target) {
            queries += it
            """{"name":"@fixture/actual","version":"2.0.1+build","engines":{"node":">=20"},
                "peerDependencies":{"react":"^18 || ^19"},"repository":{"url":"https://example.com/source?token=private#secret"},
                "homepage":"https://example.com/docs"}"""
        }
        assertEquals(listOf(listOf("@fixture/actual@2.0.1+build", "name", "version", "engines", "peerDependencies", "repository", "homepage")), queries)
        assertEquals(mapOf("node" to ">=20"), details.engines)
        assertEquals(mapOf("react" to "^18 || ^19"), details.peers)
        assertEquals("https://example.com/source", details.repository)
        val text = details.copy(declaredPeers = mapOf("react" to "~18.2.0")).description(target)
        assertTrue(text.contains("Declared baseline: 1.0.0"))
        assertTrue(text.contains("Runtime baseline: Unknown"))
        assertTrue(text.contains("react project declaration: ~18.2.0; installed version unknown"))
        assertTrue(text.contains("Compatibility: Unverified"))
        assertFalse(text.contains("private"))
        assertFalse(text.contains("secret"))
    }

    @Test fun incompleteMalformedOrMismatchedMetadataStaysUnknown() {
        for (json in listOf("", "null", "[]", "{", "{}",
            """{"name":"wrong","version":"2.0.1+build","engines":{"node":">=20"}}""",
            """{"name":"@fixture/actual","version":"2.0.2","engines":{"node":">=20"}}""")) {
            assertEquals(NpmTargetDetails(), NpmTargetDetails.parse(json, target))
        }
        val malformed = NpmTargetDetails.parse("""{"name":"@fixture/actual","version":"2.0.1+build",
            "engines":{"node":20},"peerDependencies":["react"],"homepage":{},"repository":{"url":false}}""", target)
        assertEquals(NpmTargetDetails(), malformed)
        assertTrue(malformed.description(target).contains("Peer requirements: Unknown"))
        val empty = NpmTargetDetails.parse("""{"name":"@fixture/actual","version":"2.0.1+build","engines":{}}""", target)
        assertTrue(empty.description(target).contains("Engine requirements: No entries published"))
    }

    @Test fun externalLinksOnlyKeepKnownHttpsUrlsWithoutAuthorityCredentialsOrSecrets() {
        for (url in listOf("http://example.com/repo", "file:///etc/passwd", "javascript:alert(1)",
            "git+https://example.com/repo.git", "git@example.com:repo", "//example.com/repo", "https://user:pass@example.com/repo",
            "https://user%40secret@example.com/repo", "https://example.com\\@other.example/repo", "https:///repo", "https://example.com/\nrepo")) {
            assertNull(url, NpmTargetDetails.safeHttpsUrl(url))
        }
        assertEquals("https://example.com/repo.git", NpmTargetDetails.safeHttpsUrl("https://example.com/repo.git?access_token=secret#private"))
        assertEquals("https://example.com/known/path", NpmTargetDetails.safeHttpsUrl("https://example.com/known/path"))
    }

    @Test fun selectionIsLocalAndStaleNonCooperativeCompletionsCannotPublish() {
        val pending = mutableListOf<Pair<NpmTarget, Continuation<NpmTargetDetails>>>()
        val published = mutableListOf<NpmTargetDetails?>()
        val requests = NpmTargetDetailsRequests(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            { selected -> suspendCoroutine { pending += selected to it } }, { _, details -> published += details })
        try {
            requests.select(target)
            requests.select(target.copy(version = "2.0.2"))
            assertTrue("Selection must not query", pending.isEmpty())
            requests.request()
            assertEquals("2.0.2", pending.single().first.version)
            requests.request()
            assertEquals("Repeated clicks while loading must not start another request", 1, pending.size)
            requests.select(target)
            pending[0].second.resume(NpmTargetDetails(engines = mapOf("node" to "STALE")))
            assertTrue(published.isEmpty())
            requests.request()
            pending[1].second.resume(NpmTargetDetails(engines = mapOf("node" to ">=20")))
            assertEquals(listOf(NpmTargetDetails(engines = mapOf("node" to ">=20"))), published)
            assertFalse(requests.loading)
            requests.request()
            requests.close()
            pending[2].second.resume(NpmTargetDetails(engines = mapOf("node" to "CLOSED")))
            assertEquals(1, published.size)
        } finally { requests.close() }
    }

    @Test fun failedLookupPublishesUnknownAndCanRetryWithoutChangingTheTarget() {
        var attempts = 0
        val published = mutableListOf<NpmTargetDetails?>()
        val requests = NpmTargetDetailsRequests(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            { if (++attempts == 1) error("private native output") else NpmTargetDetails() }, { _, details -> published += details })
        try {
            requests.select(target)
            requests.request()
            assertEquals(listOf<NpmTargetDetails?>(null), published)
            assertEquals(target, requests.target)
            requests.request()
            assertEquals(listOf(null, NpmTargetDetails()), published)
        } finally { requests.close() }
    }
}
