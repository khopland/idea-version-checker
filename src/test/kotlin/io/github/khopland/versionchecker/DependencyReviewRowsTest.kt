package io.github.khopland.versionchecker

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*

class DependencyReviewRowsTest : BasePlatformTestCase() {
    private val declaration = VersionDeclaration(DeclarationId("/project/pom.xml", "DEPENDENCY:20"),
        ArtifactId("com.example", "library"), "1.0", "1.0")
    private fun snapshot() = BuildSnapshot(BuildContextId("maven", "/project", "root"), "/project/pom.xml",
        BuildFingerprint(emptyMap(), ""), listOf(declaration))
    private fun observation(report: UpdateReport, checking: Boolean = false) =
        VersionResultCache.Observation(snapshot(), report, UpdateMode.MAJOR, checking)

    fun testCoverageAndFiltersDoNotGuessSuccessFromNoCandidates() {
        val report = UpdateReport(coverage = mapOf(declaration.id to DeclarationCoverage(DeclarationOutcome.SKIPPED, "Managed externally")))
        val rows = DependencyReviewRows.from(listOf(observation(report)))
        assertEquals(DependencyReviewOutcome.SKIPPED, rows.single().outcome)
        assertEquals(rows, DependencyReviewRows.filter(rows, DependencyReviewFilter.REVIEW))
        assertEmpty(DependencyReviewRows.filter(rows, DependencyReviewFilter.UPDATES))
        assertEquals(DependencyReviewOutcome.UNREPORTED, DependencyReviewRows.from(listOf(observation(UpdateReport()))).single().outcome)
        assertEquals(DependencyReviewOutcome.PENDING, DependencyReviewRows.from(listOf(observation(UpdateReport(), true))).single().outcome)
    }

    fun testUpdatesRespectIgnoresAndPreservePhysicalNavigationAndKind() {
        val shared = declaration.copy(sourceId = DeclarationId("/project/parent/pom.xml", "PROPERTY:10"))
        val report = UpdateReport(listOf(UpdateCandidate(shared, "2.0", kind = VersionChangeKind.MAJOR)),
            coverage = mapOf(shared.id to DeclarationCoverage(DeclarationOutcome.CHECKED)))
        val observation = VersionResultCache.Observation(snapshot().copy(declarations = listOf(shared)), report, UpdateMode.MINOR, false)
        val rows = DependencyReviewRows.from(listOf(observation))
        assertEquals(shared.sourceId, rows.single().source)
        assertEquals(rows, DependencyReviewRows.filter(rows, DependencyReviewFilter.UPDATES, VersionChangeKind.MAJOR))
        assertEmpty(DependencyReviewRows.filter(rows, DependencyReviewFilter.UPDATES, VersionChangeKind.PATCH))
        val ignored = DependencyReviewRows.from(listOf(observation), "maven com.example:library = 2.0")
        assertEquals(DependencyReviewOutcome.IGNORED, ignored.single().outcome)
        assertNull(ignored.single().available)
    }

    fun testPartialFailureAndManualNoticeRemainVisibleDespiteKnownCandidate() {
        val candidate = UpdateCandidate(declaration, "2.0")
        val failed = DependencyReviewRows.from(listOf(observation(UpdateReport(listOf(candidate), failure = "Check stopped"))))
        assertTrue(failed.single().checkFailed)
        assertEquals(failed, DependencyReviewRows.filter(failed, DependencyReviewFilter.FAILED))
        val manual = DependencyReviewRows.from(listOf(observation(UpdateReport(listOf(candidate),
            notices = listOf(UpdateNotice(declaration, NoticeKind.MANUAL_REVIEW, "Shared version requires review"))))))
        assertEquals(DependencyReviewOutcome.REVIEW, manual.single().outcome)
        assertEquals(manual, DependencyReviewRows.filter(manual, DependencyReviewFilter.REVIEW))
        val failedCoverage = DependencyReviewRows.from(listOf(observation(UpdateReport(listOf(candidate),
            coverage = mapOf(declaration.id to DeclarationCoverage(DeclarationOutcome.FAILED))))))
        assertEquals(DependencyReviewOutcome.FAILED, failedCoverage.single().outcome)
    }

    fun testDisposedPanelRejectsLatePresentationAndClearsRows() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        val observed = observation(UpdateReport(listOf(UpdateCandidate(declaration, "2.0"))))
        val panel = DependencyToolWindowPanel(project) {
            entered.countDown()
            release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            completed.countDown()
            listOf(observed)
        }
        try {
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Presentation starts", { entered.count == 0L }, 10)
            panel.model.replace(DependencyReviewRows.from(listOf(observed)))
            com.intellij.openapi.util.Disposer.dispose(panel)
            release.countDown()
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Presentation completes", { completed.count == 0L }, 10)
            panel.requestUpdate()
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEquals(0, panel.model.rowCount)
        } finally {
            release.countDown()
            if (!com.intellij.openapi.util.Disposer.isDisposed(panel)) com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    fun testTableShowsCachedValuesAndCannotEdit() {
        val rows = DependencyReviewRows.from(listOf(observation(UpdateReport(listOf(UpdateCandidate(declaration, "2.0"))))))
        val model = DependencyReviewTableModel("/project")
        model.replace(rows)
        assertEquals("pom.xml", model.getValueAt(0, 1))
        assertEquals("2.0", model.getValueAt(0, 5))
        assertFalse(model.isCellEditable(0, 5))
        model.replace(emptyList())
        assertEquals(0, model.rowCount)
    }

    fun testCacheObservationExpiresAndDoesNotExtendFreshnessOrLeakLease() {
        var time = 1L
        val cache = VersionResultCache(now = { time })
        val snapshot = snapshot()
        val report = UpdateReport(listOf(UpdateCandidate(declaration, "2.0")), validUntilNanos = 10L)
        assertTrue(cache.put(snapshot, cache.begin(snapshot), report))
        repeat(10) {
            val observed = cache.observations().single()
            assertEquals(snapshot, observed.snapshot)
            assertNull(observed.report.lease)
            assertFalse(observed.checking)
        }
        time = 10L
        assertEmpty(cache.observations())
        assertNull(cache.get(snapshot))
    }

    fun testCacheProgressIsExplicitAndInvalidationRemovesObservations() {
        val cache = VersionResultCache()
        val snapshot = snapshot()
        val adapter = object : BuildSystemAdapter {
            override val id = "maven"
            override val displayName = "test"
            override val capabilities = AdapterCapabilities()
            override fun supports(project: com.intellij.openapi.project.Project, selection: BuildSelection) = true
            override fun isOffline(project: com.intellij.openapi.project.Project) = false
            override fun snapshot(project: com.intellij.openapi.project.Project, file: com.intellij.openapi.vfs.VirtualFile) = snapshot
            override fun isCurrent(project: com.intellij.openapi.project.Project, snapshot: BuildSnapshot) = true
            override suspend fun discover(project: com.intellij.openapi.project.Project, selection: BuildSelection): List<BuildSnapshot> = error("Passive presentation must not discover")
            override suspend fun check(project: com.intellij.openapi.project.Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport = error("Passive presentation must not check")
            override suspend fun prepareUpdates(project: com.intellij.openapi.project.Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan = error("Passive presentation must not prepare edits")
        }
        val revision = cache.begin(snapshot)
        assertTrue(cache.putProgress(adapter, snapshot, revision, Any(), InspectionUpdate(
            UpdateReport(coverage = mapOf(declaration.id to DeclarationCoverage(DeclarationOutcome.CHECKED))), setOf(declaration.id))))
        assertTrue(cache.observations().single().checking)
        cache.invalidateAdapter("maven")
        assertEmpty(cache.observations())
    }
}
