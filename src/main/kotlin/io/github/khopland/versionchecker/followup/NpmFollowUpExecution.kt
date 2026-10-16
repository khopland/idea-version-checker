package io.github.khopland.versionchecker.followup

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.*
import java.nio.file.Path

/** Called only after explicit approval and input validation. It never retries or starts a second command. */
internal object NpmFollowUpExecution {
    suspend fun run(
        execute: suspend () -> Int,
        inspect: suspend () -> List<Path>,
        isAlive: () -> Boolean,
        publish: (NpmFollowUpOutcome) -> Unit,
        commandTimeoutMillis: Long = 120_000,
        inspectionTimeoutMillis: Long = 5_000,
    ) {
        currentCoroutineContext().ensureActive()
        if (!isAlive()) return
        var phase = NpmFollowUpOutcome.Phase.FAILED
        var exitCode: Int? = null
        try {
            exitCode = withTimeout(commandTimeoutMillis) { execute() }
            if (exitCode == 0) phase = NpmFollowUpOutcome.Phase.COMPLETED
        } catch (_: TimeoutCancellationException) {
            phase = NpmFollowUpOutcome.Phase.TIMED_OUT
        } catch (_: CancellationException) {
            phase = NpmFollowUpOutcome.Phase.CANCELLED
        } catch (_: ProcessCanceledException) {
            phase = NpmFollowUpOutcome.Phase.CANCELLED
        } catch (_: Exception) {
            phase = NpmFollowUpOutcome.Phase.FAILED
        } finally {
            // A cancelled parent must still report possible native writes, but cannot retain us indefinitely.
            withContext(NonCancellable) {
                if (isAlive()) {
                    var changesKnown = true
                    val changed = try {
                        withTimeout(inspectionTimeoutMillis) { inspect() }
                    } catch (_: Exception) {
                        changesKnown = false
                        emptyList()
                    }
                    if (isAlive()) publish(NpmFollowUpOutcome(phase, changed, exitCode, changesKnown))
                }
            }
        }
    }
}
