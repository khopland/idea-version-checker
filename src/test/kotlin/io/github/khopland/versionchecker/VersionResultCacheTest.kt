package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.*
import org.junit.Assert.*
import org.junit.Test

class VersionResultCacheTest {
    private fun snapshot(adapter: String = "maven", root: String = "/one", selector: String = "1.0") = BuildSnapshot(
        BuildContextId(adapter, root, "$root/manifest"), "$root/manifest", BuildFingerprint(mapOf("$root/manifest" to "1"), "settings-1"),
        listOf(VersionDeclaration(DeclarationId("$root/manifest", "dependency"), ArtifactId("npm", "@scope/package"), ArtifactRole.DEPENDENCY, selector, "1.0", "1.1"))
    )
    private fun report(snapshot: BuildSnapshot, version: String = "2.0") = UpdateReport(listOf(UpdateCandidate(snapshot.declarations.single(), version)))

    @Test fun `same artifact in different repositories and providers keeps independent results`() {
        val cache = VersionResultCache()
        val first = snapshot(); val second = snapshot(root = "/two"); val npm = snapshot("npm")
        cache.put(first, cache.revision(first.context), report(first, "1.2"))
        cache.put(second, cache.revision(second.context), report(second, "1.3"))
        cache.put(npm, cache.revision(npm.context), report(npm, "1.4"))
        assertEquals("1.2", cache.get(first)!!.candidates.single().version)
        assertEquals("1.3", cache.get(second)!!.candidates.single().version)
        assertEquals("1.4", cache.get(npm)!!.candidates.single().version)
        cache.invalidateAdapter("maven")
        assertNull(cache.get(first)); assertNull(cache.get(second)); assertNotNull(cache.get(npm))
    }

    @Test fun `current file refresh rejects an old in flight result and preserves other modules`() {
        val cache = VersionResultCache(); val current = snapshot(); val other = snapshot(root = "/other")
        val oldRevision = cache.revision(current.context)
        cache.put(current, oldRevision, report(current)); cache.put(other, cache.revision(other.context), report(other))
        cache.invalidateSource("maven", current.sourceFile)
        assertFalse(cache.put(current, oldRevision, report(current)))
        assertNull(cache.get(current)); assertNotNull(cache.get(other))
        assertTrue(cache.put(current, cache.revision(current.context), report(current, "3.0")))
        assertEquals("3.0", cache.get(current)!!.candidates.single().version)
    }

    @Test fun `provider refresh rejects checks that started before the first cached result`() {
        val cache = VersionResultCache(); val current = snapshot(); val oldRevision = cache.revision(current.context)
        cache.invalidateAdapter("maven")
        assertFalse(cache.put(current, oldRevision, report(current)))
    }

    @Test fun `current file refresh rejects the first in flight check before it caches anything`() {
        val cache = VersionResultCache(); val current = snapshot()
        val oldRevision = cache.begin(current)
        cache.invalidateSource("maven", current.sourceFile)
        assertFalse(cache.put(current, oldRevision, report(current)))
    }

    @Test fun `configuration and selector changes cannot use old metadata`() {
        val cache = VersionResultCache(); val original = snapshot(selector = "^1.0")
        cache.put(original, cache.revision(original.context), report(original))
        val changed = original.copy(fingerprint = original.fingerprint.copy(configuration = "settings-2"))
        assertNull(cache.get(changed))
        val changedSelector = original.copy(declarations = original.declarations.map { it.copy(selector = "~1.0") })
        assertNull(cache.get(changedSelector))
        val declaration = cache.get(original)!!.candidates.single().declaration
        assertEquals("^1.0", declaration.selector)
        assertEquals("1.0", declaration.baseline)
        assertEquals("1.1", declaration.resolvedVersion)
    }

    @Test fun `failed checks are explicit and expire sooner without removing another provider`() {
        var time = 0L
        val cache = VersionResultCache { time }; val maven = snapshot(); val npm = snapshot("npm")
        cache.put(maven, cache.revision(maven.context), report(maven))
        cache.put(npm, cache.revision(npm.context), UpdateReport(failure = "Private registry unavailable"))
        assertFalse(cache.get(npm)!!.successful)
        assertEquals("Private registry unavailable", cache.get(npm)!!.failure)
        time = java.util.concurrent.TimeUnit.MINUTES.toNanos(2)
        assertNull(cache.get(npm)); assertNotNull(cache.get(maven))
        time = java.util.concurrent.TimeUnit.MINUTES.toNanos(11)
        assertNull(cache.get(maven))
    }
}
