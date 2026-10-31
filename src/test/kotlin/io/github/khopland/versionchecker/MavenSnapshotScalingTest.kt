package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.maven.MavenBuildSystemAdapter
import io.github.khopland.versionchecker.maven.MavenProjectInputs
import io.github.khopland.versionchecker.maven.coordinate
import kotlinx.coroutines.*
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.model.MavenModel
import org.jetbrains.idea.maven.model.MavenExplicitProfiles
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class MavenSnapshotScalingTest : BasePlatformTestCase() {
    private lateinit var manager: MavenProjectsManager
    private lateinit var directory: Path

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("version-checker-maven-inputs").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        manager.projectsTree.ignoredFilesPaths = manager.projects.map { it.path }
    }

    override fun tearDown() {
        try {
            manager.projectsTree.setIgnoredState(manager.projects, true)
            directory.toFile().deleteRecursively()
        } finally { super.tearDown() }
    }

    private fun imported(name: String, body: String): MavenProject {
        val path = Files.createDirectories(directory.resolve(name)).resolve("pom.xml")
        Files.writeString(path, """<project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion><groupId>scaling</groupId><artifactId>${name.substringAfterLast('/')}</artifactId>
            <version>1</version>$body</project>""")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        return MavenProject(file).apply {
            updateState(MavenModel().apply { mavenId = MavenId("scaling", name.substringAfterLast('/'), "1") },
                emptyList(), "21", emptyList(), MavenExplicitProfiles.NONE, emptySet(), emptyMap(),
                this@MavenSnapshotScalingTest.directory.resolve("repository"), false)
            manager.projectsTree.putVirtualFileToProjectMapping(this, mavenId)
        }
    }

    private fun <T> background(action: suspend () -> T): T = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { action() } }), 60_000)

    private val dependency = "<dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId><version>1.2.3</version></dependency></dependencies>"

    fun testBackgroundCaptureReadsExternalInputsOnceOutsideReadLocksAndMatchesExistingGuards() {
        val first = imported("capture/first", dependency)
        imported("capture/second", dependency)
        val userSettings = directory.resolve("capture-settings/settings.xml")
        Files.createDirectories(userSettings.parent)
        Files.writeString(userSettings, "<settings/>")
        val security = userSettings.resolveSibling("settings-security.xml")
        val target = userSettings.resolveSibling("credentials/master.xml")
        Files.createDirectories(target.parent)
        Files.writeString(security, "<settingsSecurity><relocation>credentials/master.xml</relocation></settingsSecurity>")
        Files.writeString(target, "<settingsSecurity><master>original</master></settingsSecurity>")
        val previous = manager.generalSettings.userSettingsFile
        try {
            manager.generalSettings.setUserSettingsFile(userSettings.toString())
            val reads = mutableListOf<Path>()
            val adapter = MavenBuildSystemAdapter { path ->
                assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
                assertFalse(ApplicationManager.getApplication().isDispatchThread)
                reads.add(path)
                MavenProjectInputs.savedDiskFingerprint(path)
            }
            val snapshots = background { adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
            assertEquals(2, snapshots.size)
            assertEquals("Shared input paths are read once", reads.toSet().size, reads.size)
            assertTrue(reads.contains(target))
            assertEquals(MavenBuildSystemAdapter().snapshot(project, first.file), snapshots.single { it.sourceFile == first.path })
            reads.clear()
            assertTrue(background { adapter.isCurrentInBackground(project, snapshots.first()) })
            assertEquals(reads.toSet().size, reads.size)
            val captured = background { adapter.prepareInspectionSnapshot(project, first.file) }
            val validation = background { adapter.prepareEditValidation(project, snapshots) }
            val capturedReads = reads.size
            assertEquals(snapshots.single { it.sourceFile == first.path }, captured())
            assertTrue(validation.isCurrent())
            assertEquals("Live IDE overlay performs no saved-input reads", capturedReads, reads.size)
            reads.clear()
            assertEquals(2, background { adapter.prepareUpdates(project, reports(snapshots)) }.changes.size)
            assertEquals(reads.toSet().size, reads.size)
            Files.writeString(target, "<settingsSecurity><master>changed</master></settingsSecurity>")
            assertFalse(background { adapter.isCurrentInBackground(project, snapshots.first()) })
        } finally { manager.generalSettings.setUserSettingsFile(previous) }
    }

    fun testSelectedSavingAndCaptureUseTheSamePolicyWithoutSavingSiblingConfiguration() {
        val selected = imported("save/selected", dependency)
        val sibling = imported("save/sibling", dependency)
        val adapter = MavenBuildSystemAdapter()
        val selection = BuildSelection(UpdateScope.CURRENT_FILE, selected.path)
        val paths = background { adapter.resolutionInputPaths(project, selection) }
        assertTrue(paths.containsAll(listOf(selected.path, sibling.path)))
        val selectedConfig = selected.file.toNioPath().parent.resolve(".mvn/extensions.xml")
        val siblingConfig = sibling.file.toNioPath().parent.resolve(".mvn/extensions.xml")
        assertTrue(paths.contains(selectedConfig.toString()))
        assertFalse(paths.contains(siblingConfig.toString()))
        val reads = mutableListOf<Path>()
        val capturing = MavenBuildSystemAdapter { path ->
            assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
            assertFalse(ApplicationManager.getApplication().isDispatchThread)
            reads.add(path)
            MavenProjectInputs.savedDiskFingerprint(path)
        }
        val snapshot = background { capturing.discover(project, selection) }.single()
        assertEquals(paths, snapshot.fingerprint.files.keys)
        assertTrue("Capture covers every imported layout for later live overlays", reads.contains(siblingConfig))
        assertEquals(reads.toSet().size, reads.size)
        assertFalse(snapshot.fingerprint.files.containsKey(siblingConfig.toString()))
        Files.createDirectories(selectedConfig.parent)
        Files.writeString(selectedConfig, "<extensions/>")
        assertFalse(background { capturing.isCurrentInBackground(project, snapshot) })
    }

    fun testSettingsPathChangedDuringExternalCaptureDiscardsOldPaths() {
        val selected = imported("capture-path", dependency)
        val old = directory.resolve("old/settings.xml")
        val next = directory.resolve("new/settings.xml")
        for (path in listOf(old, next)) {
            Files.createDirectories(path.parent)
            Files.writeString(path, "<settings/>")
        }
        val previous = manager.generalSettings.userSettingsFile
        try {
            manager.generalSettings.setUserSettingsFile(old.toString())
            var changed = false
            val adapter = MavenBuildSystemAdapter { path ->
                if (!changed) {
                    changed = true
                    ApplicationManager.getApplication().invokeAndWait { manager.generalSettings.setUserSettingsFile(next.toString()) }
                }
                MavenProjectInputs.savedDiskFingerprint(path)
            }
            val snapshot = background { adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, selected.path)) }.single()
            assertEquals(MavenBuildSystemAdapter().snapshot(project, selected.file), snapshot)
            assertTrue(snapshot.fingerprint.files.containsKey(next.toString()))
            assertFalse(snapshot.fingerprint.files.containsKey(old.toString()))
        } finally { manager.generalSettings.setUserSettingsFile(previous) }
    }

    fun testUnsavedUnselectedPomChangedDuringDiskCaptureInvalidatesTheOriginalResult() {
        val selected = imported("capture-selected", dependency)
        val sibling = imported("capture-sibling", dependency)
        val original = MavenBuildSystemAdapter().snapshot(project, selected.file)!!
        val document = FileDocumentManager.getInstance().getDocument(sibling.file)!!
        var changed = false
        val adapter = MavenBuildSystemAdapter { path ->
            if (!changed) {
                changed = true
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(project) { document.setText(document.text + "\n") }
                }
            }
            MavenProjectInputs.savedDiskFingerprint(path)
        }
        assertFalse(background { adapter.isCurrentInBackground(project, original) })
        assertTrue(background { runCatching { adapter.prepareUpdates(project, reports(listOf(original))) }.isFailure })
    }

    fun testCancelledExternalCaptureDoesNotReadAnotherInputOrReturnSnapshots() {
        val selected = imported("capture-cancelled", dependency)
        var reads = 0
        assertTrue(background {
            supervisorScope {
                val capture = async {
                    val job = currentCoroutineContext().job
                    val adapter = MavenBuildSystemAdapter { path ->
                        reads++
                        job.cancel(CancellationException("stop external capture"))
                        MavenProjectInputs.savedDiskFingerprint(path)
                    }
                    adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, selected.path))
                }
                try { capture.await(); false } catch (_: CancellationException) { true }
            }
        })
        assertEquals(1, reads)
    }

    private fun reports(snapshots: List<BuildSnapshot>) = snapshots.associateWith { snapshot ->
        UpdateReport(snapshot.declarations.take(1).map { UpdateCandidate(it, "1.2.9") })
    }

    fun testUnselectedUnsavedSiblingRejectsPreviewBeforeAnyEdit() {
        val selected = imported("selected", dependency)
        val sibling = imported("sibling", dependency)
        val adapter = MavenBuildSystemAdapter()
        val snapshot = adapter.snapshot(project, selected.file)!!
        val plan = background { adapter.prepareUpdates(project, reports(listOf(snapshot))) }
        assertEquals(1, plan.changes.size)
        val document = FileDocumentManager.getInstance().getDocument(sibling.file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(document.text.replace("<artifactId>a</artifactId>", "<artifactId>changed</artifactId>")) }
        assertFalse(adapter.areCurrent(project, listOf(snapshot)))
        assertFalse(plan.apply(project))
        assertTrue(FileDocumentManager.getInstance().getDocument(selected.file)!!.text.contains("<version>1.2.3</version>"))
        assertTrue(background { runCatching { adapter.prepareUpdates(project, reports(listOf(snapshot))) }.isFailure })
    }

    fun testModuleConfigurationIsSeparateAndSharedAncestorIsRevalidatedBeforeApply() {
        val first = imported("root/modules/first", dependency)
        val second = imported("root/modules/second", dependency)
        val sharedConfig = Files.createDirectories(directory.resolve("root/.mvn")).resolve("maven.config")
        val localConfig = Files.createDirectories(directory.resolve("root/modules/first/.mvn")).resolve("maven.config")
        Files.writeString(sharedConfig, "-Dshared=one")
        Files.writeString(localConfig, "-Dlocal=one")
        val adapter = MavenBuildSystemAdapter()
        val firstSnapshot = adapter.snapshot(project, first.file)!!
        val secondSnapshot = adapter.snapshot(project, second.file)!!
        assertTrue(firstSnapshot.fingerprint.files.containsKey(localConfig.toString()))
        assertFalse(secondSnapshot.fingerprint.files.containsKey(localConfig.toString()))
        assertTrue(secondSnapshot.fingerprint.files.containsKey(sharedConfig.toString()))
        Files.writeString(localConfig, "-Dlocal=changed")
        assertFalse(adapter.isCurrent(project, firstSnapshot))
        assertTrue(adapter.isCurrent(project, secondSnapshot))
        val current = listOf(adapter.snapshot(project, first.file)!!, secondSnapshot)
        val plan = background { adapter.prepareUpdates(project, reports(current)) }
        assertEquals(2, plan.changes.size)
        Files.writeString(sharedConfig, "-Dshared=changed")
        assertFalse(adapter.areCurrent(project, current))
        assertFalse(plan.apply(project))
        assertTrue(listOf(first, second).all { FileDocumentManager.getInstance().getDocument(it.file)!!.text.contains("<version>1.2.3</version>") })
    }

    fun testImportIgnoreAndPolicyChangesCannotReuseAPreviousInputPass() {
        val selected = imported("selected", dependency)
        val sibling = imported("sibling", dependency)
        val adapter = MavenBuildSystemAdapter()
        val snapshot = adapter.snapshot(project, selected.file)!!
        val plan = background { adapter.prepareUpdates(project, reports(listOf(snapshot))) }
        manager.projectsTree.setIgnoredState(listOf(sibling), true)
        assertFalse(adapter.areCurrent(project, listOf(snapshot)))
        assertFalse(plan.apply(project))
        val beforeImport = adapter.snapshot(project, selected.file)!!
        imported("new-module", dependency)
        assertFalse(adapter.isCurrent(project, beforeImport))
        val beforePolicy = adapter.snapshot(project, selected.file)!!
        val options = project.service<VersionCheckerSettings>().state
        val policy = options.deprecatedDependencies
        try {
            options.deprecatedDependencies = "g:a = Replaced"
            assertFalse(adapter.isCurrent(project, beforePolicy))
        } finally { options.deprecatedDependencies = policy }
    }

    fun testExternalSettingsEditRejectsPreparedPreview() {
        val selected = imported("selected", dependency)
        val settings = directory.resolve("settings.xml")
        Files.writeString(settings, "<settings><servers/></settings>")
        val previousSettings = manager.generalSettings.userSettingsFile
        try {
            manager.generalSettings.setUserSettingsFile(settings.toString())
            val adapter = MavenBuildSystemAdapter()
            val snapshot = adapter.snapshot(project, selected.file)!!
            val plan = background { adapter.prepareUpdates(project, reports(listOf(snapshot))) }
            assertEquals(1, plan.changes.size)
            Files.writeString(settings, "<settings><servers><server><id>changed</id></server></servers></settings>")
            assertFalse(adapter.isCurrent(project, snapshot))
            assertFalse(plan.apply(project))
            assertTrue(FileDocumentManager.getInstance().getDocument(selected.file)!!.text.contains("<version>1.2.3</version>"))
        } finally { manager.generalSettings.setUserSettingsFile(previousSettings) }
    }

    fun testSameSizeSameTimestampExternalInputsRejectAllEditsWithoutVfsRefresh() {
        imported("same-stamp/first", dependency)
        imported("same-stamp/second", dependency)
        val settings = directory.resolve("same-stamp-settings/settings.xml")
        Files.createDirectories(settings.parent)
        val security = settings.resolveSibling("settings-security.xml")
        val credentials = Files.createDirectories(settings.parent.resolve("credentials")).resolve("master.xml")
        val config = Files.createDirectories(directory.resolve("same-stamp/.mvn"))
        Files.writeString(settings, "<settings><localRepository>old</localRepository></settings>")
        Files.writeString(security, "<settingsSecurity><relocation>credentials/master.xml</relocation><!-- old --></settingsSecurity>")
        Files.writeString(credentials, "<settingsSecurity><master>old</master></settingsSecurity>")
        val paths = listOf(settings, security, credentials, config.resolve("maven.config"), config.resolve("jvm.config"),
            config.resolve("extensions.xml"), Files.createDirectories(config.resolve("wrapper")).resolve("maven-wrapper.properties"))
        paths.drop(3).forEach { Files.writeString(it, "old") }
        val previousSettings = manager.generalSettings.userSettingsFile
        try {
            manager.generalSettings.setUserSettingsFile(settings.toString())
            val adapter = MavenBuildSystemAdapter()
            for (path in paths) {
                val snapshots = background { adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
                val plan = background { adapter.prepareUpdates(project, reports(snapshots)) }
                assertEquals(2, plan.changes.size)
                val stamp = Files.getLastModifiedTime(path)
                val size = Files.size(path)
                Files.writeString(path, Files.readString(path).replace("old", "new"))
                Files.setLastModifiedTime(path, stamp)
                assertEquals(size, Files.size(path))
                assertEquals(stamp, Files.getLastModifiedTime(path))
                assertFalse("Changed $path must reject synchronous validation", adapter.areCurrent(project, snapshots))
                assertFalse("Changed $path must reject background validation",
                    background { adapter.isCurrentInBackground(project, snapshots.first()) })
                assertFalse("Changed $path must reject preview preparation",
                    background { runCatching { adapter.prepareUpdates(project, reports(snapshots)) }.isSuccess })
                assertFalse("Changed $path must reject the whole transaction", plan.apply(project))
                assertTrue(plan.changes.all { it.element!!.containingFile.text.contains("<version>1.2.3</version>") })
                val current = background { adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
                assertTrue(adapter.areCurrent(project, current))
                assertTrue(background { adapter.isCurrentInBackground(project, current.first()) })
            }
        } finally { manager.generalSettings.setUserSettingsFile(previousSettings) }
    }

    fun testExternalInputFingerprintTracksContentsAndCreationRatherThanTimestampOnly() {
        val selected = imported("content-identity", dependency)
        val config = Files.createDirectories(directory.resolve("content-identity/.mvn")).resolve("maven.config")
        val adapter = MavenBuildSystemAdapter()
        val missing = adapter.snapshot(project, selected.file)!!
        assertTrue(adapter.isCurrent(project, missing))
        Files.writeString(config, "-Dflag=one")
        assertFalse("Creating a missing input changes identity", adapter.isCurrent(project, missing))
        val saved = adapter.snapshot(project, selected.file)!!
        val stamp = Files.getLastModifiedTime(config)
        Files.setLastModifiedTime(config, java.nio.file.attribute.FileTime.fromMillis(stamp.toMillis() + 60_000))
        assertTrue("Touching unchanged bytes retains identity", adapter.isCurrent(project, saved))
        Files.delete(config)
        assertFalse("Removing a present input changes identity", adapter.isCurrent(project, saved))
        assertTrue("Restoring the missing state restores input identity", adapter.isCurrent(project, missing))
    }

    fun testRelocatedCredentialsAndUnsavedChangesRejectPreparedPreview() {
        val selected = imported("credential-guard", dependency)
        val settings = directory.resolve("settings.xml")
        Files.writeString(settings, "<settings/>")
        val security = directory.resolve("settings-security.xml")
        val target = directory.resolve("credentials/master.xml")
        Files.createDirectories(target.parent)
        Files.writeString(security, "<settingsSecurity><relocation>credentials/master.xml</relocation></settingsSecurity>")
        Files.writeString(target, "<settingsSecurity><master>original</master></settingsSecurity>")
        val previousSettings = manager.generalSettings.userSettingsFile
        try {
            manager.generalSettings.setUserSettingsFile(settings.toString())
            val adapter = MavenBuildSystemAdapter()
            val snapshot = adapter.snapshot(project, selected.file)!!
            val plan = background { adapter.prepareUpdates(project, reports(listOf(snapshot))) }
            Files.writeString(target, "<settingsSecurity><master>changed-credential</master></settingsSecurity>")
            assertFalse("A changed relocated credential must invalidate the full audit", adapter.isCurrent(project, snapshot))
            assertFalse(plan.apply(project))
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)!!
            file.refresh(false, false)
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            FileDocumentManager.getInstance().reloadFromDisk(document)
            val current = adapter.snapshot(project, selected.file)!!
            val currentPlan = background { adapter.prepareUpdates(project, reports(listOf(current))) }
            assertTrue(background { adapter.resolutionInputPaths(project, BuildSelection(UpdateScope.CURRENT_FILE, selected.path)) }.contains(target.toString()))
            val savedText = document.text
            WriteCommandAction.runWriteCommandAction(project) { document.setText(savedText + "\n") }
            assertFalse("Unsaved relocated credentials must invalidate the full audit", adapter.isCurrent(project, current))
            assertFalse(currentPlan.apply(project))
            WriteCommandAction.runWriteCommandAction(project) { document.setText(savedText) }
            FileDocumentManager.getInstance().saveDocument(document)
        } finally { manager.generalSettings.setUserSettingsFile(previousSettings) }
    }

    fun testUnsavedSettingsAndAncestorConfigurationRejectPreparedPreviews() {
        val selected = imported("root/module", dependency)
        val settings = directory.resolve("settings.xml")
        Files.writeString(settings, "<settings/>")
        val previousSettings = manager.generalSettings.userSettingsFile
        try {
            manager.generalSettings.setUserSettingsFile(settings.toString())
            val configDirectory = Files.createDirectories(directory.resolve("root/.mvn"))
            val paths = listOf(settings, configDirectory.resolve("maven.config"), configDirectory.resolve("jvm.config"),
                Files.createDirectories(configDirectory.resolve("wrapper")).resolve("maven-wrapper.properties"))
            for (path in paths) {
                val original = if (path == settings) "<settings/>" else "original"
                Files.writeString(path, original)
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
                val document = FileDocumentManager.getInstance().getDocument(file)!!
                val adapter = MavenBuildSystemAdapter()
                val snapshot = adapter.snapshot(project, selected.file)!!
                val plan = background { adapter.prepareUpdates(project, reports(listOf(snapshot))) }
                assertEquals(1, plan.changes.size)
                WriteCommandAction.runWriteCommandAction(project) { document.setText(original + "\nchanged") }
                assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
                assertFalse("Unsaved ${path.fileName} must invalidate its preview", adapter.isCurrent(project, snapshot))
                assertFalse(plan.apply(project))
                assertTrue(FileDocumentManager.getInstance().getDocument(selected.file)!!.text.contains("<version>1.2.3</version>"))
                WriteCommandAction.runWriteCommandAction(project) { document.setText(original) }
                FileDocumentManager.getInstance().saveDocument(document)
            }
        } finally { manager.generalSettings.setUserSettingsFile(previousSettings) }
    }

    fun testSharedProjectInputsDiscoveryValidationAndApplication() {
        imported("root", "<packaging>pom</packaging>")
        val dependencies = (1..100).joinToString("") {
            "<dependency><groupId>g</groupId><artifactId>dependency-$it</artifactId><version>1.2.3</version></dependency>"
        }
        val modules = (1..100).map { imported("root/modules/module-$it", "<dependencies>$dependencies</dependencies>") }
        val adapter = MavenBuildSystemAdapter()
        val selection = BuildSelection(UpdateScope.WHOLE_PROJECT)
        val snapshots = background { adapter.discover(project, selection) }
        assertEquals(101, snapshots.size)
        assertEquals(10_000, snapshots.sumOf { it.declarations.size })
        val samples = mutableMapOf<String, MutableList<Long>>()
        repeat(5) {
            for (path in listOf("discovery", "validation")) {
                val started = System.nanoTime()
                if (path == "discovery") assertEquals(snapshots, background { adapter.discover(project, selection) })
                else assertTrue(adapter.areCurrent(project, snapshots))
                val elapsed = System.nanoTime() - started
                samples.getOrPut(path) { mutableListOf() } += elapsed
                println("version-check benchmark=maven-inputs path=$path elapsedNs=$elapsed poms=101 declarations=10000")
            }
        }
        for ((path, durations) in samples) {
            val sorted = durations.sorted()
            println("version-check benchmark=maven-inputs path=$path samples=5 medianNs=${sorted[2]} p95Ns=${sorted.last()}")
        }
        val plan = background { adapter.prepareUpdates(project, reports(snapshots)) }
        assertEquals(100, plan.changes.size)
        val paths = snapshots.flatMap { it.fingerprint.files.keys + it.sourceFile }.toSet()
        fun states(): Map<String, String> = paths.associateWith { path ->
            val file = LocalFileSystem.getInstance().findFileByPath(path)
            val documents = FileDocumentManager.getInstance()
            val document = file?.let(documents::getCachedDocument)
            "vfs=${file?.modificationStamp}, document=${document?.modificationStamp}, unsaved=${document?.let(documents::isDocumentUnsaved)}"
        }
        fun changed(before: Map<String, String>, after: Map<String, String>) =
            before.keys.filter { before[it] != after[it] }.take(5).map {
                "${it.removePrefix(directory.toString())}: ${before[it]} -> ${after[it]}"
            }
        var validation = "Input guard was not called"
        val diagnosed = plan.copy(prepareValidation = {
            val before = com.intellij.openapi.application.readAction { states() }
            val captured = plan.prepareValidation!!.capture()
            val prepared = com.intellij.openapi.application.readAction { states() }
            // Clean documents are softly cached. Apply must survive cache eviction.
            com.intellij.util.ref.GCUtil.tryGcSoftlyReachableObjects()
            CapturedEditInputs {
                captured.isCurrent().also { current ->
                    validation = "inputCurrent=$current, captureChanges=${changed(before, prepared)}, applyChanges=${changed(prepared, states())}"
                }
            }
        })
        if (!diagnosed.apply(project)) {
            val saved = background { prepareSavedBuildInputValidation(project, snapshots) }
            fail("Apply rejected unchanged fixture: $validation; freshSavedCurrent=${saved.isCurrent()}; " +
                "snapshotsCurrent=${adapter.areCurrent(project, snapshots)}; invalidEdits=${plan.changes.filter { !it.isValid() }.take(5).map { it.location }}")
        }
        assertTrue(modules.all { FileDocumentManager.getInstance().getDocument(it.file)!!.text.contains("<version>1.2.9</version>") })

        // A fresh validation pass must observe an uncommitted edit in an unselected sibling.
        val selected = adapter.snapshot(project, modules.first().file)!!
        val document = FileDocumentManager.getInstance().getDocument(modules.last().file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(document.text.replace("dependency-100", "changed")) }
        assertFalse(adapter.areCurrent(project, listOf(selected)))
    }
    fun testRecognizedInactiveDynamicAndReactorDeclarationsHaveExplicitSkipReasons() {
        imported("coverage-parent", "")
        val child = imported("coverage-child", """<parent><groupId>scaling</groupId><artifactId>coverage-parent</artifactId><version>1</version></parent>
            <dependencies>
            <dependency><groupId>g</groupId><artifactId>fixed</artifactId><version>1.0</version></dependency>
            <dependency><groupId>g</groupId><artifactId>range</artifactId><version>[1,2)</version></dependency>
            </dependencies>
            <profiles><profile><id>inactive</id><dependencies><dependency><groupId>g</groupId><artifactId>inactive</artifactId><version>1.0</version></dependency>
            </dependencies></profile></profiles>""")
        val adapter = MavenBuildSystemAdapter()
        val snapshot = adapter.snapshot(project, child.file)!!
        assertEquals(listOf("g:fixed"), snapshot.declarations.map { it.artifact.name })
        assertEquals(3, snapshot.skippedDeclarations.size)
        assertTrue(snapshot.skippedDeclarations.values.any { it.contains("Reactor parent") })
        assertTrue(snapshot.skippedDeclarations.values.any { it.contains("Inactive Maven profile") })
        assertTrue(snapshot.skippedDeclarations.values.any { it.contains("non-fixed") })
        val report = UpdateReport(coverage = snapshot.skippedCoverage() + snapshot.declarations.associate {
            it.id to DeclarationCoverage(DeclarationOutcome.CHECKED)
        })
        assertEquals(CoverageSummary(checked = 1, skipped = 3), report.coverageSummary(snapshot))
        val plan = background { adapter.prepareUpdates(project, mapOf(snapshot to report)) }
        assertTrue(plan.changes.isEmpty())
        assertEquals(3, plan.skipped.size)
    }

}
