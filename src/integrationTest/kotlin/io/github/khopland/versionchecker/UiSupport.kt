package io.github.khopland.versionchecker

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.Document
import com.intellij.driver.sdk.FileEditorManager
import com.intellij.driver.sdk.HighlightInfo
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.getHighlights
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.openEditor
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.waitFor
import com.intellij.ide.starter.driver.engine.BackgroundRun
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Copies a demo from src/test/resources to a temporary folder and opens it in the sandbox IDE. */
fun runWithDemo(test: String, demo: String, prepare: (Path) -> Unit = {}, block: Driver.() -> Unit) {
    val source = Path.of(System.getProperty("versionchecker.demos"), demo)
    val copy = Files.createTempDirectory("vc-ui-$demo").resolve(demo)
    source.toFile().copyRecursively(copy.toFile())
    // Leave out build output that a local Maven run left in the demo.
    copy.toFile().walkBottomUp().filter { it.isDirectory && it.name == "target" }.forEach { it.deleteRecursively() }
    prepare(copy)
    Starter.newContext(test, TestCase(LocalIde.info, LocalProjectInfo(copy))).apply {
        PluginConfigurator(this).installPluginFromPath(Path.of(System.getProperty("versionchecker.pluginZip")))
    }.runIdeWithDriver().useDriverAndCloseIde {
        waitFor("the project is open", 2.minutes) { getOpenProjects().isNotEmpty() }
        // Indexing and the Maven or Gradle import run first.
        ideFrame { waitForIndicators(5.minutes) }
        block()
    }
}

/** The document of the file that is open in the editor. */
fun Driver.openDocument(relativePath: String): Document {
    val project = singleProject()
    // Open by full path: a name such as pom.xml matches several files.
    val path = "${project.getBasePath()}/$relativePath"
    val file = utility(LocalFileSystemRef::class).getInstance().refreshAndFindFileByPath(path) ?: error("$path does not exist")
    openEditor(file, project)
    return waitFor("$relativePath is open", 30.seconds, getter = {
        service(FileEditorManager::class, project).getSelectedTextEditor()?.getDocument()
    }, checker = { it != null })!!
}

/** Waits until a highlight in [document] matches [predicate]. Version lookups go to the real repositories. */
fun Driver.waitForHighlight(document: Document, description: String, timeout: Duration = 2.minutes,
                            predicate: (HighlightInfo) -> Boolean): HighlightInfo {
    val project: Project = singleProject()
    return waitFor(description, timeout, getter = {
        getHighlights(document, project).firstOrNull { runCatching { predicate(it) }.getOrDefault(false) }
    }, checker = { it != null })!!
}

fun HighlightInfo.message(): String = getDescription().orEmpty()

/** The version a warning offers for an update kind, for example `1.7.36` from "… 1.7.36 (patch) · …". */
fun HighlightInfo.target(kind: String): String? = Regex("""(\S+) \($kind\)""").find(message())?.groupValues?.get(1)
