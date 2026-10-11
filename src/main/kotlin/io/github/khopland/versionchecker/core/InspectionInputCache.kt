package io.github.khopland.versionchecker.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.readAction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Short-lived disk captures for non-suspending inspection visitors. Never authorizes an edit. */
internal class InspectionInputCache(
    private val project: Project,
    private val scope: CoroutineScope,
    private val ready: (String) -> Unit,
    private val capacity: Int = 16,
    private val lifetimeMillis: Long = 2_000
) {
    private data class Key(val adapter: BuildSystemAdapter, val file: VirtualFile)
    private class Entry {
        @Volatile var reader: (() -> BuildSnapshot?)? = null
        var expires = Long.MAX_VALUE
        var job: Job? = null
    }
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private val slots = Semaphore(4)
    private var disposed = false

    /** Caller holds a read action; only the stored reader touches live PSI here. */
    fun snapshot(adapter: BuildSystemAdapter, file: VirtualFile): BuildSnapshot? {
        val key = Key(adapter, file)
        val entry = synchronized(this) {
            if (disposed || project.isDisposed || !file.isValid) return null
            val now = System.nanoTime()
            entries.entries.removeIf { (_, value) ->
                if (value.reader != null && value.expires <= now) {
                    value.job?.cancel(); true
                } else false
            }
            entries[key] ?: create(key).also { entries[key] = it }
        }
        val result = entry.reader?.invoke()
        if (entry.reader != null && result == null) synchronized(this) {
            if (entries[key] === entry) {
                entry.job?.cancel()
                entries[key] = create(key)
            }
        }
        return result
    }

    /** Called under the monitor. Both pending workers and retained captures share the bound. */
    private fun create(key: Key): Entry {
        while (entries.size >= capacity) {
            val oldest = entries.entries.first()
            oldest.value.job?.cancel()
            entries.remove(oldest.key)
        }
        val entry = Entry()
        entry.job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                slots.withPermit {
                    val reader = key.adapter.prepareInspectionSnapshot(project, key.file)
                    val available = readAction { !project.isDisposed && key.file.isValid && reader() != null }
                    synchronized(this@InspectionInputCache) {
                        if (disposed || entries[key] !== entry) return@withPermit
                        entry.reader = reader
                        entry.expires = System.nanoTime() + lifetimeMillis * 1_000_000
                    }
                    if (available) ready(key.file.path)
                }
                delay(lifetimeMillis)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) {
                throw cancelled
            } catch (_: Exception) {
                // Capture failures are not repository failures. Retry on the next inspection after a pause.
                delay(lifetimeMillis)
            } finally {
                synchronized(this@InspectionInputCache) {
                    if (entries[key] === entry) entries.remove(key)
                }
            }
        }
        entry.job!!.start()
        return entry
    }

    @Synchronized fun invalidate(adapterId: String? = null, sourceFile: String? = null) {
        entries.entries.removeIf { (key, entry) ->
            if ((adapterId == null || key.adapter.id == adapterId) && (sourceFile == null || key.file.path == sourceFile)) {
                entry.job?.cancel(); true
            } else false
        }
    }

    @Synchronized fun dispose() { disposed = true; invalidate() }
    @Synchronized internal fun size() = entries.size
}
