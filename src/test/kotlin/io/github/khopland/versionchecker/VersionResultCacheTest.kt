package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.UpdateMode
import org.junit.Assert.*
import org.junit.Test
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class VersionResultCacheTest {
    private fun snapshot(adapter: String = "maven", root: String = "/one", selector: String = "1.0") = BuildSnapshot(
        BuildContextId(adapter, root, "$root/manifest"), "$root/manifest", BuildFingerprint(mapOf("$root/manifest" to "1"), "settings-1"),
        listOf(VersionDeclaration(DeclarationId("$root/manifest", "dependency"), ArtifactId("npm", "@scope/package"), selector, "1.0", "1.1"))
    )
    private fun report(snapshot: BuildSnapshot, version: String = "2.0") = UpdateReport(listOf(UpdateCandidate(snapshot.declarations.single(), version)))
    private val presentationAdapter = object : BuildSystemAdapter {
        override val id = "npm"
        override val displayName = "test"
        override val capabilities = AdapterCapabilities()
        override fun supports(project: Project, selection: BuildSelection) = true
        override fun isOffline(project: Project) = false
        override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? = null
        override fun isCurrent(project: Project, snapshot: BuildSnapshot) = true
        override suspend fun discover(project: Project, selection: BuildSelection) = emptyList<BuildSnapshot>()
        override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode) = error("Not used")
        override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>) = error("Not used")
        override fun retainInspectionReport(previous: BuildSnapshot, report: UpdateReport, current: BuildSnapshot) =
            report.copy(candidates = report.candidates.filter { it.declaration in current.declarations },
                notices = report.notices.filter { it.declaration in current.declarations })
    }

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

    @Test fun `reusing native metadata cannot extend its original freshness deadline`() {
        var time = 0L
        val cache = VersionResultCache { time }; val npm = snapshot("npm")
        val result = report(npm).copy(validUntilNanos = 100)
        assertTrue(cache.put(npm, cache.revision(npm.context), result))
        time = 90
        assertTrue(cache.put(npm, cache.revision(npm.context), result))
        assertNotNull(cache.get(npm))
        time = 100
        assertNull(cache.get(npm))
        assertFalse(cache.put(npm, cache.revision(npm.context), result))
    }

    @Test fun `modes are isolated and source refresh invalidates every mode`() {
        val cache = VersionResultCache()
        val snapshot = snapshot()
        for ((mode, version) in listOf(UpdateMode.PATCH to "1.0.9", UpdateMode.MINOR to "1.9.0", UpdateMode.MAJOR to "2.0.0")) {
            cache.put(snapshot, cache.begin(snapshot), report(snapshot, version), mode)
        }
        assertEquals("1.0.9", cache.get(snapshot, UpdateMode.PATCH)!!.candidates.single().version)
        assertEquals("1.9.0", cache.get(snapshot, UpdateMode.MINOR)!!.candidates.single().version)
        assertEquals("2.0.0", cache.get(snapshot)!!.candidates.single().version)
        cache.invalidateSource(snapshot.context.adapterId, snapshot.sourceFile)
        assertTrue(UpdateMode.entries.all { cache.get(snapshot, it) == null })
    }

    @Test fun `preview lineage expires and cannot follow a replaced result even at the same clock tick`() {
        var time = 0L
        val cache = VersionResultCache { time }
        val snapshot = snapshot()
        cache.put(snapshot, cache.begin(snapshot), report(snapshot).copy(validUntilNanos = 100), UpdateMode.PATCH)
        val prepared = cache.getResult(snapshot, UpdateMode.PATCH)!!
        assertTrue(cache.isCurrent(snapshot.context, UpdateMode.PATCH, prepared))
        cache.put(snapshot, cache.begin(snapshot), report(snapshot, "3.0"), UpdateMode.PATCH)
        assertFalse(cache.isCurrent(snapshot.context, UpdateMode.PATCH, prepared))
        val replaced = cache.getResult(snapshot, UpdateMode.PATCH)!!
        cache.invalidateSource(snapshot.context.adapterId, snapshot.sourceFile)
        assertFalse(cache.isCurrent(snapshot.context, UpdateMode.PATCH, replaced))
        cache.put(snapshot, cache.begin(snapshot), report(snapshot).copy(validUntilNanos = 100), UpdateMode.PATCH)
        val last = cache.getResult(snapshot, UpdateMode.PATCH)!!
        time = 100
        assertFalse(cache.isCurrent(snapshot.context, UpdateMode.PATCH, last))
    }

    @Test fun `early hints cannot satisfy a complete check and expire with their original metadata`() {
        var time = 0L
        val cache = VersionResultCache { time }
        val snapshot = snapshot("npm")
        val owner = Any()
        val revision = cache.begin(snapshot)
        assertTrue(cache.putProgress(presentationAdapter, snapshot, revision, owner,
            InspectionUpdate(report(snapshot).copy(validUntilNanos = 100), snapshot.declarations.map { it.id }.toSet())))
        assertEquals("2.0", cache.inspectionProgress(presentationAdapter, snapshot)!!.candidates.single().version)
        assertNull(cache.get(snapshot))
        assertNull(cache.getResult(snapshot, UpdateMode.MAJOR))
        time = 90
        cache.putProgress(presentationAdapter, snapshot, revision, owner,
            InspectionUpdate(report(snapshot), snapshot.declarations.map { it.id }.toSet()))
        time = 100
        assertNull(cache.inspectionProgress(presentationAdapter, snapshot))
    }

    @Test fun `finished up to date declarations replace old hints while unprocessed declarations retain theirs`() {
        val cache = VersionResultCache()
        val base = snapshot("npm")
        val second = base.declarations.single().copy(id = DeclarationId(base.sourceFile, "second"), artifact = ArtifactId("npm", "second"))
        val snapshot = base.copy(declarations = base.declarations + second)
        cache.put(snapshot, cache.begin(snapshot), UpdateReport(snapshot.declarations.map { UpdateCandidate(it, "2.0") }))
        val changed = snapshot.copy(fingerprint = snapshot.fingerprint.copy(files = mapOf(base.sourceFile to "changed")))
        val owner = Any()
        cache.putProgress(presentationAdapter, changed, cache.begin(changed), owner,
            InspectionUpdate(UpdateReport(), setOf(snapshot.declarations.first().id)))
        assertEquals(listOf(second), cache.inspectionProgress(presentationAdapter, changed)!!.candidates.map { it.declaration })
        cache.putProgress(presentationAdapter, changed, cache.begin(changed), owner,
            InspectionUpdate(UpdateReport(listOf(UpdateCandidate(second, "3.0"))), setOf(second.id)))
        assertEquals("3.0", cache.inspectionProgress(presentationAdapter, changed)!!.candidates.single().version)
        cache.put(changed, cache.begin(changed), UpdateReport())
        assertNull(cache.inspectionProgress(presentationAdapter, changed))
    }

    @Test fun `refresh and cancellation reject obsolete progress without clearing replacement owners`() {
        val cache = VersionResultCache()
        val snapshot = snapshot("npm")
        val revision = cache.begin(snapshot)
        val first = Any()
        val second = Any()
        val update = InspectionUpdate(report(snapshot), snapshot.declarations.map { it.id }.toSet())
        cache.putProgress(presentationAdapter, snapshot, revision, first, update)
        cache.putProgress(presentationAdapter, snapshot, revision, second, update)
        assertFalse(cache.clearProgress(snapshot.context, first))
        assertNotNull(cache.inspectionProgress(presentationAdapter, snapshot))
        assertTrue(cache.clearProgress(snapshot.context, second))
        assertNull(cache.inspectionProgress(presentationAdapter, snapshot))
        cache.putProgress(presentationAdapter, snapshot, revision, first, update)
        cache.invalidateSource("npm", snapshot.sourceFile)
        assertFalse(cache.putProgress(presentationAdapter, snapshot, revision, first, update))
        assertNull(cache.inspectionProgress(presentationAdapter, snapshot))
    }

    @Test fun `a newer partial check stops an older complete report authorizing a preview`() {
        val cache = VersionResultCache()
        val snapshot = snapshot("npm")
        cache.put(snapshot, cache.begin(snapshot), report(snapshot), UpdateMode.MAJOR)
        val prepared = cache.getResult(snapshot, UpdateMode.MAJOR)!!
        cache.putProgress(presentationAdapter, snapshot, cache.begin(snapshot), Any(),
            InspectionUpdate(report(snapshot, "3.0"), snapshot.declarations.map { it.id }.toSet()))
        assertNull(cache.get(snapshot))
        assertFalse(cache.isCurrent(snapshot.context, UpdateMode.MAJOR, prepared))
        assertEquals("3.0", cache.inspectionProgress(presentationAdapter, snapshot)!!.candidates.single().version)
    }
}
