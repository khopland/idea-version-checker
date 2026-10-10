package io.github.khopland.versionchecker

import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.PluginMainDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.cl.PluginClassLoader
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.loadDescriptorInTest
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.UiInterceptors
import io.github.khopland.versionchecker.core.BuildSystemAdapter
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Load the shipped ZIP in a real plugin class loader, independent of Gradle's shared test loader. */
@Suppress("UnstableApiUsage")
class DynamicPluginLifecycleTest : HeavyPlatformTestCase() {
    private lateinit var source: PluginMainDescriptor
    private lateinit var packaged: PluginMainDescriptor
    private lateinit var unpacked: Path

    override fun setUpProject() {
        source = descriptor()
        unpacked = unpackArchive()
        val directory = Files.list(unpacked).use { it.findFirst().orElseThrow() }
        packaged = loadDescriptorInTest(directory)
        assertTrue(DynamicPlugins.unloadPlugin(source, DynamicPlugins.UnloadPluginOptions(disable = false, save = false)))
        assertTrue(DynamicPlugins.loadPlugin(packaged))
        // Create the project after loading the ZIP: shared-loader light services from Gradle's
        // source plugin cannot be reassigned to a different plugin class loader in an existing project.
        super.setUpProject()
    }

    override fun tearDown() {
        // The test framework clears instance fields in tearDown; keep restoration handles locally.
        val packagedPlugin = if (::packaged.isInitialized) packaged else null
        val sourcePlugin = if (::source.isInitialized) source else null
        val directory = if (::unpacked.isInitialized) unpacked else null
        try { super.tearDown() } finally {
            if (packagedPlugin?.pluginClassLoader != null) {
                assertTrue(DynamicPlugins.unloadPlugin(packagedPlugin, DynamicPlugins.UnloadPluginOptions(disable = false, save = false)))
            }
            if (sourcePlugin != null) assertTrue(DynamicPlugins.loadPlugin(sourcePlugin))
            if (directory != null) Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
    fun testDescriptorAndAdapterExtensionPointAreDynamic() {
        assertNull(DynamicPlugins.checkCanUnloadWithoutRestart(descriptor()))
        assertTrue(BuildSystemAdapter.EP.point.isDynamic)
    }

    fun testPackagedPluginRepeatedUnloadReloadReleasesServicesAndClassLoaders() {
        repeat(3) {
            if (packaged.pluginClassLoader == null) assertTrue(DynamicPlugins.loadPlugin(packaged))
            assertTrue(packaged.pluginClassLoader is PluginClassLoader)
            awaitClassLoaderCollection(unloadWithServices(packaged))
        }
    }

    fun testUnloadCancelsScheduledAndInFlightChecksAndClosesAPreview() {
        awaitClassLoaderCollection(unloadWithActiveWorkAndPreview(packaged))
    }

    fun testUnloadCancelsScheduledAndInFlightChecks() {
        awaitClassLoaderCollection(unloadWithActiveWorkAndPreview(packaged, includePreview = false))
    }

    private fun awaitClassLoaderCollection(previousLoader: WeakReference<ClassLoader>) {
        PlatformTestUtil.waitWithEventsDispatching("Plugin class loader collected", {
            System.gc()
            previousLoader.get() == null
        }, 10)
    }

    private fun unloadWithActiveWorkAndPreview(plugin: PluginMainDescriptor, includePreview: Boolean = true): WeakReference<ClassLoader> {
        val loader = plugin.pluginClassLoader!!
        val previousLoader = WeakReference(loader)
        val settings = service(plugin, "VersionCheckerSettings")
        val options = settings.javaClass.getMethod("getState").invoke(settings)
        options.javaClass.getMethod("setScheduledChecks", Boolean::class.javaPrimitiveType).invoke(options, true)
        val scheduled = service(plugin, "ScheduledVersionChecks")
        scheduled.javaClass.getMethod("configure").invoke(scheduled)
        val timer = scheduled.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(scheduled) as Job
        assertTrue(timer.isActive)

        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val check: suspend () -> Any = {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val adapterType = loader.loadClass("io.github.khopland.versionchecker.core.BuildSystemAdapter")
        val capabilitiesType = loader.loadClass("io.github.khopland.versionchecker.core.AdapterCapabilities")
        val defaults = capabilitiesType.getConstructor().newInstance()
        val capabilities = capabilitiesType.constructors.single { it.parameterCount == 2 }
            .newInstance(capabilitiesType.getMethod("getUpdateModes").invoke(defaults), true)
        val adapter = Proxy.newProxyInstance(loader, arrayOf(adapterType)) { proxy, method, args ->
            when (method.name) {
                "getId", "getDisplayName" -> "dynamic-lifecycle-test"
                "getCapabilities" -> capabilities
                "isOffline" -> false
                "isCurrent", "canCheckInBackground" -> true
                "check" -> {
                    @Suppress("UNCHECKED_CAST")
                    (check as Function1<Continuation<Any>, Any?>).invoke(args.last() as Continuation<Any>)
                }
                "checkIncrementally" -> {
                    @Suppress("UNCHECKED_CAST")
                    val publish = args[3] as (suspend (Any) -> Unit)
                    val report = loader.loadClass("io.github.khopland.versionchecker.core.UpdateReport").getConstructor().newInstance()
                    val update = loader.loadClass("io.github.khopland.versionchecker.core.InspectionUpdate")
                        .constructors.single { it.parameterCount == 2 }.newInstance(report, emptySet<Any>())
                    val incremental: suspend () -> Any = { publish(update); check() }
                    @Suppress("UNCHECKED_CAST")
                    (incremental as Function1<Continuation<Any>, Any?>).invoke(args.last() as Continuation<Any>)
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                "toString" -> "Dynamic lifecycle test adapter"
                else -> error("Unexpected adapter method: ${method.name}")
            }
        }
        val contextType = loader.loadClass("io.github.khopland.versionchecker.core.BuildContextId")
        val context = contextType.constructors.single { it.parameterCount == 3 }.newInstance("dynamic-lifecycle-test", project.basePath, "test")
        val fingerprintType = loader.loadClass("io.github.khopland.versionchecker.core.BuildFingerprint")
        val fingerprint = fingerprintType.constructors.single { it.parameterCount == 2 }.newInstance(emptyMap<String, String>(), "test")
        val snapshotType = loader.loadClass("io.github.khopland.versionchecker.core.BuildSnapshot")
        val snapshot = snapshotType.constructors.single { it.parameterCount == 5 }
            .newInstance(context, "test.build", fingerprint, emptyList<Any>(), null)
        val checks = service(plugin, "VersionCheckService")
        checks.javaClass.methods.single { it.name.startsWith("updates$") }.invoke(checks, adapter, snapshot)
        PlatformTestUtil.waitWithEventsDispatching("Check starts", { started.isCompleted }, 10)

        if (!includePreview) {
            assertTrue(DynamicPlugins.unloadPlugin(plugin, DynamicPlugins.UnloadPluginOptions(disable = false, save = false)))
            PlatformTestUtil.waitWithEventsDispatching("Checks cancelled", { timer.isCompleted && cancelled.isCompleted }, 10)
            return previousLoader
        }

        val previews = service(plugin, "BulkUpdateService")
        val previewScope = previews.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(previews) as CoroutineScope
        val show = previews.javaClass.methods.single { it.name.startsWith("showDialog$") }
        var shown: DialogWrapper? = null
        UiInterceptors.registerPossible(testRootDisposable, object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
            override fun doIntercept(component: DialogWrapper) {
                shown = component
            }
        })
        val job = previewScope.launch(Dispatchers.IO) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                try {
                    val factory = { createPackagedPreview(plugin) }
                    val result = show.invoke(previews, factory, continuation)
                    if (result !== COROUTINE_SUSPENDED) continuation.resume(result as Boolean)
                } catch (failure: Throwable) {
                    continuation.resumeWithException(failure)
                }
            }
        }
        PlatformTestUtil.waitWithEventsDispatching("Preview shown", { shown != null }, 10)
        val dialog = shown!!
        assertFalse(dialog.isDisposed)
        assertFalse(job.isCompleted)
        assertTrue(DynamicPlugins.unloadPlugin(plugin, DynamicPlugins.UnloadPluginOptions(disable = false, save = false)))
        PlatformTestUtil.waitWithEventsDispatching("Preview closed and checks cancelled", {
            dialog.isDisposed && job.isCompleted && timer.isCompleted && cancelled.isCompleted
        }, 10)
        assertEquals(DialogWrapper.CANCEL_EXIT_CODE, dialog.exitCode)
        assertTrue(job.isCancelled)
        assertTrue(timer.isCancelled)
        assertFalse(project.isDisposed)
        // registerPossible retains its interceptor until test teardown; release its captured dialog.
        shown = null
        return previousLoader
    }

    private fun createPackagedPreview(plugin: PluginMainDescriptor): DialogWrapper {
        val loader = plugin.pluginClassLoader!!
        val planType = loader.loadClass("io.github.khopland.versionchecker.BulkUpdatePlan")
        val plan = planType.constructors.single { it.parameterCount == 4 }.newInstance(emptyList<Any>(), emptyList<String>(), { true }, emptyList<String>())
        val mode = loader.loadClass("io.github.khopland.versionchecker.UpdateMode").enumConstants.first()
        val constructor = loader.loadClass("io.github.khopland.versionchecker.BulkUpdateDialog").declaredConstructors.single()
        constructor.isAccessible = true
        return constructor.newInstance(project, mode, "Whole Project", "Test", plan) as DialogWrapper
    }

    private fun unloadWithServices(plugin: PluginMainDescriptor): WeakReference<ClassLoader> {
        val npm = service(plugin, "npm.NpmProjectCache") as Disposable
        val metadata = service(plugin, "npm.NpmMetadataService") as Disposable
        val mavenMetadata = service(plugin, "maven.MavenMetadataService") as Disposable
        val gradle = service(plugin, "gradle.GradleProjectCache") as Disposable
        val previews = service(plugin, "BulkUpdateService") as Disposable
        val feedback = service(plugin, "VersionCheckFeedback") as Disposable
        val notifications = mutableListOf<Notification>()
        val connection = project.messageBus.connect()
        connection.subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.title == "Version update needs a fresh check") notifications += notification
            }
        })
        val targetType = plugin.pluginClassLoader!!.loadClass("io.github.khopland.versionchecker.VersionCheckTarget")
        val target = targetType.getConstructor(String::class.java, String::class.java).newInstance("maven", "test.build")
        feedback.javaClass.methods.single { it.name.startsWith("staleFix") }.invoke(feedback, target)
        PlatformTestUtil.waitWithEventsDispatching("Packaged recovery notification", { notifications.size == 1 }, 10)
        val recovery = notifications.single()
        connection.disconnect()
        notifications.clear()
        assertFalse(recovery.isExpired)
        var disposedServices = 0
        listOf(npm, metadata, mavenMetadata, gradle, previews, feedback).forEach { Disposer.register(it) { disposedServices++ } }
        val previousLoader = WeakReference(plugin.pluginClassLoader!!)
        assertNotNull(ActionManager.getInstance().getAction("VersionChecker.Refresh"))
        val adapters = ApplicationManager.getApplication().extensionArea.getExtensionPoint<Any>(BuildSystemAdapter.EP.name)
            .extensionList.map { it.javaClass.getMethod("getId").invoke(it) }.toSet()
        assertEquals(setOf("maven", "npm", "gradle"), adapters)
        assertNull(DynamicPlugins.checkCanUnloadWithoutRestart(plugin))
        assertTrue(DynamicPlugins.unloadPlugin(plugin, DynamicPlugins.UnloadPluginOptions(disable = false, save = false)))
        // DynamicPlugins clears disposal traces, so observe cleanup rather than calling isDisposed.
        assertEquals(6, disposedServices)
        assertTrue("Unload expires actionable notifications", recovery.isExpired)
        assertFalse(project.isDisposed)
        assertNull(ActionManager.getInstance().getAction("VersionChecker.Refresh"))
        assertNull(ApplicationManager.getApplication().extensionArea.getExtensionPointIfRegistered<Any>(BuildSystemAdapter.EP.name))
        return previousLoader
    }

    @Suppress("UNCHECKED_CAST")
    private fun service(plugin: PluginMainDescriptor, name: String): Any =
        project.getService(plugin.pluginClassLoader!!.loadClass("io.github.khopland.versionchecker.$name") as Class<Any>)

    private fun unpackArchive(): Path {
        val unpacked = Files.createTempDirectory("version-checker-dynamic-")
        val archive = Path.of(System.getProperty("versionchecker.pluginArchive"))
        ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val target = unpacked.resolve(entry.name).normalize()
                check(target.startsWith(unpacked))
                if (entry.isDirectory) Files.createDirectories(target)
                else {
                    Files.createDirectories(target.parent)
                    zip.getInputStream(entry).use { Files.copy(it, target) }
                }
            }
        }
        return unpacked
    }

    private fun descriptor() = PluginManagerCore.getPlugin(PluginId.getId("io.github.khopland.version-checker")) as PluginMainDescriptor
}
