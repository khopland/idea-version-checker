package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.npm.NpmBuildSystemAdapter
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Callable

class NpmSnapshotScalingTest : BasePlatformTestCase() {
    private fun <T> background(action: suspend () -> T): T = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { action() } }), 60_000)

    fun testLargeWorkspaceDiscoveryValidationAndApplication() {
        val adapter = NpmBuildSystemAdapter()
        myFixture.addFileToProject("large/package.json", """{"workspaces":["packages/*"]}""")
        val dependencies = (1..100).joinToString(",") { "\"dependency-$it\":\"^1.2.3\"" }
        val files = (1..100).map {
            myFixture.addFileToProject("large/packages/package-$it/package.json",
                """{"name":"local-$it","dependencies":{$dependencies}}""")
        }
        val discoveryStart = System.nanoTime()
        val snapshots = background { adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
        val discoveryMs = (System.nanoTime() - discoveryStart) / 1_000_000
        assertEquals(101, snapshots.size)
        assertEquals(10_000, snapshots.sumOf { it.declarations.size })
        // Ordinary editing should not repeatedly rebuild the same workspace inputs. Alternate
        // warm validation and source edits within one fixture, without imposing timing thresholds.
        val source = myFixture.addFileToProject("large/src/app.js", "export const value = 0;\n")
        val sourceDocument = FileDocumentManager.getInstance().getDocument(source.virtualFile)!!
        val selected = snapshots.first { it.declarations.isNotEmpty() }
        val samples = mutableMapOf<String, MutableList<Long>>()
        repeat(5) { sample ->
            assertTrue(adapter.isCurrent(project, selected))
            for (edited in listOf(false, true)) {
                if (edited) WriteCommandAction.runWriteCommandAction(project) {
                    sourceDocument.setText("export const value = ${sample + 1};\n")
                }
                val started = System.nanoTime()
                assertTrue(adapter.isCurrent(project, selected))
                val elapsed = System.nanoTime() - started
                val path = if (edited) "source-edit" else "warm"
                samples.getOrPut(path) { mutableListOf() } += elapsed
                println("version-check benchmark=npm-source-edit path=$path elapsedNs=$elapsed manifests=101 declarations=10000")
            }
        }
        for ((path, durations) in samples) {
            val sorted = durations.sorted()
            println("version-check benchmark=npm-source-edit path=$path samples=5 medianNs=${sorted[2]} p95Ns=${sorted.last()}")
        }
        val reports = snapshots.associateWith { snapshot ->
            UpdateReport(snapshot.declarations.take(1).map { UpdateCandidate(it, "1.2.9", "^1.2.9") })
        }
        val preparationStart = System.nanoTime()
        val plan = background { adapter.prepareUpdates(project, reports) }
        val preparationMs = (System.nanoTime() - preparationStart) / 1_000_000
        assertEquals(100, plan.changes.size)
        val validationStart = System.nanoTime()
        assertTrue(snapshots.all { adapter.isCurrent(project, it) })
        val validationMs = (System.nanoTime() - validationStart) / 1_000_000
        val applicationStart = System.nanoTime()
        assertTrue(plan.apply(project))
        val applicationMs = (System.nanoTime() - applicationStart) / 1_000_000
        println("npm benchmark: 101 manifests, 10,000 declarations; discovery=${discoveryMs}ms, " +
            "preparation=${preparationMs}ms, individual validation=${validationMs}ms, application=${applicationMs}ms")

        // Rebuild the cache after application, then check that an uncommitted sibling edit is still detected.
        val snapshot = adapter.snapshot(project, files.first().virtualFile)!!
        val document = FileDocumentManager.getInstance().getDocument(files.last().virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(document.text.replace("local-100", "renamed")) }
        assertFalse(adapter.isCurrent(project, snapshot))
    }
}
