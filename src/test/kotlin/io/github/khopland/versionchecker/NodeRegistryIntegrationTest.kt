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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
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
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in real CLI checks; fixtures never install project dependencies or synchronize lockfiles. */
class NodeRegistryIntegrationTest : BasePlatformTestCase() {
    fun testPnpm() = exercise(NodePackageManager.PNPM, "VERSIONCHECKER_PNPM_PATH", "pnpm")
    fun testPnpmLegacy() = exercise(NodePackageManager.PNPM, "VERSIONCHECKER_PNPM_LEGACY_PATH", null)
    fun testYarnClassic() = exercise(NodePackageManager.YARN, "VERSIONCHECKER_YARN_CLASSIC_PATH", "yarn")
    fun testYarnModern() = exercise(NodePackageManager.YARN, "VERSIONCHECKER_YARN_MODERN_PATH", null, modernYarn = true)
    fun testBun() = exercise(NodePackageManager.BUN, "VERSIONCHECKER_BUN_PATH", "bun")

    private fun exercise(manager: NodePackageManager, environment: String, executable: String?, modernYarn: Boolean = false) {
        val node = PathEnvironmentVariableUtil.findInPath("node")?.toPath()
        val selected = System.getenv(environment)?.let(Path::of) ?: executable?.let { PathEnvironmentVariableUtil.findInPath(it)?.toPath() }
        check(node != null && selected != null) { "Configure $environment and a local Node.js executable for this integration test" }
        val runtime = selected!!
        val nodePath = node!!
        val versionCommand = if (Files.isDirectory(runtime)) listOf(nodePath.toString(), runtime.resolve("bin/yarn.js").toString(), "--version")
            else listOf(runtime.toString(), "--version")
        val process = ProcessBuilder(versionCommand).start()
        val version = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0 && Regex("[0-9]+\\.[0-9]+\\.[0-9]+.*").matches(version)) { "Could not determine fixture runtime version" }
        val directory = Files.createTempDirectory("version-checker-${manager.id}-integration-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString(), nodePath.parent.toString(), runtime.parent.toString())
        val requests = CopyOnWriteArrayList<String>()
        val denied = AtomicBoolean()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val metadata = JsonObject().apply {
            addProperty("name", "@fixture/alpha")
            add("dist-tags", JsonObject().apply { addProperty("latest", "2.0.0") })
            add("versions", JsonObject().apply {
                for (v in listOf("1.2.3", "1.2.8", "1.2.9", "1.9.0", "2.0.0", "3.0.0-beta.1", "4.0.0")) add(v, JsonObject().apply {
                    addProperty("name", "@fixture/alpha"); addProperty("version", v)
                    add("engines", JsonObject().apply { addProperty("node", ">=20") })
                    add("peerDependencies", JsonObject().apply { addProperty("react", "^19") })
                    if (v == "1.2.3") addProperty("deprecated", "Upgrade old version")
                    if (v == "1.2.9") addProperty("deprecated", "Broken release")
                    add("dist", JsonObject().apply { addProperty("tarball", "http://127.0.0.1:${server.address.port}/must-not-download.tgz") })
                })
            })
        }.toString().toByteArray()
        server.createContext("/") { exchange ->
            try {
                requests += exchange.requestURI.path
                if (denied.get() || exchange.requestHeaders.getFirst("Authorization") != "Bearer fixture-token") {
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
        val previousManager = npmManager.packageRef
        var contentRootAdded = false
        try {
            interpreterManager.setInterpreterRef(NodeJsInterpreterRef.create(NodeJsLocalInterpreter(nodePath.toString())))
            npmManager.setPackageRef(NodePackageRef.create(NpmNodePackage(runtime.toString())))
            project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
            Files.writeString(directory.resolve("package.json"), """{
                "name":"integration-demo", "private":true, "packageManager":"${manager.id}@$version", "workspaces":["packages/*"],
                "dependencies":{"@fixture/alpha":"^1.2.3","@local/library":"workspace:*"},
                "devDependencies":{"alias":"npm:@fixture/alpha@~1.2.3"}
            }""")
            val workspace = Files.createDirectories(directory.resolve("packages/library"))
            Files.writeString(workspace.resolve("package.json"), """{"name":"@local/library","version":"1.0.0","dependencies":{"@fixture/alpha":"1.2.3"}}""")
            val registry = "http://127.0.0.1:${server.address.port}/"
            val npmrc = """
                registry=http://127.0.0.1:9/
                @fixture:registry=$registry
                //127.0.0.1:${server.address.port}/:_authToken=fixture-token
                fetch-retries=0
            """.trimIndent()
            Files.writeString(directory.resolve(".npmrc"), npmrc)
            if (manager == NodePackageManager.PNPM) Files.writeString(directory.resolve("pnpm-workspace.yaml"), "packages: ['packages/*']\n")
            if (modernYarn) Files.writeString(directory.resolve(".yarnrc.yml"), """
                npmRegistryServer: "http://127.0.0.1:9/"
                unsafeHttpWhitelist: ["127.0.0.1"]
                httpRetry: 0
                npmScopes:
                  fixture:
                    npmRegistryServer: "$registry"
                    npmAuthToken: "fixture-token"
                    npmAlwaysAuth: true
            """.trimIndent())
            if (manager == NodePackageManager.BUN) {
                // Bun must honor its own config instead of being queried through npm.
                Files.writeString(directory.resolve(".npmrc"), "registry=http://127.0.0.1:9/\n@fixture:registry=http://127.0.0.1:9/\n")
                Files.writeString(directory.resolve("bunfig.toml"), """
                    [install]
                    registry = "http://127.0.0.1:9/"
                    [install.scopes]
                    fixture = { url = "$registry", token = "fixture-token" }
                """.trimIndent())
            }
            val lockName = when(manager) { NodePackageManager.PNPM -> "pnpm-lock.yaml"; NodePackageManager.YARN -> "yarn.lock"; else -> "bun.lock" }
            val lock = directory.resolve(lockName)
            val lockText = if (manager == NodePackageManager.YARN) {
                if (modernYarn) "__metadata:\n  version: 8\n  cacheKey: 10c0\n" else "# yarn lockfile v1\n"
            } else "unchanged lockfile fixture"
            Files.writeString(lock, lockText)
            val virtualRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
            VfsUtil.markDirtyAndRefresh(false, true, true, virtualRoot)
            ModuleRootModificationUtil.addContentRoot(myFixture.module, directory.toString())
            contentRootAdded = true
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory.resolve("package.json"))!!
            val child = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(workspace.resolve("package.json"))!!
            val adapter = BuildSystemAdapter.find(manager.id)!!
            assertEquals(directory.toString(), adapter.snapshot(project, child)!!.context.root)
            for ((mode, expected) in listOf(UpdateMode.PATCH to "1.2.8", UpdateMode.MINOR to "1.9.0", UpdateMode.MAJOR to "4.0.0")) {
                val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                    runBlocking { project.service<BulkUpdateService>().createPlan(mode, UpdateScope.CURRENT_FILE, root) }
                })
                val plan = PlatformTestUtil.waitForFuture(future, 120_000)
                assertEquals(2, plan.changes.size)
                assertTrue(plan.changes.all { it.latest.endsWith(expected) && it.location.startsWith(root.path) })
                assertTrue(plan.changes.all { it.buildSystemId == manager.id })
                assertTrue(plan.followUp.none { it.startsWith("npm updates") })
            }
            val snapshot = adapter.snapshot(project, root)!!
            val service = project.service<VersionCheckService>()
            service.updates(adapter, snapshot)
            PlatformTestUtil.waitWithEventsDispatching("${manager.title} background check", { service.cached(snapshot) != null }, 120_000)
            assertNull(service.cached(snapshot)!!.failure)
            val psi = PsiManager.getInstance(project).findFile(root)!!
            val holder = ProblemsHolder(InspectionManager.getInstance(project), psi, true)
            awaitInspectionInputs(manager.id, psi)
            val visitor = NewerNpmDependencyInspection().buildVisitor(holder, true)
            PsiTreeUtil.collectElements(psi) { true }.forEach { it.accept(visitor) }
            assertEquals(2, holder.results.size)
            assertTrue(holder.results.all { it.highlightType == ProblemHighlightType.LIKE_DEPRECATED && it.fixes?.size == 3 })
            val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { project.service<BulkUpdateService>().createPlan(UpdateMode.PATCH) }
            })
            val plan = PlatformTestUtil.waitForFuture(future, 120_000)
            assertEquals(3, plan.changes.size)
            assertTrue(plan.apply(project))
            FileDocumentManager.getInstance().saveAllDocuments()
            assertTrue(Files.readString(directory.resolve("package.json")).contains("^1.2.8"))
            assertTrue(Files.readString(directory.resolve("package.json")).contains("npm:@fixture/alpha@~1.2.8"))
            assertTrue(Files.readString(workspace.resolve("package.json")).contains("1.2.8"))
            assertEquals(lockText, Files.readString(lock))
            assertFalse(Files.exists(directory.resolve("node_modules")))
            assertTrue(requests.isNotEmpty())
            assertTrue(requests.all { it == "/@fixture/alpha" })
            val beforeFailure = Files.readString(directory.resolve("package.json"))
            val updated = adapter.snapshot(project, root)!!
            adapter.invalidateMetadata(project)
            denied.set(true)
            val failureFuture = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { runCatching { service.checkNow(adapter, updated, UpdateMode.PATCH) }.exceptionOrNull() }
            })
            val failure = PlatformTestUtil.waitForFuture(failureFuture, 120_000)
            assertNotNull("Registry denial should fail the check", failure)
            assertFalse(failure!!.message.orEmpty().contains("fixture-token"))
            assertEquals(beforeFailure, Files.readString(directory.resolve("package.json")))
            assertEquals(lockText, Files.readString(lock))
            if (manager == NodePackageManager.PNPM) {
                denied.set(false)
                // A mismatched declaration must not download another manager before a read-only query.
                val manifestPath = directory.resolve("package.json")
                val manifest = Files.readString(manifestPath)
                try {
                    Files.writeString(manifestPath, manifest.replace("pnpm@$version", "pnpm@9999.0.0"))
                    val versions = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
                        runBlocking { NpmRegistry.metadata(project, directory, "@fixture/alpha", manager) }
                    }), 30_000)
                    assertTrue(versions.versions.contains("4.0.0"))
                } finally { Files.writeString(manifestPath, manifest) }
                // Even metadata commands can bootstrap config dependencies. Reject before spawning pnpm.
                Files.writeString(directory.resolve("pnpm-workspace.yaml"), "packages: ['packages/*']\nconfigDependencies:\n  '@fixture/config-never-install': '1.0.0'\n")
                val requestCount = requests.size
                val bootstrap = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                    runBlocking { runCatching { NpmRegistry.metadata(project, directory, "@fixture/alpha", manager) }.exceptionOrNull() }
                })
                val rejected = PlatformTestUtil.waitForFuture(bootstrap, 30_000)
                assertTrue(rejected?.message.orEmpty().contains("configDependencies"))
                assertEquals(requestCount, requests.size)
                assertEquals(lockText, Files.readString(lock))
                assertFalse(Files.exists(directory.resolve("node_modules")))
            }
        } finally {
            if (contentRootAdded) ModuleRootModificationUtil.updateModel(myFixture.module) { model ->
                model.contentEntries.filter { it.url == virtualUrl(directory) }.forEach(model::removeContentEntry)
            }
            interpreterManager.setInterpreterRef(previousInterpreter)
            npmManager.setPackageRef(previousManager)
            server.stop(0)
            directory.toFile().deleteRecursively()
        }
    }
    private fun virtualUrl(path: Path) = "file://$path"
}
