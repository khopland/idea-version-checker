package io.github.khopland.versionchecker.followup

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** A separate native operation: declaration undo never claims to undo lockfile writes. */
internal object NpmLockfileSync {
    val arguments = listOf("--package-lock-only", "--ignore-scripts", "--no-audit", "--no-fund", "--update-notifier=false")
    val outputNames = listOf("package-lock.json", "npm-shrinkwrap.json")

    data class Snapshot(val files: Map<Path, String?>) {
        fun changed(checkCancellation: () -> Unit = {}): List<Path> =
            files.filter { (path, hash) -> fingerprint(path, checkCancellation) != hash }.keys.toList()
        fun isCurrent(checkCancellation: () -> Unit = {}) = changed(checkCancellation).isEmpty()
    }

    fun capture(paths: Collection<Path>, checkCancellation: () -> Unit = {}) =
        Snapshot(paths.distinct().associateWith { fingerprint(it, checkCancellation) })
    private fun fingerprint(path: Path, checkCancellation: () -> Unit): String? {
        checkCancellation()
        return if (!Files.exists(path)) null else {
            require(Files.isRegularFile(path)) { "Expected a regular file: $path" }
            Files.newInputStream(path).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) {
                    checkCancellation()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }

    fun result(exitCode: Int?, cancelled: Boolean, changed: List<Path>): String = NpmFollowUpOutcome(
        phase = when {
            cancelled -> NpmFollowUpOutcome.Phase.CANCELLED
            exitCode == 0 -> NpmFollowUpOutcome.Phase.COMPLETED
            else -> NpmFollowUpOutcome.Phase.FAILED
        },
        changed = changed,
        exitCode = exitCode,
    ).message
}
