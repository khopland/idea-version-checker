package io.github.khopland.versionchecker.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Metadata responses on disk with their `ETag` and `Last-Modified`, so a check after a restart sends a
 * conditional request and a `304` needs no download. Entries unused for 30 days are removed.
 */
class HttpCache(private val directory: Path) {
    class Entry(val body: ByteArray, val etag: String?, val lastModified: String?)

    private val pruned by lazy { prune() }

    fun key(url: String, headers: Map<String, String>): String {
        // Credentials are part of the key, because a repository can answer differently per user.
        val text = url + "\n" + headers.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
    }

    fun get(key: String): Entry? = runCatching {
        pruned
        val meta = directory.resolve("$key.meta")
        val body = directory.resolve("$key.body")
        if (!Files.isRegularFile(meta) || !Files.isRegularFile(body)) return null
        val lines = Files.readAllLines(meta)
        Entry(Files.readAllBytes(body), lines.getOrNull(0)?.ifEmpty { null }, lines.getOrNull(1)?.ifEmpty { null })
            .also { Files.setLastModifiedTime(meta, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())) }
    }.getOrNull()

    fun put(key: String, entry: Entry) {
        if (entry.etag == null && entry.lastModified == null || entry.body.size > MAX_BODY) return
        runCatching {
            Files.createDirectories(directory)
            write(directory.resolve("$key.body"), entry.body)
            write(directory.resolve("$key.meta"), "${entry.etag.orEmpty()}\n${entry.lastModified.orEmpty()}\n".toByteArray())
        }
    }

    private fun write(target: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(directory, "tmp", ".part")
        Files.write(temporary, bytes)
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun prune() = runCatching {
        if (!Files.isDirectory(directory)) return@runCatching
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".meta") && Files.getLastModifiedTime(it).toMillis() < cutoff }.forEach { meta ->
                Files.deleteIfExists(meta)
                Files.deleteIfExists(meta.resolveSibling(meta.fileName.toString().removeSuffix(".meta") + ".body"))
            }
        }
    }

    companion object {
        private const val MAX_BODY = 8 * 1024 * 1024
    }
}
