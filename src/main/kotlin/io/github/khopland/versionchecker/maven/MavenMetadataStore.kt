package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.CheckPerformance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Optional local optimization: only successful, unexpired facts survive a project/IDE restart. */
internal class MavenMetadataStore(
    private val file: () -> Path,
    private val now: () -> Long = System::nanoTime,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val capacity: Int = 2048,
    private val versionBudget: Int = 100_000,
    private val characterBudget: Int = 8_000_000
) {
    private data class Key(val context: String, val artifact: MavenMetadataKey)
    private data class Clock(val wallStart: Long, val nanos: Long, val wallEnd: Long)
    private fun clock(): Clock {
        val start = wallTime(); val monotonic = now()
        return Clock(start, monotonic, wallTime())
    }
    private data class Record(val value: MavenMetadataValue, val fetched: Long, val expires: Long,
                              val fetchedNanos: Long, val expiresNanos: Long) {
        val characters = value.versions.sumOf { it.length.toLong() } + value.requiredMaven.length
    }
    private val epoch = AtomicLong()
    private val mutex = Mutex()
    private val entries = LinkedHashMap<Key, Record>(16, .75f, true)
    private var loadedEpoch = -1L
    @Volatile private var closed = false
    fun revision() = epoch.get()
    fun invalidate() { epoch.incrementAndGet() }
    fun close() { closed = true; invalidate() }

    suspend fun read(context: String, keys: List<MavenMetadataKey>, revision: Long): Map<MavenMetadataKey, MavenMetadataLease> =
        withContext(Dispatchers.IO) { mutex.withLock {
            if (closed || revision != epoch.get()) return@withLock emptyMap()
            CheckPerformance.measure(CheckPerformance.Stage.MAVEN_METADATA_DISK_READ, keys.size) {
                load(revision)
                val monotonic = now()
                val wall = wallTime()
                keys.mapNotNull { key -> entries[Key(context, key)]?.takeIf { fresh(it, wall) }?.let { record ->
                    val expires = minOf(record.expiresNanos,
                        monotonic + TimeUnit.MILLISECONDS.toNanos(record.expires - wall - 1))
                    if (expires <= monotonic) null else key to MavenMetadataLease(record.value, expires, record.fetchedNanos)
                } }.toMap().also { CheckPerformance.record(CheckPerformance.Stage.MAVEN_METADATA_DISK_REUSED, System.nanoTime(), it.size) }
            }
        } }

    suspend fun write(context: String, values: Map<MavenMetadataKey, MavenMetadataLease>, revision: Long) =
        withContext(Dispatchers.IO) { mutex.withLock {
            if (closed || revision != epoch.get()) return@withLock
            load(revision)
            val wall = wallTime()
            val monotonic = now()
            entries.entries.removeIf { !fresh(it.value, wall) || it.value.expiresNanos <= monotonic }
            for ((key, lease) in values) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(lease.expiresAt - monotonic)
                val age = TimeUnit.NANOSECONDS.toMillis((monotonic - lease.checkedAtNanos).coerceAtLeast(0))
                if (remaining <= 0 || remaining + age > MAX_AGE || context.length > 1024 ||
                    listOf(key.group, key.artifact, key.version).any { it.length > 1024 } ||
                    lease.value.versions.any { it.length > 16_000 } || lease.value.requiredMaven.length > 16_000) continue
                entries[Key(context, key)] = Record(lease.value, wall - age, wall + remaining, lease.checkedAtNanos, lease.expiresAt)
            }
            trim()
            CheckPerformance.measure(CheckPerformance.Stage.MAVEN_METADATA_DISK_WRITE, values.size) { save(revision) }
        } }

    /** Refresh bypasses old records immediately; the disk purge runs off the event thread. */
    suspend fun clearInvalidated() = withContext(Dispatchers.IO) { mutex.withLock {
        if (closed) return@withLock
        if (loadedEpoch != epoch.get()) {
            entries.clear(); loadedEpoch = epoch.get()
            try { Files.deleteIfExists(file()) } catch (_: IOException) { }
        }
    } }

    private fun fresh(record: Record, wall: Long) = record.fetched <= wall && record.expires > wall &&
        record.expires - record.fetched in 1..MAX_AGE

    private fun load(revision: Long) {
        if (loadedEpoch == revision) return
        entries.clear(); loadedEpoch = revision
        // Only initial project opening imports disk facts. New refresh generations start empty.
        if (revision != 0L) {
            try { Files.deleteIfExists(file()) } catch (_: IOException) { }
            return
        }
        try {
            val path = file()
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) return
            val digest = MessageDigest.getInstance("SHA-256")
            val stream = DigestInputStream(Files.newInputStream(path).buffered(), digest)
            DataInputStream(stream).use { input ->
                check(input.readInt() == MAGIC && input.readInt() == FORMAT)
                val saved = Clock(input.readLong(), input.readLong(), input.readLong())
                val current = clock()
                val nanosElapsed = current.nanos - saved.nanos
                val earliest = current.wallStart - saved.wallEnd
                val latest = current.wallEnd - saved.wallStart
                // Different clock origins, reboots and wall-clock adjustments are cache misses.
                // Retaining the original monotonic deadlines prevents reuse from extending TTL.
                check(saved.wallEnd >= saved.wallStart && current.wallEnd >= current.wallStart && nanosElapsed >= 0 &&
                    TimeUnit.NANOSECONDS.toMillis(nanosElapsed) in maxOf(0, earliest - 1)..minOf(MAX_AGE, latest + 1))
                val count = input.readInt(); check(count in 0..capacity)
                var versions = 0L
                var characters = 0L
                repeat(count) {
                    val context = input.readUTF()
                    val key = MavenMetadataKey(input.readUTF(), input.readUTF(), input.readBoolean(), input.readUTF())
                    check(context.length <= 1024 && listOf(key.group, key.artifact, key.version).all { it.length <= 1024 })
                    characters += context.length + key.group.length + key.artifact.length + key.version.length
                    val fetched = input.readLong(); val expires = input.readLong()
                    val fetchedNanos = input.readLong(); val expiresNanos = input.readLong()
                    check(expiresNanos - fetchedNanos in 1..TimeUnit.MILLISECONDS.toNanos(MAX_AGE))
                    val length = input.readInt(); check(length >= 0 && versions + length <= versionBudget)
                    versions += length
                    val history = List(length) {
                        input.readUTF().also { text -> characters += text.length; check(characters <= characterBudget) }
                    }
                    val required = input.readUTF(); characters += required.length; check(characters <= characterBudget)
                    val record = Record(MavenMetadataValue(history, required), fetched, expires, fetchedNanos, expiresNanos)
                    if (fresh(record, wallTime()) && record.expiresNanos > now()) entries[Key(context, key)] = record
                }
                val expected = digest.digest(); stream.on(false)
                check(MessageDigest.isEqual(expected, input.readNBytes(32)) && input.read() == -1)
            }
        } catch (_: IOException) { entries.clear() }
        catch (_: IllegalStateException) { entries.clear() }
    }

    private fun trim() {
        var versions = entries.values.sumOf { it.value.versions.size.toLong() }
        fun weight(key: Key, record: Record) = record.characters + key.context.length +
            key.artifact.group.length + key.artifact.artifact.length + key.artifact.version.length
        var characters = entries.entries.sumOf { weight(it.key, it.value) }
        val iterator = entries.entries.iterator()
        while ((entries.size > capacity || versions > versionBudget || characters > characterBudget) && iterator.hasNext()) {
            val entry = iterator.next()
            versions -= entry.value.value.versions.size
            characters -= weight(entry.key, entry.value)
            iterator.remove()
        }
    }

    private suspend fun save(revision: Long) {
        var temporary: Path? = null
        try {
            val path = file()
            Files.createDirectories(path.parent)
            if (Files.getFileAttributeView(path.parent, PosixFileAttributeView::class.java) != null)
                Files.setPosixFilePermissions(path.parent, PosixFilePermissions.fromString("rwx------"))
            temporary = Files.createTempFile(path.parent, "metadata-", ".tmp")
            if (Files.getFileAttributeView(temporary, PosixFileAttributeView::class.java) != null)
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
            val digest = MessageDigest.getInstance("SHA-256")
            val stream = DigestOutputStream(Files.newOutputStream(temporary).buffered(), digest)
            DataOutputStream(stream).use { output ->
                output.writeInt(MAGIC); output.writeInt(FORMAT)
                val clock = clock()
                output.writeLong(clock.wallStart); output.writeLong(clock.nanos); output.writeLong(clock.wallEnd)
                output.writeInt(entries.size)
                for ((key, record) in entries) {
                    currentCoroutineContext().ensureActive()
                    output.writeUTF(key.context); output.writeUTF(key.artifact.group); output.writeUTF(key.artifact.artifact)
                    output.writeBoolean(key.artifact.plugin); output.writeUTF(key.artifact.version)
                    output.writeLong(record.fetched); output.writeLong(record.expires)
                    output.writeLong(record.fetchedNanos); output.writeLong(record.expiresNanos)
                    output.writeInt(record.value.versions.size)
                    record.value.versions.forEach(output::writeUTF)
                    output.writeUTF(record.value.requiredMaven)
                }
                val checksum = digest.digest(); stream.on(false); output.write(checksum)
            }
            currentCoroutineContext().ensureActive()
            if (!closed && revision == epoch.get() && Files.size(temporary) <= MAX_BYTES)
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) { /* A disk-cache failure must not discard successful native metadata. */ }
        finally { temporary?.let { try { Files.deleteIfExists(it) } catch (_: IOException) { } } }
    }

    companion object {
        private const val MAGIC = 0x56434d31
        private const val FORMAT = 1
        private const val MAX_BYTES = 32L * 1024 * 1024
        private val MAX_AGE = TimeUnit.MINUTES.toMillis(10)
    }
}
