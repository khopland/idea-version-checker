package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.UpdateMode
import org.junit.Assert.*
import org.junit.Test
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class VersionResultCacheTest {
    @Test fun `scoped partial fixes survive unrelated rows but retire touched declarations without revival`() {
        val cache = VersionResultCache { 100L }
        val original = snapshot("npm")
        val a = original.declarations.single()
        val b = a.copy(id = a.id.copy(location = "second"), artifact = ArtifactId("npm", "second"))
        val snapshot = original.copy(declarations = listOf(a, b))
        val revision = cache.begin(snapshot); val owner = Any()
        fun publish(declaration: VersionDeclaration, version: String? = "2.0") = cache.putProgress(presentationAdapter,
            snapshot, revision, owner, InspectionUpdate(UpdateReport(candidates = version?.let {
                listOf(UpdateCandidate(declaration, it))
            }.orEmpty()), setOf(declaration.id)))
        assertTrue(publish(a))
        val observation = cache.inspectionReport(presentationAdapter, snapshot)!!.lease!!
        val fixA = observation.forDeclarations(setOf(a.id))
        assertTrue(publish(b))
        assertFalse(observation.isCurrent())
        assertTrue("Unrelated completion must keep a held fix actionable", fixA.isCurrent())
        val fixB = cache.inspectionReport(presentationAdapter, snapshot)!!.lease!!.forDeclarations(setOf(b.id))
        assertTrue(publish(a))
        assertFalse("Even an identical reobservation replaces this declaration", fixA.isCurrent())
        assertTrue(fixB.isCurrent())
        val replacedA = cache.inspectionReport(presentationAdapter, snapshot)!!.lease!!.forDeclarations(setOf(a.id))
        assertTrue(publish(a, null)); assertFalse(replacedA.isCurrent())
        assertTrue(publish(a)); assertFalse(replacedA.isCurrent())
        assertTrue(fixB.isCurrent())
        assertNull("Partial facts cannot authorize a complete preview", cache.getResult(snapshot, UpdateMode.MAJOR))
    }

    @Test fun `shared partial scopes guard every contributor and fail closed for absent or late bindings`() {
        val cache = VersionResultCache { 100L }
        val original = snapshot("npm"); val a = original.declarations.single()
        val b = a.copy(id = a.id.copy(location = "second"))
        val c = a.copy(id = a.id.copy(location = "third"), artifact = ArtifactId("npm", "other"))
        val snapshot = original.copy(declarations = listOf(a, b, c))
        val owner = Any(); val revision = cache.begin(snapshot)
        assertTrue(cache.putProgress(presentationAdapter, snapshot, revision, owner,
            InspectionUpdate(UpdateReport(listOf(UpdateCandidate(a, "2.0"), UpdateCandidate(b, "2.0"))), setOf(a.id, b.id))))
        val observation = cache.inspectionReport(presentationAdapter, snapshot)!!.lease!!
        val shared = observation.forDeclarations(setOf(a.id, b.id))
        val local = observation.forDeclarations(setOf(a.id))
        assertFalse("A local authorization cannot broaden into a shared edit", local.forDeclarations(setOf(a.id, b.id)).isCurrent())
        assertSame("Narrowing retains the original conservative contributors", shared, shared.forDeclarations(setOf(a.id)))
        assertFalse(observation.forDeclarations(emptySet()).isCurrent())
        assertFalse(observation.forDeclarations(setOf(a.id, c.id)).isCurrent())
        assertTrue(cache.putProgress(presentationAdapter, snapshot, revision, owner,
            InspectionUpdate(UpdateReport(failure = "other package failed", coverage = mapOf(c.id to
                DeclarationCoverage(DeclarationOutcome.FAILED, "offline"))), setOf(c.id))))
        assertTrue(shared.isCurrent()); assertTrue(local.isCurrent())
        assertFalse("Binding a retired observation cannot bless its old rows", observation.forDeclarations(setOf(a.id)).isCurrent())
        assertTrue(cache.putProgress(presentationAdapter, snapshot, revision, owner,
            InspectionUpdate(UpdateReport(notices = listOf(UpdateNotice(b, NoticeKind.DEPRECATED, "retired"))), setOf(b.id))))
        assertFalse(shared.isCurrent()); assertTrue(local.isCurrent())
    }

    @Test fun `scoped partial leases keep original deadlines and cannot outlive shorter later deadlines`() {
        var time = 100L
        val cache = VersionResultCache { time }; val snapshot = snapshot("npm")
        val owner = Any(); val revision = cache.begin(snapshot)
        assertTrue(cache.putProgress(presentationAdapter, snapshot, revision, owner, InspectionUpdate(
            report(snapshot).copy(checkedAtNanos = 10, validUntilNanos = 1_000), emptySet())))
        val lease = cache.inspectionReport(presentationAdapter, snapshot)!!.lease!!.forDeclarations(snapshot.declarations.map { it.id }.toSet())
        assertEquals(10L, lease.checkedAtNanos); assertEquals(1_000L, lease.expiresAtNanos)
        time = 500
        assertTrue(cache.putProgress(presentationAdapter, snapshot, revision, owner,
            InspectionUpdate(UpdateReport(validUntilNanos = 600), emptySet())))
        assertTrue(lease.isCurrent())
        time = 600
        assertFalse(lease.isCurrent())
        assertTrue(cache.putProgress(presentationAdapter, snapshot, revision, owner,
            InspectionUpdate(report(snapshot).copy(validUntilNanos = 2_000), emptySet())))
        assertFalse("A new progress entry cannot revive expired lineage", lease.isCurrent())
    }

    @Test fun `refresh cancellation owner replacement eviction and completion retire scoped partial leases`() {
        for (retire in listOf<(VersionResultCache, BuildSnapshot, Any) -> Unit>(
            { cache, snapshot, _ -> cache.invalidateSource(snapshot.context.adapterId, snapshot.sourceFile) },
            { cache, snapshot, _ -> cache.invalidateAdapter(snapshot.context.adapterId) },
            { cache, snapshot, owner -> cache.clearProgress(snapshot.context, owner) },
            { cache, snapshot, _ -> cache.putProgress(presentationAdapter, snapshot, cache.revision(snapshot.context), Any(),
                InspectionUpdate(report(snapshot), emptySet())) },
            { cache, snapshot, _ -> cache.put(snapshot, cache.revision(snapshot.context), report(snapshot)) },
            { cache, _, _ -> cache.invalidate() },
            { cache, _, _ -> val other = snapshot("maven", "/other"); cache.put(other, cache.begin(other), report(other)) }
        )) {
            val cache = VersionResultCache(VersionResultCache.Limits(contexts = 1))
            val snapshot = snapshot("npm"); val owner = Any()
            assertTrue(cache.putProgress(presentationAdapter, snapshot, cache.begin(snapshot), owner, InspectionUpdate(report(snapshot), emptySet())))
            val scoped = cache.inspectionReport(presentationAdapter, snapshot)!!.lease!!.forDeclarations(snapshot.declarations.map { it.id }.toSet())
            assertTrue(scoped.isCurrent())
            retire(cache, snapshot, owner)
            assertFalse(scoped.isCurrent())
            cache.putProgress(presentationAdapter, snapshot, cache.begin(snapshot), owner, InspectionUpdate(report(snapshot), emptySet()))
            assertFalse("Retired tokens cannot follow an identical replacement", scoped.isCurrent())
        }
    }

    @Test fun `remapped partial hints retain conservative whole observation authorization`() {
        val cache = VersionResultCache(); val snapshot = snapshot("npm"); val owner = Any()
        val revision = cache.begin(snapshot)
        assertTrue(cache.putProgress(presentationAdapter, snapshot, revision, owner, InspectionUpdate(report(snapshot), emptySet())))
        val edited = snapshot.copy(fingerprint = snapshot.fingerprint.copy(configuration = "edited"))
        val view = cache.inspectionReport(presentationAdapter, edited)!!.lease!!
        assertSame(view, view.forDeclarations(snapshot.declarations.map { it.id }.toSet()))
        assertTrue(cache.putProgress(presentationAdapter, snapshot, revision, owner, InspectionUpdate(UpdateReport(), emptySet())))
        assertFalse(view.isCurrent())
    }

    @Test fun `partial declaration token history remains bounded and removed rows release its weight`() {
        val cache = VersionResultCache(); val snapshot = snapshot("npm"); val owner = Any(); val revision = cache.begin(snapshot)
        fun publish(report: UpdateReport, completed: Set<DeclarationId> = emptySet()) =
            cache.putProgress(presentationAdapter, snapshot, revision, owner, InspectionUpdate(report, completed))
        assertTrue(publish(UpdateReport()))
        val emptyWeight = cache.retention().payloadUnits
        assertTrue(publish(report(snapshot)))
        val filledWeight = cache.retention().payloadUnits
        repeat(1_000) { assertTrue(publish(report(snapshot))); assertEquals(filledWeight, cache.retention().payloadUnits) }
        assertTrue(publish(UpdateReport(), snapshot.declarations.map { it.id }.toSet()))
        assertEquals(emptyWeight, cache.retention().payloadUnits)
    }

    @Test fun `quick fix and preview leases share original complete result identity and deadline`() {
        var time = 100L
        val cache = VersionResultCache { time }
        val snapshot = snapshot("npm")
        val report = report(snapshot).copy(checkedAtNanos = 10, validUntilNanos = 1_000)
        assertTrue(cache.put(snapshot, cache.begin(snapshot), report))
        val preview = cache.getResult(snapshot, UpdateMode.MAJOR)!!
        val inspection = cache.inspectionReport(presentationAdapter, snapshot)!!
        val fixLease = inspection.lease!!
        assertNull("Raw metadata must not own a presentation lease", cache.get(snapshot)!!.lease)
        assertEquals(preview.checkedAtNanos, fixLease.checkedAtNanos)
        assertEquals(preview.expiresAtNanos, fixLease.expiresAtNanos)
        assertEquals(preview.revision, fixLease.revision)
        assertTrue(preview.lease.isCurrent()); assertTrue(fixLease.isCurrent())
        time = 999
        val filtered = inspection.copy(candidates = emptyList())
        assertSame(inspection.lease, filtered.lease)
        time = 1_000
        assertFalse(preview.lease.isCurrent()); assertFalse(filtered.lease!!.isCurrent())
        assertFalse(cache.isCurrent(snapshot.context, UpdateMode.MAJOR, preview))
    }

    @Test fun `republishing the same object in the same clock tick retires held quick fixes and previews`() {
        val cache = VersionResultCache { 100L }
        val snapshot = snapshot("npm"); val report = report(snapshot)
        val revision = cache.begin(snapshot)
        cache.put(snapshot, revision, report)
        val fix = cache.inspectionReport(presentationAdapter, snapshot)!!.lease!!
        val preview = cache.getResult(snapshot, UpdateMode.MAJOR)!!
        cache.put(snapshot, revision, report)
        assertFalse(fix.isCurrent()); assertFalse(preview.lease.isCurrent())
        assertTrue(cache.inspectionReport(presentationAdapter, snapshot)!!.lease!!.isCurrent())
    }

    @Test fun `partial observations authorize their quick fixes but never a complete preview`() {
        var time = 100L
        val cache = VersionResultCache { time }
        val snapshot = snapshot("npm"); val owner = Any(); val revision = cache.begin(snapshot)
        fun update(version: String) = InspectionUpdate(report(snapshot, version).copy(validUntilNanos = 1_000,
            checkedAtNanos = 10), snapshot.declarations.map { it.id }.toSet())
        assertTrue(cache.putProgress(presentationAdapter, snapshot, revision, owner, update("2.0")))
        val first = cache.inspectionReport(presentationAdapter, snapshot)!!.lease!!
        assertTrue(first.isCurrent()); assertNull(cache.getResult(snapshot, UpdateMode.MAJOR))
        time = 500
        assertTrue(cache.putProgress(presentationAdapter, snapshot, revision, owner, update("3.0")))
        assertFalse(first.isCurrent())
        val second = cache.inspectionReport(presentationAdapter, snapshot)!!.lease!!
        assertEquals(10L, second.checkedAtNanos); assertEquals(1_000L, second.expiresAtNanos)
        assertTrue(second.isCurrent())
        assertTrue(cache.put(snapshot, revision, report(snapshot, "3.0")))
        assertFalse(second.isCurrent())
        assertTrue(cache.getResult(snapshot, UpdateMode.MAJOR)!!.lease.isCurrent())
    }

    @Test fun `retained hints cannot renew their originating lease after declaration edits`() {
        var time = 100L
        val cache = VersionResultCache { time }
        val original = snapshot("npm")
        val second = original.declarations.single().copy(id = DeclarationId(original.sourceFile, "second"))
        val previous = original.copy(declarations = original.declarations + second)
        cache.put(previous, cache.begin(previous), UpdateReport(previous.declarations.map { UpdateCandidate(it, "2.0") },
            checkedAtNanos = 10, validUntilNanos = 1_000))
        val changed = previous.copy(fingerprint = previous.fingerprint.copy(configuration = "changed"), declarations = listOf(second))
        time = 900
        val retained = cache.inspectionReport(presentationAdapter, changed)!!
        val retainedLease = retained.lease!!
        assertEquals(listOf(second), retained.candidates.map { it.declaration })
        assertEquals(10L, retainedLease.checkedAtNanos); assertEquals(1_000L, retainedLease.expiresAtNanos)
        assertTrue(retainedLease.isCurrent())
        assertNull(cache.getResult(changed, UpdateMode.MAJOR))
        time = 1_000
        assertFalse(retainedLease.isCurrent())
    }

    @Test fun `source provider refresh and progress cancellation reject held hint leases`() {
        val cache = VersionResultCache()
        val snapshot = snapshot("npm"); val owner = Any()
        cache.put(snapshot, cache.begin(snapshot), report(snapshot))
        val source = cache.inspectionReport(presentationAdapter, snapshot)!!.lease!!
        cache.invalidateSource("npm", snapshot.sourceFile)
        assertFalse(source.isCurrent())
        cache.put(snapshot, cache.begin(snapshot), report(snapshot))
        val provider = cache.inspectionReport(presentationAdapter, snapshot)!!.lease!!
        cache.invalidateAdapter("npm")
        assertFalse(provider.isCurrent())
        cache.putProgress(presentationAdapter, snapshot, cache.begin(snapshot), owner, InspectionUpdate(report(snapshot), emptySet()))
        val partial = cache.inspectionReport(presentationAdapter, snapshot)!!.lease!!
        cache.clearProgress(snapshot.context, owner)
        assertFalse(partial.isCurrent())
        cache.put(snapshot, cache.begin(snapshot), report(snapshot))
        val disposed = cache.inspectionReport(presentationAdapter, snapshot)!!.lease!!
        cache.invalidate()
        assertFalse(disposed.isCurrent())
    }

    @Test fun `capacity eviction retires lineage and late complete or partial workers cannot revive it`() {
        val cache = VersionResultCache(VersionResultCache.Limits(contexts = 2))
        val first = snapshot("npm", "/first")
        val revision = cache.begin(first)
        assertTrue(cache.put(first, revision, report(first)))
        val prepared = cache.getResult(first, UpdateMode.MAJOR)!!
        val heldFix = cache.inspectionReport(presentationAdapter, first)!!.lease!!
        listOf("/second", "/third").forEach { root ->
            val other = snapshot("npm", root)
            assertTrue(cache.put(other, cache.begin(other), report(other)))
        }
        assertNull(cache.get(first))
        assertFalse(heldFix.isCurrent())
        assertFalse(cache.isCurrent(first.context, UpdateMode.MAJOR, prepared))
        assertFalse(cache.put(first, revision, report(first)))
        assertFalse(cache.putProgress(presentationAdapter, first, revision, Any(), InspectionUpdate(report(first), emptySet())))
        val fresh = cache.begin(first)
        assertNotEquals(revision, fresh)
        assertFalse(cache.put(first, revision, report(first)))
        // Even the same report object and timestamp cannot restore the previous preview lease.
        assertTrue(cache.put(first, fresh, prepared.report))
        assertFalse(cache.isCurrent(first.context, UpdateMode.MAJOR, prepared))
    }

    @Test fun `recently used contexts survive result count pressure across update modes`() {
        val cache = VersionResultCache(VersionResultCache.Limits(results = 3))
        val first = snapshot(root = "/first"); val second = snapshot(root = "/second")
        assertTrue(cache.put(first, cache.begin(first), report(first), UpdateMode.PATCH))
        assertTrue(cache.put(first, cache.begin(first), report(first), UpdateMode.MINOR))
        assertTrue(cache.put(second, cache.begin(second), report(second)))
        assertNotNull(cache.get(first, UpdateMode.PATCH))
        val third = snapshot(root = "/third")
        assertTrue(cache.put(third, cache.begin(third), report(third)))
        assertNull(cache.get(second))
        assertNotNull(cache.get(first, UpdateMode.PATCH))
        assertNotNull(cache.get(first, UpdateMode.MINOR))
        assertEquals(3, cache.retention().results)
    }

    @Test fun `payload budget includes partial rows input paths and large text`() {
        val sample = snapshot("npm")
        val measure = VersionResultCache()
        measure.put(sample, measure.begin(sample), report(sample))
        val budget = measure.retention().payloadUnits * 2
        val cache = VersionResultCache(VersionResultCache.Limits(payloadUnits = budget))
        val stable = snapshot("maven", "/stable")
        assertTrue(cache.put(stable, cache.begin(stable), report(stable)))
        val oversized = sample.copy(fingerprint = BuildFingerprint(mapOf("/config" to "x".repeat((budget * 256).toInt())), "config"))
        val revision = cache.begin(oversized)
        assertFalse(cache.put(oversized, revision, report(oversized)))
        assertNotNull("An oversized single result must not evict other providers", cache.get(stable))
        val owner = Any()
        val fresh = cache.begin(sample)
        assertTrue(cache.putProgress(presentationAdapter, sample, fresh, owner, InspectionUpdate(report(sample), emptySet())))
        assertFalse(cache.putProgress(presentationAdapter, sample, fresh, owner, InspectionUpdate(
            UpdateReport(notices = listOf(UpdateNotice(sample.declarations.single(), NoticeKind.MANUAL_REVIEW,
                "x".repeat((budget * 256).toInt())))), emptySet())))
        assertNull(cache.inspectionProgress(presentationAdapter, sample))
        assertNotNull(cache.get(stable))
        assertTrue(cache.retention().payloadUnits <= budget)
    }

    @Test fun `partial and complete results share one capacity budget and owner cleanup stays isolated`() {
        val cache = VersionResultCache(VersionResultCache.Limits(results = 1))
        val first = snapshot("npm", "/first"); val second = snapshot("npm", "/second")
        val owner = Any(); val revision = cache.begin(first)
        assertTrue(cache.putProgress(presentationAdapter, first, revision, owner, InspectionUpdate(report(first), emptySet())))
        assertEquals(1, cache.retention().progress)
        assertTrue(cache.put(second, cache.begin(second), report(second)))
        assertEquals(0, cache.retention().progress)
        assertFalse(cache.clearProgress(first.context, owner))
        assertFalse(cache.putProgress(presentationAdapter, first, revision, owner, InspectionUpdate(report(first), emptySet())))
        assertNotNull(cache.get(second))
    }

    @Test fun `expiry physically removes reports progress sources and unused generation records`() {
        var time = 0L
        val cache = VersionResultCache { time }
        val complete = snapshot(root = "/complete"); val partial = snapshot("npm", "/partial")
        cache.put(complete, cache.begin(complete), report(complete).copy(validUntilNanos = 100))
        cache.putProgress(presentationAdapter, partial, cache.begin(partial), Any(),
            InspectionUpdate(report(partial).copy(validUntilNanos = 100), emptySet()))
        val detached = snapshot(root = "/detached"); val oldRevision = cache.begin(detached)
        time = 100
        assertEquals(0, cache.retention().results)
        assertEquals(0, cache.retention().progress)
        assertEquals(0L, cache.retention().payloadUnits)
        time = java.util.concurrent.TimeUnit.MINUTES.toNanos(11)
        assertEquals(VersionResultCache.Retention(0, 0, 0, 0, 0), cache.retention())
        assertFalse(cache.put(detached, oldRevision, report(detached)))
        assertNotEquals(oldRevision, cache.begin(detached))
    }

    @Test fun `provider and global cleanup retire never cached workers without keeping tombstones`() {
        val cache = VersionResultCache()
        val first = snapshot(); val other = snapshot("npm")
        val firstRevision = cache.begin(first); val otherRevision = cache.begin(other)
        cache.invalidateAdapter("maven")
        assertEquals(1, cache.retention().contexts)
        assertFalse(cache.put(first, firstRevision, report(first)))
        assertTrue(cache.put(other, otherRevision, report(other)))
        val replacement = cache.begin(first)
        assertNotEquals(firstRevision, replacement)
        cache.invalidate()
        assertEquals(VersionResultCache.Retention(0, 0, 0, 0, 0), cache.retention())
        assertFalse(cache.put(first, replacement, report(first)))
        assertFalse(cache.put(other, otherRevision, report(other)))
    }

    @Test fun `import rename and scope churn remains bounded and late workers stay retired`() {
        val cache = VersionResultCache(VersionResultCache.Limits(contexts = 32, results = 16, payloadUnits = 2_000))
        val old = snapshot(root = "/removed"); val oldRevision = cache.begin(old)
        repeat(10_000) { index ->
            val current = snapshot(if (index % 2 == 0) "npm" else "maven", "/renamed-$index")
            val revision = cache.begin(current)
            if (index % 3 == 0) cache.putProgress(presentationAdapter, current, revision, Any(), InspectionUpdate(report(current), emptySet()))
            else cache.put(current, revision, report(current), UpdateMode.entries[index % 3])
            if (index % 7 == 0) cache.invalidateSource(current.context.adapterId, current.sourceFile)
            val retained = cache.retention()
            assertTrue(retained.contexts <= 32)
            assertTrue(retained.sources <= 32)
            assertTrue(retained.results + retained.progress <= 16)
            assertTrue(retained.payloadUnits <= 2_000)
        }
        assertFalse(cache.put(old, oldRevision, report(old)))
        assertNotEquals(oldRevision, cache.begin(old))
    }

    @Test fun `derived reports preserve original metadata age and deadline across modes`() {
        var time = 100L
        val cache = VersionResultCache { time }
        val snapshot = snapshot()
        val report = report(snapshot).copy(validUntilNanos = 1000, checkedAtNanos = 10)
        assertTrue(cache.put(snapshot, cache.begin(snapshot), report))
        assertEquals(10L, cache.getResult(snapshot, UpdateMode.MAJOR)!!.checkedAtNanos)
        time = 500
        assertTrue(cache.put(snapshot, cache.begin(snapshot), report, UpdateMode.PATCH))
        assertEquals(10L, cache.getResult(snapshot, UpdateMode.PATCH)!!.checkedAtNanos)
        assertEquals(1000L, cache.getResult(snapshot, UpdateMode.PATCH)!!.expiresAtNanos)
        time = 1000
        assertNull(cache.get(snapshot, UpdateMode.PATCH))
    }
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
    @Test fun `partial coverage counts only fresh completions while retaining older hints`() {
        val cache = VersionResultCache()
        val base = snapshot("npm")
        val declarations = (1..3).map { index -> base.declarations.single().copy(
            id = DeclarationId(base.sourceFile, "dependency-$index"), artifact = ArtifactId("npm", "dependency-$index")) }
        val skipped = DeclarationId(base.sourceFile, "peer")
        val previous = base.copy(declarations = declarations, skippedDeclarations = mapOf(skipped to "Peer compatibility"))
        cache.put(previous, cache.begin(previous), UpdateReport(declarations.map { UpdateCandidate(it, "2.0") },
            coverage = declarations.associate { it.id to DeclarationCoverage(DeclarationOutcome.CHECKED) } + previous.skippedCoverage()))
        val changed = previous.copy(fingerprint = previous.fingerprint.copy(configuration = "changed"))
        val owner = Any(); val revision = cache.begin(changed)
        fun publish(index: Int, outcome: DeclarationOutcome, candidate: Boolean = false) {
            val declaration = declarations[index]
            assertTrue(cache.putProgress(presentationAdapter, changed, revision, owner, InspectionUpdate(UpdateReport(
                candidates = if (candidate) listOf(UpdateCandidate(declaration, "2.0")) else emptyList(),
                coverage = mapOf(declaration.id to DeclarationCoverage(outcome))), setOf(declaration.id))))
        }
        publish(0, DeclarationOutcome.CHECKED)
        assertEquals(2, cache.inspectionProgress(presentationAdapter, changed)!!.candidates.size)
        val hidden = VersionPresentationPolicy("npm", "npm dependency-2 = 2.0\nnpm dependency-3 = 2.0")
        assertEquals(CoverageSummary(checked = 1, skipped = 1, pending = 2),
            cache.inspectionProgressCoverage(changed, hidden.candidateFilter))
        publish(1, DeclarationOutcome.FAILED)
        assertEquals(CoverageSummary(checked = 1, skipped = 1, failed = 1, pending = 1), cache.inspectionProgressCoverage(changed))
        publish(2, DeclarationOutcome.CHECKED, candidate = true)
        assertEquals(CoverageSummary(checked = 2, skipped = 1, failed = 1, ignored = 1),
            cache.inspectionProgressCoverage(changed, hidden.candidateFilter))
        assertEquals(cache.inspectionProgressCoverage(changed, hidden.candidateFilter),
            hidden.filter(cache.inspectionProgress(presentationAdapter, changed)!!).coverageSummary(changed))
    }

    @Test fun `coverage reasons and excluded declarations participate in complete and partial payload budgets`() {
        val base = snapshot("npm")
        val huge = mapOf(base.declarations.single().id to DeclarationCoverage(DeclarationOutcome.SKIPPED, "reason".repeat(10_000)))
        val cache = VersionResultCache(VersionResultCache.Limits(payloadUnits = 100))
        assertFalse(cache.put(base, cache.begin(base), UpdateReport(coverage = huge)))
        assertEquals(0, cache.retention().results)
        assertFalse(cache.putProgress(presentationAdapter, base, cache.begin(base), Any(),
            InspectionUpdate(UpdateReport(coverage = huge), huge.keys)))
        assertEquals(0, cache.retention().progress)
        val excluded = base.copy(skippedDeclarations = huge.mapValues { it.value.reason!! })
        assertFalse(cache.put(excluded, cache.begin(excluded), UpdateReport()))
        assertEquals(0L, cache.retention().payloadUnits)
    }

    @Test fun `changed exclusion coverage cannot reuse an otherwise matching complete result`() {
        val cache = VersionResultCache()
        val base = snapshot("maven")
        cache.put(base, cache.begin(base), UpdateReport())
        assertNull(cache.get(base.copy(skippedDeclarations = mapOf(DeclarationId(base.sourceFile, "inactive-profile") to "Inactive profile"))))
    }

}
