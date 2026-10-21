package io.github.khopland.versionchecker

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.khopland.versionchecker.core.BuildSystemAdapter
import io.github.khopland.versionchecker.core.UpdateScope
import io.github.khopland.versionchecker.core.VersionCheckFailureAdvice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PreviewRecheckRequest(
    val mode: UpdateMode,
    val updateScope: UpdateScope,
    val forceRefresh: Boolean,
    val interaction: CheckPerformance.Interaction? = null,
    val includeTargetChoices: Boolean = false
)

internal data class PreviewRecheckResult(
    val preview: PreparedVersionPreview,
    val adapters: List<BuildSystemAdapter>,
    val currentAvailable: Boolean,
    val platformAvailable: Boolean
)

/** One dialog's rechecks. Requests, disposal and delivery are serialized on EDT. */
internal class PreviewRequestController(
    private val scope: CoroutineScope,
    private val isAvailable: () -> Boolean,
    private val load: suspend (PreviewRecheckRequest) -> PreviewRecheckResult,
    private val onPrepared: (PreviewRecheckRequest, PreviewRecheckResult) -> Unit,
    private val onFailure: (String) -> Unit
) : Disposable {
    private val log = Logger.getInstance(PreviewRequestController::class.java)
    private var request: Job? = null
    private var serial = 0L
    private var disposed = false

    fun request(input: PreviewRecheckRequest): Job? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed || !isAvailable()) return null
        val version = ++serial
        request?.cancel()
        return scope.launch(Dispatchers.IO + CheckPerformance.context(input.interaction)) {
            try {
                val result = load(input)
                withContext(Dispatchers.EDT) {
                    if (canDeliver(version)) onPrepared(input, result)
                }
            } catch (cancelled: CancellationException) {
                failed(version, "Check cancelled.")
                throw cancelled
            } catch (cancelled: ProcessCanceledException) {
                failed(version, "Check cancelled.")
                throw cancelled
            } catch (failure: Exception) {
                log.warn("Version preview recheck failed", failure)
                failed(version, (failure as? VersionCheckFailureAdvice)?.recoveryMessage
                    ?: "Could not check dependency versions. Check the build tool and repository settings.")
            }
        }.also { request = it }
    }

    private fun canDeliver(version: Long) = !disposed && version == serial && isAvailable()

    private suspend fun failed(version: Long, message: String) {
        // Cancellation must clear the pending state, but an older request cannot clear its successor.
        withContext(NonCancellable + Dispatchers.EDT) {
            if (canDeliver(version)) onFailure(message)
        }
    }

    override fun dispose() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        disposed = true
        serial++
        request?.cancel()
        request = null
    }
}
