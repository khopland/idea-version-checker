package io.github.khopland.versionchecker

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.event.EditorEventMulticasterImpl
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.gradle.GradleProjectCache
import io.github.khopland.versionchecker.npm.NpmProjectCache

class CacheDisposalTest : BasePlatformTestCase() {
    fun testNpmListenerIsRemovedWhenItsServiceIsDisposed() = assertListenerLifecycle { NpmProjectCache(project) }

    fun testGradleListenerIsRemovedWhenItsServiceIsDisposed() = assertListenerLifecycle { GradleProjectCache(project) }

    private fun assertListenerLifecycle(create: () -> Disposable) {
        // IntelliJ exposes listener enumeration specifically for lifecycle tests.
        val multicaster = EditorFactory.getInstance().eventMulticaster as EditorEventMulticasterImpl
        fun listeners() = multicaster.listeners[DocumentListener::class.java].orEmpty().toSet()
        val before = listeners()
        val cache = create()
        Disposer.register(testRootDisposable, cache)
        val added = listeners() - before
        assertEquals("The cache must register one document listener", 1, added.size)

        // Simulate service disposal on plugin unload while its project remains open.
        Disposer.dispose(cache)
        assertFalse(project.isDisposed)
        assertTrue(Disposer.isDisposed(cache))
        assertTrue("Disposing the service must remove its document listener", added.none { it in listeners() })
    }
}
