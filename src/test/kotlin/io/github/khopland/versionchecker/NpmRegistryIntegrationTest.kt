package io.github.khopland.versionchecker

import com.google.gson.JsonObject
import com.intellij.codeInspection.*
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.npm.NpmManager
import com.intellij.javascript.nodejs.npm.NpmNodePackage
import com.intellij.javascript.nodejs.util.NodePackageRef
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject as PsiJsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in: real IntelliJ-selected Node/npm, with an authenticated local registry and no installs. */
class NpmRegistryIntegrationTest : BasePlatformTestCase() {
    fun testScopedRegistryWorkspaceModesInspectionsAndManifestOnlyApply() {
        if (!java.lang.Boolean.getBoolean("versionchecker.npmIntegration")) return
        val node = PathEnvironmentVariableUtil.findInPath("node")!!.toPath().toRealPath()
        val npm = PathEnvironmentVariableUtil.findInPath("npm")!!.toPath().toRealPath()
        val directory = Files.createTempDirectory("version-checker-npm-integration-")
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString(), directory.toRealPath().toString(), node.parent.toString(), npm.parent.toString())
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val versions = listOf("1.2.3", "1.2.5", "1.2.8", "1.2.9", "1.9.0", "2.0.0", "3.0.0-beta.1", "4.0.0")
        val metadata = JsonObject().apply {
            addProperty("name", "@fixture/alpha")
            add("dist-tags", JsonObject().apply { addProperty("latest", "2.0.0"); addProperty("next", "4.0.0") })
            add("versions", JsonObject().apply {
                for (version in versions) add(version, JsonObject().apply {
                    addProperty("name", "@fixture/alpha"); addProperty("version", version)
                    if (version == "1.2.3") addProperty("deprecated", "Upgrade from this old version")
                    if (version == "1.2.9") addProperty("deprecated", "Broken release")
                    add("dist", JsonObject().apply { addProperty("tarball", "http://127.0.0.1:${server.address.port}/must-not-download.tgz") })
                })
            })
        }.toString().toByteArray()
        server.createContext("/") { exchange ->
            try {
                requests += exchange.requestURI.path
                if (exchange.requestHeaders.getFirst("Authorization") != "Bearer fixture-token") {
                    exchange.sendResponseHeaders(401, -1)
                } else if (exchange.requestURI.path == "/@fixture/alpha") {
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, metadata.size.toLong())
                    exchange.responseBody.write(metadata)
                } else exchange.sendResponseHeaders(404, -1)
            } finally { exchange.close() }
        }
        server.start()
        val interpreterManager = NodeJsInterpreterManager.getInstance(project)
        val npmManager = NpmManager.getInstance(project)
        val previousInterpreter = interpreterManager.interpreterRef
        val previousNpm = npmManager.packageRef
        var contentRootAdded = false
        try {
            interpreterManager.setInterpreterRef(NodeJsInterpreterRef.create(NodeJsLocalInterpreter(node.toString())))
            npmManager.setPackageRef(NodePackageRef.create(NpmNodePackage(npm.toString())))
            project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
            Files.writeString(directory.resolve("package.json"), """{
              "name":"integration-demo", "private":true, "packageManager":"npm@10.9.8", "workspaces":["packages/*"],
              "dependencies":{"@fixture/alpha":"^1.2.3","@local/library":"workspace:*"},
              "devDependencies":{"alias":"npm:@fixture/alpha@~1.2.3"}
            }""")
            val workspace = Files.createDirectories(directory.resolve("packages/library"))
            Files.writeString(workspace.resolve("package.json"), """{"name":"@local/library","version":"1.0.0","dependencies":{"@fixture/alpha":"1.2.3"}}""")
            Files.writeString(directory.resolve(".npmrc"), """
                registry=http://127.0.0.1:9/
                @fixture:registry=http://127.0.0.1:${server.address.port}/
                //127.0.0.1:${server.address.port}/:_authToken=fixture-token
            """.trimIndent())
            val lock = directory.resolve("package-lock.json")
            val lockText = """{"lockfileVersion":3,"packages":{}}"""
            Files.writeString(lock, lockText)
            val virtualRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
            ModuleRootModificationUtil.addContentRoot(myFixture.module, virtualRoot.url)
            contentRootAdded = true
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory.resolve("package.json"))!!
            val childFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(workspace.resolve("package.json"))!!
            val adapter = BuildSystemAdapter.find("npm")!!
            for ((mode, expected) in listOf(UpdateMode.PATCH to "1.2.8", UpdateMode.MINOR to "1.9.0", UpdateMode.MAJOR to "2.0.0")) {
                for (scope in UpdateScope.entries) {
                    val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                        runBlocking { project.service<BulkUpdateService>().createPlan(mode, scope, rootFile) }
                    })
                    val plan = PlatformTestUtil.waitForFuture(future, 120_000)
                    assertEquals(if (scope == UpdateScope.CURRENT_FILE) 2 else 3, plan.changes.size)
                    assertTrue(plan.changes.all { it.latest.endsWith(expected) })
                    assertTrue(plan.skipped.single().contains("local workspace"))
                    if (scope == UpdateScope.CURRENT_FILE) assertTrue(plan.changes.all { it.location.startsWith(rootFile.path) })
                }
            }
            // Inspection goes through the real asynchronous shared cache and native JSON visitor.
            val snapshot = adapter.snapshot(project, rootFile)!!
            val service = project.service<VersionCheckService>()
            service.updates(adapter, snapshot)
            PlatformTestUtil.waitWithEventsDispatching("npm background check", { service.cached(snapshot) != null }, 120_000)
            val psi = PsiManager.getInstance(project).findFile(rootFile)!!
            fun problems(): List<ProblemDescriptor> {
                val holder = ProblemsHolder(InspectionManager.getInstance(project), psi, true)
                val visitor = NewerNpmDependencyInspection().buildVisitor(holder, true)
                PsiTreeUtil.collectElements(psi) { true }.forEach { it.accept(visitor) }
                return holder.results
            }
            assertEquals(2, problems().size)
            assertTrue(problems().all { it.highlightType == ProblemHighlightType.GENERIC_ERROR && "deprecated" in it.descriptionTemplate })
            val options = project.service<VersionCheckerSettings>().state
            options.deprecatedSeverity = VersionSeverity.DISABLED
            assertTrue(problems().isEmpty())
            options.deprecatedSeverity = VersionSeverity.WARNING
            assertTrue(problems().all { it.highlightType == ProblemHighlightType.WARNING })
            options.deprecatedSeverity = VersionSeverity.ERROR
            // Apply actual whole-project results; npm must never write the lockfile or install packages.
            val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { project.service<BulkUpdateService>().createPlan(UpdateMode.PATCH) }
            })
            val plan = PlatformTestUtil.waitForFuture(future, 120_000)
            assertTrue(plan.apply(project))
            FileDocumentManager.getInstance().saveAllDocuments()
            assertTrue(Files.readString(directory.resolve("package.json")).contains("^1.2.8"))
            assertTrue(Files.readString(directory.resolve("package.json")).contains("npm:@fixture/alpha@~1.2.8"))
            assertTrue(Files.readString(workspace.resolve("package.json")).contains("1.2.8"))
            assertEquals(lockText, Files.readString(lock))
            assertFalse(Files.exists(directory.resolve("node_modules")))
            assertTrue(requests.isNotEmpty())
            assertTrue(requests.all { it == "/@fixture/alpha" })
            val updated = adapter.snapshot(project, rootFile)!!
            service.updates(adapter, updated)
            PlatformTestUtil.waitWithEventsDispatching("npm updated diagnostics", { service.cached(updated) != null }, 120_000)
            assertTrue(problems().all { it.highlightType == ProblemHighlightType.WARNING && "declared range" in it.descriptionTemplate })
            assertEquals("1.2.8", ((PsiManager.getInstance(project).findFile(childFile) as JsonFile).topLevelValue as PsiJsonObject)
                .findProperty("dependencies")!!.value.let { ((it as PsiJsonObject).propertyList.single().value as JsonStringLiteral).value })
        } finally {
            if (contentRootAdded) ModuleRootModificationUtil.updateModel(myFixture.module) { model ->
                model.contentEntries.filter { it.url == "file://${directory}" }.forEach(model::removeContentEntry)
            }
            interpreterManager.setInterpreterRef(previousInterpreter)
            npmManager.setPackageRef(previousNpm)
            server.stop(0)
            directory.toFile().deleteRecursively()
        }
    }
}
