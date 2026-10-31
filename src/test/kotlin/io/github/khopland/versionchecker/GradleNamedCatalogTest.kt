package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.util.concurrent.Callable

class GradleNamedCatalogTest : BasePlatformTestCase() {
    private val adapter = GradleBuildSystemAdapter()
    private fun registration(name: String, path: String) = "create(\"$name\") { from(files(\"$path\")) }"
    private fun link(body: String, kotlin: Boolean = true): PsiFile {
        val script = myFixture.addFileToProject("catalogs/settings.gradle${if (kotlin) ".kts" else ""}",
            "dependencyResolutionManagement { versionCatalogs { $body } }")
        GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply {
            externalProjectPath = script.virtualFile.parent.path
        })
        FileDocumentManager.getInstance().saveAllDocuments()
        return script
    }
    private fun <T> background(action: suspend () -> T): T = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { action() } }), 30_000)
    private fun report(snapshot: BuildSnapshot) = UpdateReport(snapshot.declarations.map { UpdateCandidate(it, "1.2.9") })
    private fun edit(file: PsiFile, text: String, save: Boolean = true) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!
            document.setText(text)
            PsiDocumentManager.getInstance(project).commitDocument(document)
            if (save) FileDocumentManager.getInstance().saveDocument(document)
        }
    }

    override fun tearDown() {
        try {
            val settings = GradleSettings.getInstance(project)
            settings.linkedProjectsSettings.mapNotNull { it.externalProjectPath }.forEach(settings::unlinkExternalProject)
        } finally { super.tearDown() }
    }

    fun testMultipleNamedCatalogsKeepPhysicalAliasesDefaultAndCurrentFileIsolation() {
        val named = myFixture.addFileToProject("catalogs/versions/tools.toml", "[libraries]\nalpha = 'g:alpha:1.2.3'")
        val tests = myFixture.addFileToProject("catalogs/versions/tests.toml", "[libraries]\nbeta = 'g:beta:1.2.3'")
        val default = myFixture.addFileToProject("catalogs/gradle/libs.versions.toml", "[libraries]\ngamma = 'g:gamma:1.2.3'")
        val unregistered = myFixture.addFileToProject("catalogs/versions/unregistered.toml", "[libraries]\ndelta = 'g:delta:1.2.3'")
        link(listOf(registration("tools", "versions/tools.toml"), registration("otherName", "versions/tools.toml"),
            registration("testing", "versions/tests.toml")).joinToString("\n"), kotlin = false)
        val discovered = background { adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
        assertEquals(setOf(named, tests, default).map { it.virtualFile.path }.toSet(), discovered.map { it.sourceFile }.toSet())
        assertEquals(3, discovered.sumOf { it.declarations.size })
        assertNull(adapter.snapshot(project, unregistered.virtualFile))
        val snapshot = background { adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, named.virtualFile.path)) }.single()
        assertEquals(named.virtualFile.path, snapshot.declarations.single().id.file)
        assertEquals("libraries/alpha", snapshot.declarations.single().id.location)
        val paths = background { adapter.resolutionInputPaths(project, BuildSelection(UpdateScope.CURRENT_FILE, named.virtualFile.path)) }
        assertTrue(paths.containsAll(listOf(named, tests, default).map { it.virtualFile.path }))
        val plan = background { adapter.prepareUpdates(project, mapOf(snapshot to report(snapshot))) }
        assertEquals(1, plan.changes.size)
        assertTrue(plan.apply(project))
        assertTrue(named.text.contains("1.2.9"))
        assertTrue(tests.text.contains("1.2.3"))
        assertTrue(default.text.contains("1.2.3"))
    }

    fun testChangedSettingsRejectHeldEditsAndUnsavedRegistrationsUseNewOwnership() {
        val first = myFixture.addFileToProject("catalogs/versions/first.toml", "[libraries]\nalpha = 'g:alpha:1.2.3'")
        val second = myFixture.addFileToProject("catalogs/versions/second.toml", "[libraries]\nbeta = 'g:beta:1.2.3'")
        val settings = link(registration("tools", "versions/first.toml"))
        val snapshot = adapter.snapshot(project, first.virtualFile)!!
        val held = background { adapter.prepareUpdates(project, mapOf(snapshot to report(snapshot))) }
        edit(settings, settings.text.replace("first.toml", "second.toml"), save = false)
        assertFalse(adapter.isCurrent(project, snapshot))
        assertFalse(held.apply(project))
        assertNull(adapter.inspectionSnapshot(project, first.virtualFile))
        assertNotNull(adapter.inspectionSnapshot(project, second.virtualFile))
        assertNull(adapter.snapshot(project, second.virtualFile))
        FileDocumentManager.getInstance().saveAllDocuments()
        assertNotNull(adapter.snapshot(project, second.virtualFile))
        assertTrue(first.text.contains("1.2.3"))
    }

    fun testUntrackedRegistrationsAppearInSettingsCoverageAndPreventForgedEdits() {
        val valid = myFixture.addFileToProject("catalogs/versions/valid.toml", "[libraries]\nalpha = 'g:alpha:1.2.3'")
        val settings = link(registration("valid", "versions/valid.toml") + "\n" + registration("missing", "versions/missing.toml"))
        val snapshot = adapter.snapshot(project, settings.virtualFile)!!
        assertTrue(snapshot.skippedDeclarations.values.single().contains("available local TOML"))
        val checked = background { adapter.check(project, snapshot, UpdateMode.MAJOR) }
        assertEquals(CoverageSummary(skipped = 1), checked.coverageSummary(snapshot))
        val diagnostic = GradlePreparedUpdate.prepare(settings, checked, adapter.declarations(project, settings)).single()
        assertNotNull(diagnostic.notice)
        assertNull(diagnostic.edit)
        assertTrue(diagnostic.target!!.range.startOffset > 0)
        val catalog = adapter.snapshot(project, valid.virtualFile)!!
        val plan = background { adapter.prepareUpdates(project, mapOf(catalog to report(catalog))) }
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.reviewItems.single().reason.contains("available local TOML"))

        for (body in listOf("create(\"remote\") { from(\"g:catalog:1.0.0\") }",
            "create(\"computed\") { from(files(catalogFile)) }", registration("outside", "../external.toml"))) {
            edit(settings, "dependencyResolutionManagement { versionCatalogs { $body } }")
            val unsupported = adapter.snapshot(project, settings.virtualFile)!!
            assertEquals(1, unsupported.skippedDeclarations.size)
            assertTrue(background { adapter.check(project, unsupported, UpdateMode.MAJOR) }.candidates.isEmpty())
        }
    }

    fun testNamedCatalogSharesPluginReferencesAndRetainsMalformedSyntax() {
        val catalog = myFixture.addFileToProject("catalogs/versions/tools.toml", """
            [versions]
            shared = "1.2.3"
            [libraries]
            alpha = { module = "g:alpha", version.ref = "shared" }
            beta = { module = "g:beta", version.ref = "shared" }
            direct = "g:direct:1.2.3"
            [plugins]
            plugin = { id = "g.plugin", version.ref = "shared" }
        """.trimIndent())
        link(registration("tools", "versions/tools.toml"))
        val snapshot = adapter.snapshot(project, catalog.virtualFile)!!
        assertTrue(snapshot.skippedDeclarations.isEmpty())
        val plan = background { adapter.prepareUpdates(project, mapOf(snapshot to report(snapshot))) }
        // The shared version has library and plugin consumers that agree on one target.
        assertEquals(2, plan.changes.size)
        assertTrue(plan.changes.any { it.location.contains("libraries/alpha") && it.location.contains("plugins/plugin") })
        assertTrue(plan.changes.any { it.location.contains("libraries/direct") })
        edit(catalog, "[libraries]\nalpha = 'g:alpha:1.2.3'\nalpha = 'g:alpha:1.2.9'")
        val malformed = adapter.snapshot(project, catalog.virtualFile)!!
        assertEquals("catalog/syntax", malformed.declarations.single().id.location)
        assertTrue(background { adapter.prepareUpdates(project, mapOf(malformed to report(malformed))) }.changes.isEmpty())
    }

    fun testDistinctCatalogOwnersCannotBorrowOneConsumedAliasAuthority() {
        val first = myFixture.addFileToProject("catalogs/versions/first.toml", "[libraries]\nalpha = 'g:alpha:1.2.3'")
        val second = myFixture.addFileToProject("catalogs/versions/second.toml", "[libraries]\nalpha = 'g:alpha:1.2.3'")
        myFixture.addFileToProject("catalogs/build.gradle", "dependencies { implementation first.alpha }")
        link(registration("first", "versions/first.toml") + "\n" + registration("second", "versions/second.toml"))
        for (file in listOf(first, second)) {
            val snapshot = adapter.snapshot(project, file.virtualFile)!!
            assertEquals("1.2.3", snapshot.declarations.single().selector)
            assertTrue(snapshot.declarations.single().baseline.isEmpty())
            assertEquals(GradleDeclarations.CATALOG_OWNER_REASON, snapshot.skippedDeclarations.values.single())
            val checked = background { adapter.check(project, snapshot, UpdateMode.MAJOR) }
            assertTrue(checked.candidates.isEmpty())
            assertEquals(CoverageSummary(skipped = 1), checked.coverageSummary(snapshot))
            val prepared = GradlePreparedUpdate.prepare(file, checked, adapter.declarations(project, file)).single()
            assertEquals(GradleDeclarations.CATALOG_OWNER_REASON, prepared.notice!!.message)
            assertNull(prepared.edit)
            val forged = UpdateReport(listOf(UpdateCandidate(snapshot.declarations.single().copy(baseline = "1.2.3"), "1.2.9")))
            val plan = background { adapter.prepareUpdates(project, mapOf(snapshot to forged)) }
            assertTrue(plan.changes.isEmpty())
            assertTrue(plan.reviewItems.single().reason.contains("which catalog alias"))
        }
        edit(second, second.text.replace("1.2.3", "1.2.4"))
        assertEquals("1.2.3", adapter.snapshot(project, first.virtualFile)!!.declarations.single().baseline)
        assertEquals("1.2.4", adapter.snapshot(project, second.virtualFile)!!.declarations.single().baseline)
    }

    fun testOnePhysicalCatalogSharedAcrossLinkedBuildsNeedsReview() {
        val shared = myFixture.addFileToProject("catalogs/child/gradle/libs.versions.toml", "[libraries]\nalpha = 'g:alpha:1.2.3'")
        link(registration("childLibraries", "child/gradle/libs.versions.toml"))
        val childSettings = myFixture.addFileToProject("catalogs/child/settings.gradle", "rootProject.name = 'child'")
        GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply { externalProjectPath = childSettings.virtualFile.parent.path })
        FileDocumentManager.getInstance().saveAllDocuments()
        assertNull(adapter.snapshot(project, shared.virtualFile))
        val discovered = background { adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
        assertEquals(2, discovered.size)
        assertTrue(discovered.all { it.skippedDeclarations.values.single().contains("independently linked") })
    }

    fun testCatalogSymlinksKeepOnePhysicalOwnerAndRejectExternalFiles() {
        val directory = Files.createTempDirectory("version-checker-catalog-identity-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val root = Files.createDirectory(directory.resolve("project"))
        try {
            val versions = Files.createDirectory(root.resolve("versions"))
            val catalog = versions.resolve("tools.toml")
            Files.writeString(catalog, "[libraries]\nalpha = 'g:alpha:1.2.3'")
            Files.createSymbolicLink(versions.resolve("alias.toml"), catalog)
            val external = directory.resolve("external.toml")
            Files.writeString(external, "[libraries]\nbeta = 'g:beta:1.2.3'")
            Files.createSymbolicLink(versions.resolve("external.toml"), external)
            val settingsPath = root.resolve("settings.gradle.kts")
            Files.writeString(settingsPath, "dependencyResolutionManagement { versionCatalogs { " +
                registration("tools", "versions/tools.toml") + "\n" + registration("alias", "versions/alias.toml") + " } }")
            val virtualRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
            virtualRoot.refresh(false, true)
            GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply { externalProjectPath = root.toString() })
            val snapshots = background { adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
            assertEquals(1, snapshots.size)
            assertEquals(catalog, java.nio.file.Path.of(snapshots.single().declarations.single().id.file).toRealPath())
            val plan = background { adapter.prepareUpdates(project, mapOf(snapshots.single() to report(snapshots.single()))) }
            assertEquals(1, plan.changes.size)

            val settings = virtualRoot.findChild("settings.gradle.kts")!!
            val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(settings)!!
            edit(psi, "dependencyResolutionManagement { versionCatalogs { " + registration("external", "versions/external.toml") + " } }")
            assertFalse(plan.apply(project))
            val review = adapter.snapshot(project, settings)!!
            assertTrue(review.skippedDeclarations.values.single().contains("available local TOML"))
            assertNull(adapter.snapshot(project, virtualRoot.findFileByRelativePath("versions/external.toml")!!))
            assertEquals("[libraries]\nbeta = 'g:beta:1.2.3'", Files.readString(external))
        } finally {
            GradleSettings.getInstance(project).unlinkExternalProject(root.toString())
            Files.walk(directory).use { paths -> paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    fun testNamedVersionsRetainUnchangedWarningsButUnregisteredTomlChangesDoNot() {
        val named = myFixture.addFileToProject("catalogs/versions/tools.toml", "[libraries]\nalpha = 'g:alpha:1.2.3'\nbeta = 'g:beta:1.2.3'")
        val unregistered = myFixture.addFileToProject("catalogs/versions/inputs.toml", "[libraries]\npolicy = 'g:policy:1.2.3'")
        link(registration("tools", "versions/tools.toml"))
        val original = adapter.snapshot(project, named.virtualFile)!!
        edit(named, named.text.replace("g:beta:1.2.3", "g:beta:1.2.9"))
        val updated = adapter.snapshot(project, named.virtualFile)!!
        assertEquals(listOf("alpha"), adapter.retainInspectionReport(original, report(original), updated)!!.candidates.map { it.declaration.artifact.name })
        edit(unregistered, unregistered.text.replace("1.2.3", "1.2.9"))
        assertNull(adapter.retainInspectionReport(updated, report(updated), adapter.snapshot(project, named.virtualFile)!!))
    }
}
