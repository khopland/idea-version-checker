package io.github.khopland.versionchecker

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInspection.*
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Opt-in: real IntelliJ-selected Node/npm, with an authenticated local registry and no installs. */
class NpmRegistryIntegrationTest : BasePlatformTestCase() {
    fun testScopedRegistryWorkspaceModesInspectionsAndManifestOnlyApply() {
        if (!java.lang.Boolean.getBoolean("versionchecker.npmIntegration")) return
        val node = PathEnvironmentVariableUtil.findInPath("node")!!.toPath().toRealPath()
        val npm = PathEnvironmentVariableUtil.findInPath("npm")!!.toPath().toRealPath()
        val directory = Files.createTempDirectory("version-checker-npm-integration-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString(), directory.toRealPath().toString(), node.parent.toString(), npm.parent.parent.toString())
        val requests = CopyOnWriteArrayList<String>()
        val rejectAccess = AtomicBoolean()
        val blockResponse = AtomicBoolean()
        val queryStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
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
        val publishedMetadata = AtomicReference(metadata)
        server.createContext("/") { exchange ->
            try {
                requests += exchange.requestURI.path
                if (blockResponse.get()) {
                    queryStarted.countDown()
                    releaseResponse.await(15, TimeUnit.SECONDS)
                }
                if (rejectAccess.get() || exchange.requestHeaders.getFirst("Authorization") != "Bearer fixture-token") {
                    exchange.sendResponseHeaders(401, -1)
                } else if (exchange.requestURI.path == "/@fixture/alpha") {
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.responseHeaders.add("Cache-Control", "max-age=3600")
                    val body = publishedMetadata.get()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body)
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
            npmManager.setPackageRef(NodePackageRef.create(NpmNodePackage(npm.parent.parent.toString())))
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
            com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true, virtualRoot)
            ModuleRootModificationUtil.addContentRoot(myFixture.module, directory.toString())
            contentRootAdded = true
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory.resolve("package.json"))!!
            val childFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(workspace.resolve("package.json"))!!
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val adapter = BuildSystemAdapter.find("npm")!!
            assertFalse("Indexing is still running", com.intellij.openapi.project.DumbService.isDumb(project))
            assertTrue("Manifest is outside project content", com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInContent(rootFile))
            val indexed = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName("package.json", com.intellij.psi.search.GlobalSearchScope.allScope(project))
            assertEquals("Indexed manifests: ${indexed.map { it.path }}; root: ${rootFile.path}", 2,
                NpmManifest.files(project, BuildSelection(UpdateScope.WHOLE_PROJECT)).size)
            assertNotNull(adapter.snapshot(project, rootFile))
            assertEquals(directory.toString(), adapter.snapshot(project, childFile)!!.context.root)
            // Compare the old and combined native queries against exactly the same authenticated
            // fixture. Aliases share each package response; later deprecated candidates are skipped.
            val benchmarkSnapshot = adapter.snapshot(project, rootFile)!!
            val samples = mutableMapOf<String, MutableList<Long>>()
            repeat(5) {
                for (combined in listOf(false, true)) {
                    val before = requests.size
                    val benchmark = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                        runBlocking {
                            val started = System.nanoTime()
                            val report = checkNpmVersions(benchmarkSnapshot.declarations, UpdateMode.PATCH, emptyMap(), Semaphore(4),
                                metadata = { name ->
                                    if (combined) NpmRegistry.metadata(project, directory, name)
                                    else {
                                        val output = CapturingProcessHandler(NpmRegistry.command(project, directory, listOf(name, "versions"))).runProcess(60_000)
                                        check(output.exitCode == 0 && !output.isTimeout)
                                        NpmRegistry.parseLegacyMetadata(output.stdout)
                                    }
                                }, deprecated = { name, version -> NpmRegistry.deprecated(project, directory, name, version) })
                            report to (System.nanoTime() - started)
                        }
                    })
                    val (report, nanos) = PlatformTestUtil.waitForFuture(benchmark, 120_000)
                    assertEquals(listOf("1.2.8", "1.2.8"), report.candidates.map { it.version })
                    assertEquals(2, report.notices.size)
                    assertEquals("One response replaces versions, baseline and two candidate-deprecation queries",
                        if (combined) 1 else 4, requests.size - before)
                    val label = if (combined) "combined" else "legacy"
                    samples.getOrPut(label) { mutableListOf() } += nanos
                    println("version-check benchmark=npm path=$label elapsedNs=$nanos httpRequests=${requests.size - before}")
                }
            }
            for ((label, nanos) in samples) println("version-check benchmark=npm path=$label samples=${nanos.size} medianNs=${nanos.sorted()[nanos.size / 2]} p95Ns=${nanos.max()}")
            // Exercise real npm's single-object normalization, omitted deprecation fields and
            // the no-stable-release range error without changing the workspace declarations.
            val scenarios = listOf(
                """{"name":"@fixture/alpha","dist-tags":{"latest":"1.2.3"},"versions":{"1.2.3":{"name":"@fixture/alpha","version":"1.2.3"}}}""" to "single",
                JsonParser.parseString(String(metadata)).asJsonObject.apply {
                    getAsJsonObject("versions").getAsJsonObject("1.2.3").remove("deprecated")
                }.toString() to "later-deprecated",
                """{"name":"@fixture/alpha","dist-tags":{"latest":"3.0.0-beta.1"},"versions":{"3.0.0-beta.1":{"name":"@fixture/alpha","version":"3.0.0-beta.1"}}}""" to "prerelease-only"
            )
            for ((body, scenario) in scenarios) {
                publishedMetadata.set(body.toByteArray())
                val before = requests.size
                val query = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                    runBlocking { NpmRegistry.metadata(project, directory, "@fixture/alpha") }
                })
                val result = PlatformTestUtil.waitForFuture(query, 120_000)
                assertEquals(if (scenario == "prerelease-only") 2 else 1, requests.size - before)
                when (scenario) {
                    "single" -> { assertEquals(listOf("1.2.3"), result.versions); assertTrue(result.deprecatedByVersion!!.isEmpty()) }
                    "later-deprecated" -> assertEquals(mapOf("1.2.9" to "Broken release"), result.deprecatedByVersion)
                    else -> assertTrue(NpmRegistry.eligible(result, NpmVersion(1, 2, 3), UpdateMode.MAJOR).isEmpty())
                }
            }
            publishedMetadata.set(metadata)
            for ((mode, expected) in listOf(UpdateMode.PATCH to "1.2.8", UpdateMode.MINOR to "1.9.0", UpdateMode.MAJOR to "4.0.0")) {
                for (scope in UpdateScope.entries) {
                    val before = requests.size
                    val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                        runBlocking { project.service<BulkUpdateService>().createPlan(mode, scope, rootFile) }
                    })
                    val plan = PlatformTestUtil.waitForFuture(future, 120_000)
                    assertEquals("One combined lookup per manifest's unique package", if (scope == UpdateScope.CURRENT_FILE) 1 else 2, requests.size - before)
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
            val beforePublication = requests.size
            val published = JsonParser.parseString(String(metadata)).asJsonObject.apply {
                getAsJsonObject("versions").add("5.0.0", JsonObject().apply {
                    addProperty("name", "@fixture/alpha")
                    addProperty("version", "5.0.0")
                })
            }
            publishedMetadata.set(published.toString().toByteArray())
            service.refresh(adapter.id, rootFile)
            PlatformTestUtil.waitWithEventsDispatching("New npm release appears after refresh", {
                service.cached(snapshot)?.candidates?.all { it.version == "5.0.0" } == true
            }, 120_000)
            assertTrue("Refresh must revalidate npm's cached registry metadata", requests.size > beforePublication)
            assertEquals("Refresh should need only one combined query", 1, requests.size - beforePublication)
            val psi = PsiManager.getInstance(project).findFile(rootFile)!!
            fun problems(): List<ProblemDescriptor> {
                val holder = ProblemsHolder(InspectionManager.getInstance(project), psi, true)
                val visitor = NewerNpmDependencyInspection().buildVisitor(holder, true)
                PsiTreeUtil.collectElements(psi) { true }.forEach { it.accept(visitor) }
                return holder.results
            }
            assertEquals(2, problems().size)
            assertTrue(problems().all { it.fixes?.size == 2 })
            assertTrue(problems().all { "locally" in it.fixes!![0].name && "across workspace" in it.fixes!![1].name })
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
            // Registry failures must propagate, without publishing an empty successful plan or touching declarations.
            val beforeFailure = Files.readString(directory.resolve("package.json"))
            rejectAccess.set(true)
            val denied = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { runCatching { service.checkNow(adapter, updated, UpdateMode.PATCH) }.exceptionOrNull() }
            })
            val failure = PlatformTestUtil.waitForFuture(denied, 30_000)
            assertTrue(failure is java.io.IOException)
            assertTrue(failure!!.message.orEmpty().contains("npm view failed"))
            assertFalse(failure.message.orEmpty().contains("fixture-token"))
            assertEquals(beforeFailure, Files.readString(directory.resolve("package.json")))
            rejectAccess.set(false)
            // Cancel after npm has started a real registry request, while the server withholds its response.
            blockResponse.set(true)
            val cancelled = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking {
                    withTimeout(15_000) {
                        val query = async { NpmRegistry.metadata(project, directory, "@fixture/alpha") }
                        try {
                            check(withContext(Dispatchers.IO) { queryStarted.await(10, TimeUnit.SECONDS) })
                            query.cancelAndJoin()
                            query.isCancelled
                        } finally { query.cancel(); releaseResponse.countDown() }
                    }
                }
            })
            assertTrue(PlatformTestUtil.waitForFuture(cancelled, 30_000))
            assertEquals(beforeFailure, Files.readString(directory.resolve("package.json")))
            assertEquals(lockText, Files.readString(lock))
            assertFalse(Files.exists(directory.resolve("node_modules")))
            assertEquals("1.2.8", ((PsiManager.getInstance(project).findFile(childFile) as JsonFile).topLevelValue as PsiJsonObject)
                .findProperty("dependencies")!!.value.let { ((it as PsiJsonObject).propertyList.single().value as JsonStringLiteral).value })
        } finally {
            if (contentRootAdded) ModuleRootModificationUtil.updateModel(myFixture.module) { model ->
                model.contentEntries.filter { it.url == "file://${directory}" }.forEach(model::removeContentEntry)
            }
            interpreterManager.setInterpreterRef(previousInterpreter)
            npmManager.setPackageRef(previousNpm)
            releaseResponse.countDown()
            server.stop(0)
            directory.toFile().deleteRecursively()
        }
    }
}
