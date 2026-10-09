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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Opt-in: real IntelliJ-selected Node/npm, with an authenticated local registry and no installs. */
class NpmRegistryIntegrationTest : BasePlatformTestCase() {
    fun testFastNativeRegistryHintsAppearWhileAnotherResponseIsBlocked() {
        if (!java.lang.Boolean.getBoolean("versionchecker.npmIntegration")) return
        val node = PathEnvironmentVariableUtil.findInPath("node")!!.toPath().toRealPath()
        val npm = PathEnvironmentVariableUtil.findInPath("npm")!!.toPath().toRealPath()
        val directory = Files.createTempDirectory("version-checker-npm-early-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString(), node.parent.toString(), npm.parent.parent.toString())
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<String>()
        val executor = Executors.newFixedThreadPool(2)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = executor }
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                requests += path
                if (path == "/@early/slow") { slowStarted.countDown(); releaseSlow.await(30, TimeUnit.SECONDS) }
                if (exchange.requestHeaders.getFirst("Authorization") != "Bearer fixture-token") exchange.sendResponseHeaders(401, -1)
                else if (path in setOf("/@early/fast", "/@early/slow")) {
                    val name = path.removePrefix("/")
                    val body = """{"name":"$name","dist-tags":{"latest":"1.1.0"},"versions":{"1.0.0":{"name":"$name","version":"1.0.0"},"1.1.0":{"name":"$name","version":"1.1.0"}}}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
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
        var scan: Job? = null
        try {
            interpreterManager.setInterpreterRef(NodeJsInterpreterRef.create(NodeJsLocalInterpreter(node.toString())))
            npmManager.setPackageRef(NodePackageRef.create(NpmNodePackage(npm.parent.parent.toString())))
            Files.writeString(directory.resolve("package.json"), """{"dependencies":{"@early/slow":"^1.0.0","@early/fast":"^1.0.0"}}""")
            Files.writeString(directory.resolve(".npmrc"), """
                registry=http://127.0.0.1:9/
                @early:registry=http://127.0.0.1:${server.address.port}/
                //127.0.0.1:${server.address.port}/:_authToken=fixture-token
                fetch-retries=0
            """.trimIndent())
            val virtualRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
            com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true, virtualRoot)
            ModuleRootModificationUtil.addContentRoot(myFixture.module, directory.toString())
            contentRootAdded = true
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val file = virtualRoot.findChild("package.json")!!
            val adapter = BuildSystemAdapter.find("npm")!!
            val snapshot = adapter.snapshot(project, file)!!
            val service = project.service<VersionCheckService>()
            scan = service.refresh(listOf(adapter), file)
            PlatformTestUtil.waitWithEventsDispatching("Slow native npm request started", { slowStarted.count == 0L }, 20_000)
            PlatformTestUtil.waitWithEventsDispatching("Fast native npm result is available", {
                service.updates(adapter, snapshot)?.candidates?.any { it.declaration.artifact.name == "@early/fast" } == true
            }, 20_000)
            assertFalse(scan.isCompleted)
            assertNull("Early hints are inspection-only", service.cached(snapshot))
            val psi = PsiManager.getInstance(project).findFile(file)!!
            val holder = ProblemsHolder(InspectionManager.getInstance(project), psi, true)
            val visitor = NewerNpmDependencyInspection().buildVisitor(holder, true)
            PsiTreeUtil.collectElements(psi) { true }.forEach { it.accept(visitor) }
            assertEquals(1, holder.results.size)
            assertTrue(holder.results.single().descriptionTemplate.contains("@early/fast"))
            releaseSlow.countDown()
            PlatformTestUtil.waitWithEventsDispatching("Native npm scan completed", { scan.isCompleted }, 20_000)
            assertTrue(service.cached(snapshot)!!.successful)
            assertEquals(2, service.cached(snapshot)!!.candidates.size)
            assertEquals(setOf("/@early/fast", "/@early/slow"), requests.toSet())
            assertFalse(Files.exists(directory.resolve("node_modules")))
            assertFalse(Files.exists(directory.resolve("package-lock.json")))
        } finally {
            releaseSlow.countDown()
            scan?.cancel()
            scan?.let { PlatformTestUtil.waitWithEventsDispatching("Native early-result scan stopped", { it.isCompleted }, 20_000) }
            if (contentRootAdded) ModuleRootModificationUtil.updateModel(myFixture.module) { model ->
                model.contentEntries.filter { it.url == "file://$directory" }.forEach(model::removeContentEntry)
            }
            interpreterManager.setInterpreterRef(previousInterpreter)
            npmManager.setPackageRef(previousNpm)
            server.stop(0)
            executor.shutdownNow()
            directory.toFile().deleteRecursively()
        }
    }

    fun testRuntimeShimIsSharedDuringAScanAndResolvedAgainAfterwards() {
        if (!java.lang.Boolean.getBoolean("versionchecker.npmIntegration")) return
        if (com.intellij.openapi.util.SystemInfo.isWindows) return // This fixture emulates POSIX version-manager shims.
        val node = PathEnvironmentVariableUtil.findInPath("node")!!.toPath().toRealPath()
        val directory = Files.createTempDirectory("version-checker-npm-shim-").toRealPath()
        val probes = directory.resolve("probes")
        fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
        val shim = directory.resolve("node-shim")
        Files.writeString(shim, """#!/bin/sh
            if [ "${'$'}1" = "-p" ] && [ "${'$'}2" = "process.execPath" ]; then
              printf 'probe\n' >> ${quote(probes.toString())}
            fi
            exec ${quote(node.toString())} "${'$'}@"
        """.trimIndent())
        check(shim.toFile().setExecutable(true))
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString(), node.parent.parent.toString())
        val interpreterManager = NodeJsInterpreterManager.getInstance(project)
        val npmManager = NpmManager.getInstance(project)
        val previousInterpreter = interpreterManager.interpreterRef
        val previousNpm = npmManager.packageRef
        try {
            interpreterManager.setInterpreterRef(NodeJsInterpreterRef.create(NodeJsLocalInterpreter(shim.toString())))
            npmManager.setPackageRef(NodePackageRef.create(NpmNodePackage(directory.toString())))
            val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking {
                    val context = NpmResolutionContext(directory.toString(), "fixture")
                    val sessions = project.service<NpmMetadataService>().runtimes
                    val load: suspend () -> NpmRuntime = { NpmRegistry.resolve(project, directory) }
                    sessions.withSession(context, load) {
                        repeat(3) {
                            List(4) { async { sessions.withSession(context, load) { it.await() } } }.awaitAll().forEach { runtime ->
                                assertEquals(shim.toString(), runtime.interpreter.interpreterSystemDependentPath)
                                assertTrue(Files.isRegularFile(java.nio.file.Path.of(runtime.npm.systemDependentPath).resolve("bin/npm-cli.js")))
                            }
                        }
                        assertEquals(1, Files.readAllLines(probes).size)
                        sessions.invalidate()
                        sessions.withSession(context, load) { it.await() }
                        assertEquals(2, Files.readAllLines(probes).size)
                    }
                    sessions.withSession(context, load) { it.await() }
                    assertEquals(3, Files.readAllLines(probes).size)
                    println("version-check benchmark=npm-runtime runtimeRequests=12 shimProbes=1 laterScanProbes=1 refreshProbes=1")
                }
            })
            PlatformTestUtil.waitForFuture(future, 30_000)
        } finally {
            interpreterManager.setInterpreterRef(previousInterpreter)
            npmManager.setPackageRef(previousNpm)
            directory.toFile().deleteRecursively()
        }
    }

    fun testNativeRuntimeProbeStopsOnCancellationAndTimeout() {
        if (!java.lang.Boolean.getBoolean("versionchecker.npmIntegration")) return
        val node = PathEnvironmentVariableUtil.findInPath("node")!!.toPath().toRealPath()
        val directory = Files.createTempDirectory("version-checker-npm-cancel-").toRealPath()
        try {
            val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking {
                    for (timeout in listOf(false, true)) {
                        val pidFile = directory.resolve(if (timeout) "timeout.pid" else "cancel.pid")
                        val script = "require('fs').writeFileSync(process.argv[1], String(process.pid)); setInterval(() => {}, 1000)"
                        val command = com.intellij.execution.configurations.GeneralCommandLine(node.toString(), "-e", script, pidFile.toString())
                        val query = async {
                            if (timeout) withTimeoutOrNull(2_000) { NpmRegistry.execute(command) }
                            else NpmRegistry.execute(command)
                        }
                        try {
                            withTimeout(10_000) { while (!Files.exists(pidFile)) delay(10) }
                            val process = ProcessHandle.of(Files.readString(pidFile).toLong()).orElseThrow()
                            assertTrue(process.isAlive)
                            if (timeout) assertNull(query.await()) else query.cancelAndJoin()
                            withTimeout(5_000) { while (process.isAlive) delay(10) }
                        } finally { query.cancelAndJoin() }
                    }
                }
            })
            PlatformTestUtil.waitForFuture(future, 30_000)
        } finally { directory.toFile().deleteRecursively() }
    }

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
            // External edits must be detected even if IDEA still has a saved document with the
            // previous package.json content. Native runtime shims may read fields such as volta.
            val nativeAdapter = adapter as NpmBuildSystemAdapter
            val externalRoot = Files.createTempDirectory("version-checker-npm-runtime")
            try {
                val externalManifest = externalRoot.resolve("package.json")
                Files.writeString(externalManifest, "{}")
                val externalVirtualRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(externalRoot)!!
                val externalFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(externalManifest)!!
                FileDocumentManager.getInstance().getDocument(externalFile)
                val hashes = project.service<NpmProjectCache>()
                val originalDigest = hashes.digest(externalFile)
                val originalContext = NpmBuildInputs.resolutionContext(project, externalVirtualRoot)
                Files.writeString(externalManifest, """{"volta":{"node":"24.0.0"}}""")
                assertFalse("An external runtime-selection edit must change the metadata context",
                    originalContext == NpmBuildInputs.resolutionContext(project, externalVirtualRoot))
                com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, false, false, externalFile)
                assertFalse("Manifest hashes must observe refreshed saved bytes despite a cached document",
                    originalDigest == hashes.digest(externalFile))
            } finally { externalRoot.toFile().deleteRecursively() }
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
                        runBlocking { project.service<BulkUpdateService>().createPlan(mode, scope, rootFile, forceRefresh = true) }
                    })
                    val plan = PlatformTestUtil.waitForFuture(future, 120_000)
                    assertEquals("One combined lookup per workspace's unique package and fresh generation", 1, requests.size - before)
                    assertEquals(if (scope == UpdateScope.CURRENT_FILE) 2 else 3, plan.changes.size)
                    assertTrue(plan.changes.all { it.latest.endsWith(expected) })
                    assertTrue(plan.skipped.single().contains("local workspace"))
                    if (scope == UpdateScope.CURRENT_FILE) assertTrue(plan.changes.all { it.location.startsWith(rootFile.path) })
                    val warmBefore = requests.size
                    val warm = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
                        runBlocking { project.service<BulkUpdateService>().createPlan(mode, scope, rootFile) }
                    }), 120_000)
                    assertEquals("Warm same-mode previews must not query the registry", warmBefore, requests.size)
                    assertEquals(plan.changes.map { it.latest }, warm.changes.map { it.latest })
                }
            }
            // A real duplicate workspace exercises context construction, native auth and cache
            // sharing across files. Keep it independent of the manifests edited below.
            val scaling = Files.createDirectories(directory.resolve("scaling"))
            Files.writeString(scaling.resolve("package.json"), """{"private":true,"workspaces":["packages/*"]}""")
            Files.copy(directory.resolve(".npmrc"), scaling.resolve(".npmrc"))
            repeat(100) { index ->
                val member = Files.createDirectories(scaling.resolve("packages/member-$index"))
                Files.writeString(member.resolve("package.json"), """{"name":"member-$index","dependencies":{
                    "@fixture/alpha":"^1.2.3","alias":"npm:@fixture/alpha@~1.2.3"}}""")
            }
            val scalingRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(scaling)!!
            com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true, scalingRoot)
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val scalingBefore = requests.size
            val scalingCheck = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking {
                    val started = System.nanoTime()
                    val snapshots = adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)).filter { it.context.root == scaling.toString() }
                    check(snapshots.size == 101)
                    adapter.invalidateMetadata(project)
                    val reports = snapshots.associateWith { project.service<VersionCheckService>().checkNow(adapter, it, UpdateMode.PATCH) }
                    adapter.prepareUpdates(project, reports) to (System.nanoTime() - started)
                }
            })
            val (scalingPlan, scalingNanos) = PlatformTestUtil.waitForFuture(scalingCheck, 120_000)
            assertEquals(200, scalingPlan.changes.size)
            assertTrue(scalingPlan.changes.all { it.latest.endsWith("1.2.8") })
            assertEquals("One native package query across 100 workspace manifests and aliases", 1, requests.size - scalingBefore)
            println("version-check benchmark=npm-workspace manifests=101 declarations=200 elapsedNs=$scalingNanos httpRequests=${requests.size - scalingBefore}")
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { scalingRoot.delete(this) }
            adapter.invalidateMetadata(project)
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
            adapter.invalidateMetadata(project)
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
